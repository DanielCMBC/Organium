package organium;

import organium.audio.RenderMonitor;
import organium.audio.Sample;
import organium.audio.VoiceEngine;
import organium.audio.asio.AsioOutput;
import organium.io.OrganParser;
import organium.organ.Console;
import organium.organ.Organ;
import organium.organ.spatial.MeasuredPanTable;
import organium.ui.ConsoleWindow;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.awt.Robot;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Sustained live-performance test through ASIO, using the same stack as App: the organ loaded with
 * OrganParser, Console with the measured pan table, VoiceEngine (4096 voices, -10 dB trim), the
 * console window, and AsioOutput. A performance thread plays a scripted, seeded concert in real
 * time (as the MIDI thread would) while a monitor thread records, every second, voices, callback
 * render times and gaps, audio-thread allocation, garbage collection, heap and free RAM.
 *
 * Usage: LiveStress <organ.xml> <seconds> <out dir> [garbage MB/s] [asio driver] [options]
 * The optional garbage rate runs a background thread that allocates (and briefly keeps) memory at that
 * rate, to make the collector run often enough to measure its pauses within a short test.
 * Writes <out dir>/timeline.csv, summary.txt and console-*.png.
 *
 * Options (for startup diagnosis):
 *   --no-screenshots   take no screenshots of the console window
 *   --stop-after=S     end the performance S seconds after it starts, even mid-round
 *   --stage0-release   use the release transition from before the crossfade fix (A/B comparisons)
 * Every run also writes startup-callbacks.csv (the time and render time of the first callbacks,
 * from the very first one) and startup-events.csv (what the harness did, on the same clock: zero
 * is the moment AsioOutput.start() was called).
 */
public final class LiveStress {
    private static final int RATE = 48000;
    private record Event(long nanoTime, long uptimeMs, String what) {
    }

    private static final List<Event> EVENTS = java.util.Collections.synchronizedList(new ArrayList<>());
    private static volatile long clock0 = System.nanoTime(); // reset when ASIO is started
    private static volatile long stopAt = Long.MAX_VALUE;
    private static boolean screenshots = true;
    private static boolean stage0Release;  // --stage0-release: the release transition before the crossfade fix, for A/B
    private boolean firstNote = true;

    private final Console console;
    private final Organ organ;
    private final ConsoleWindow window;
    private final Map<String, Organ.Switch> controls = new HashMap<>();
    private final int hw;
    private final int bw;
    private final int ped;
    private final Random rnd = new Random(20261107);
    private final long start = System.nanoTime();
    private final Path out;
    volatile String section = "loading";
    private int screenshot;

    private LiveStress(Console console, ConsoleWindow window, Path out) {
        this.console = console;
        this.organ = console.organ();
        this.window = window;
        this.out = out;
        for (Organ.Stop s : organ.stops()) {
            controls.put(s.name(), s.control());
        }
        for (Organ.KeyAction a : organ.keyActions()) {
            if (a.control() != null) {
                controls.put(a.name(), a.control());
            }
        }
        hw = channelOf("Hauptwerk");
        bw = channelOf("Brustwerk");
        ped = channelOf("Pedal");
    }

    private int channelOf(String keyboard) {
        for (int ch = 0; ch < Console.CHANNELS; ch++) {
            Organ.Keyboard k = console.keyboardOn(ch);
            if (k != null && k.name().equals(keyboard)) {
                return ch;
            }
        }
        throw new IllegalStateException("No channel plays " + keyboard);
    }

    // ---- performance primitives (performance thread) ----

    private void sleep(double ms) throws InterruptedException {
        if (System.nanoTime() > stopAt) {
            throw new InterruptedException("stop-after reached");
        }
        if (ms > 0) {
            Thread.sleep((long) ms, (int) ((ms % 1) * 1e6));
        }
    }

