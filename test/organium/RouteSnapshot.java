package organium;

import organium.audio.Sample;
import organium.audio.VoiceEngine;
import organium.io.OrganParser;
import organium.organ.Console;
import organium.organ.Organ;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes which pipes every key of every keyboard sounds under a set of registrations, so the key routing
 * can be compared before and after a change to switch handling. No sample files are read: every
 * attack is a short synthetic sample (Console only routes pipes that have one).
 *
 * Registrations: no switch engaged; each stop alone; all stops; all stops plus each coupler alone;
 * all stops and all couplers. Stops and couplers are engaged through their control switches,
 * as {@code Console.setEngaged} does today.
 *
 * Usage: RouteSnapshot <organ.xml> <out.txt>
 */
public final class RouteSnapshot {
    public static void main(String[] args) throws Exception {
        Organ organ = OrganParser.parse(Path.of(args[0]));
        List<String> lines = snapshot(organ);
        Files.write(Path.of(args[1]), lines);
        System.out.printf("%s: %d lines written to %s%n", organ.name(), lines.size(), args[1]);
    }

    static Map<Integer, Sample> syntheticSamples(Organ organ) {
        Sample s = new Sample(new short[480], 1, 48000, 0, 480, -1);
        Map<Integer, Sample> samples = new HashMap<>();
        organ.pipes().forEach(p -> samples.put(p.attackSampleId(), s));
        return samples;
    }

    static List<String> snapshot(Organ organ) throws Exception {
        List<Organ.Switch> stopControls = organ.stops().stream().map(Organ.Stop::control).distinct().toList();
        List<Organ.Switch> couplerControls = organ.keyActions().stream().map(Organ.KeyAction::control)
                .filter(c -> c != null).distinct().toList();
        List<String> out = new ArrayList<>();
        record(organ, "none", List.of(), out);
        for (Organ.Stop s : organ.stops()) {
            record(organ, "stop " + s.id() + " " + s.name(), List.of(s.control()), out);
        }
        record(organ, "all stops", stopControls, out);
        for (Organ.Switch c : couplerControls) {
            List<Organ.Switch> on = new ArrayList<>(stopControls);
            on.add(c);
            record(organ, "all stops + coupler " + c.id() + " " + c.name(), on, out);
        }
        List<Organ.Switch> all = new ArrayList<>(stopControls);
        all.addAll(couplerControls);
        record(organ, "all stops + all couplers", all, out);
        return out;
    }

    private static void record(Organ organ, String label, List<Organ.Switch> engaged, List<String> out)
            throws Exception {
        VoiceEngine engine = new VoiceEngine(4096, organ.pipes().size(), 48000);
        Console console = new Console(organ, syntheticSamples(organ), engine);
        for (Organ.Switch s : engaged) {
            console.setEngaged(s, true);
        }
        Field driveField = Console.class.getDeclaredField("drive");
        driveField.setAccessible(true);
        int[] drive = (int[]) driveField.get(console);
        float[] block = new float[2 * 64];
        out.add("## " + label);
        for (Organ.Keyboard kb : organ.keyboards()) {
            console.mapChannel(0, kb);
            for (int note = kb.firstNote(); note < kb.firstNote() + kb.keyCount(); note++) {
                console.keyDown(0, note);
                StringBuilder sb = new StringBuilder();
                for (int p = 0; p < drive.length; p++) {
                    if (drive[p] > 0) {
                        sb.append(' ').append(organ.pipes().get(p).id()).append('x').append(drive[p]);
                    }
                }
                if (!sb.isEmpty()) {
                    out.add(kb.id() + ":" + note + sb);
                }
                console.keyUp(0, note);
                engine.render(block, 64); // drains the command queue
            }
        }
    }
}
