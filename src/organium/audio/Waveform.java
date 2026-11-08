package organium.audio;

/**
 * Full-precision float data from a WAV file, for control signals rather than audio:
 * tremulant pitch/amplitude modulation curves are small values (around +/-0.05) that
 * 16-bit {@link Sample} storage would quantize badly.
 */
public final class Waveform {
    public final float[] data;      // interleaved when channels == 2
    public final int channels;
    public final int sampleRate;
    public final int frames;
    public final int loopStart;     // -1 if not looped
    public final int loopEnd;       // exclusive

    public Waveform(float[] data, int channels, int sampleRate, int loopStart, int loopEnd) {
        this.data = data;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.frames = data.length / channels;
        boolean validLoop = loopStart >= 0 && loopEnd > loopStart && loopEnd <= frames;
        this.loopStart = validLoop ? loopStart : -1;
        this.loopEnd = validLoop ? loopEnd : -1;
    }

    public boolean isLooped() {
        return loopStart >= 0;
    }
}