    private void set(boolean on, String... names) {
        for (String n : names) {
            Organ.Switch s = controls.entrySet().stream().filter(e -> e.getKey().startsWith(n)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No stop or coupler " + n)).getValue();
            console.setEngaged(s, on);
        }
        window.repaint();
    }

    private void clearAll() {
        controls.values().forEach(s -> console.setEngaged(s, false));
        window.repaint();
    }

    private void down(int ch, int... notes) {
        if (firstNote) {
            firstNote = false;
            mark("first note on");
        }
        for (int n : notes) {
            console.keyDown(ch, n);
        }
    }

    private void up(int ch, int... notes) {
        for (int n : notes) {
            console.keyUp(ch, n);
        }
    }

    private void shot(String name) {
        if (!screenshots) {
            return;
        }
        mark("screenshot " + name + " begins");
        try {
            Rectangle[] r = new Rectangle[1];
            SwingUtilities.invokeAndWait(() -> r[0] = window.getBounds());
            ImageIO.write(new Robot().createScreenCapture(r[0]), "png",
                    out.resolve(String.format("console-%02d-%s.png", ++screenshot, name)).toFile());
        } catch (Exception e) {
            System.out.println("screenshot failed: " + e);
        }
        mark("screenshot " + name + " ends");
    }

    /** Logs an event with its time since ASIO was started, to line up with the callback trace. */
    private static void mark(String what) {
        long now = System.nanoTime();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime(); // the clock of the -Xlog "uptime" decoration
        EVENTS.add(new Event(now, uptime, what));
        System.out.printf(Locale.ROOT, "  [uptime %6d ms] %s%n", uptime, what);
    }

    /** Records when each of the first callbacks started and how long it rendered. Allocation-free. */
    private static final class CallbackTrace implements organium.audio.AudioOutput.Renderer {
        private final organium.audio.AudioOutput.Renderer inner;
        private final long[] at = new long[8000];
        private final int[] rendered = new int[at.length];
        private volatile int count;

        CallbackTrace(organium.audio.AudioOutput.Renderer inner) {
            this.inner = inner;
        }

        @Override
        public void render(float[] out, int frames) {
            long s = System.nanoTime();
            inner.render(out, frames);
            int i = count;
            if (i < at.length) {
                at[i] = s;
                rendered[i] = (int) (System.nanoTime() - s);
                count = i + 1;
            }
        }

        void write(Path file) throws java.io.IOException {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
                w.println("index,t_ms,interval_ms,render_ms");
                int n = count;
                for (int i = 0; i < n; i++) {
                    w.printf(Locale.ROOT, "%d,%.3f,%s,%.3f%n", i, (at[i] - clock0) / 1e6,
                            i == 0 ? "" : String.format(Locale.ROOT, "%.3f", (at[i] - at[i - 1]) / 1e6), rendered[i] / 1e6);
                }
            }
        }
    }

    private static final int[][] PROGRESSION = {
            {60, 64, 67, 72}, {62, 65, 69, 74}, {64, 67, 71, 76}, {65, 69, 72, 77},
            {67, 71, 74, 79}, {65, 69, 72, 77}, {62, 67, 71, 74}, {60, 64, 67, 72}};

    // ---- the concert ----

