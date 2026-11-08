package organium.io;

import organium.audio.ReleaseSet;
import organium.audio.Sample;
import organium.odf.OdfDocument;
import organium.odf.OdfObject;
import organium.organ.Organ;
import organium.organ.Organ.Display;
import organium.organ.Organ.DisplayPage;
import organium.organ.Organ.Division;
import organium.organ.Organ.ImageInstance;
import organium.organ.Organ.ImageSet;
import organium.organ.Organ.KeyAction;
import organium.organ.Organ.Keyboard;
import organium.organ.Organ.Pipe;
import organium.organ.Organ.Rank;
import organium.organ.Organ.Release;
import organium.organ.Organ.SampleFile;
import organium.organ.Organ.Stop;
import organium.organ.Organ.StopRank;
import organium.organ.Organ.Switch;
import organium.organ.Organ.SwitchImage;
import organium.organ.Organ.TextInstance;
import organium.organ.Organ.TextStyle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Builds a linked {@link Organ} from a Hauptwerk organ definition (.Organ_Hauptwerk_xml).
 *
 * Hauptwerk saves organ definitions "compacted": field tags are shortened to a, b, ... a1 and
 * fields holding their default value are left out. The tags below were identified by
 * cross-referencing tables in the Polyphony Testing Organ and St. Anne's Moseley definitions:
 * every ID reference resolves in both, and names/values agree with the meaning given here
 * (e.g. octave couplers carry transpose 12, "Octave 8" borrowed from a 16' rank carries offset 12).
 */
public final class OrganParser {
    // Shared by every table
    private static final String ID = "a";
    private static final String NAME = "b";

    private static final String KEYBOARD_KEY_COUNT = "h";
    private static final String KEYBOARD_FIRST_NOTE = "i";

    private static final String SWITCH_DEFAULT_ENGAGED = "e";
    private static final String GROUP_ELEMENT_SWITCH = "a";
    private static final String GROUP_ELEMENT_GROUP = "b";

    private static final String STOP_DIVISION = "c";
    private static final String STOP_SWITCH = "d";

    private static final String STOPRANK_STOP = "a";
    private static final String STOPRANK_RANK = "d";
    private static final String STOPRANK_FIRST_KEY = "h";
    private static final String STOPRANK_KEY_COUNT = "i";
    private static final String STOPRANK_NOTE_OFFSET = "j";

    private static final String KEYACTION_SOURCE_KEYBOARD = "a";
    private static final String KEYACTION_TARGET_DIVISION = "d";
    private static final String KEYACTION_NAME = "e";
    private static final String KEYACTION_SWITCH = "f";
    private static final String KEYACTION_ACTIVE_WHEN_ENGAGED = "g";
    private static final String KEYACTION_KIND = "h";       // 2 = bass coupler, 3 = melody coupler
    private static final String KEYACTION_KEY_COUNT = "m";
    private static final String KEYACTION_TRANSPOSE = "n";

    private static final String PIPE_RANK = "b";
    private static final String PIPE_NOTE = "d";
    private static final int DEFAULT_PIPE_NOTE = 60;        // omitted on every middle-C pipe
    // Virtual output position in metres, 0 when omitted. X and Y confirmed by the custom-organ probe
    // (research/hauptwerk-gain-pan-report.md); Z follows X and Y in the schema but is never set by the
    // custom-organ compiler, and Hauptwerk's stereo output ignores it (measured).
    private static final String PIPE_X = "n";
    private static final String PIPE_Y = "p";
    private static final String PIPE_Z = "q";

    private static final String LAYER_PIPE = "b";
    private static final String LAYER_SAMPLE_LAYER = "b";   // in Attack/ReleaseSample rows
    private static final String LAYER_SAMPLE_SAMPLE = "c";
    private static final String RELEASE_MAX_SOUNDED_MS = "q";   // 200 on "02" (short), 500 on "03" (medium) releases

    private static final String SAMPLE_PACKAGE = "b";
    private static final String SAMPLE_PATH = "c";

    // Display. Widths/heights were checked against the bitmaps they describe; fill sizes against the
    // 200x200 "seamless" panel tile in St. Anne's, placed with a 1016x602 fill area.
    private static final String SWITCH_CLICKABLE = "i";         // absent on switches only driven by other controls
    private static final String SWITCH_IMAGE = "k";
    private static final String SWITCH_ENGAGED_ELEMENT = "l";
    private static final String SWITCH_DISENGAGED_ELEMENT = "m";

    private static final String IMAGESET_PACKAGE = "c";
    private static final String IMAGESET_WIDTH = "g";
    private static final String IMAGESET_HEIGHT = "i";
    private static final String IMAGESET_MASK = "j";

    private static final String IMAGE_ELEMENT_SET = "a";
    private static final String IMAGE_ELEMENT_INDEX = "b";      // omitted on element 1
    private static final String IMAGE_ELEMENT_PATH = "d";

    private static final String IMAGE_SET = "c";
    private static final String IMAGE_DEFAULT_ELEMENT = "d";
    private static final String IMAGE_PAGE = "e";
    private static final String IMAGE_LAYER = "f";
    private static final String IMAGE_X = "g";
    private static final String IMAGE_Y = "h";
    private static final String IMAGE_FILL_WIDTH = "i";
    private static final String IMAGE_FILL_HEIGHT = "j";

    // Omitted on most images. It isn't 1 (written on backgrounds) or 12 (written on the Polyphony
    // Testing Organ's stop list), and St. Anne's draws gauge frames over gauges with layer 6.
    private static final int DEFAULT_IMAGE_LAYER = 5;

    private static final String TEXTSTYLE_FONT = "c";            // d and e repeat it, likely for other platforms
    private static final String TEXTSTYLE_SIZE_PIXELS = "f";
    private static final String TEXTSTYLE_RED = "j";
    private static final String TEXTSTYLE_GREEN = "k";
    private static final String TEXTSTYLE_BLUE = "l";
    private static final String TEXTSTYLE_ALIGNMENT = "n";       // 1 = centred: stop names sit at x 77 on a 152-pixel bar
    private static final int ALIGNMENT_CENTRED = 1;
    // Omitted on the "Small" styles, written as 8 on "Very small"; unconfirmed
    private static final int DEFAULT_TEXT_SIZE_PIXELS = 10;

    private static final String TEXT_STYLE = "c";
    private static final String TEXT_TEXT = "d";
    private static final String TEXT_PAGE = "e";
    private static final String TEXT_X = "f";
    private static final String TEXT_Y = "g";                    // top edge: two-line captions are written at 3 and 13
    private static final String TEXT_IMAGE = "k";
    private static final String TEXT_RELATIVE_TO_IMAGE = "l";

    // Never observed omitted, so their defaults are unconfirmed; these are the common compass
    private static final int DEFAULT_FIRST_NOTE = 36;
    private static final int DEFAULT_KEY_COUNT = 61;

    private OrganParser() {
    }

    public static Organ parse(Path organDefinition) throws IOException {
        OdfDocument doc = OdfDocument.read(organDefinition);
        if (!"Organ".equals(doc.fileFormat())) {
            throw new IOException(organDefinition + " is a Hauptwerk '" + doc.fileFormat()
                    + "' file, not an organ definition");
        }
        List<String> warnings = new ArrayList<>();
        OdfObject general = doc.general();
        String name = general != null ? general.get("Identification_Name", "Unnamed organ") : "Unnamed organ";
        double gainDb = general != null ? general.getDouble("AudioOut_AmplitudeLevelAdjustDecibels", 0) : 0;

        Map<Integer, Keyboard> keyboards = new LinkedHashMap<>();
        for (OdfObject o : doc.table("Keyboard")) {
            int id = o.getInt(ID, -1);
            keyboards.put(id, new Keyboard(id, o.get(NAME, "Keyboard " + id),
                    o.getInt(KEYBOARD_FIRST_NOTE, DEFAULT_FIRST_NOTE), o.getInt(KEYBOARD_KEY_COUNT, DEFAULT_KEY_COUNT)));
        }

        Map<Integer, Division> divisions = new LinkedHashMap<>();
        for (OdfObject o : doc.table("Division")) {
            int id = o.getInt(ID, -1);
            divisions.put(id, new Division(id, o.get(NAME, "Division " + id)));
        }

        Map<Integer, Switch> switches = new LinkedHashMap<>();
        for (OdfObject o : doc.table("Switch")) {
            int id = o.getInt(ID, -1);
            switches.put(id, new Switch(switches.size(), id, o.get(NAME, "Switch " + id),
                    o.getBool(SWITCH_DEFAULT_ENGAGED, false)));
        }

        Map<Integer, List<Switch>> groups = new LinkedHashMap<>();
        for (OdfObject o : doc.table("SwitchExclusiveSelectGroupElement")) {
            Switch s = switches.get(o.getInt(GROUP_ELEMENT_SWITCH, -1));
            if (s != null) {
                groups.computeIfAbsent(o.getInt(GROUP_ELEMENT_GROUP, 1), g -> new ArrayList<>()).add(s);
            }
        }

        // Pipes: rank -> note -> pipe, with attack/release samples resolved through the layer tables
        Map<Integer, Integer> layerToPipe = new HashMap<>();
        Map<Integer, Integer> layersPerPipe = new HashMap<>();
        for (OdfObject o : doc.table("Pipe_SoundEngine01_Layer")) {
            int pipeId = o.getInt(LAYER_PIPE, -1);
            if (layersPerPipe.merge(pipeId, 1, Integer::sum) == 1) {
                layerToPipe.put(o.getInt(ID, -1), pipeId);
            }
        }
        long multiLayerPipes = layersPerPipe.values().stream().filter(n -> n > 1).count();
        if (multiLayerPipes > 0) {
            warnings.add(multiLayerPipes + " pipes have several layers; only the first is played");
        }
        Map<Integer, Integer> attackOfPipe = firstSampleOfPipe(doc.table("Pipe_SoundEngine01_AttackSample"), layerToPipe);
        Map<Integer, List<Release>> releasesOfPipe = new HashMap<>();
        for (OdfObject o : doc.table("Pipe_SoundEngine01_ReleaseSample")) {
            Integer pipe = layerToPipe.get(o.getInt(LAYER_SAMPLE_LAYER, -1));
            if (pipe != null) {
                releasesOfPipe.computeIfAbsent(pipe, p -> new ArrayList<>()).add(new Release(
                        o.getInt(LAYER_SAMPLE_SAMPLE, -1), o.getInt(RELEASE_MAX_SOUNDED_MS, ReleaseSet.UNLIMITED)));
            }
        }

        Map<Integer, int[]> notesOfRank = new LinkedHashMap<>();
        for (OdfObject o : doc.table("Rank")) {
            int[] byNote = new int[128];
            Arrays.fill(byNote, -1);
            notesOfRank.put(o.getInt(ID, -1), byNote);
        }
        List<Pipe> pipes = new ArrayList<>();
        int pipesWithoutSample = 0;
        for (OdfObject o : doc.table("Pipe_SoundEngine01")) {
            int id = o.getInt(ID, -1);
            int rankId = o.getInt(PIPE_RANK, -1);
            int note = o.getInt(PIPE_NOTE, DEFAULT_PIPE_NOTE);
            Integer attack = attackOfPipe.get(id);
            int[] byNote = notesOfRank.get(rankId);
            if (attack == null || byNote == null || note < 0 || note > 127) {
                pipesWithoutSample++;
                continue;
            }
            Pipe pipe = new Pipe(pipes.size(), id, rankId, note, attack,
                    List.copyOf(releasesOfPipe.getOrDefault(id, List.of())),
                    o.getDouble(PIPE_X, 0), o.getDouble(PIPE_Y, 0), o.getDouble(PIPE_Z, 0));
            pipes.add(pipe);
            byNote[note] = pipe.index();
        }
        if (pipesWithoutSample > 0) {
            warnings.add(pipesWithoutSample + " pipes skipped (no attack sample or unknown rank)");
        }

        Map<Integer, Rank> ranks = new LinkedHashMap<>();
        for (OdfObject o : doc.table("Rank")) {
            int id = o.getInt(ID, -1);
            ranks.put(id, new Rank(id, o.get(NAME, "Rank " + id), notesOfRank.get(id)));
        }

        Map<Integer, List<StopRank>> ranksOfStop = new HashMap<>();
        for (OdfObject o : doc.table("StopRank")) {
            Rank rank = ranks.get(o.getInt(STOPRANK_RANK, -1));
            if (rank == null) {
                warnings.add("StopRank '" + o.get(NAME) + "' refers to a missing rank");
                continue;
            }
            ranksOfStop.computeIfAbsent(o.getInt(STOPRANK_STOP, -1), s -> new ArrayList<>())
                    .add(new StopRank(rank, o.getInt(STOPRANK_FIRST_KEY, DEFAULT_FIRST_NOTE),
                            o.getInt(STOPRANK_KEY_COUNT, DEFAULT_KEY_COUNT), o.getInt(STOPRANK_NOTE_OFFSET, 0)));
        }

        List<Stop> stops = new ArrayList<>();
        for (OdfObject o : doc.table("Stop")) {
            int id = o.getInt(ID, -1);
            Division division = divisions.get(o.getInt(STOP_DIVISION, -1));
            Switch control = switches.get(o.getInt(STOP_SWITCH, -1));
            if (division == null || control == null) {
                warnings.add("Stop '" + o.get(NAME) + "' skipped: missing division or switch");
                continue;
            }
            stops.add(new Stop(id, o.get(NAME, "Stop " + id), division, control,
                    List.copyOf(ranksOfStop.getOrDefault(id, List.of()))));
        }

        List<KeyAction> keyActions = new ArrayList<>();
        for (OdfObject o : doc.table("KeyAction")) {
            String actionName = o.get(KEYACTION_NAME, "Key action");
            Keyboard source = keyboards.get(o.getInt(KEYACTION_SOURCE_KEYBOARD, -1));
            Division target = divisions.get(o.getInt(KEYACTION_TARGET_DIVISION, -1));
            if (source == null || target == null) {
                warnings.add("Key action '" + actionName + "' skipped: missing keyboard or division");
                continue;
            }
            KeyAction.Kind kind = switch (o.getInt(KEYACTION_KIND, 0)) {
                case 2 -> KeyAction.Kind.BASS;
                case 3 -> KeyAction.Kind.MELODY;
                default -> KeyAction.Kind.NORMAL;
            };
            keyActions.add(new KeyAction(actionName, source, target, o.getInt(KEYACTION_TRANSPOSE, 0),
                    o.getInt(KEYACTION_KEY_COUNT, source.keyCount()), switches.get(o.getInt(KEYACTION_SWITCH, -1)),
                    o.getBool(KEYACTION_ACTIVE_WHEN_ENGAGED, false), kind));
        }

        for (KeyAction a : keyActions) {
            if (a.kind() != KeyAction.Kind.NORMAL) {
                warnings.add("'" + a.name() + "' is a " + a.kind().name().toLowerCase()
                        + " coupler, which is not supported yet");
            }
        }

        Map<Integer, SampleFile> sampleFiles = new HashMap<>();
        for (OdfObject o : doc.table("Sample")) {
            int id = o.getInt(ID, -1);
            String path = o.get(SAMPLE_PATH);
            if (path != null) {
                sampleFiles.put(id, new SampleFile(id, o.getInt(SAMPLE_PACKAGE, -1), path));
            }
        }

        Display display = parseDisplay(doc, general, switches, warnings);

        return new Organ(name, gainDb, List.copyOf(keyboards.values()), List.copyOf(divisions.values()),
                List.copyOf(switches.values()), List.copyOf(stops), List.copyOf(keyActions),
                List.copyOf(ranks.values()), List.copyOf(pipes),
                groups.values().stream().map(List::copyOf).toList(), Map.copyOf(sampleFiles), display,
                List.copyOf(warnings));
    }

    private static Display parseDisplay(OdfDocument doc, OdfObject general, Map<Integer, Switch> switches,
                                        List<String> warnings) {
        Map<Integer, Map<Integer, String>> elementsOfSet = new HashMap<>();
        for (OdfObject o : doc.table("ImageSetElement")) {
            String path = o.get(IMAGE_ELEMENT_PATH);
            if (path != null) {
                elementsOfSet.computeIfAbsent(o.getInt(IMAGE_ELEMENT_SET, -1), s -> new HashMap<>())
                        .put(o.getInt(IMAGE_ELEMENT_INDEX, 1), path);
            }
        }

        Map<Integer, ImageSet> imageSets = new HashMap<>();
        for (OdfObject o : doc.table("ImageSet")) {
            int id = o.getInt(ID, -1);
            imageSets.put(id, new ImageSet(id, o.get(NAME, "Image set " + id), o.getInt(IMAGESET_PACKAGE, -1),
                    o.getInt(IMAGESET_WIDTH, 0), o.getInt(IMAGESET_HEIGHT, 0), o.get(IMAGESET_MASK),
                    Map.copyOf(elementsOfSet.getOrDefault(id, Map.of()))));
        }

        List<DisplayPage> pages = new ArrayList<>();
        for (OdfObject o : doc.table("DisplayPage")) {
            int id = o.getInt(ID, -1);
            pages.add(new DisplayPage(id, o.get(NAME, "Page " + id)));
        }

        Map<Integer, ImageInstance> images = new LinkedHashMap<>();
        for (OdfObject o : doc.table("ImageSetInstance")) {
            int id = o.getInt(ID, -1);
            String imageName = o.get(NAME, "Image " + id);
            ImageSet set = imageSets.get(o.getInt(IMAGE_SET, -1));
            if (set == null) {
                warnings.add("Image '" + imageName + "' skipped: missing image set");
                continue;
            }
            images.put(id, new ImageInstance(id, imageName, set, o.getInt(IMAGE_PAGE, -1),
                    o.getInt(IMAGE_LAYER, DEFAULT_IMAGE_LAYER), o.getInt(IMAGE_X, 0), o.getInt(IMAGE_Y, 0),
                    o.getInt(IMAGE_DEFAULT_ELEMENT, 1), o.getInt(IMAGE_FILL_WIDTH, 0), o.getInt(IMAGE_FILL_HEIGHT, 0)));
        }

        List<SwitchImage> switchImages = new ArrayList<>();
        for (OdfObject o : doc.table("Switch")) {
            Switch s = switches.get(o.getInt(ID, -1));
            int imageId = o.getInt(SWITCH_IMAGE, -1);
            if (s == null || imageId < 0) {
                continue;
            }
            ImageInstance image = images.get(imageId);
            if (image == null) {
                warnings.add("Switch '" + s.name() + "' is not drawn: missing image " + imageId);
                continue;
            }
            switchImages.add(new SwitchImage(s, image, o.getInt(SWITCH_ENGAGED_ELEMENT, 1),
                    o.getInt(SWITCH_DISENGAGED_ELEMENT, 1), o.getBool(SWITCH_CLICKABLE, false)));
        }

        Map<Integer, TextStyle> styles = new HashMap<>();
        for (OdfObject o : doc.table("TextStyle")) {
            int id = o.getInt(ID, -1);
            int rgb = (o.getInt(TEXTSTYLE_RED, 0) & 0xFF) << 16 | (o.getInt(TEXTSTYLE_GREEN, 0) & 0xFF) << 8
                    | (o.getInt(TEXTSTYLE_BLUE, 0) & 0xFF);
            styles.put(id, new TextStyle(id, o.get(NAME, "Text style " + id), o.get(TEXTSTYLE_FONT),
                    o.getInt(TEXTSTYLE_SIZE_PIXELS, DEFAULT_TEXT_SIZE_PIXELS), rgb,
                    o.getInt(TEXTSTYLE_ALIGNMENT, 0) == ALIGNMENT_CENTRED));
        }

        List<TextInstance> texts = new ArrayList<>();
        for (OdfObject o : doc.table("TextInstance")) {
            int id = o.getInt(ID, -1);
            String text = o.get(TEXT_TEXT);
            TextStyle style = styles.get(o.getInt(TEXT_STYLE, -1));
            if (text == null) {
                continue;
            }
            if (style == null) {
                warnings.add("Text '" + text + "' skipped: missing text style");
                continue;
            }
            ImageInstance image = images.get(o.getInt(TEXT_IMAGE, -1));
            int x = o.getInt(TEXT_X, 0);
            int y = o.getInt(TEXT_Y, 0);
            if (image != null && o.getBool(TEXT_RELATIVE_TO_IMAGE, false)) {
                x += image.x();
                y += image.y();
            }
            texts.add(new TextInstance(id, text, style, o.getInt(TEXT_PAGE, -1), x, y, image));
        }

        int width = general != null ? general.getInt("Display_ConsoleScreenWidthPixels", 0) : 0;
        int height = general != null ? general.getInt("Display_ConsoleScreenHeightPixels", 0) : 0;
        int defaultPage = general != null ? general.getInt("SpecialObjects_DefaultDisplayPageID", 1) : 1;
        return new Display(width, height, defaultPage, List.copyOf(pages), List.copyOf(images.values()),
                List.copyOf(switchImages), List.copyOf(texts));
    }

    private static Map<Integer, Integer> firstSampleOfPipe(List<OdfObject> rows, Map<Integer, Integer> layerToPipe) {
        Map<Integer, Integer> result = new HashMap<>();
        for (OdfObject o : rows) {
            Integer pipe = layerToPipe.get(o.getInt(LAYER_SAMPLE_LAYER, -1));
            if (pipe != null) {
                result.putIfAbsent(pipe, o.getInt(LAYER_SAMPLE_SAMPLE, -1));
            }
        }
        return result;
    }

    /** Result of loading an organ's samples: sample ID to audio, plus problems encountered. */
    public record LoadedSamples(Map<Integer, Sample> byId, List<String> warnings) {
    }

    /**
     * Loads the sample files of every pipe that a stop can play. Files are looked up as
     * {@code <sampleSetsRoot>/OrganInstallationPackages/<6-digit package id>/<path>}.
     * Ranks no stop plays (for example tremulant alternate ranks and noise ranks, which Organium
     * cannot sound yet) are skipped to save memory and named in a warning. Unsupported or missing
     * files are reported as warnings; their pipes stay silent.
     */
    public static LoadedSamples loadSamples(Organ organ, Path sampleSetsRoot) {
        Set<Integer> playable = new LinkedHashSet<>();
        for (Stop s : organ.stops()) {
            s.ranks().forEach(sr -> playable.add(sr.rank().id()));
        }
        ConcurrentLinkedQueue<String> warnings = new ConcurrentLinkedQueue<>();
        List<String> skipped = organ.ranks().stream().filter(r -> !playable.contains(r.id())).map(Rank::name).toList();
        if (!skipped.isEmpty()) {
            warnings.add("Not loaded (no stop plays these ranks yet): " + String.join(", ", skipped));
        }
        Set<Integer> used = new LinkedHashSet<>();
        for (Pipe p : organ.pipes()) {
            if (playable.contains(p.rankId())) {
                used.add(p.attackSampleId());
                p.releases().forEach(r -> used.add(r.sampleId()));
            }
        }
        Path packages = sampleSetsRoot.resolve("OrganInstallationPackages");
        Map<Integer, Sample> loaded = new ConcurrentHashMap<>();
        used.parallelStream().forEach(id -> {
            SampleFile file = organ.sampleFiles().get(id);
            if (file == null) {
                warnings.add("Sample " + id + " is not defined");
                return;
            }
            Path path = packages.resolve(String.format("%06d", file.packageId())).resolve(file.path());
            try {
                if (!Files.isRegularFile(path)) {
                    warnings.add("Missing sample file " + path);
                    return;
                }
                loaded.put(id, WavReader.read(path));
            } catch (UnsupportedWavFormatException e) {
                warnings.add("Skipped (unsupported format): " + e.getMessage());
            } catch (IOException e) {
                warnings.add("Failed to read " + path + ": " + e.getMessage());
            }
        });
        List<String> sorted = new ArrayList<>(warnings);
        sorted.sort(null);
        return new LoadedSamples(Map.copyOf(loaded), sorted);
    }

    /** Organ definitions live in {@code <root>/OrganDefinitions}; returns {@code <root>}. */
    public static Path sampleSetsRootOf(Path organDefinition) {
        Path parent = organDefinition.toAbsolutePath().getParent();
        return parent != null && parent.getParent() != null ? parent.getParent() : parent;
    }
}
