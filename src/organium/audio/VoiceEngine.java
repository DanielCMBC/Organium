package organium.audio;

/**
 * Polyphonic sample playback engine. All voice state lives in preallocated primitive arrays
 * owned by the audio thread; other threads talk to it only through the command queue.
 *
 * A "key" identifies one sounding pipe (an index in [0, maxKeys)). Each key has at most one
 * sustaining voice, tracked in a table so note-off finds it in O(1).
 *
 * Each voice has its own left and right gain, fixed when it starts (release voices inherit them).
 * A mono sample is scaled into both channels by those gains; a stereo sample's left channel by the
 * left gain and its right channel by the right gain. Gains above 1 are allowed: the mix is float and
 * the outputs clamp to full scale.
 */
public final class VoiceEngine {
    private static final int FREE = 0;
    private static final int SUSTAIN = 1;
    private static final int RELEASE = 2;
    private static final float PCM_SCALE = 1f / 32768f;

    private final int maxVoices;
    private final int outputRate;
    private final float releaseFadeStep;
    private final CommandQueue commands = new CommandQueue(16384);
    private final CommandQueue.Sink sink = new Sink();

    private final int[] state;
    private final int[] key;
    private final Sample[] sample;
    private final ReleaseSet[] releases;
    private final double[] position;
    private final double[] step;
    private final int[] end;          // frame at which an unlooped voice stops
    private final boolean[] loops;
    private final float[] gainL;
    private final float[] gainR;
    private final float[] env;
    private final float[] envDelta;
    private final long[] soundedFrames; // output frames rendered since the voice started
    private final int[] sustainVoice; // key -> its sustaining voice, or -1
    private final int[] freeStack;    // indices of FREE voices; allocation is a pop, not a scan
    private int freeCount;

    private volatile float outputGain = 1f;
    private volatile int activeVoices;
    private volatile long droppedNotes;

    public VoiceEngine(int maxVoices, int maxKeys, int outputRate, float releaseCrossfadeSeconds) {
        this.maxVoices = maxVoices;
        this.outputRate = outputRate;
        this.releaseFadeStep = 1f / Math.max(1f, releaseCrossfadeSeconds * outputRate);
        state = new int[maxVoices];
        key = new int[maxVoices];
        sample = new Sample[maxVoices];
        releases = new ReleaseSet[maxVoices];
        position = new double[maxVoices];
        step = new double[maxVoices];
        end = new int[maxVoices];
        loops = new boolean[maxVoices];
        gainL = new float[maxVoices];
        gainR = new float[maxVoices];
        env = new float[maxVoices];
        envDelta = new float[maxVoices];
        soundedFrames = new long[maxVoices];
        sustainVoice = new int[Math.max(maxKeys, maxVoices)]; // warmUp() needs one key per voice
        java.util.Arrays.fill(sustainVoice, -1);
        freeStack = new int[maxVoices];
        for (int v = 0; v < maxVoices; v++) {
            freeStack[v] = maxVoices - 1 - v; // pop order 0, 1, 2...
        }
        freeCount = maxVoices;
    }

    /**
     * Thread-safe. {@code releases} may be null; at note-off the engine picks the release matching
     * how long this voice sounded. A release that is the attack sample itself plays from that
     * sample's release marker, as in Hauptwerk-style single-file pipes.
     * {@code pitch} is a playback-rate multiplier. Returns false if the command queue is full.
     */
    public boolean noteOn(int key, Sample attack, ReleaseSet releases, float gainL, float gainR, float pitch) {
        return commands.offer(CommandQueue.NOTE_ON, key, attack, releases, gainL, gainR, pitch);
    }

    /** Thread-safe. As above with the same gain on both channels. */
    public boolean noteOn(int key, Sample attack, ReleaseSet releases, float gain, float pitch) {
        return noteOn(key, attack, releases, gain, gain, pitch);
    }

    /** Thread-safe. Returns false if the command queue is full. */
    public boolean noteOff(int key) {
        return commands.offer(CommandQueue.NOTE_OFF, key, null, null, 0f, 0f, 0f);
    }