    private void playOnce(int round) throws InterruptedException {
        section = "1-chords";
        mark("round " + round + " begins");
        clearAll();
        set(true, "I Nachthorn", "I Prinzipal 4", "II Gedackt", "P Subbass", "P Gedacktbass");
        if (round == 0) {
            shot("chords-registration");
        }
        int[] prevR = null;
        int[] prevL = null;
        int prevP = -1;
        for (int[] c : PROGRESSION) {
            int[] l = {c[0] - 12, c[1] - 12, c[2] - 12};
            int p = c[0] - 24;
            down(hw, c);
            down(bw, l);
            down(ped, p);
            sleep(30);                           // legato overlap
            if (prevR != null) {
                up(hw, prevR);
                up(bw, prevL);
                up(ped, prevP);
            }
            prevR = c;
            prevL = l;
            prevP = p;
            sleep(1970);
        }
        up(hw, prevR);
        up(bw, prevL);
        up(ped, prevP);

        section = "2-counterpoint";
        set(true, "I Oktave", "I Mixtur", "HW/PED", "II Rohrfl", "BW/HW");
        int mel = 72;
        int bass = 48;
        int pedal = 36;
        for (int beat = 0; beat < 60; beat++) {   // 30 s at 120 bpm, eighths in the melody
            if (beat % 2 == 0) {
                down(bw, bass);
            }
            if (beat % 4 == 0) {
                down(ped, pedal);
            }
            for (int e = 0; e < 2; e++) {
                int next = Math.max(60, Math.min(88, mel + rnd.nextInt(5) - 2));
                down(hw, next);
                sleep(15);
                up(hw, mel);
                mel = next;
                sleep(235);
            }
            if (beat % 2 == 1) {
                up(bw, bass);
                bass = Math.max(41, Math.min(60, bass + rnd.nextInt(5) - 2));
            }
            if (beat % 4 == 3) {
                up(ped, pedal);
                pedal = Math.max(36, Math.min(55, pedal + rnd.nextInt(7) - 3));
            }
        }
        up(hw, mel);

        section = "3-rapid";
        clearAll();
        set(true, "I Nachthorn", "I Prinzipal 4", "I Oktave", "I Mixtur", "I Trompete", "P Subbass", "P Trompete");
        for (int i = 0; i < 50; i++) {            // repeated note, 10 per second
            down(hw, 67);
            sleep(50);
            up(hw, 67);
            sleep(50);
        }
        for (int i = 0; i < 60; i++) {            // trill, 12 per second
            int n = i % 2 == 0 ? 74 : 76;
            down(hw, n);
            sleep(70);
            up(hw, n);
            sleep(13);
        }
        for (int i = 0; i < 60; i++) {            // staccato chords, 6 per second, with pedal
            int[] c = PROGRESSION[i % PROGRESSION.length];
            down(hw, c);
            down(ped, c[0] - 24);
            sleep(60);
            up(hw, c);
            up(ped, c[0] - 24);
            sleep(107);
        }
        for (int i = 0; i < 160; i++) {           // scales, 16 notes per second, both manuals
            int n = 48 + (i % 32 < 16 ? i % 16 : 16 - i % 16) * 2;
            down(hw, n + 12);
            down(bw, n);
            sleep(55);
            up(hw, n + 12);
            up(bw, n);
            sleep(7.5);
        }

        section = "4-stop-changes";
        clearAll();
        set(true, "I Nachthorn", "II Gedackt", "P Subbass");
        down(hw, 60, 64, 67, 72);
        down(bw, 48, 55, 60);
        down(ped, 36);
        String[] toggles = {"I Prinzipal 4", "I Oktave", "I Mixtur", "I Trompete", "I Nasard", "II Quintat",
                "II Rohrfl", "II Prinzpal", "II Quinte", "II Siffl", "II Krumhorn", "P Gedacktbass", "P Choralbas",
                "P Oktave", "P Trompete", "HW/PED", "BW/PED", "BW/HW"};
        for (int i = 0; i < 60; i++) {            // 30 s, a stop or coupler every 0.5 s
            String t = toggles[rnd.nextInt(toggles.length)];
            Organ.Switch s = controls.entrySet().stream().filter(e -> e.getKey().startsWith(t)).findFirst().orElseThrow().getValue();
            console.setEngaged(s, !console.isEngaged(s));
            window.repaint();
            if (i == 30 && round == 0) {
                shot("stop-changes");
            }
            sleep(500);
        }
        up(hw, 60, 64, 67, 72);
        up(bw, 48, 55, 60);
        up(ped, 36);

        section = "5-tutti";
        for (Organ.Switch s : controls.values()) {
            console.setEngaged(s, true);
        }
        window.repaint();
        if (round == 0) {
            shot("tutti");
        }
        for (int i = 0; i < 26; i++) {            // 20 s of legato 10-note chords plus pedal
            int[] c = PROGRESSION[i % PROGRESSION.length];
            int[] r = {c[0], c[1], c[2], c[3], c[3] + 4};
            int[] l = {c[0] - 24, c[0] - 12, c[1] - 12, c[2] - 12, c[3] - 12};
            down(hw, r);
            down(bw, l);
            down(ped, c[0] - 24, c[0] - 12);
            sleep(740);
            up(hw, r);
            up(bw, l);
            up(ped, c[0] - 24, c[0] - 12);
            sleep(10);
        }
        for (int i = 0; i < 40; i++) {            // 10 s of staccato tutti chords, 4 per second
            int[] c = PROGRESSION[i % PROGRESSION.length];
            down(hw, c);
            down(bw, c[0] - 12, c[2] - 12);
            down(ped, c[0] - 24);
            sleep(80);
            up(hw, c);
            up(bw, c[0] - 12, c[2] - 12);
            up(ped, c[0] - 24);
            sleep(170);
        }

        section = "6-random";
        clearAll();
        set(true, "I Prinzipal 4", "II Gedackt", "P Subbass");
        long end = System.nanoTime() + 30_000_000_000L;
        List<int[]> held = new ArrayList<>();  // {channel, note, release time in ms since start}
        long nextToggle = 0;
        while (System.nanoTime() < end) {
            long now = (System.nanoTime() - start) / 1_000_000;
            for (int i = held.size() - 1; i >= 0; i--) {
                if (held.get(i)[2] <= now) {
                    console.keyUp(held.get(i)[0], held.get(i)[1]);
                    held.remove(i);
                }
            }
            if (held.size() < 12) {
                int ch = rnd.nextInt(3);
                int lo = ch == ped ? 36 : 36;
                int hi = ch == ped ? 65 : 91;
                int n = lo + rnd.nextInt(hi - lo + 1);
                int dur = (int) Math.min(2000, 20 - Math.log(1 - rnd.nextDouble()) * 250);
                console.keyDown(ch, n);
                held.add(new int[]{ch, n, (int) (now + dur)});
            }
            if (now >= nextToggle) {
                String t = toggles[rnd.nextInt(toggles.length)];
                Organ.Switch s = controls.entrySet().stream().filter(e -> e.getKey().startsWith(t)).findFirst().orElseThrow().getValue();
                console.setEngaged(s, !console.isEngaged(s));
                window.repaint();
                nextToggle = now + 500;
            }
            sleep(25 + rnd.nextInt(60));
        }
        for (int[] h : held) {
            console.keyUp(h[0], h[1]);
        }

        section = "7-silence";
        sleep(8000);
    }

