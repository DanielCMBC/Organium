package organium.organ;

import organium.audio.ReleaseSet;
import organium.audio.Sample;
import organium.audio.VoiceEngine;
import organium.organ.Organ.Division;
import organium.organ.Organ.KeyAction;
import organium.organ.Organ.Keyboard;
import organium.organ.Organ.Pipe;
import organium.organ.Organ.Stop;
import organium.organ.Organ.StopRank;
import organium.organ.Organ.Switch;
import organium.organ.spatial.PanLaw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Live state of an organ: which keys are held and which switches (stops, couplers) are engaged.
 * Thread-safe; called from MIDI and UI threads, never from the audio thread.
 *
 * Key events are O(1) lookups in {@code route[channel][note]}, a flat list of the pipes that key
 * sounds under the current registration. The table is rebuilt whenever a switch changes; held
 * keys are then re-evaluated so engaging a stop under held keys makes those pipes speak.
 * {@code drive[pipe]} counts how many held keys sound each pipe, so couplers reaching the same
 * pipe from two keys only release it when both keys are up.
 *
 * MIDI channels: organ definitions don't store MIDI assignments (those are user settings in
 * Hauptwerk), so keyboards start on channels 1, 2, 3... in {@link #priorityOrder} (manuals, then
 * pedals). These are provisional until a note arrives on the channel. A note on an unmapped channel
 * claims the highest-priority keyboard not yet confirmed by playing, so a single controller on any
 * channel plays the first manual, and a second controller picks up the next one.
 */
public final class Console {
    public static final int CHANNELS = 16;
    private static final int[] NO_PIPES = new int[0];
    private static final long QUEUE_WAIT_NANOS = 100_000_000L;

    private final Organ organ;
    private final VoiceEngine engine;
    private final Sample[] attackOf;
    private final ReleaseSet[] releaseOf;
    private final float[] gainLeft;   // per pipe: organ gain and stereo position, fixed at load
    private final float[] gainRight;
    private final Map<Division, List<Stop>> stopsByDivision = new HashMap<>();
    private final List<List<Switch>> groupOfSwitch;
    private final boolean[] engaged;
    private final Keyboard[] keyboardOfChannel = new Keyboard[CHANNELS];
    private final boolean[] channelHeard = new boolean[CHANNELS];
    private final List<Keyboard> priority;
    private boolean autoAssign = true;
    private ChannelListener channelListener = (channel, keyboard) -> { };
    private final boolean[][] held = new boolean[CHANNELS][128];
    private final int[][][] route = new int[CHANNELS][128][];
    private final int[] drive;
    private long lostEvents;
    private boolean stalled;

    /** Every pipe centred. */
    public Console(Organ organ, Map<Integer, Sample> samples, VoiceEngine engine) {
        this(organ, samples, engine, PanLaw.CENTRED);
    }

    /**
     * {@code panLaw} places each mono pipe in the stereo field from its virtual position. Stereo
     * samples play as recorded: Hauptwerk documents positions as applying to mono recordings, and
     * its treatment of stereo samples has not been measured.
     */
    public Console(Organ organ, Map<Integer, Sample> samples, VoiceEngine engine, PanLaw panLaw) {
        this.organ = organ;
        this.engine = engine;
        float gain = (float) Math.pow(10, organ.gainDecibels() / 20);

        int pipeCount = organ.pipes().size();
        attackOf = new Sample[pipeCount];
        releaseOf = new ReleaseSet[pipeCount];
        gainLeft = new float[pipeCount];
        gainRight = new float[pipeCount];
        for (Pipe p : organ.pipes()) {
            Sample attack = samples.get(p.attackSampleId());
            attackOf[p.index()] = attack;
            releaseOf[p.index()] = releaseSet(p, samples);
            double pan = attack != null && attack.channels == 1 ? panLaw.pan(p.x(), p.y(), p.z()) : 0;
            gainLeft[p.index()] = gain * (float) (1 - pan);
            gainRight[p.index()] = gain * (float) (1 + pan);
        }
        drive = new int[pipeCount];

        for (Stop s : organ.stops()) {
            stopsByDivision.computeIfAbsent(s.division(), d -> new ArrayList<>()).add(s);
        }

        engaged = new boolean[organ.switches().size()];
        groupOfSwitch = new ArrayList<>(java.util.Collections.nCopies(engaged.length, (List<Switch>) null));
        for (List<Switch> group : organ.exclusiveGroups()) {
            for (Switch s : group) {
                groupOfSwitch.set(s.index(), group);
            }
        }
        for (Switch s : organ.switches()) {
            if (s.defaultEngaged()) {
                engage(s, true);
            }
        }

        priority = priorityOrder(organ.keyboards());
        for (int ch = 0; ch < Math.min(CHANNELS, priority.size()); ch++) {
            keyboardOfChannel[ch] = priority.get(ch);
        }
        rebuild();
    }

