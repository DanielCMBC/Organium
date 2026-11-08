package organium;

import organium.audio.ReleaseSet;
import organium.audio.Sample;
import organium.audio.VoiceEngine;

/**
 * Checks per-voice left/right gains: a note rendered with gains (gL, gR) must equal the same note
 * rendered with (1, 1), scaled per channel, for mono and stereo samples, through attack, loop,
 * release crossfade and release tail. Tolerance: float rounding (relative 1e-6 of full scale).
 */
public final class GainCheck {
    private static final int BLOCK = 256;

    private static float[] render(Sample attack, ReleaseSet rel, float gl, float gr) {
        return render(attack, rel, gl, gr, null);
    }

    private static float[] render(Sample attack, ReleaseSet rel, float gl, float gr, Double trimDb) {
        VoiceEngine e = new VoiceEngine(16, 16, 48000, 0.05f);
        if (trimDb != null) {
            e.setOutputTrimDecibels(trimDb);
        }
        e.noteOn(0, attack, rel, gl, gr, 1.0731f);
        float[] all = new float[BLOCK * 2 * 400];
        float[] b = new float[BLOCK * 2];
        for (int i = 0; i < 400; i++) {
            if (i == 250) {
                e.noteOff(0);
            }
            e.render(b, BLOCK);
            System.arraycopy(b, 0, all, i * BLOCK * 2, BLOCK * 2);
        }
        return all;
    }

    private static Sample synth(int channels, int seed, boolean looped) {
        short[] d = new short[40000 * channels];
        for (int i = 0; i < d.length; i++) {
            d[i] = (short) (12000 * Math.sin(i * 0.0137 * (seed + 1 + (i % channels))) + (i % channels) * 3000);
        }
        return looped ? new Sample(d, channels, 48000, 8000, 32000, -1) : new Sample(d, channels, 48000, -1, -1, -1);
    }

    public static void main(String[] args) {
        int fails = 0;
        float[][] gains = {{1.0f, 1.0f}, {0.5f, 1.5f}, {1.8f, 0.2f}, {0.0f, 2.0f}, {0.6487f, 1.3513f}};
        for (int channels = 1; channels <= 2; channels++) {
            Sample attack = synth(channels, 1, true);
            ReleaseSet rel = ReleaseSet.of(synth(channels, 2, false));
            float[] unity = render(attack, rel, 1f, 1f);
            for (float[] g : gains) {
                float[] got = render(attack, rel, g[0], g[1]);
                double worst = 0;
                for (int i = 0; i < got.length; i++) {
                    double want = unity[i] * g[i & 1];
                    worst = Math.max(worst, Math.abs(got[i] - want));
                }
                boolean ok = worst < 1e-6;
                fails += ok ? 0 : 1;
                System.out.printf("%s gains L %.4f R %.4f: max deviation from scaled unity %.3g  %s%n",
                        channels == 1 ? "mono  " : "stereo", g[0], g[1], worst, ok ? "OK" : "FAIL");
            }
        }
        // master trim: 0 dB leaves the output bit-identical; -10 dB scales it by 10^(-10/20)
        Sample attack = synth(1, 1, true);
        ReleaseSet rel = ReleaseSet.of(synth(1, 2, false));
        float[] plain = render(attack, rel, 0.7f, 1.3f);
        boolean same = java.util.Arrays.equals(plain, render(attack, rel, 0.7f, 1.3f, 0.0));
        fails += same ? 0 : 1;
        System.out.println("trim  0 dB: " + (same ? "bit-identical OK" : "DIFFERS FAIL"));
        float[] trimmed = render(attack, rel, 0.7f, 1.3f, -10.0);
        double k = Math.pow(10, -0.5);
        double worstTrim = 0;
        for (int i = 0; i < plain.length; i++) {
            worstTrim = Math.max(worstTrim, Math.abs(trimmed[i] - plain[i] * k));
        }
        fails += worstTrim < 1e-6 ? 0 : 1;
        System.out.printf("trim -10 dB: max deviation from output x %.6f = %.3g  %s%n", k, worstTrim, worstTrim < 1e-6 ? "OK" : "FAIL");
        System.out.println(fails == 0 ? "GAIN CHECK PASSED" : fails + " GAIN CHECK(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