    /** Thread-safe. Releases every sustaining voice. Returns false if the command queue is full. */
    public boolean allNotesOff() {
        return commands.offer(CommandQueue.ALL_NOTES_OFF, 0, null, null, 0f, 0f, 0f);
    }

    /**
     * Runs every playback path (looped/unlooped, mono/stereo, separate and embedded releases,
     * voice stealing) on silent synthetic data so the JIT has compiled it before the first
     * real-time callback. Call before the audio device starts.
     */
    public void warmUp() {
        Sample mono = syntheticSample(1, true, -1);
        Sample stereo = syntheticSample(2, true, -1);
        Sample embedded = syntheticSample(1, true, 3600);
        Sample tail = syntheticSample(1, false, -1);
        ReleaseSet tailOnly = ReleaseSet.of(tail);
        ReleaseSet embeddedOnly = ReleaseSet.of(embedded);
        ReleaseSet byLength = new ReleaseSet(new Sample[]{tail, embedded, tail},
                new int[]{20, 60, ReleaseSet.UNLIMITED});
        float[] out = new float[512];
        for (int round = 0; round < 60; round++) {
            for (int k = 0; k < 48; k++) {
                switch (k % 4) {
                    case 0 -> noteOn(k, mono, tailOnly, 0f, 0.5f + k * 0.05f);
                    case 1 -> noteOn(k, stereo, tailOnly, 0f, 0.5f + k * 0.05f);
                    case 2 -> noteOn(k, embedded, embeddedOnly, 0f, 0.5f + k * 0.05f);
                    default -> noteOn(k, embedded, byLength, 0f, 0.5f + k * 0.05f);
                }
            }
            for (int b = 0; b < 2 + round % 20; b++) { // vary note length so every release is chosen
                render(out, 256);
            }
            for (int k = 0; k < 48; k++) {
                noteOff(k);
            }
            do {
                render(out, 256);
            } while (activeVoices > 0);
        }

        // Fill every voice, then release them so release tails have to steal voices
        for (int k = 0; k < maxVoices; k++) {
            noteOn(k, mono, tailOnly, 0f, 1f);
            if ((k & 1023) == 1023) {
                render(out, 256);
            }
        }
        render(out, 256);
        for (int k = 0; k < maxVoices; k++) {
            noteOff(k);
        }
        do {
            render(out, 256);
        } while (activeVoices > 0);
        droppedNotes = 0;
    }

    private static Sample syntheticSample(int channels, boolean looped, int releaseOffset) {
        int frames = 4800;
        short[] data = new short[frames * channels];
        for (int i = 0; i < data.length; i++) {
            data[i] = (short) ((i * 7919) % 2000 - 1000);
        }
        return looped ? new Sample(data, channels, 48000, 1200, 2400, releaseOffset)
                : new Sample(data, channels, 48000, -1, -1, -1);
    }

    public int activeVoices() {
        return activeVoices;
    }

    public long droppedNotes() {
        return droppedNotes;
    }

    /**
     * Thread-safe. Master output trim, applied to the finished mix before the audio output clips it
     * to full scale (Hauptwerk's "Output level adjustment"). 0 dB, the default, leaves the mix
     * bit-for-bit unchanged.
     */
    public void setOutputTrimDecibels(double db) {
        outputGain = (float) Math.pow(10, db / 20);
    }

    public double outputTrimDecibels() {
        return 20 * Math.log10(outputGain);
    }

    /** Audio thread only. Fills {@code out} with {@code frames} interleaved stereo frames. */
    public void render(float[] out, int frames) {
        commands.drain(sink);

        int stereoLen = frames << 1;
        for (int i = 0; i < stereoLen; i++) {
            out[i] = 0f;
        }

        int active = 0;
        for (int v = 0; v < maxVoices; v++) {
            if (state[v] != FREE) {
                mixVoice(v, out, frames);
                if (state[v] != FREE) {
                    active++;
                }
            }
        }
        activeVoices = active;

        float trim = outputGain;
        for (int i = 0; i < stereoLen; i++) {
            out[i] *= trim;
        }
    }

