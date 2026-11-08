package organium;

import organium.audio.ReleaseSet;
import organium.audio.Sample;
import organium.audio.VoiceEngine;
import organium.io.OrganParser;
import organium.organ.Console;
import organium.organ.Organ;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Renders deterministic scenarios through the engine's public API and records the raw output.
 *
 *   RenderRegression record <dir> [organ.xml]   write <scenario>.f32 (little-endian float stereo) per scenario
 *   RenderRegression compare <dir> [organ.xml]  render again and compare with the recorded files
 *
 * The engine is driven from one thread: commands are queued, then render() is called in fixed
 * 256-frame blocks, so the output is fully determined by the scenario script.
 */
public final class RenderRegression {
    private static final int RATE = 48000;
    private static final int BLOCK = 256;

    /** A scenario script: issues commands on the engine between rendered blocks. */
    private interface Script {
        void run(VoiceEngine engine, Renderer r);
    }

    /** Renders blocks into an accumulating buffer. */
    private static final class Renderer {
        final VoiceEngine engine;
        final float[] block = new float[BLOCK * 2];
        final List<float[]> out = new ArrayList<>();

        Renderer(VoiceEngine engine) {
            this.engine = engine;
        }

        void blocks(int n) {
            for (int i = 0; i < n; i++) {
                engine.render(block, BLOCK);
                out.add(block.clone());
            }
        }

        float[] all() {
            float[] a = new float[out.size() * BLOCK * 2];
            for (int i = 0; i < out.size(); i++) {
                System.arraycopy(out.get(i), 0, a, i * BLOCK * 2, BLOCK * 2);
            }
            return a;
        }
    }

    private static Sample synth(int channels, int frames, int loopStart, int loopEnd, int releaseOffset, int seed) {
        short[] d = new short[frames * channels];
        long s = seed;
        for (int i = 0; i < d.length; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            int tone = (int) (9000 * Math.sin(2 * Math.PI * (i / channels) * (110 + seed * 7) / RATE));
            d[i] = (short) (tone + ((s >>> 52) - 2048) + (i % channels) * 1500);
        }
        return new Sample(d, channels, RATE, loopStart, loopEnd, releaseOffset);
    }

    private static Map<String, Script> engineScenarios() {
        Sample mono = synth(1, 24000, 6000, 18000, -1, 1);
        Sample stereo = synth(2, 24000, 6000, 18000, -1, 2);
        Sample embedded = synth(1, 30000, 6000, 18000, 21000, 3);
        Sample shortRel = synth(1, 4000, -1, -1, -1, 4);
        Sample midRel = synth(1, 7000, -1, -1, -1, 5);
        Sample longRel = synth(2, 12000, -1, -1, -1, 6);
        ReleaseSet three = new ReleaseSet(new Sample[]{shortRel, midRel, longRel}, new int[]{40, 120, ReleaseSet.UNLIMITED});
        Map<String, Script> m = new java.util.LinkedHashMap<>();
        m.put("mono-sustain-release", (e, r) -> {
            e.noteOn(1, mono, ReleaseSet.of(shortRel), 0.5f, 1f);
            r.blocks(200);
            e.noteOff(1);
            r.blocks(60);
        });
        m.put("stereo-pitched", (e, r) -> {
            e.noteOn(1, stereo, ReleaseSet.of(longRel), 0.4f, 1.0f);
            e.noteOn(2, stereo, ReleaseSet.of(longRel), 0.3f, 1.4983f);
            e.noteOn(3, mono, ReleaseSet.of(longRel), 0.2f, 0.7071f);
            r.blocks(150);
            e.noteOff(2);
            r.blocks(30);
            e.noteOff(1);
            e.noteOff(3);
            r.blocks(80);
        });
        m.put("embedded-release", (e, r) -> {
            e.noteOn(5, embedded, ReleaseSet.of(embedded), 0.6f, 1.12f);
            r.blocks(120);
            e.noteOff(5);
            r.blocks(80);
        });
        m.put("multiple-releases-by-duration", (e, r) -> {
            int[] holds = {5, 20, 60};      // 27 ms, 107 ms, 320 ms at 256 frames/block
            for (int k = 0; k < holds.length; k++) {
                e.noteOn(10 + k, mono, three, 0.5f, 1f + 0.1f * k);
                r.blocks(holds[k]);
                e.noteOff(10 + k);
                r.blocks(40);
            }
        });
        m.put("retrigger", (e, r) -> {
            e.noteOn(7, mono, three, 0.5f, 1f);
            r.blocks(30);
            e.noteOn(7, stereo, three, 0.5f, 1f);
            r.blocks(30);
            e.noteOff(7);
            r.blocks(60);
        });
        m.put("polyphony-stealing", (e, r) -> {
            for (int k = 0; k < 300; k++) {   // engine has 256 voices: tails get stolen
                e.noteOn(k, (k & 1) == 0 ? mono : stereo, ReleaseSet.of(longRel), 0.01f, 0.5f + k * 0.005f);
                if ((k & 15) == 15) {
                    r.blocks(1);
                }
            }
            r.blocks(20);
            for (int k = 0; k < 300; k += 2) {
                e.noteOff(k);
            }
            r.blocks(10);
            for (int k = 300; k < 360; k++) {
                e.noteOn(k, mono, ReleaseSet.of(shortRel), 0.01f, 1.3f);
            }
            r.blocks(40);
            e.allNotesOff();
            r.blocks(80);
        });
        return m;
    }

