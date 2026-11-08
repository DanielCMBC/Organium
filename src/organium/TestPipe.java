package organium;

import organium.audio.Sample;

/** Synthesized principal-like pipe so the engine can be exercised without a sample set. */
final class TestPipe {
    private static final int PERIOD = 184;          // 48000 / 184 ≈ 260.9 Hz, roughly middle C
    private static final double[] HARMONICS = {1.0, 0.45, 0.22, 0.12, 0.06, 0.03};

    private TestPipe() {
    }

    /** Attack transient followed by a loop spanning a whole number of periods. */
    static Sample sustain(int sampleRate) {
        int attackFrames = sampleRate / 20;
        int loopFrames = PERIOD * 100;
        short[] data = new short[attackFrames + loopFrames];
        for (int i = 0; i < data.length; i++) {
            double ramp = Math.min(1.0, i / (double) attackFrames);
            data[i] = (short) (wave(i) * ramp * 20000);
        }
        return new Sample(data, 1, sampleRate, attackFrames, data.length, -1);
    }

    /** Short exponentially decaying tail, standing in for a recorded release. */
    static Sample release(int sampleRate) {
        short[] data = new short[sampleRate / 2];
        double decay = 1.0 / (0.12 * sampleRate);
        for (int i = 0; i < data.length; i++) {
            data[i] = (short) (wave(i) * Math.exp(-i * decay) * 20000);
        }
        return new Sample(data, 1, sampleRate, -1, -1, -1);
    }

    private static double wave(int frame) {
        double phase = 2 * Math.PI * frame / PERIOD;
        double sum = 0;
        double norm = 0;
        for (int h = 0; h < HARMONICS.length; h++) {
            sum += HARMONICS[h] * Math.sin(phase * (h + 1));
            norm += HARMONICS[h];
        }
        return sum / norm;
    }
}