    public static void main(String[] all) throws Exception {
        List<String> positional = new ArrayList<>();
        double stopAfter = -1;
        for (String a : all) {
            if (a.equals("--no-screenshots")) {
                screenshots = false;
            } else if (a.equals("--stage0-release")) {
                stage0Release = true;
            } else if (a.startsWith("--stop-after=")) {
                stopAfter = Double.parseDouble(a.substring("--stop-after=".length()));
            } else if (a.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option " + a);
            } else {
                positional.add(a);
            }
        }
        String[] args = positional.toArray(new String[0]);
        Path def = Path.of(args[0]);
        int seconds = Integer.parseInt(args[1]);
        Path outDir = Path.of(args[2]);
        double garbageMbPerSecond = args.length > 3 ? Double.parseDouble(args[3]) : 0;
        String driver = args.length > 4 && !args[4].equals("-") ? args[4] : null; // "-": first driver
        Files.createDirectories(outDir);

        long t0 = System.nanoTime();
        Organ organ = OrganParser.parse(def);
        OrganParser.LoadedSamples samples = OrganParser.loadSamples(organ, OrganParser.sampleSetsRootOf(def));
        long t1 = System.nanoTime();
        Map<Integer, Sample> byId = samples.byId();
        long sampleBytes = byId.values().stream().mapToLong(s -> s.data.length * 2L).sum();
        System.out.printf("%s: %d samples, %d MiB, loaded in %.1f s%n", organ.name(), byId.size(), sampleBytes >> 20,
                (t1 - t0) / 1e9);

        VoiceEngine engine = new VoiceEngine(4096, organ.pipes().size(), RATE,
                stage0Release ? organium.audio.ReleaseTransition.STAGE0 : organium.audio.ReleaseTransition.DEFAULT);
        mark("release transition: " + (stage0Release ? "stage0" : "default"));
        engine.setOutputTrimDecibels(-10);
        engine.warmUp();
        Console console = new Console(organ, byId, engine, MeasuredPanTable.hauptwerk());
        mark("console window opening");
        ConsoleWindow window = ConsoleWindow.open(console, OrganParser.sampleSetsRootOf(def), () -> { });
        mark("console window open");
        RenderMonitor monitor = new RenderMonitor(engine::render, RATE);
        CallbackTrace trace = new CallbackTrace(monitor);
        AsioOutput asio = new AsioOutput(driver);
        clock0 = System.nanoTime();
        mark("ASIO start called");
        asio.start(RATE, 256, trace);
        mark("ASIO start returned");
        if (stopAfter >= 0) {
            stopAt = System.nanoTime() + (long) (stopAfter * 1e9);
        }
        System.out.printf("ASIO: %s, %d-frame buffer (%.1f ms), reported output latency %.1f ms%n",
                asio.driverDescription(), asio.bufferFrames(), asio.bufferFrames() * 1000.0 / RATE,
                asio.outputLatencyFrames() * 1000.0 / RATE);

        LiveStress perf = new LiveStress(console, window, outDir);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        Runtime rt = Runtime.getRuntime();

        Thread player = new Thread(() -> {
            try {
                for (int round = 0; (System.nanoTime() - perf.start) / 1e9 < seconds; round++) {
                    perf.playOnce(round);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            perf.section = "done";
        }, "performance");
        player.start();
        if (garbageMbPerSecond > 0) {
            Thread garbage = new Thread(() -> {
                byte[][] keep = new byte[64][];    // some objects survive a while, like real GUI/MIDI garbage
                int k = 0;
                long chunk = 64 * 1024;
                long perSecond = (long) (garbageMbPerSecond * 1024 * 1024);
                while (true) {
                    long t = System.nanoTime();
                    for (long b = 0; b < perSecond / 10; b += chunk) {
                        byte[] a = new byte[(int) chunk];
                        if ((b / chunk) % 16 == 0) {
                            keep[k++ & 63] = a;
                        }
                    }
                    try {
                        Thread.sleep(Math.max(0, 100 - (System.nanoTime() - t) / 1_000_000));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "garbage");
            garbage.setDaemon(true);
            garbage.start();
        }

        long warmupEnd = 5;  // seconds: allocation is counted after the first callbacks have compiled
        long allocAtWarmup = -1;
        int maxVoices = 0;
        int maxPipes = 0;
        long minFree = Long.MAX_VALUE;
        long maxHeap = 0;
        long gcCount0 = gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long gcTime0 = gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(outDir.resolve("timeline.csv")))) {
            csv.println("t_s,section,voices,sounding_pipes,max_render_ms,max_gap_ms,overruns,gaps,audio_alloc_bytes,gc_count,gc_ms,heap_used_mb,free_ram_mb,dropped_notes,lost_events");
            while (player.isAlive()) {
                Thread.sleep(1000);
                long t = (System.nanoTime() - perf.start) / 1_000_000_000L;
                long tid = monitor.audioThreadId();
                long alloc = tid >= 0 ? threads.getThreadAllocatedBytes(tid) : 0;
                if (t >= warmupEnd && allocAtWarmup < 0 && tid >= 0) {
                    allocAtWarmup = alloc;
                }
                int voices = engine.activeVoices();
                int pipes = console.soundingPipes();
                maxVoices = Math.max(maxVoices, voices);
                maxPipes = Math.max(maxPipes, pipes);
                long heap = rt.totalMemory() - rt.freeMemory();
                maxHeap = Math.max(maxHeap, heap);
                long free = os.getFreeMemorySize();
                minFree = Math.min(minFree, free);
                csv.printf(Locale.ROOT, "%d,%s,%d,%d,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d%n", t, perf.section, voices, pipes,
                        monitor.takeMaxRenderNanos() / 1e6, monitor.takeMaxGapNanos() / 1e6, monitor.overruns(),
                        monitor.gaps(), alloc, gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum() - gcCount0,
                        gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum() - gcTime0, heap >> 20,
                        free >> 20, engine.droppedNotes(), console.lostEvents());
                csv.flush();
            }
        }
        long allocEnd = threads.getThreadAllocatedBytes(monitor.audioThreadId());
        mark("callback thread priority after boost: " + asio.callbackThreadPriority());
        asio.stop();
        trace.write(outDir.resolve("startup-callbacks.csv"));
        // times relative to the ASIO start call (negative before it)
        Files.write(outDir.resolve("startup-events.csv"), (Iterable<String>) () -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of("t_ms,uptime_ms,event"), new ArrayList<>(EVENTS).stream().map(e -> String.format(
                        Locale.ROOT, "%.3f,%d,%s", (e.nanoTime() - clock0) / 1e6, e.uptimeMs(), e.what()))).iterator());
        SwingUtilities.invokeLater(window::dispose);

        long period = monitor.periodNanos();
        long[] h = monitor.histogram();
        String[] labels = RenderMonitor.histogramLabels();
        StringBuilder hist = new StringBuilder();
        for (int i = 0; i < h.length; i++) {
            hist.append(String.format("%s %d  ", labels[i], h[i]));
        }
        StringBuilder gcText = new StringBuilder();
        for (GarbageCollectorMXBean g : gcs) {
            gcText.append(String.format("%s: %d collections, %d ms; ", g.getName(), g.getCollectionCount(), g.getCollectionTime()));
        }
        double played = (System.nanoTime() - perf.start) / 1e9;
        String summary = String.format(Locale.ROOT, """
                organ: %s (%d samples, %d MiB)
                duration: %.0f s played (%d s asked for; a round always finishes), %d callbacks, buffer %d frames (period %.2f ms)
                render time: mean %.3f ms, worst %.3f ms (%.0f%% of the period); histogram: %s
                callback gaps (every callback, from the first after ASIO start): worst %.2f ms (ended %.3f s after ASIO start; the callback before it rendered for %.3f ms); gaps over 1.5 periods: %d; renders over one period: %d
                audio-thread allocation after warm-up: %d bytes
                garbage collection: %s
                heap in use: max %d MiB; free RAM: min %d MiB
                voices: max %d; sounding pipes: max %d; dropped notes: %d; lost events: %d
                """, organ.name(), byId.size(), sampleBytes >> 20, played, seconds, monitor.calls(), asio.bufferFrames(),
                period / 1e6, monitor.totalRenderNanos() / 1e6 / Math.max(1, monitor.calls()), monitor.worstRenderNanos() / 1e6,
                100.0 * monitor.worstRenderNanos() / period, hist, monitor.worstGapNanos() / 1e6,
                (monitor.worstGapEndNanos() - clock0) / 1e9, monitor.renderBeforeWorstGapNanos() / 1e6, monitor.gaps(),
                monitor.overruns(), allocAtWarmup < 0 ? -1 : allocEnd - allocAtWarmup, gcText, maxHeap >> 20, minFree >> 20,
                maxVoices, maxPipes, engine.droppedNotes(), console.lostEvents());
        Files.writeString(outDir.resolve("summary.txt"), summary);
        System.out.print(summary);
        System.exit(0);
    }
}
