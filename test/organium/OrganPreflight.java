package organium;

import organium.io.OrganParser;
import organium.odf.OdfDocument;
import organium.organ.Organ;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compatibility preflight for an organ definition, without loading any audio: what the parser
 * builds, its warnings, which Hauptwerk tables have rows that Organium does not read yet, and the
 * sample files the organ would load (written to a list for size estimation).
 *
 * Usage: OrganPreflight <organ.xml> <sample-list-out.txt>
 */
public final class OrganPreflight {
    /** Tables OrganParser reads (see OrganParser.parse). */
    private static final Set<String> READ = Set.of("_General", "Keyboard", "Division", "Switch",
            "SwitchExclusiveSelectGroupElement", "Pipe_SoundEngine01", "Pipe_SoundEngine01_Layer",
            "Pipe_SoundEngine01_AttackSample", "Pipe_SoundEngine01_ReleaseSample", "Rank", "StopRank", "Stop",
            "KeyAction", "Sample", "DisplayPage", "ImageSet", "ImageSetElement", "ImageSetInstance", "TextStyle",
            "TextInstance");

    public static void main(String[] args) throws Exception {
        Path def = Path.of(args[0]);
        OdfDocument doc = OdfDocument.read(def);
        Organ organ = OrganParser.parse(def);
        System.out.printf("Organ: %s (format version %s)%n", organ.name(), doc.fileFormatVersion());
        System.out.printf("  %d keyboards, %d divisions, %d stops, %d switches, %d ranks, %d pipes, %d key actions%n",
                organ.keyboards().size(), organ.divisions().size(), organ.stops().size(), organ.switches().size(),
                organ.ranks().size(), organ.pipes().size(), organ.keyActions().size());
        organ.keyboards().forEach(k -> System.out.printf("  keyboard %-20s notes %d-%d%n", k.name(), k.firstNote(),
                k.firstNote() + k.keyCount() - 1));
        organ.stops().forEach(s -> System.out.printf("  stop %-36s %-10s ranks %d default %s%n", s.name(),
                s.division().name(), s.ranks().size(), s.control().defaultEngaged()));
        organ.keyActions().stream().filter(a -> a.control() != null)
                .forEach(a -> System.out.printf("  coupler %-36s %s -> %s transpose %d %s%n", a.name(), a.source().name(),
                        a.target().name(), a.transpose(), a.kind()));
        organ.warnings().forEach(w -> System.out.println("  WARNING " + w));

        Map<String, Integer> unread = new TreeMap<>();
        doc.tables().forEach((name, rows) -> {
            if (!rows.isEmpty() && !READ.contains(name)) {
                unread.put(name, rows.size());
            }
        });
        System.out.println("  tables with rows that Organium does not read: " + unread);
        System.out.printf("  layers %d, attack samples %d, release samples %d, Sample rows %d%n",
                doc.table("Pipe_SoundEngine01_Layer").size(), doc.table("Pipe_SoundEngine01_AttackSample").size(),
                doc.table("Pipe_SoundEngine01_ReleaseSample").size(), doc.table("Sample").size());

        Set<Integer> used = new TreeSet<>();
        int mono = 0;
        for (Organ.Pipe p : organ.pipes()) {
            used.add(p.attackSampleId());
            p.releases().forEach(r -> used.add(r.sampleId()));
        }
        java.util.List<String> lines = new ArrayList<>();
        for (int id : used) {
            Organ.SampleFile f = organ.sampleFiles().get(id);
            lines.add(f == null ? "MISSING " + id : String.format("%06d/%s", f.packageId(), f.path()));
        }
        Files.write(Path.of(args[1]), lines);
        System.out.printf("  %d sample files would be loaded (list written to %s)%n", used.size(), args[1]);
    }
}