    /** Notified when a keyboard is moved to a channel by auto-assignment (called under the console lock). */
    public interface ChannelListener {
        void assigned(int channel, Keyboard keyboard);
    }

    /** Manuals in definition order, then pedalboards. */
    public static List<Keyboard> priorityOrder(List<Keyboard> keyboards) {
        List<Keyboard> ordered = new ArrayList<>();
        keyboards.stream().filter(k -> !isPedal(k)).forEach(ordered::add);
        keyboards.stream().filter(Console::isPedal).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    /** Pedalboards have at most 32 keys; the name is checked too in case a manual is unusually short. */
    public static boolean isPedal(Keyboard k) {
        return k.name().toLowerCase(java.util.Locale.ROOT).contains("pedal") || k.keyCount() <= 32;
    }

    /** The pipe's loaded releases (missing files are left out), or null if none loaded. */
    private static ReleaseSet releaseSet(Pipe pipe, Map<Integer, Sample> samples) {
        List<Sample> found = new ArrayList<>();
        List<Integer> limits = new ArrayList<>();
        for (Organ.Release r : pipe.releases()) {
            Sample s = samples.get(r.sampleId());
            if (s != null) {
                found.add(s);
                limits.add(r.maxSoundedMs());
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        return new ReleaseSet(found.toArray(new Sample[0]), limits.stream().mapToInt(Integer::intValue).toArray());
    }

    public Organ organ() {
        return organ;
    }

    public synchronized Keyboard keyboardOn(int channel) {
        return keyboardOfChannel[channel];
    }

    /** Plays {@code keyboard} (or nothing, if null) from MIDI {@code channel} (0-15); counts as confirmed. */
    public synchronized void mapChannel(int channel, Keyboard keyboard) {
        keyboardOfChannel[channel] = keyboard;
        channelHeard[channel] = keyboard != null;
        rebuild();
    }

    public synchronized void setAutoAssign(boolean enabled) {
        autoAssign = enabled;
    }

    public synchronized void setChannelListener(ChannelListener listener) {
        channelListener = listener;
    }

    public synchronized void keyDown(int channel, int note) {
        if (held[channel][note]) {
            return;
        }
        if (!channelHeard[channel]) {
            if (keyboardOfChannel[channel] == null && autoAssign) {
                claimKeyboard(channel); // one-off table rebuild; later events on this channel are O(1)
            }
            channelHeard[channel] = keyboardOfChannel[channel] != null;
        }
        held[channel][note] = true;
        for (int p : route[channel][note]) {
            if (drive[p]++ == 0) {
                start(p);
            }
        }
    }

    public synchronized void keyUp(int channel, int note) {
        if (!held[channel][note]) {
            return;
        }
        held[channel][note] = false;
        for (int p : route[channel][note]) {
            if (--drive[p] == 0) {
                stop(p);
            }
        }
    }

    /** Moves the highest-priority keyboard whose channel hasn't been played yet onto {@code channel}. */
    private void claimKeyboard(int channel) {
        for (Keyboard kb : priority) {
            int current = channelOf(kb);
            if (current < 0 || !channelHeard[current]) {
                if (current >= 0) {
                    keyboardOfChannel[current] = null; // unheard channel, so no keys are held on it
                }
                keyboardOfChannel[channel] = kb;
                rebuild();
                channelListener.assigned(channel, kb);
                return;
            }
        }
    }

    private int channelOf(Keyboard kb) {
        for (int ch = 0; ch < CHANNELS; ch++) {
            if (kb.equals(keyboardOfChannel[ch])) {
                return ch;
            }
        }
        return -1;
    }

    public synchronized void allNotesOff() {
        for (boolean[] notes : held) {
            Arrays.fill(notes, false);
        }
        rebuild();
    }

    public synchronized boolean isEngaged(Switch s) {
        return engaged[s.index()];
    }

    public synchronized void setEngaged(Switch s, boolean on) {
        engage(s, on);
        rebuild();
    }

    public synchronized void toggle(Switch s) {
        setEngaged(s, !engaged[s.index()]);
    }

    /** Number of pipes currently held on by keys. */
    public synchronized int soundingPipes() {
        int n = 0;
        for (int d : drive) {
            if (d > 0) {
                n++;
            }
        }
        return n;
    }

    private void engage(Switch s, boolean on) {
        List<Switch> group = groupOfSwitch.get(s.index());
        if (on && group != null) {
            for (Switch other : group) {
                engaged[other.index()] = false;
            }
        }
        engaged[s.index()] = on;
    }

    private boolean isActive(KeyAction a) {
        return a.control() == null || engaged[a.control().index()] == a.activeWhenEngaged();
    }

    /** Recomputes the routing table, then starts/stops pipes so held keys match the new registration. */
    private void rebuild() {
        for (int ch = 0; ch < CHANNELS; ch++) {
            Keyboard kb = keyboardOfChannel[ch];
            for (int note = 0; note < 128; note++) {
                route[ch][note] = kb != null && kb.hasNote(note) ? pipesFor(kb, note) : NO_PIPES;
            }
        }

        int[] wanted = new int[drive.length];
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int note = 0; note < 128; note++) {
                if (held[ch][note]) {
                    for (int p : route[ch][note]) {
                        wanted[p]++;
                    }
                }
            }
        }
        for (int p = 0; p < drive.length; p++) {
            int old = drive[p];
            drive[p] = wanted[p];
            if (wanted[p] > 0 && old == 0) {
                start(p);
            } else if (wanted[p] == 0 && old > 0) {
                stop(p);
            }
        }
    }

    private int[] pipesFor(Keyboard kb, int note) {
        int[] found = new int[16];
        int count = 0;
        int keyIndex = note - kb.firstNote();
        for (KeyAction action : organ.keyActions()) {
            if (!action.source().equals(kb) || action.kind() != KeyAction.Kind.NORMAL
                    || keyIndex >= action.keyCount() || !isActive(action)) {
                continue;
            }
            int target = note + action.transpose();
            for (Stop stop : stopsByDivision.getOrDefault(action.target(), List.of())) {
                if (!engaged[stop.control().index()]) {
                    continue;
                }
                for (StopRank sr : stop.ranks()) {
                    if (target < sr.firstKey() || target >= sr.firstKey() + sr.keyCount()) {
                        continue;
                    }
                    int p = sr.rank().pipeAt(target + sr.noteOffset());
                    if (p >= 0 && attackOf[p] != null) {
                        if (count == found.length) {
                            found = Arrays.copyOf(found, count * 2);
                        }
                        found[count++] = p;
                    }
                }
            }
        }
        return count == 0 ? NO_PIPES : Arrays.copyOf(found, count);
    }

    private void start(int pipe) {
        send(() -> engine.noteOn(pipe, attackOf[pipe], releaseOf[pipe], gainLeft[pipe], gainRight[pipe], 1f));
    }

    private void stop(int pipe) {
        send(() -> engine.noteOff(pipe));
    }

    /**
     * If the command queue is full, gives the audio thread a moment to drain it, but never blocks
     * MIDI input indefinitely: once audio has stalled, events fail fast. When the queue accepts
     * commands again, the engine is resynchronized from {@code drive} (which already reflects the
     * wanted state), so lost note-offs cannot leave pipes stuck on.
     */
    private void send(BooleanSupplier command) {
        if (stalled) {
            if (!engine.allNotesOff()) {
                lostEvents++;
                return;
            }
            stalled = false;
            for (int p = 0; p < drive.length; p++) {
                if (drive[p] > 0) {
                    start(p);
                }
            }
            return;
        }
        long deadline = System.nanoTime() + QUEUE_WAIT_NANOS;
        while (!command.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                stalled = true;
                lostEvents++;
                return;
            }
            Thread.onSpinWait();
        }
    }

    /** Note events dropped because the audio thread was not consuming them. */
    public synchronized long lostEvents() {
        return lostEvents;
    }
}