    /** Polyphony Testing Organ through Console: chord held while the exclusive stops are switched. */
    private static Script consoleScenario(Organ organ, Map<Integer, Sample> samples, Consumer<Console> hook) {
        return (e, r) -> {
            Console c = new Console(organ, samples, e);
            hook.accept(c);
            Organ.Stop ten = organ.stops().get(0);
            Organ.Stop twenty = organ.stops().get(1);
            c.setEngaged(ten.control(), true);
            int[] chord = {48, 55, 60, 64, 67};
            for (int n : chord) {
                c.keyDown(0, n);
            }
            r.blocks(100);
            c.setEngaged(twenty.control(), true);     // exclusive group: 10 -> 20 under held keys
            r.blocks(100);
            c.keyUp(0, 55);
            c.setEngaged(ten.control(), true);        // 20 -> 10
            r.blocks(60);
            for (int n : chord) {
                c.keyUp(0, n);
            }
            r.blocks(150);
        };
    }

    /** Render cost with 200 sounding voices (100 mono, 100 stereo; 50 of them crossfading), and allocation. */
    private static void bench() {
        Sample mono = synth(1, 96000, 24000, 90000, -1, 1);
        Sample stereo = synth(2, 96000, 24000, 90000, -1, 2);
        Sample tail = synth(1, 480000, -1, -1, -1, 4);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        float[] out = new float[BLOCK * 2];
        for (int round = 0; round < 7; round++) {
            VoiceEngine e = new VoiceEngine(256, 4096, RATE, 2.0f);   // 2 s crossfade keeps released voices fading
            e.warmUp();
            for (int k = 0; k < 200; k++) {
                e.noteOn(k, (k & 1) == 0 ? mono : stereo, ReleaseSet.of(tail), 0.004f, 0.8f + k * 0.001f);
            }
            e.render(out, BLOCK);
            for (int k = 0; k < 50; k++) {
                e.noteOff(k);                                          // 50 voices crossfade into their release
            }
            for (int i = 0; i < 2000; i++) {
                e.render(out, BLOCK);
            }
            long id = Thread.currentThread().threadId();
            long a0 = mx.getThreadAllocatedBytes(id);
            long t0 = System.nanoTime();
            int blocks = 3000;
            for (int i = 0; i < blocks; i++) {
                e.render(out, BLOCK);
            }
            long t1 = System.nanoTime();
            long a1 = mx.getThreadAllocatedBytes(id);
            double voiceFrames = (double) e.activeVoices() * blocks * BLOCK;
            System.out.printf("bench round %d: %d active voices, %.2f ns per voice-frame, %.1f us per %d-frame block, allocated %d bytes in %d blocks%n",
                    round, e.activeVoices(), (t1 - t0) / voiceFrames, (t1 - t0) / 1000.0 / blocks, BLOCK, a1 - a0, blocks);
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("bench")) {
            bench();
            return;
        }
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);
        Map<String, Script> scenarios = new java.util.LinkedHashMap<>(engineScenarios());
        if (args.length > 2) {
            Path def = Path.of(args[2]);
            Organ organ = OrganParser.parse(def);
            Map<Integer, Sample> samples = OrganParser.loadSamples(organ, OrganParser.sampleSetsRootOf(def)).byId();
            scenarios.put("console-stop-changes", consoleScenario(organ, samples, c -> { }));
        }
        int failures = 0;
        for (Map.Entry<String, Script> s : scenarios.entrySet()) {
            VoiceEngine engine = new VoiceEngine(256, 4096, RATE, 0.05f);
            Renderer r = new Renderer(engine);
            s.getValue().run(engine, r);
            float[] audio = r.all();
            Path file = dir.resolve(s.getKey() + ".f32");
            if (mode.equals("record")) {
                write(file, audio);
                System.out.printf("recorded %-32s %7d frames  peak %.4f  sha256 %s%n", s.getKey(), audio.length / 2,
                        peak(audio), sha(audio).substring(0, 16));
            } else if (!Files.exists(file)) {
                // Baselines rendered from third-party samples are kept out of the public repository
                System.out.printf("%-32s NO BASELINE (record one locally to enable this scenario)%n", s.getKey());
            } else {
                float[] ref = read(file);
                boolean identical = java.util.Arrays.equals(ref, audio);
                double maxDiff = 0;
                for (int i = 0; i < Math.min(ref.length, audio.length); i++) {
                    maxDiff = Math.max(maxDiff, Math.abs(ref[i] - audio[i]));
                }
                if (!identical) {
                    failures++;
                }
                System.out.printf("%-32s %s  frames %d/%d  max |diff| %.3g%n", s.getKey(),
                        identical ? "BIT-IDENTICAL" : "DIFFERS      ", audio.length / 2, ref.length / 2, maxDiff);
            }
        }
        if (mode.equals("compare")) {
            System.out.println(failures == 0 ? "ALL SCENARIOS BIT-IDENTICAL" : failures + " SCENARIO(S) DIFFER");
            System.exit(failures == 0 ? 0 : 1);
        }
    }

    private static double peak(float[] a) {
        double p = 0;
        for (float v : a) {
            p = Math.max(p, Math.abs(v));
        }
        return p;
    }

    private static String sha(float[] a) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asFloatBuffer().put(a);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.array()));
    }

    private static void write(Path file, float[] a) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asFloatBuffer().put(a);
        Files.write(file, b.array());
    }

    private static float[] read(Path file) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        float[] a = new float[b.remaining() / 4];
        b.asFloatBuffer().get(a);
        return a;
    }
}
