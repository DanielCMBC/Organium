package organium;

import organium.audio.Sample;
import organium.audio.VoiceEngine;
import organium.io.OrganParser;
import organium.organ.Console;
import organium.organ.Organ;

import java.nio.file.Path;
import java.util.Map;

/** Diagnostic: what Organium makes of an organ definition (warnings, samples, stops, a test note). */
public final class ProbeDiag {
    public static void main(String[] args) throws Exception {
        Path def = Path.of(args[0]);
        Organ organ = OrganParser.parse(def);
        OrganParser.LoadedSamples ls = OrganParser.loadSamples(organ, OrganParser.sampleSetsRootOf(def));
        System.out.printf("%d pipes, %d stops, %d keyboards, %d samples loaded%n", organ.pipes().size(),
                organ.stops().size(), organ.keyboards().size(), ls.byId().size());
        organ.warnings().stream().limit(8).forEach(w -> System.out.println("  organ warning: " + w));
        ls.warnings().stream().limit(8).forEach(w -> System.out.println("  sample warning: " + w));
        VoiceEngine engine = new VoiceEngine(64, organ.pipes().size(), 48000, 0.05f);
        Console c = new Console(organ, ls.byId(), engine);
        for (Organ.Stop s : organ.stops()) {
            System.out.printf("  stop %-30s switch %d '%s' default %s engaged %s ranks %d%n", s.name(), s.control().id(),
                    s.control().name(), s.control().defaultEngaged(), c.isEngaged(s.control()), s.ranks().size());
        }
        for (int ch = 0; ch < 3; ch++) {
            System.out.println("  channel " + ch + " -> " + c.keyboardOn(ch));
        }
        organ.keyActions().forEach(a -> System.out.println("  key action " + a.name() + " " + a.source().name() + " -> "
                + a.target().name() + " control " + (a.control() == null ? "-" : a.control().name()) + " kind " + a.kind()));
        c.keyDown(0, 60);
        System.out.println("  sounding pipes after key 60 on channel 0: " + c.soundingPipes());
    }
}
