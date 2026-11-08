package organium;

import organium.audio.Sample;
import organium.io.OrganParser;
import organium.io.WavReader;
import organium.organ.Organ;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Decodes, one file at a time, every sample an organ definition uses, through Organium's
 * WavReader, and reports failures, formats, loops and release markers, plus the memory the
 * decoded samples would take. Nothing is kept in memory between files.
 *
 * Usage: SampleDecodeCheck <organ.xml>
 */
public final class SampleDecodeCheck {
    public static void main(String[] args) throws Exception {
        Path def = Path.of(args[0]);
        Organ organ = OrganParser.parse(def);
        Path packages = OrganParser.sampleSetsRootOf(def).resolve("OrganInstallationPackages");
        Set<Integer> used = new TreeSet<>();
        Set<Integer> attacks = new TreeSet<>();
        for (Organ.Pipe p : organ.pipes()) {
            used.add(p.attackSampleId());
            attacks.add(p.attackSampleId());
            p.releases().forEach(r -> used.add(r.sampleId()));
        }
        Map<String, Integer> kinds = new TreeMap<>();
        Map<String, Integer> failures = new TreeMap<>();
        long bytes = 0;
        long frames = 0;
        int looped = 0;
        int attacksLooped = 0;
        int withCue = 0;
        long t0 = System.nanoTime();
        for (int id : used) {
            Organ.SampleFile f = organ.sampleFiles().get(id);
            Path path = packages.resolve(String.format("%06d", f.packageId())).resolve(f.path());
            try {
                Sample s = WavReader.read(path);
                kinds.merge(s.channels + " ch, " + s.sampleRate + " Hz", 1, Integer::sum);
                bytes += (long) s.data.length * 2;
                frames += s.frames;
                if (s.isLooped()) {
                    looped++;
                    if (attacks.contains(id)) {
                        attacksLooped++;
                    }
                }
                if (s.releaseOffset > 0) {
                    withCue++;
                }
            } catch (Exception e) {
                failures.merge(e.getClass().getSimpleName() + ": " + e.getMessage(), 1, Integer::sum);
            }
        }
        long t1 = System.nanoTime();
        System.out.printf("%d sample files (%d used as attacks), decoded in %.1f s%n", used.size(), attacks.size(), (t1 - t0) / 1e9);
        System.out.println("  formats after decoding: " + kinds);
        System.out.printf("  looped: %d (attacks with a loop: %d of %d); with a release (cue) marker: %d%n",
                looped, attacksLooped, attacks.size(), withCue);
        System.out.printf("  decoded size: %.0f MiB as 16-bit (%.1f hours of audio frames)%n", bytes / 1048576.0,
                frames / 48000.0 / 3600);
        if (failures.isEmpty()) {
            System.out.println("  ALL FILES DECODED");
        } else {
            failures.forEach((m, n) -> System.out.println("  FAILED x" + n + ": " + m));
        }
    }
}
