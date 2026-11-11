package organium.audio;

import java.util.Arrays;
import java.util.Random;

/**
 * Checks the release transition and voice stealing on synthetic pipes (no sample files needed):
 *
 *  1. discontinuities: note-off at many phases, mono and stereo, never makes a sample-to-sample step
 *     larger than 1.5x the largest step of the sustain itself (and the old STAGE0 transition does,
 *     which shows the check can detect the click);
 *  2. crossfade shape: the release fades in from 0 while the sustain fades out from 1, over 10 ms,
 *     and the two envelopes sum to 1 throughout;
 *  3. pipes without a release sample fade out over 50 ms;
 *  4. short and long notes select the right release sample;
 *  5. repeated notes on one key (retriggers, fast on/off) stay click-free and leave nothing sounding;
 *  6. stealing protects releases that are fading in, but falls back to them rather than dropping a
 *     note, drops a note only when every voice is sustaining, and is deterministic;
 *  7. random high-polyphony churn keeps those invariants, and render() allocates nothing.
 *
 * Usage: ReleaseTransitionCheck
 */
public final class ReleaseTransitionCheck {
    private static final int RATE = 48000;
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        discontinuities();
        crossfadeShape();
        fadeOutWithoutRelease();
        releaseSelection();
        repeatedNotes();
        stealing();
        churn();
        System.out.println(failures == 0 ? "RELEASE TRANSITION CHECK PASSED (" + checks + " checks)" : failures + " FAILURES of " + checks);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- synthetic pipes ----

