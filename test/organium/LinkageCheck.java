package organium;

import organium.audio.Sample;
import organium.audio.VoiceEngine;
import organium.io.OrganParser;
import organium.organ.Console;
import organium.organ.Organ;
import organium.organ.Organ.KeyAction;
import organium.organ.Organ.Keyboard;
import organium.organ.Organ.Stop;
import organium.organ.Organ.StopRank;
import organium.organ.Organ.Switch;
import organium.organ.Organ.SwitchLinkage;
import organium.organ.Organ.SwitchLinkage.LinkAction;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Switch linkages, headless, on Freedom Morton 3/10 (every stop and coupler is reached only through
 * linkages: console tab -> logical switch -> control node). Console tabs are "clicked" with
 * {@link Console#toggle}, as ConsoleWindow does; a real VoiceEngine is rendered after each action so
 * its voice count can be compared with the pipes Console drives. Samples are synthetic (no files read).
 *
 * Usage: LinkageCheck <FreedomMorton3-10.Organ_Hauptwerk_xml>
 */
public final class LinkageCheck {
    private static final int RATE = 48000;
    private static int failures;
    private static Organ organ;
    private static Map<Integer, List<SwitchLinkage>> linksInto;
    private static Set<Switch> clickable;

    public static void main(String[] args) throws Exception {
        organ = OrganParser.parse(Path.of(args[0]));
        linksInto = new HashMap<>();
        organ.switchLinkages().forEach(l -> linksInto.computeIfAbsent(l.dest().index(), k -> new ArrayList<>()).add(l));
        clickable = new HashSet<>();
        organ.display().switchImages().stream().filter(Organ.SwitchImage::clickable).forEach(si -> clickable.add(si.control()));

        parsing();
        acceptance();
        multipleStops();
        heldChord();
        couplers();
        cycles();
        rebuildOnlyWhenNeeded();
        rapidChanges();
        oscillationGuard();

        System.out.println(failures == 0 ? "ALL CHECKS PASS" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    // ---- fixtures

    private record Rig(Console console, VoiceEngine engine, int[] drive) {
        void settle() {
            float[] out = new float[2 * 256];
            for (int i = 0; i < 40; i++) {              // 213 ms: past the 50 ms fade of release-less pipes
                engine.render(out, 256);
            }
        }

        Set<Integer> sounding() {
            Set<Integer> s = new TreeSet<>();
            for (int p = 0; p < drive.length; p++) {
                if (drive[p] > 0) {
                    s.add(p);
                }
            }
            return s;
        }

        /** After settling, the engine plays exactly as many voices as Console drives pipes. */
        boolean engineAgrees() {
            settle();
            return engine.activeVoices() == sounding().size();
        }
    }

    private static Rig rig() throws Exception {
        Sample s = new Sample(new short[4800], 1, RATE, 0, 4800, -1);
        Map<Integer, Sample> samples = new HashMap<>();
        organ.pipes().forEach(p -> samples.put(p.attackSampleId(), s));
        VoiceEngine engine = new VoiceEngine(4096, organ.pipes().size(), RATE);
        Console console = new Console(organ, samples, engine);
        Field f = Console.class.getDeclaredField("drive");
        f.setAccessible(true);
        return new Rig(console, engine, (int[]) f.get(console));
    }

    private static Stop stop(String name) {
        return organ.stops().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException(name));
    }

    private static Keyboard keyboard(String name) {
        return organ.keyboards().stream().filter(k -> k.name().equals(name)).findFirst().orElseThrow();
    }

    private static KeyAction coupler(String name) {
        return organ.keyActions().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    /** The clickable console switch from which linkages reach {@code control} (breadth-first, backwards). */
    private static Switch tabFor(Switch control) {
        ArrayDeque<Switch> todo = new ArrayDeque<>(List.of(control));
        Set<Switch> seen = new HashSet<>(todo);
        while (!todo.isEmpty()) {
            Switch s = todo.poll();
            if (clickable.contains(s)) {
                return s;
            }
            for (SwitchLinkage l : linksInto.getOrDefault(s.index(), List.of())) {
                if (seen.add(l.source())) {
                    todo.add(l.source());
                }
            }
        }
        return null;
    }

    /** Pipes {@code stop} sounds for {@code note} played on its own division (unison, no couplers). */
    private static Set<Integer> expected(Stop stop, int note) {
        Set<Integer> pipes = new TreeSet<>();
        for (StopRank sr : stop.ranks()) {
            if (note >= sr.firstKey() && note < sr.firstKey() + sr.keyCount()) {
                int p = sr.rank().pipeAt(note + sr.noteOffset());
                if (p >= 0) {
                    pipes.add(p);
                }
            }
        }
        return pipes;
    }

    private static String rankNames(Set<Integer> pipes) {
        Set<String> names = new TreeSet<>();
        for (int p : pipes) {
            int rankId = organ.pipes().get(p).rankId();
            organ.ranks().stream().filter(r -> r.id() == rankId).forEach(r -> names.add(r.name()));
        }
        return names.toString();
    }

    private static Set<Integer> union(Set<Integer> a, Set<Integer> b) {
        Set<Integer> u = new TreeSet<>(a);
        u.addAll(b);
        return u;
    }

    // ---- checks

    private static void parsing() {
        System.out.println("Parsing");
        long linkWarnings = organ.warnings().stream().filter(w -> w.contains("linkage")).count();
        check(organ.switchLinkages().size() == 714, "714 switch linkages parsed (got " + organ.switchLinkages().size() + ")");
        check(linkWarnings == 0, "no linkage skipped or unresolved (warnings: " + linkWarnings + ")");
        check(organ.switchLinkages().stream().allMatch(l -> l.onEngage() == LinkAction.ENGAGE && l.onDisengage() == LinkAction.DISENGAGE),
                "all are plain mirrors (engage -> engage, disengage -> disengage)");
        long stopsReachable = organ.stops().stream().filter(s -> tabFor(s.control()) != null).count();
        check(stopsReachable == organ.stops().size(), "every stop is reachable from a console tab: " + stopsReachable + "/" + organ.stops().size());
        long couplers = organ.keyActions().stream().filter(a -> a.control() != null).count();
        long couplersReachable = organ.keyActions().stream().filter(a -> a.control() != null && tabFor(a.control()) != null).count();
        check(couplersReachable == couplers, "every coupler is reachable from a console tab: " + couplersReachable + "/" + couplers);
    }

    private static void acceptance() throws Exception {
        System.out.println("Acceptance: Great Tibia 8");
        Rig r = rig();
        Stop tibia = stop("Great_Tibia8");
        Switch tab = tabFor(tibia.control());
        r.console.mapChannel(0, keyboard("Great"));
        check(!r.console.isEngaged(tibia.control()), "stop starts off");
        r.console.toggle(tab);
        check(r.console.isEngaged(tab) && r.console.isEngaged(tibia.control()), "click tab '" + tab.name() + "': tab and stop node engaged");
        r.console.keyDown(0, 60);
        Set<Integer> want = expected(tibia, 60);
        check(!want.isEmpty() && r.sounding().equals(want), "note 60 sounds exactly " + rankNames(r.sounding()) + " pipe(s) " + r.sounding());
        check(rankNames(r.sounding()).contains("Tibia Clausa"), "the rank is Tibia Clausa");
        check(r.engineAgrees(), "engine plays " + r.engine.activeVoices() + " voice(s)");
        r.console.keyUp(0, 60);
        r.console.toggle(tab);
        check(!r.console.isEngaged(tab) && !r.console.isEngaged(tibia.control()), "click tab again: tab and stop node disengaged");
        r.console.keyDown(0, 62);
        check(r.sounding().isEmpty(), "note 62 sounds nothing");
        check(r.engineAgrees() && r.engine.activeVoices() == 0, "engine silent");
        r.console.keyUp(0, 62);
        // a low note reaches the Bass rank of the same stop
        r.console.toggle(tab);
        r.console.keyDown(0, 36);
        check(r.sounding().equals(expected(tibia, 36)) && !r.sounding().isEmpty(),
                "note 36 sounds " + rankNames(r.sounding()));
        r.console.keyUp(0, 36);
    }

    private static void multipleStops() throws Exception {
        System.out.println("Multiple stops");
        Rig r = rig();
        r.console.mapChannel(0, keyboard("Great"));
        List<Stop> stops = List.of(stop("Great_Tibia8"), stop("Great_Vox8"), stop("Great_Flute4"), stop("Great_Tuba16"));
        Set<Integer> want = new TreeSet<>();
        for (Stop s : stops) {
            r.console.toggle(tabFor(s.control()));
            want.addAll(expected(s, 67));
        }
        r.console.keyDown(0, 67);
        check(r.sounding().equals(want), "4 stops, note 67: " + rankNames(r.sounding()));
        check(r.engineAgrees(), "engine plays " + r.engine.activeVoices() + " voice(s)");
        r.console.keyUp(0, 67);
    }

    private static void heldChord() throws Exception {
        System.out.println("Held chord while registration changes");
        Rig r = rig();
        r.console.mapChannel(0, keyboard("Great"));
        int[] chord = {60, 64, 67};
        Stop tibia = stop("Great_Tibia8");
        Stop vox = stop("Great_Vox8");
        for (int n : chord) {
            r.console.keyDown(0, n);
        }
        check(r.sounding().isEmpty(), "chord held, no stops: silent");
        r.console.toggle(tabFor(tibia.control()));
        Set<Integer> tibiaChord = new TreeSet<>();
        Set<Integer> voxChord = new TreeSet<>();
        for (int n : chord) {
            tibiaChord.addAll(expected(tibia, n));
            voxChord.addAll(expected(vox, n));
        }
        check(r.sounding().equals(tibiaChord) && r.engineAgrees(), "Tibia on under held chord: its 3 pipes speak");
        r.console.toggle(tabFor(vox.control()));
        check(r.sounding().equals(union(tibiaChord, voxChord)) && r.engineAgrees(), "Vox added: Tibia + Vox");
        r.console.toggle(tabFor(tibia.control()));
        check(r.sounding().equals(voxChord) && r.engineAgrees(), "Tibia off: only Vox remains");
        for (int n : chord) {
            r.console.keyUp(0, n);
        }
        check(r.sounding().isEmpty() && r.engineAgrees() && r.engine.activeVoices() == 0, "chord released: silent");
    }

    private static void couplers() throws Exception {
        System.out.println("Couplers");
        Rig r = rig();
        r.console.mapChannel(0, keyboard("Great"));
        Stop soloTuba = stop("Solo_Tuba8");
        Stop greatTibia = stop("Great_Tibia8");
        KeyAction soloToGreat = coupler("Coupler: Solo to Great 8");
        r.console.toggle(tabFor(soloTuba.control()));
        r.console.keyDown(0, 60);
        check(r.sounding().isEmpty(), "Solo Tuba on, Great key: silent without coupler");
        r.console.keyUp(0, 60);
        r.console.toggle(tabFor(soloToGreat.control()));
        check(r.console.isEngaged(soloToGreat.control()), "click 'Solo to Great 8' tab: coupler node engaged");
        r.console.keyDown(0, 60);
        check(r.sounding().equals(expected(soloTuba, 60)) && r.engineAgrees(), "Great key now sounds Solo Tuba: " + rankNames(r.sounding()));
        r.console.toggle(tabFor(soloToGreat.control()));
        check(r.sounding().isEmpty() && r.engineAgrees(), "coupler off under the held key: Solo Tuba stops");
        r.console.keyUp(0, 60);

        KeyAction unisonOff = coupler("Coupler: Great UnisonOff");
        KeyAction greatOct = coupler("Coupler: Great Oct");
        r.console.toggle(tabFor(greatTibia.control()));
        r.console.toggle(tabFor(unisonOff.control()));
        r.console.keyDown(0, 60);
        check(r.sounding().isEmpty(), "Great Unison Off: Great Tibia silent");
        r.console.toggle(tabFor(greatOct.control()));
        check(r.sounding().equals(expected(greatTibia, 72)) && r.engineAgrees(), "plus Great Octave: only the octave above sounds");
        r.console.toggle(tabFor(unisonOff.control()));
        check(r.sounding().equals(union(expected(greatTibia, 60), expected(greatTibia, 72))), "Unison Off released: unison + octave");
        r.console.keyUp(0, 60);
    }

    /** Every console tab: on and off again, with the tab <-> logical back-links in place. */
    private static void cycles() throws Exception {
        System.out.println("Linkage cycles (all " + clickable.size() + " clickable switches)");
        Rig r = rig();
        boolean[] before = states(r.console);
        int consistent = 0;
        int clickedOff = 0;
        for (Switch tab : clickable) {
            r.console.toggle(tab);
            boolean on = r.console.isEngaged(tab);
            Set<Switch> reached = reachable(tab);
            boolean allFollow = reached.stream().allMatch(s -> r.console.isEngaged(s) == on); // all links mirror
            r.console.toggle(tab);
            boolean back = java.util.Arrays.equals(before, states(r.console));
            if (on && allFollow && back) {
                consistent++;
            }
            if (!r.console.isEngaged(tab)) {
                clickedOff++;
            }
        }
        check(consistent == clickable.size(), "click on: the tab stays on and every switch it reaches follows; click off: all back to the start ("
                + consistent + "/" + clickable.size() + ")");
        check(clickedOff == clickable.size(), "no back-link re-engages a tab");
        check(r.console.cascadeOverflows() == 0, "no cascade hit the change limit");
    }

    private static Set<Switch> reachable(Switch from) {
        Map<Integer, List<SwitchLinkage>> out = new HashMap<>();
        organ.switchLinkages().forEach(l -> out.computeIfAbsent(l.source().index(), k -> new ArrayList<>()).add(l));
        Set<Switch> seen = new HashSet<>(List.of(from));
        ArrayDeque<Switch> todo = new ArrayDeque<>(seen);
        while (!todo.isEmpty()) {
            for (SwitchLinkage l : out.getOrDefault(todo.poll().index(), List.of())) {
                if (seen.add(l.dest())) {
                    todo.add(l.dest());
                }
            }
        }
        return seen;
    }

    private static boolean[] states(Console c) {
        boolean[] s = new boolean[organ.switches().size()];
        for (Switch sw : organ.switches()) {
            s[sw.index()] = c.isEngaged(sw);
        }
        return s;
    }

    /** Routing is rebuilt for stop/coupler changes only: a piston tab leaves the routing table as it was. */
    private static void rebuildOnlyWhenNeeded() throws Exception {
        System.out.println("Routing rebuilds");
        Rig r = rig();
        r.console.mapChannel(0, keyboard("Great"));
        r.console.toggle(tabFor(stop("Great_Tibia8").control()));
        Field f = Console.class.getDeclaredField("route");
        f.setAccessible(true);
        int[][][] route = (int[][][]) f.get(r.console);
        int[] before = route[0][60];
        Switch piston = organ.switches().stream().filter(s -> s.name().equals("CustPg1_Comb piston: General 01")).findFirst().orElseThrow();
        r.console.toggle(piston);
        r.console.toggle(piston);
        check(route[0][60] == before, "piston tab (no stop or coupler behind it): routing table not rebuilt");
        r.console.toggle(tabFor(stop("Great_Vox8").control()));
        check(route[0][60] != before, "stop tab: routing table rebuilt");
    }

    /** Random clicks on Great/Solo stop and coupler tabs under random key presses; then compare with a fresh console. */
    private static void rapidChanges() throws Exception {
        System.out.println("Rapid registration changes");
        Rig r = rig();
        Keyboard great = keyboard("Great");
        r.console.mapChannel(0, great);
        r.console.mapChannel(1, keyboard("Solo"));
        List<Switch> tabs = new ArrayList<>();
        for (Stop s : organ.stops()) {
            if (s.division().name().equals("Great") || s.division().name().equals("Solo")) {
                tabs.add(tabFor(s.control()));
            }
        }
        for (KeyAction a : organ.keyActions()) {
            if (a.control() != null && (a.source().name().equals("Great") || a.source().name().equals("Solo"))) {
                tabs.add(tabFor(a.control()));
            }
        }
        Random rnd = new Random(310);
        boolean[][] held = new boolean[2][128];
        float[] out = new float[2 * 256];
        for (int i = 0; i < 1000; i++) {
            if (rnd.nextInt(3) == 0) {
                int ch = rnd.nextInt(2);
                int note = 36 + rnd.nextInt(61);
                if (held[ch][note]) {
                    r.console.keyUp(ch, note);
                } else {
                    r.console.keyDown(ch, note);
                }
                held[ch][note] = !held[ch][note];
            } else {
                r.console.toggle(tabs.get(rnd.nextInt(tabs.size())));
            }
            r.engine.render(out, 256);
        }
        Rig fresh = rig();
        fresh.console.mapChannel(0, great);
        fresh.console.mapChannel(1, keyboard("Solo"));
        for (Switch s : organ.switches()) {
            if (r.console.isEngaged(s) && clickable.contains(s)) {
                fresh.console.setEngaged(s, true);
            }
        }
        check(java.util.Arrays.equals(states(r.console), states(fresh.console)),
                "after 1000 random actions, every switch matches a console set up directly from the final tabs");
        for (int ch = 0; ch < 2; ch++) {
            for (int n = 0; n < 128; n++) {
                if (held[ch][n]) {
                    fresh.console.keyDown(ch, n);
                }
            }
        }
        check(r.sounding().equals(fresh.sounding()), "the same pipes sound (" + r.sounding().size() + ")");
        check(r.engineAgrees(), "engine plays " + r.engine.activeVoices() + " voice(s), as driven");
        for (int ch = 0; ch < 2; ch++) {
            for (int n = 0; n < 128; n++) {
                if (held[ch][n]) {
                    r.console.keyUp(ch, n);
                }
            }
        }
        check(r.sounding().isEmpty() && r.engineAgrees() && r.engine.activeVoices() == 0, "all keys up: no stuck pipes");
        check(r.console.cascadeOverflows() == 0, "no cascade hit the change limit");
    }

    /** A definition whose links would flip two switches forever: the cascade stops and is counted. */
    private static void oscillationGuard() {
        System.out.println("Oscillation guard (synthetic definition)");
        Switch x = new Switch(0, 1, "X", false);
        Switch y = new Switch(1, 2, "Y", false);
        List<SwitchLinkage> links = List.of(
                new SwitchLinkage(x, y, LinkAction.ENGAGE, LinkAction.DISENGAGE),     // Y follows X
                new SwitchLinkage(y, x, LinkAction.DISENGAGE, LinkAction.ENGAGE));    // X opposes Y
        Organ o = new Organ("oscillator", 0, List.of(), List.of(), List.of(x, y), List.of(), List.of(), List.of(),
                List.of(), List.of(), links, Map.of(), null, List.of());
        Console c = new Console(o, Map.of(), new VoiceEngine(8, 8, RATE));
        long t0 = System.nanoTime();
        c.setEngaged(x, true);
        double ms = (System.nanoTime() - t0) / 1e6;
        check(c.cascadeOverflows() == 1, "cascade cut short and counted once (" + c.cascadeOverflows() + "), in " + String.format("%.2f", ms) + " ms");
    }
}
