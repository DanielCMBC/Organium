package organium.io;

import organium.audio.Sample;
import organium.audio.Waveform;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads RIFF/WAVE files: 16/24/32-bit integer PCM and 32/64-bit IEEE float, mono or stereo,
 * including loop points from the 'smpl' chunk and the first marker from the 'cue ' chunk
 * (used by many sample sets to mark where the release starts).
 *
 * {@link #read} produces 16-bit audio {@link Sample}s (higher resolutions are reduced);
 * {@link #readWaveform} keeps full float precision for control data such as tremulant curves.
 */
public final class WavReader {
    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_IEEE_FLOAT = 3;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;

    private WavReader() {
    }

    /** Parsed header plus the location of the sample data inside {@code buf}. */
    private record Wav(Path file, ByteBuffer buf, int format, int channels, int sampleRate, int bits,
                       int dataOffset, int frames, int loopStart, int loopEnd, int cueOffset) {
        int bytesPerSample() {
            return bits / 8;
        }
    }

    /** Reads audio as 16-bit PCM. Float data in [-1, 1] is scaled to full range; overs are clipped. */
    public static Sample read(Path file) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            return toPcm16(parse(file, arena));
        }
    }

    private static Sample toPcm16(Wav w) throws UnsupportedWavFormatException {
        int count = w.frames() * w.channels();
        short[] pcm = new short[count];
        ByteBuffer buf = w.buf();
        int stride = w.bytesPerSample();
        int b = w.dataOffset();
        switch (w.format() * 100 + w.bits()) {
            case FORMAT_PCM * 100 + 16 -> {
                buf.position(b);
                buf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
            }
            case FORMAT_PCM * 100 + 24 -> {
                for (int i = 0; i < count; i++, b += stride) {
                    pcm[i] = buf.getShort(b + 1); // keep the top two bytes
                }
            }
            case FORMAT_PCM * 100 + 32 -> {
                for (int i = 0; i < count; i++, b += stride) {
                    pcm[i] = buf.getShort(b + 2);
                }
            }
            case FORMAT_IEEE_FLOAT * 100 + 32 -> {
                for (int i = 0; i < count; i++, b += stride) {
                    pcm[i] = toPcm16(buf.getFloat(b));
                }
            }
            case FORMAT_IEEE_FLOAT * 100 + 64 -> {
                for (int i = 0; i < count; i++, b += stride) {
                    pcm[i] = toPcm16(buf.getDouble(b));
                }
            }
            default -> throw unsupported(w);
        }
        return new Sample(pcm, w.channels(), w.sampleRate(), w.loopStart(), w.loopEnd(), w.cueOffset());
    }

    /** Reads any supported encoding as floats: integer PCM is scaled to [-1, 1), float data is exact. */
    public static Waveform readWaveform(Path file) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            return toFloat(parse(file, arena));
        }
    }

    private static Waveform toFloat(Wav w) throws UnsupportedWavFormatException {
        int count = w.frames() * w.channels();
        float[] data = new float[count];
        ByteBuffer buf = w.buf();
        int stride = w.bytesPerSample();
        int b = w.dataOffset();
        switch (w.format() * 100 + w.bits()) {
            case FORMAT_PCM * 100 + 16 -> {
                for (int i = 0; i < count; i++, b += stride) {
                    data[i] = buf.getShort(b) / 32768f;
                }
            }
            case FORMAT_PCM * 100 + 24 -> {
                for (int i = 0; i < count; i++, b += stride) {
                    int s = (buf.get(b) & 0xFF) | (buf.get(b + 1) & 0xFF) << 8 | buf.get(b + 2) << 16;
                    data[i] = s / 8388608f;
                }
            }
            case FORMAT_PCM * 100 + 32 -> {
                for (int i = 0; i < count; i++, b += stride) {
                    data[i] = (float) (buf.getInt(b) / 2147483648.0);
                }
            }
            case FORMAT_IEEE_FLOAT * 100 + 32 -> {
                buf.position(b);
                buf.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data);
            }
            case FORMAT_IEEE_FLOAT * 100 + 64 -> {
                for (int i = 0; i < count; i++, b += stride) {
                    data[i] = (float) buf.getDouble(b);
                }
            }
            default -> throw unsupported(w);
        }
        return new Waveform(data, w.channels(), w.sampleRate(), w.loopStart(), w.loopEnd());
    }

    private static short toPcm16(double x) {
        double s = x * 32767.0;
        return (short) (s > 32767 ? 32767 : (s < -32768 ? -32768 : Math.round(s)));
    }

    private static UnsupportedWavFormatException unsupported(Wav w) {
        String encoding = w.format() == FORMAT_IEEE_FLOAT ? "float" : "integer";
        return new UnsupportedWavFormatException(w.file() + ": " + w.bits() + "-bit " + encoding + " samples not supported");
    }

    /**
     * Maps the file into {@code arena} rather than reading it: the bytes stay in the OS page cache
     * instead of becoming a file-sized garbage array on the Java heap for every sample. The caller
     * closes the arena right after decoding, which unmaps the file at once instead of whenever the
     * garbage collector gets to the mapping (with a rarely-collecting collector, thousands of stale
     * mappings kept gigabytes of sample files in the process's working set).
     */
    private static Wav parse(Path file, Arena arena) throws IOException {
        ByteBuffer buf;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena).asByteBuffer()
                    .order(ByteOrder.LITTLE_ENDIAN);
        }
        if (buf.remaining() < 12 || buf.getInt(0) != fourCC("RIFF") || buf.getInt(8) != fourCC("WAVE")) {
            throw new IOException(file + " is not a RIFF/WAVE file");
        }

        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int dataOffset = -1;
        int dataLength = 0;
        int loopStart = -1;
        int loopEnd = -1;
        int cueOffset = -1;

        int pos = 12;
        while (pos + 8 <= buf.limit()) {
            int id = buf.getInt(pos);
            int size = buf.getInt(pos + 4);
            int body = pos + 8;
            if (size < 0 || body + size > buf.limit()) {
                size = buf.limit() - body; // tolerate truncated trailing chunks
            }

            if (id == fourCC("fmt ") && size >= 16) {
                format = buf.getShort(body) & 0xFFFF;
                channels = buf.getShort(body + 2);
                sampleRate = buf.getInt(body + 4);
                bits = buf.getShort(body + 14);
                if (format == FORMAT_EXTENSIBLE && size >= 26) {
                    format = buf.getShort(body + 24) & 0xFFFF; // first two bytes of the SubFormat GUID
                }
            } else if (id == fourCC("data")) {
                dataOffset = body;
                dataLength = size;
            } else if (id == fourCC("smpl") && size >= 36) {
                int loops = buf.getInt(body + 28);
                if (loops > 0 && size >= 60) {
                    loopStart = buf.getInt(body + 36 + 8);
                    loopEnd = buf.getInt(body + 36 + 12) + 1; // smpl end is inclusive
                }
            } else if (id == fourCC("cue ") && size >= 28) {
                if (buf.getInt(body) > 0) {
                    cueOffset = buf.getInt(body + 4 + 20);
                }
            }
            pos = body + size + (size & 1);
        }

        if (dataOffset < 0 || format < 0) {
            throw new IOException(file + ": missing fmt or data chunk");
        }
        if (format != FORMAT_PCM && format != FORMAT_IEEE_FLOAT) {
            throw new UnsupportedWavFormatException(file + ": unsupported WAV encoding " + format);
        }
        if (channels != 1 && channels != 2) {
            throw new UnsupportedWavFormatException(file + ": " + channels + " channels not supported");
        }
        if (bits % 8 != 0 || bits == 0) {
            throw new UnsupportedWavFormatException(file + ": " + bits + "-bit samples not supported");
        }
        int frames = dataLength / (bits / 8 * channels);
        return new Wav(file, buf, format, channels, sampleRate, bits, dataOffset, frames, loopStart, loopEnd, cueOffset);
    }

    private static int fourCC(String s) {
        return (s.charAt(0)) | (s.charAt(1) << 8) | (s.charAt(2) << 16) | (s.charAt(3) << 24);
    }
}