    /**
     * A looped sine "pipe" whose loop holds a whole number of periods, so the loop is seamless and
     * the only possible discontinuity is the release transition. Like a recorded attack (Melcer's
     * start below 7/32768 in their first millisecond and build up over 8-48 ms), it starts from
     * silence: the first 10 ms rise linearly.
     */
    private static Sample sustain(int channels, double hz, double amp) {
        int period = (int) Math.round(RATE / hz);
        int loopLen = period * 40;
        int frames = loopLen + period * 20;
        short[] d = new short[frames * channels];
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                double onset = Math.min(1.0, f / (RATE * 0.010));
                d[f * channels + c] = (short) Math.round(onset * amp * 32767 * Math.sin(2 * Math.PI * f / period + c * 0.9));
            }
        }
        return new Sample(d, channels, RATE, period * 10, period * 10 + loopLen, -1);
    }

    /** A release recording: the same pitch at full level from its first frame, starting at phase {@code phase}, decaying. */
    private static Sample release(int channels, double hz, double amp, double phase, double seconds) {
        int period = (int) Math.round(RATE / hz);
        int frames = (int) (seconds * RATE);
        short[] d = new short[frames * channels];
        int taper = RATE / 50;  // like a recording, the tail reaches silence: the last 20 ms fade to zero
        for (int f = 0; f < frames; f++) {
            double decay = Math.exp(-3.0 * f / frames) * Math.min(1.0, (frames - 1 - f) / (double) taper);
            for (int c = 0; c < channels; c++) {
                d[f * channels + c] = (short) Math.round(amp * 32767 * decay * Math.sin(2 * Math.PI * f / period + phase + c * 0.9));
            }
        }
        return new Sample(d, channels, RATE, -1, -1, -1);
    }

    private static float[] renderFrames(VoiceEngine e, int frames) {
        float[] out = new float[frames * 2];
        float[] block = new float[512];
        for (int f = 0; f < frames; f += 256) {
            int n = Math.min(256, frames - f);
            e.render(block, n);
            System.arraycopy(block, 0, out, f * 2, n * 2);
        }
        return out;
    }

    private static float maxStep(float[] mix, int c, int from, int to) {
        float m = 0;
        for (int f = Math.max(1, from); f < to; f++) {
            m = Math.max(m, Math.abs(mix[f * 2 + c] - mix[(f - 1) * 2 + c]));
        }
        return m;
    }

    // ---- 1. discontinuities ----

    private static void discontinuities() {
        double worstDefault = 0;
        double worstStage0 = 0;
        for (int channels = 1; channels <= 2; channels++) {
            for (int i = 0; i < 24; i++) {
                double phase = i * Math.PI / 12;
                for (int heldFrames : new int[]{4800, 4800 + 37, 4800 + 101, 33600 + 5}) {
                    worstDefault = Math.max(worstDefault, stepRatio(ReleaseTransition.DEFAULT, channels, phase, heldFrames));
                    worstStage0 = Math.max(worstStage0, stepRatio(ReleaseTransition.STAGE0, channels, phase, heldFrames));
                }
            }
        }
        System.out.printf("1. discontinuity at note-off (worst step / steady step, 192 cases): default %.2f, stage0 %.2f%n",
                worstDefault, worstStage0);
        check(worstDefault <= 1.5, "default transition: step at note-off " + worstDefault + "x the sustain's");
        check(worstStage0 > 3, "the check detects the old click (stage0 " + worstStage0 + "x)");
    }

    private static double stepRatio(ReleaseTransition t, int channels, double phase, int heldFrames) {
        VoiceEngine e = new VoiceEngine(16, 16, RATE, t);
        Sample s = sustain(channels, 220, 0.5);
        Sample r = release(channels, 220, 0.5, phase, 0.5);
        e.noteOn(0, s, ReleaseSet.of(r), 1f, 1f);
        float[] before = renderFrames(e, heldFrames);
        e.noteOff(0);
        float[] after = renderFrames(e, 4800);
        double worst = 0;
        for (int c = 0; c < 2; c++) {
            float steady = maxStep(before, c, heldFrames / 2, heldFrames);
            float[] joined = new float[(heldFrames + 4800) * 2];
            System.arraycopy(before, 0, joined, 0, before.length);
            System.arraycopy(after, 0, joined, before.length, after.length);
            float step = maxStep(joined, c, heldFrames, heldFrames + 960);
            worst = Math.max(worst, step / steady);
        }
        return worst;
    }

    // ---- 2. crossfade shape ----

    private static void crossfadeShape() {
        VoiceEngine e = new VoiceEngine(16, 16, RATE);
        e.noteOn(3, sustain(1, 220, 0.5), ReleaseSet.of(release(1, 220, 0.5, 1, 0.5)), 1f, 1f);
        renderFrames(e, 2400);
        e.noteOff(3);
        e.render(new float[2], 1);                  // the command is drained, one frame rendered
        int sustainV = -1;
        int releaseV = -1;
        for (int v = 0; v < e.maxVoices(); v++) {
            if (e.voiceState(v) == VoiceEngine.STATE_RELEASE) {
                if (e.voiceEnvelopeDelta(v) < 0) {
                    sustainV = v;
                } else if (e.voiceEnvelopeDelta(v) > 0) {
                    releaseV = v;
                }
            }
        }
        check(sustainV >= 0 && releaseV >= 0, "2. note-off makes one voice fade out and one fade in");
        if (sustainV < 0 || releaseV < 0) {
            return;
        }
        double worstSum = 0;
        int settledAt = -1;
        for (int f = 1; f <= 600; f++) {
            float out = e.voiceEnvelope(sustainV);
            float in = e.voiceEnvelope(releaseV);
            boolean sustainGone = e.voiceState(sustainV) == VoiceEngine.STATE_FREE;
            if (!sustainGone) {
                worstSum = Math.max(worstSum, Math.abs(out + in - 1f));
            }
            if (settledAt < 0 && e.voiceEnvelopeDelta(releaseV) == 0f) {
                settledAt = f;
            }
            e.render(new float[2], 1);
        }
        System.out.printf("2. crossfade: release at full level after %d frames (10 ms = 480), envelopes sum to 1 within %.2e%n",
                settledAt, worstSum);
        check(settledAt >= 479 && settledAt <= 481, "release fades in over 10 ms (" + settledAt + " frames)");
        check(worstSum < 1e-4, "sustain and release envelopes sum to 1 (" + worstSum + ")");
        check(e.voiceState(sustainV) == VoiceEngine.STATE_FREE, "the sustain voice is freed after the crossfade");
        check(e.voiceEnvelope(releaseV) == 1f && e.voiceEnvelopeDelta(releaseV) == 0f, "the release plays on at full level");
    }

    // ---- 3. no release sample ----

    private static void fadeOutWithoutRelease() {
        VoiceEngine e = new VoiceEngine(16, 16, RATE);
        e.noteOn(1, sustain(2, 330, 0.4), null, 1f, 1f);
        renderFrames(e, 2400);
        e.noteOff(1);
        int frames = 0;
        float[] one = new float[2];
        do {
            e.render(one, 1);
            frames++;
        } while (e.activeVoices() > 0 && frames < 10000);
        System.out.printf("3. without a release sample the pipe fades out in %d frames (50 ms = 2400)%n", frames);
        check(frames >= 2398 && frames <= 2402, "fade-out without release is 50 ms (" + frames + " frames)");
    }

    // ---- 4. release selection ----

    private static void releaseSelection() {
        Sample s = sustain(2, 262, 0.4);
        Sample r60 = release(2, 262, 0.4, 0.3, 0.3);
        Sample r140 = release(2, 262, 0.4, 1.3, 0.4);
        Sample r280 = release(2, 262, 0.4, 2.3, 0.5);
        Sample rLong = release(2, 262, 0.4, 3.3, 0.6);
        ReleaseSet set = new ReleaseSet(new Sample[]{rLong, r60, r280, r140}, new int[]{ReleaseSet.UNLIMITED, 60, 280, 140});
        int[][] cases = {{30, 0}, {59, 0}, {61, 1}, {139, 1}, {141, 2}, {279, 2}, {281, 3}, {3000, 3}};
        Sample[] expected = {r60, r140, r280, rLong};
        int ok = 0;
        for (int[] c : cases) {
            VoiceEngine e = new VoiceEngine(16, 16, RATE);
            e.noteOn(9, s, set, 1f, 1f);
            renderFrames(e, c[0] * RATE / 1000);
            e.noteOff(9);
            e.render(new float[2], 1);
            Sample chosen = null;
            for (int v = 0; v < e.maxVoices(); v++) {
                if (e.voiceState(v) == VoiceEngine.STATE_RELEASE && e.voiceEnvelopeDelta(v) > 0) {
                    chosen = e.voiceSample(v);
                }
            }
            boolean right = chosen == expected[c[1]];
            ok += right ? 1 : 0;
            check(right, "4. a " + c[0] + " ms note selects release " + c[1]);
        }
        System.out.printf("4. release selection: %d/%d note lengths chose the expected release (limits 60/140/280 ms)%n",
                ok, cases.length);
    }

    // ---- 5. repeated notes ----

    private static void repeatedNotes() {
        // Retriggering one pipe sums the new attack with the earlier notes' releases, all at the same
        // pitch, so the waveform can grow to a few times one pipe's. A click is a step no sinusoid of
        // that pitch and the local amplitude could make: |x[n] - x[n-1]| > w * (local peak), w = 2 pi f / rate.
        double hz = 196;
        double w = 2 * Math.PI * hz / RATE;
        VoiceEngine e = new VoiceEngine(64, 16, RATE);
        Sample s = sustain(2, hz, 0.4);
        ReleaseSet r = ReleaseSet.of(release(2, hz, 0.4, 2.0, 0.4));
        e.noteOn(2, s, r, 1f, 1f);
        float[] all = renderFrames(e, 4800);
        Random rnd = new Random(7);
        int sustaining = 0;
        for (int i = 0; i < 60; i++) {
            switch (i % 3) {
                case 0, 2 -> e.noteOn(2, s, r, 1f, 1f);   // case 0 retriggers while the key still sounds
                default -> e.noteOff(2);
            }
            float[] mix = renderFrames(e, 240 + rnd.nextInt(1200));
            float[] joined = Arrays.copyOf(all, all.length + mix.length);
            System.arraycopy(mix, 0, joined, all.length, mix.length);
            all = joined;
            int count = 0;
            for (int v = 0; v < e.maxVoices(); v++) {
                count += e.voiceState(v) == VoiceEngine.STATE_SUSTAIN && e.voiceKey(v) == 2 ? 1 : 0;
            }
            sustaining = Math.max(sustaining, count);
        }
        e.noteOff(2);
        renderFrames(e, RATE);
        int frames = all.length / 2;
        double worst = 0;
        for (int c = 0; c < 2; c++) {
            for (int f = 241; f < frames - 240; f++) {
                float local = 0;
                for (int g = f - 240; g <= f + 240; g += 4) {
                    local = Math.max(local, Math.abs(all[g * 2 + c]));
                }
                if (local > 1e-4f) {
                    worst = Math.max(worst, Math.abs(all[f * 2 + c] - all[(f - 1) * 2 + c]) / (w * local));
                }
            }
        }
        System.out.printf("5. 60 repeated notes on one key: worst step %.2fx what a %s Hz tone of the local level allows, "
                + "at most %d sustaining voice(s) for the key, %d voices left after%n", worst, hz, sustaining, e.activeVoices());
        check(worst <= 1.5, "repeated notes stay free of clicks (" + worst + "x)");
        check(sustaining <= 1, "a key never has two sustaining voices");
        check(e.activeVoices() == 0, "nothing keeps sounding after the last release");
        check(e.droppedNotes() == 0, "no notes dropped");
    }

    // ---- 6. stealing ----

    private static int count(VoiceEngine e, int state, int deltaSign) {
        int n = 0;
        for (int v = 0; v < e.maxVoices(); v++) {
            if (e.voiceState(v) == state && (deltaSign == 0 ? e.voiceEnvelopeDelta(v) == 0f
                    : Math.signum(e.voiceEnvelopeDelta(v)) == deltaSign)) {
                n++;
            }
        }
        return n;
    }

    private static void stealing() {
        Sample s = sustain(1, 220, 0.3);
        ReleaseSet r = ReleaseSet.of(release(1, 220, 0.3, 1.0, 1.0));
        float[] one = new float[256 * 2];

        // a. a new note steals a fading-out sustain or a settled tail, not a release fading in
        VoiceEngine e = new VoiceEngine(8, 16, RATE);
        for (int k = 0; k < 3; k++) {
            e.noteOn(k, s, r, 1f, 1f);
        }
        e.render(one, 64);
        for (int k = 0; k < 3; k++) {
            e.noteOff(k);
        }
        e.render(one, 256);
        e.render(one, 256);                          // 512 frames: crossfades done, 3 settled tails
        e.noteOn(3, s, r, 1f, 1f);
        e.noteOn(4, s, r, 1f, 1f);
        e.render(one, 64);
        e.noteOff(3);
        e.noteOff(4);
        e.render(one, 64);                           // fading-in releases at 0.13, fading-out sustains at 0.87
        int fadingIn = count(e, VoiceEngine.STATE_RELEASE, 1);
        e.noteOn(5, s, r, 1f, 1f);                   // takes the last free voice
        e.noteOn(6, s, r, 1f, 1f);                   // must steal
        e.render(one, 1);
        check(fadingIn == 2, "6a. setup: two releases fading in (" + fadingIn + ")");
        check(count(e, VoiceEngine.STATE_RELEASE, 1) == 2, "6a. a new note does not steal a release that is fading in");
        check(e.droppedNotes() == 0, "6a. no note dropped");

        // b. fallback: when every releasing voice is fading in, the newest of them is stolen, not the note
        e = new VoiceEngine(4, 16, RATE);
        e.noteOn(0, s, r, 1f, 1f);
        e.noteOn(1, s, r, 1f, 1f);
        e.noteOn(2, s, r, 1f, 1f);
        e.render(one, 64);
        e.noteOff(0);                                // release takes the 4th voice
        e.render(one, 32);
        e.noteOn(3, s, r, 1f, 1f);                   // steals the fading-out sustain of key 0
        e.render(one, 32);
        int onlyFadingIn = count(e, VoiceEngine.STATE_RELEASE, 1);
        int others = count(e, VoiceEngine.STATE_RELEASE, -1) + count(e, VoiceEngine.STATE_RELEASE, 0);
        e.noteOn(4, s, r, 1f, 1f);                   // only fading-in releases are left to steal
        e.render(one, 1);
        check(onlyFadingIn == 1 && others == 0, "6b. setup: the only releasing voice is fading in");
        check(e.droppedNotes() == 0 && count(e, VoiceEngine.STATE_SUSTAIN, 0) == 4,
                "6b. the note is not dropped: the fading-in release is stolen");

        // c. a note is dropped only when every voice is sustaining
        e.noteOn(5, s, r, 1f, 1f);
        e.render(one, 1);
        check(e.droppedNotes() == 1, "6c. with every voice sustaining the new note is dropped and counted");

        // d. deterministic: the same events give the same output, sample for sample
        check(Arrays.equals(stealScript(), stealScript()), "6d. stealing is deterministic");
        System.out.println("6. stealing: fading-in releases protected, fallback instead of dropping, drop only when all sustain, deterministic");
    }

    private static float[] stealScript() {
        VoiceEngine e = new VoiceEngine(6, 64, RATE);
        Sample s = sustain(2, 247, 0.3);
        ReleaseSet r = ReleaseSet.of(release(2, 247, 0.3, 0.5, 0.3));
        Random rnd = new Random(42);
        float[] all = new float[0];
        for (int i = 0; i < 200; i++) {
            int k = rnd.nextInt(12);
            if (rnd.nextBoolean()) {
                e.noteOn(k, s, r, 1f, 1f);
            } else {
                e.noteOff(k);
            }
            float[] mix = renderFrames(e, 32 + rnd.nextInt(200));
            float[] joined = Arrays.copyOf(all, all.length + mix.length);
            System.arraycopy(mix, 0, joined, all.length, mix.length);
            all = joined;
        }
        return all;
    }

    // ---- 7. churn ----

    private static void churn() {
        VoiceEngine e = new VoiceEngine(32, 128, RATE);
        e.warmUp();
        Sample[] sustains = {sustain(1, 110, 0.05), sustain(2, 147, 0.05), sustain(1, 523, 0.05)};
        ReleaseSet[] releases = {ReleaseSet.of(release(1, 110, 0.05, 1, 0.8)), ReleaseSet.of(release(2, 147, 0.05, 2, 0.5)), null};
        Random rnd = new Random(2026);
        boolean[] down = new boolean[128];
        float[] block = new float[256 * 2];
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long renderAlloc = 0;
        int allocEvents = 0;
        int dropsWhenNotAllSustaining = 0;
        long dropped = e.droppedNotes();
        for (int i = 0; i < 20000; i++) {
            int k = rnd.nextInt(128);
            int sus = 0;
            for (int v = 0; v < e.maxVoices(); v++) {
                sus += e.voiceState(v) == VoiceEngine.STATE_SUSTAIN ? 1 : 0;
            }
            boolean allSustaining = sus == e.maxVoices();
            if (down[k]) {
                e.noteOff(k);
            } else {
                e.noteOn(k, sustains[k % 3], releases[k % 3], 1f, 1f);
            }
            down[k] = !down[k];
            long a0 = mx.getThreadAllocatedBytes(id);
            e.render(block, 1 + rnd.nextInt(256));
            if (i > 2000) {
                long a = mx.getThreadAllocatedBytes(id) - a0;
                if (a != 0 && allocEvents < 8) {
                    System.out.println("   render " + i + " allocated " + a + " bytes");
                    allocEvents++;
                }
                renderAlloc += a;
            }
            if (e.droppedNotes() != dropped) {
                // a drop is allowed only for a note-on when every voice was sustaining, or for a release
                // that found no voice (its sustain then fades out instead)
                boolean wasNoteOn = down[k];          // toggled above: true now means the event was a note-on
                if (wasNoteOn && !allSustaining) {
                    dropsWhenNotAllSustaining++;
                }
                dropped = e.droppedNotes();
            }
            for (int v = 0; v < e.maxVoices(); v++) {
                if (e.voiceState(v) != VoiceEngine.STATE_FREE) {
                    float env = e.voiceEnvelope(v);
                    if (env < 0f || env > 1f) {
                        check(false, "7. envelope out of range: " + env);
                    }
                }
            }
        }
        for (int k = 0; k < 128; k++) {
            if (down[k]) {
                e.noteOff(k);
            }
        }
        renderFrames(e, RATE * 2);
        System.out.printf("7. churn: 20000 events on 32 voices, %d notes dropped (%d while some voice was not sustaining), render() allocated %d bytes, %d voices left%n",
                e.droppedNotes(), dropsWhenNotAllSustaining, renderAlloc, e.activeVoices());
        check(dropsWhenNotAllSustaining == 0, "7. no note-on dropped while a releasing voice could be stolen");
        check(renderAlloc == 0, "7. render() allocates nothing (" + renderAlloc + " bytes)");
        check(e.activeVoices() == 0, "7. everything ends after all notes are released");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
