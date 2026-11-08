package organium.organ;

import java.util.List;
import java.util.Map;

/**
 * Immutable, fully linked organ produced by OrganParser. Objects reference each other directly;
 * pipes are numbered densely ({@link Pipe#index}) so they can double as voice-engine keys.
 */
public record Organ(
        String name,
        double gainDecibels,
        List<Keyboard> keyboards,
        List<Division> divisions,
        List<Switch> switches,
        List<Stop> stops,
        List<KeyAction> keyActions,
        List<Rank> ranks,
        List<Pipe> pipes,
        List<List<Switch>> exclusiveGroups,
        Map<Integer, SampleFile> sampleFiles,
        Display display,
        List<String> warnings) {

    public record Keyboard(int id, String name, int firstNote, int keyCount) {
        public boolean hasNote(int note) {
            return note >= firstNote && note < firstNote + keyCount;
        }
    }

    public record Division(int id, String name) {
    }

    /** Anything on/off: stop knobs, coupler tabs, blower, indicator lights. */
    public record Switch(int index, int id, String name, boolean defaultEngaged) {
    }

    /** How one stop draws pipes from one rank: keys [firstKey, firstKey + keyCount) play rank note key + noteOffset. */
    public record StopRank(Rank rank, int firstKey, int keyCount, int noteOffset) {
    }

    public record Stop(int id, String name, Division division, Switch control, List<StopRank> ranks) {
    }

    /**
     * Connects a keyboard to a division: the normal action of each manual, and couplers.
     * Active when {@code control} is null, or when its engaged state equals {@code activeWhenEngaged}
     * (unison-off switches disable the normal action by being engaged).
     */
    public record KeyAction(String name, Keyboard source, Division target, int transpose, int keyCount,
                            Switch control, boolean activeWhenEngaged, Kind kind) {
        public enum Kind { NORMAL, BASS, MELODY }
    }

    /** A rank of pipes; {@code pipeByNote[note]} is a pipe index or -1. */
    public record Rank(int id, String name, int[] pipeByNote) {
        public int pipeAt(int note) {
            return note >= 0 && note < pipeByNote.length ? pipeByNote[note] : -1;
        }
    }

    /**
     * One pipe. Sample IDs refer to {@link #sampleFiles}. (x, y, z) is the pipe's virtual output
     * position in metres (Hauptwerk's VirtualOutputPos_*); 0 where the definition leaves it out.
     */
    public record Pipe(int index, int id, int rankId, int note, int attackSampleId, List<Release> releases,
                       double x, double y, double z) {
    }

    /**
     * A release sample, used if the pipe sounded for less than {@code maxSoundedMs}
     * ({@link organium.audio.ReleaseSet#UNLIMITED} for the normal, long-note release).
     */
    public record Release(int sampleId, int maxSoundedMs) {
    }

    /** A sample file: path relative to its installation package folder. */
    public record SampleFile(int id, int packageId, String path) {
    }

    /**
     * The console as drawn on screen: pages of {@code width} x {@code height} pixels, the images
     * placed on them, and which images show switch states. {@code width}/{@code height} are 0 if unspecified.
     */
    public record Display(int width, int height, int defaultPageId, List<DisplayPage> pages,
                          List<ImageInstance> images, List<SwitchImage> switchImages, List<TextInstance> texts) {
    }

    public record DisplayPage(int id, String name) {
    }

    /**
     * Interchangeable bitmaps for one control (e.g. a knob drawn and pushed in), numbered from 1, with
     * paths relative to installation package {@code packageId}. {@code maskPath} (or null) is a 1-bit
     * bitmap: black pixels are drawn, white ones are transparent. Width and height are 0 if unspecified.
     */
    public record ImageSet(int id, String name, int packageId, int width, int height, String maskPath,
                           Map<Integer, String> elements) {
    }

    /**
     * An image set placed at absolute position (x, y) on a page, showing {@code defaultElement}.
     * Lower layers are drawn first. If {@code fillWidth}/{@code fillHeight} are set, the image is
     * tiled over that area.
     */
    public record ImageInstance(int id, String name, ImageSet imageSet, int pageId, int layer, int x, int y,
                                int defaultElement, int fillWidth, int fillHeight) {
    }

    /** Shows a switch's state as one of two elements of an image; clicking a clickable one toggles the switch. */
    public record SwitchImage(Switch control, ImageInstance image, int engagedElement, int disengagedElement,
                              boolean clickable) {
    }

    /** Font and colour for text drawn on the console; {@code centred} text is centred on its x position. */
    public record TextStyle(int id, String name, String fontName, int sizePixels, int rgb, boolean centred) {
    }

    /**
     * Text drawn over a page, such as a stop's name on its control. (x, y) is the absolute page
     * position of the text's top edge, at its left or (for centred styles) its centre. {@code attachedTo}
     * is the image it labels, or null.
     */
    public record TextInstance(int id, String text, TextStyle style, int pageId, int x, int y,
                               ImageInstance attachedTo) {
    }
}
