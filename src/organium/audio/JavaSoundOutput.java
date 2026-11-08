package organium.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Java Sound backend. On Windows this goes through DirectSound, so expect tens of
 * milliseconds of latency; fine for development, not for live playing.
 */
public final class JavaSoundOutput implements AudioOutput {
    private static final int BUFFERED_PERIODS = 4;

    private volatile boolean running;
    private Thread thread;
    private SourceDataLine line;

    @Override
    public void start(int sampleRate, int periodFrames, Renderer renderer) throws LineUnavailableException {
        AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
        line = AudioSystem.getSourceDataLine(format);
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
