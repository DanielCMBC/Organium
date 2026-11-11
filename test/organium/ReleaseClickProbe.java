package organium;

import organium.audio.ReleaseSet;
import organium.audio.ReleaseTransition;
import organium.audio.Sample;
import organium.audio.VoiceEngine;
import organium.io.OrganParser;
import organium.io.WavReader;
import organium.organ.Organ;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Diagnoses clicks at note-off: renders single pipes offline through the real VoiceEngine (trim 0 dB,
 * the float mix before any PCM conversion or clipping), with note-on at frame 0 and note-off exactly
 * at a block boundary, for note lengths that select each of the pipe's releases. Reports, per
 * channel, the largest sample-to-sample step right after note-off against the largest step while the
 * pipe sustained, the start of each release file, and the release's level against the sustain.
 *
 * Variant "fadein" (diagnostic only) renders the same notes with a copy of each release sample whose
 * first {@code fadeMs} are faded in linearly; the production engine is unchanged.
 *
 * Variants: "stage0" is the engine before the release crossfade (ReleaseTransition.STAGE0), "default"
 * the engine as shipped (ReleaseTransition.DEFAULT).
 *
 * Usage: ReleaseClickProbe <organ.xml> <out dir> <note> <stop prefix>... [--fadein=ms [--symmetric]]
 * Writes <out dir>/<stop>-<note>-<ms>ms-<variant>.wav (32-bit float stereo) for listening.
 */
public final class ReleaseClickProbe {
    private static final int RATE = 48000;
    private static final int BLOCK = 256;
    private static final int[] NOTE_MS = {100, 300, 700, 3000};
    private static boolean symmetric;   // --symmetric: the sustain also fades out over the fade-in length
    private static double xfadeMs = 50;

