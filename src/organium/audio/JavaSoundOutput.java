package organium.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Java Sound backend. On Windows this goes through DirectSound, so expect tens of
 * milliseconds of latency; fine for development, not for live playing.
 */
public final class JavaSoundOutput implements AudioOutput {
    private static final int BUFFERED_PERIODS = 4;

    private final String deviceName;
    private volatile boolean running;
    private Thread thread;
    private SourceDataLine line;

    /** Plays through Windows' default output device. */
    public JavaSoundOutput() {
        this(null);
    }

    /**
     * Plays through the output device whose name contains {@code deviceName} (ignoring case), for
     * example "Fones de ouvido"; null means Windows' default device.
     */
    public JavaSoundOutput(String deviceName) {
        this.deviceName = deviceName;
    }

    /** Names of the devices that can play 16-bit stereo at {@code sampleRate}. */
    public static List<String> outputs(int sampleRate) {
        AudioFormat format = format(sampleRate);
        List<String> names = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(new DataLine.Info(SourceDataLine.class, format))) {
                names.add(info.getName());
            }
        }
        return names;
    }

    private static AudioFormat format(int sampleRate) {
        return new AudioFormat(sampleRate, 16, 2, true, false);
    }

    /** The name of the device playing, or "default". */
    public String deviceName() {
        return deviceName == null ? "default" : deviceName;
    }

    @Override
    public void start(int sampleRate, int periodFrames, Renderer renderer) throws LineUnavailableException {
        AudioFormat format = format(sampleRate);
        line = deviceName == null ? AudioSystem.getSourceDataLine(format) : lineOf(deviceName, format);
        line.open(format, periodFrames * 4 * BUFFERED_PERIODS);

        float[] mix = new float[periodFrames * 2];
        byte[] pcm = new byte[periodFrames * 4];
        running = true;
        thread = new Thread(() -> loop(renderer, mix, pcm, periodFrames), "organium-audio");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.setDaemon(true);
        line.start();
        thread.start();
    }

    private static SourceDataLine lineOf(String name, AudioFormat format) throws LineUnavailableException {
        DataLine.Info wanted = new DataLine.Info(SourceDataLine.class, format);
        String key = name.toLowerCase(Locale.ROOT);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().toLowerCase(Locale.ROOT).contains(key)
                    && AudioSystem.getMixer(info).isLineSupported(wanted)) {
                return AudioSystem.getSourceDataLine(format, info);
            }
        }
        throw new LineUnavailableException("No output device named '" + name + "'. Available: "
                + String.join("; ", outputs((int) format.getSampleRate())));
    }

    private void loop(Renderer renderer, float[] mix, byte[] pcm, int frames) {
        while (running) {
            renderer.render(mix, frames);
            int b = 0;
            for (float x : mix) {
                if (x > 1f) x = 1f;
                if (x < -1f) x = -1f;
                short s = (short) (x * 32767f);
                pcm[b++] = (byte) s;
                pcm[b++] = (byte) (s >> 8);
            }
            line.write(pcm, 0, pcm.length);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (line != null) {
            line.stop();
            line.close();
        }
    }
}