    /**
     * Mixes a voice at constant level (the common case: anything not crossfading) in runs that
     * need no per-frame checks: each run stops before the interpolation's next sample would cross
     * the loop seam or the end, and the frame at the boundary takes the slow path. Arithmetic and
     * summation order match {@link #mixVoiceFading}, so the output is bit-identical.
     */
    private void mixVoice(int v, float[] out, int frames) {
        if (envDelta[v] != 0f) {
            mixVoiceFading(v, out, frames);
            return;
        }
        Sample s = sample[v];
        short[] d = s.data;
        boolean stereo = s.channels == 2;
        boolean looped = loops[v];
        int limit = looped ? s.loopEnd : end[v];
        double p = position[v];
        double st = step[v];
        float ampL = gainL[v] * PCM_SCALE * env[v];
        float ampR = gainR[v] * PCM_SCALE * env[v];

        int f = 0;
        while (f < frames) {
            if (p >= limit) {
                if (!looped) {
                    freeVoice(v);
                    return;
                }
                p -= s.loopEnd - s.loopStart;
                continue;
            }
            // frames k = 0..run-1 satisfy p + k*st < limit - 1, so index + 1 stays inside the region;
            // one frame is held back so accumulated rounding in p can never reach the boundary
            int run = (int) ((limit - 1 - p) / st) - 1;
            if (run > frames - f) {
                run = frames - f;
            }
            if (run > 0) {
                p = stereo ? mixStereoRun(d, out, f, run, p, st, ampL, ampR) : mixMonoRun(d, out, f, run, p, st, ampL, ampR);
                f += run;
                continue;
            }
            // boundary frame: interpolate across the loop seam, or hold the last sample
            int i = (int) p;
            float frac = (float) (p - i);
            int n = i + 1 < limit ? i + 1 : (looped ? s.loopStart : i);
            int o = f << 1;
            if (stereo) {
                int a = i << 1;
                int b = n << 1;
                out[o] += (d[a] + (d[b] - d[a]) * frac) * ampL;
                out[o + 1] += (d[a + 1] + (d[b + 1] - d[a + 1]) * frac) * ampR;
            } else {
                float x = d[i] + (d[n] - d[i]) * frac;
                out[o] += x * ampL;
                out[o + 1] += x * ampR;
            }
            p += st;
            f++;
        }
        if (looped && p >= s.loopEnd) {
            p -= s.loopEnd - s.loopStart;
        }
        position[v] = p;
        soundedFrames[v] += frames;
    }

    private static double mixMonoRun(short[] d, float[] out, int f0, int run, double p, double st,
                                     float ampL, float ampR) {
        int o = f0 << 1;
        for (int k = 0; k < run; k++, o += 2) {
            int i = (int) p;
            float frac = (float) (p - i);
            float x = d[i] + (d[i + 1] - d[i]) * frac;
            out[o] += x * ampL;
            out[o + 1] += x * ampR;
            p += st;
        }
        return p;
    }

    private static double mixStereoRun(short[] d, float[] out, int f0, int run, double p, double st,
                                       float ampL, float ampR) {
        int o = f0 << 1;
        for (int k = 0; k < run; k++, o += 2) {
            int i = (int) p;
            float frac = (float) (p - i);
            int a = i << 1;
            out[o] += (d[a] + (d[a + 2] - d[a]) * frac) * ampL;
            out[o + 1] += (d[a + 1] + (d[a + 3] - d[a + 1]) * frac) * ampR;
            p += st;
        }
        return p;
    }

    /** General per-frame loop, used while a voice's envelope is moving (release crossfades). */
    private void mixVoiceFading(int v, float[] out, int frames) {
        Sample s = sample[v];
        short[] d = s.data;
        boolean stereo = s.channels == 2;
        boolean looped = loops[v];
        int limit = looped ? s.loopEnd : end[v];
        int loopLength = looped ? s.loopEnd - s.loopStart : 0;
        double p = position[v];
        double st = step[v];
        float gl = gainL[v] * PCM_SCALE;
        float gr = gainR[v] * PCM_SCALE;
        float e = env[v];
        float de = envDelta[v];

        for (int f = 0; f < frames; f++) {
            int i = (int) p;
            if (i >= limit) {
                freeVoice(v); // only reachable for unlooped voices
                return;
            }
            float frac = (float) (p - i);
            int n = i + 1;
            if (n >= limit) {
                n = looped ? s.loopStart : i;
            }

            float l;
            float r;
            if (stereo) {
                int a = i << 1;
                int b = n << 1;
                l = d[a] + (d[b] - d[a]) * frac;
                r = d[a + 1] + (d[b + 1] - d[a + 1]) * frac;
            } else {
                l = d[i] + (d[n] - d[i]) * frac;
                r = l;
            }

            out[f << 1] += l * (gl * e);
            out[(f << 1) + 1] += r * (gr * e);

            e += de;
            if (e <= 0f) {
                freeVoice(v);
                return;
            }
            p += st;
            if (looped && p >= s.loopEnd) {
                p -= loopLength;
            }
        }
        position[v] = p;
        env[v] = e;
        soundedFrames[v] += frames;
    }