    public static void main(String[] args) throws Exception {
        Path def = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int note = Integer.parseInt(args[2]);
        List<String> stops = new ArrayList<>();
        double fadeInMs = 0;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--symmetric")) {
                symmetric = true;
            } else if (args[i].startsWith("--fadein=")) {
                fadeInMs = Double.parseDouble(args[i].substring("--fadein=".length()));
            } else {
                stops.add(args[i]);
            }
        }
        Files.createDirectories(out);
        Organ organ = OrganParser.parse(def);
        Path packages = OrganParser.sampleSetsRootOf(def).resolve("OrganInstallationPackages");

        for (String prefix : stops) {
            Organ.Stop stop = organ.stops().stream().filter(s -> s.name().startsWith(prefix)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No stop " + prefix));
            Organ.Rank rank = stop.ranks().get(0).rank();
            Organ.Pipe pipe = organ.pipes().stream().filter(p -> p.rankId() == rank.id())
                    .min((a, b) -> Integer.compare(Math.abs(a.note() - note), Math.abs(b.note() - note))).orElseThrow();
            System.out.printf("%n=== %s, rank %s, pipe note %d%n", stop.name(), rank.name(), pipe.note());
            Sample attack = load(organ, packages, pipe.attackSampleId(), "attack");
            List<Sample> rel = new ArrayList<>();
            int[] limits = new int[pipe.releases().size()];
            for (int i = 0; i < limits.length; i++) {
                Organ.Release r = pipe.releases().get(i);
                limits[i] = r.maxSoundedMs();
                rel.add(load(organ, packages, r.sampleId(),
                        "release (used below " + (r.maxSoundedMs() == ReleaseSet.UNLIMITED ? "any length" : r.maxSoundedMs() + " ms") + ")"));
            }
            describeSustain(attack);
            for (Sample r : rel) {
                describeReleaseStart(r, attack);
            }
            ReleaseSet set = new ReleaseSet(rel.toArray(new Sample[0]), limits);
            double fade = fadeInMs;
            if (symmetric && fade > 0) {
                xfadeMs = fade;
            }
            ReleaseSet faded = fade > 0 ? new ReleaseSet(rel.stream().map(s -> fadeIn(s, fade)).toArray(Sample[]::new), limits) : null;

            for (int ms : NOTE_MS) {
                analyse(stop.name(), pipe.note(), ms, "stage0", attack, set, out);
                analyse(stop.name(), pipe.note(), ms, "default", attack, set, out);
                if (faded != null) {
                    analyse(stop.name(), pipe.note(), ms, "fadein" + (int) fadeInMs + "ms", attack, faded, out);
                }
            }
        }
    }

    private static Sample load(Organ organ, Path packages, int id, String role) throws IOException {
        Organ.SampleFile f = organ.sampleFiles().get(id);
        Path path = packages.resolve(String.format("%06d", f.packageId())).resolve(f.path());
        Sample s = WavReader.read(path);
        System.out.printf("  %-38s %s: %d ch, %d Hz, %d frames (%.2f s), loop %d-%d, release marker %d%n", role,
                f.path(), s.channels, s.sampleRate, s.frames, s.frames / (double) s.sampleRate, s.loopStart, s.loopEnd,
                s.releaseOffset);
        return s;
    }

    /** Peak and mean (DC) of the attack's sustain loop, per channel. */
    private static void describeSustain(Sample a) {
        int from = a.isLooped() ? a.loopStart : a.frames / 2;
        int to = a.isLooped() ? a.loopEnd : a.frames;
        for (int c = 0; c < a.channels; c++) {
            long sum = 0;
            int peak = 0;
            for (int f = from; f < to; f++) {
                int x = a.data[f * a.channels + c];
                sum += x;
                peak = Math.max(peak, Math.abs(x));
            }
            System.out.printf("  sustain loop ch%d: peak %d, mean (DC) %.1f%n", c, peak, sum / (double) (to - from));
        }
    }

    /** The first frames of a release file, its level in the first 1, 5 and 20 ms, and its DC. */
    private static void describeReleaseStart(Sample r, Sample attack) {
        for (int c = 0; c < r.channels; c++) {
            StringBuilder first = new StringBuilder();
            for (int f = 0; f < 6 && f < r.frames; f++) {
                first.append(r.data[f * r.channels + c]).append(' ');
            }
            System.out.printf("  release ch%d first frames: %s| peak in first 1 ms %d, 5 ms %d, 20 ms %d; mean of first 20 ms %.1f%n",
                    c, first, peak(r, c, 0, RATE / 1000), peak(r, c, 0, RATE / 200), peak(r, c, 0, RATE / 50), mean(r, c, 0, RATE / 50));
        }
    }

    private static int peak(Sample s, int c, int from, int to) {
        int p = 0;
        for (int f = from; f < Math.min(to, s.frames); f++) {
            p = Math.max(p, Math.abs(s.data[f * s.channels + c]));
        }
        return p;
    }

    private static double mean(Sample s, int c, int from, int to) {
        long sum = 0;
        int n = 0;
        for (int f = from; f < Math.min(to, s.frames); f++, n++) {
            sum += s.data[f * s.channels + c];
        }
        return n == 0 ? 0 : sum / (double) n;
    }

    private static Sample fadeIn(Sample s, double ms) {
        short[] d = s.data.clone();
        int n = (int) (ms * s.sampleRate / 1000);
        for (int f = 0; f < Math.min(n, s.frames); f++) {
            for (int c = 0; c < s.channels; c++) {
                d[f * s.channels + c] = (short) Math.round(d[f * s.channels + c] * (f / (double) n));
            }
        }
        return new Sample(d, s.channels, s.sampleRate, s.loopStart, s.loopEnd, s.releaseOffset);
    }

    private static void analyse(String stop, int note, int ms, String variant, Sample attack, ReleaseSet set, Path out)
            throws IOException {
        // "stage0": the engine before the fix; "default": the engine as shipped; "fadein…": diagnostic
        // variants that fade a copy of the release in (sustain fade-out as set by --symmetric)
        ReleaseTransition transition = variant.equals("stage0") ? ReleaseTransition.STAGE0
                : variant.equals("default") ? ReleaseTransition.DEFAULT
                : new ReleaseTransition((float) (xfadeMs / 1000), 0.05f, false);
        VoiceEngine engine = new VoiceEngine(64, 128, RATE, transition);
        int offBlock = Math.round(ms * RATE / 1000f / BLOCK);
        int off = offBlock * BLOCK;
        int total = off + RATE * 2; // two seconds of release
        float[] mix = new float[total * 2];
        float[] block = new float[BLOCK * 2];
        engine.noteOn(0, attack, set, 1f, 1f, 1f);
        for (int b = 0; b * BLOCK < total; b++) {
            if (b == offBlock) {
                engine.noteOff(0);
            }
            engine.render(block, BLOCK);
            int n = Math.min(BLOCK, total - b * BLOCK) * 2;
            System.arraycopy(block, 0, mix, b * BLOCK * 2, n);
        }
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-8s %5d ms note, off at frame %6d:", variant, ms, off));
        for (int c = 0; c < 2; c++) {
            // steady state: largest step in the 100 ms before note-off (at least 20 ms after note-on)
            int from = Math.max(RATE / 50, off - RATE / 10);
            float steady = maxStep(mix, c, from, off);
            float steadyPeak = maxAbs(mix, c, from, off);
            float after = maxStep(mix, c, off, off + RATE / 200);   // first 5 ms after note-off
            int at = argMaxStep(mix, c, off, off + RATE / 200);
            float level = maxAbs(mix, c, off, off + RATE / 50);
            line.append(String.format(Locale.ROOT, " ch%d step after off %.4f at +%d (steady max step %.4f, x%.1f); peak 20 ms after %.3f vs before %.3f;",
                    c, after, at - off, steady, after / Math.max(1e-9f, steady), level, steadyPeak));
        }
        // level through the transition: RMS of 5 ms windows over 60 ms after note-off, against the
        // RMS of the 50 ms before it (both channels together), in dB
        double before = rms(mix, off - RATE / 20, off);
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int w = off; w < off + RATE * 60 / 1000; w += RATE / 200) {
            double db = 20 * Math.log10(rms(mix, w, w + RATE / 200) / before);
            lo = Math.min(lo, db);
            hi = Math.max(hi, db);
        }
        line.append(String.format(Locale.ROOT, " level in 60 ms after off: %+.1f to %+.1f dB", lo, hi));
        System.out.println(line);
        if (ms == NOTE_MS[1] || ms == NOTE_MS[3]) {
            StringBuilder around = new StringBuilder("           L around off:");
            for (int f = off - 4; f < off + 6; f++) {
                around.append(String.format(Locale.ROOT, " %s%.4f", f == off ? "|" : "", mix[f * 2]));
            }
            System.out.println(around);
        }
        writeWav(out.resolve(String.format("%s-%d-%dms-%s.wav", stop.replaceAll("[^A-Za-z0-9]+", "_"), note, ms, variant)), mix);
    }

    private static double rms(float[] mix, int from, int to) {
        double sum = 0;
        for (int i = from * 2; i < to * 2; i++) {
            sum += mix[i] * (double) mix[i];
        }
        return Math.sqrt(sum / ((to - from) * 2)) + 1e-12;
    }

    private static float maxStep(float[] mix, int c, int from, int to) {
        float m = 0;
        for (int f = Math.max(1, from); f < to; f++) {
            m = Math.max(m, Math.abs(mix[f * 2 + c] - mix[(f - 1) * 2 + c]));
        }
        return m;
    }

    private static int argMaxStep(float[] mix, int c, int from, int to) {
        float m = -1;
        int at = from;
        for (int f = Math.max(1, from); f < to; f++) {
            float d = Math.abs(mix[f * 2 + c] - mix[(f - 1) * 2 + c]);
            if (d > m) {
                m = d;
                at = f;
            }
        }
        return at;
    }

    private static float maxAbs(float[] mix, int c, int from, int to) {
        float m = 0;
        for (int f = from; f < to; f++) {
            m = Math.max(m, Math.abs(mix[f * 2 + c]));
        }
        return m;
    }

    private static void writeWav(Path file, float[] stereo) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + stereo.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + stereo.length * 4).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 3).putShort((short) 2).putInt(RATE).putInt(RATE * 8)
                .putShort((short) 8).putShort((short) 32);
        b.put("data".getBytes()).putInt(stereo.length * 4);
        for (float x : stereo) {
            b.putFloat(x);
        }
        try (OutputStream o = Files.newOutputStream(file); DataOutputStream d = new DataOutputStream(o)) {
            d.write(b.array());
        }
    }
}