    private int startVoice(int k, Sample s, int newState, float gl, float gr, double rateRatio, int startFrame,
                           boolean loop) {
        int v = findVoice();
        if (v < 0) {
            droppedNotes++;
            return -1;
        }
        if (state[v] == SUSTAIN && sustainVoice[key[v]] == v) {
            sustainVoice[key[v]] = -1;
        }
        state[v] = newState;
        key[v] = k;
        sample[v] = s;
        releases[v] = null;
        soundedFrames[v] = 0;
        position[v] = startFrame;
        step[v] = rateRatio;
        loops[v] = loop;
        // an attack that shares its file with the release must stop at the release marker
        end[v] = newState == SUSTAIN && s.releaseOffset > 0 ? s.releaseOffset : s.frames;
        gainL[v] = gl;
        gainR[v] = gr;
        env[v] = 1f;
        envDelta[v] = 0f;
        return v;
    }

    /** Pops a free voice in O(1); only when none is free, scans to steal the quietest releasing voice. */
    private int findVoice() {
        if (freeCount > 0) {
            return freeStack[--freeCount];
        }
        int quietest = -1;
        float quietestEnv = Float.MAX_VALUE;
        for (int v = 0; v < maxVoices; v++) {
            if (state[v] == RELEASE && env[v] < quietestEnv) {
                quietestEnv = env[v];
                quietest = v;
            }
        }
        return quietest;
    }

    private void freeVoice(int v) {
        if (state[v] == SUSTAIN && sustainVoice[key[v]] == v) {
            sustainVoice[key[v]] = -1;
        }
        state[v] = FREE;
        sample[v] = null;
        releases[v] = null;
        freeStack[freeCount++] = v;
    }

    private void release(int v) {
        Sample attack = sample[v];
        ReleaseSet set = releases[v];
        Sample release = set != null ? set.select(soundedFrames[v] * 1000 / outputRate) : null;
        sustainVoice[key[v]] = -1;
        state[v] = RELEASE;
        envDelta[v] = -releaseFadeStep;
        if (release != null) {
            int start = release == attack && attack.releaseOffset > 0 ? attack.releaseOffset : 0;
            double ratio = step[v] * release.sampleRate / attack.sampleRate;
            startVoice(key[v], release, RELEASE, gainL[v], gainR[v], ratio, start, false);
        }
    }

    private final class Sink implements CommandQueue.Sink {
        @Override
        public void noteOn(int k, Sample attack, ReleaseSet releaseSet, float gl, float gr, float pitch) {
            if (k < 0 || k >= sustainVoice.length) {
                droppedNotes++;
                return;
            }
            if (sustainVoice[k] >= 0) {
                release(sustainVoice[k]); // retrigger: release the old voice first
            }
            double ratio = (double) attack.sampleRate / outputRate * pitch;
            int v = startVoice(k, attack, SUSTAIN, gl, gr, ratio, 0, attack.isLooped());
            if (v >= 0) {
                releases[v] = releaseSet;
                sustainVoice[k] = v;
            }
        }

        @Override
        public void noteOff(int k) {
            if (k < 0 || k >= sustainVoice.length) {
                return;
            }
            int v = sustainVoice[k];
            if (v >= 0) {
                release(v);
            }
        }

        @Override
        public void allNotesOff() {
            for (int v = 0; v < maxVoices; v++) {
                if (state[v] == SUSTAIN) {
                    release(v);
                }
            }
        }
    }
}
