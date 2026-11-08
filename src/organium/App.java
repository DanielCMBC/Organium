package organium;

import organium.audio.AudioOutput;
import organium.audio.JavaSoundOutput;
import organium.audio.ReleaseSet;
import organium.audio.Sample;
import organium.audio.VoiceEngine;
import organium.audio.asio.AsioOutput;
import organium.io.OrganParser;
import organium.io.WavReader;
import organium.organ.Console;
import organium.organ.Organ;
import organium.organ.spatial.MeasuredPanTable;
import organium.organ.spatial.PanLaw;
import organium.ui.ConsoleWindow;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Development harness.
 * <pre>
 * App --organ=&lt;file.Organ_Hauptwerk_xml&gt; [--asio[=driver]] [--demo] [--pan=hauptwerk|centred] [--trim=dB]   play a Hauptwerk organ
 * App [--asio[=driver]] [--demo] [sample.wav] [release.wav]          play one sample across the keyboard
 * App --list-asio
 * </pre>
 * Without sample files a test pipe is synthesized; --demo plays a chord instead of listening to MIDI.
 * --pan chooses how mono pipes are placed in stereo: hauptwerk (default; the table measured from
 * Hauptwerk 4.2.1) or centred (every pipe in the middle). --trim sets the master output level in dB,
 * applied before the output clips; the default, -10, is Hauptwerk's default output level adjustment.
 */
public final class App {
    private static final int SAMPLE_RATE = 48000;
    private static final int PERIOD_FRAMES = 256;
    private static final int ROOT_NOTE = 60;
    private static final double DEFAULT_TRIM_DB = -10;     // Hauptwerk's default output level adjustment

    private boolean useAsio;
    private String asioDriver;
    private boolean demo;
    private String organFile;
    private PanLaw panLaw = MeasuredPanTable.hauptwerk();
    private double trimDb = DEFAULT_TRIM_DB;
    private final List<String> files = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        App app = new App();
        for (String arg : args) {
            if (arg.equals("--list-asio")) {
                AsioOutput.drivers().forEach(System.out::println);
                return;
            } else if (arg.equals("--asio")) {
                app.useAsio = true;
            } else if (arg.startsWith("--asio=")) {
                app.useAsio = true;
                app.asioDriver = arg.substring("--asio=".length());
            } else if (arg.equals("--demo")) {
                app.demo = true;
            } else if (arg.equals("--pan=centred")) {
                app.panLaw = PanLaw.CENTRED;
            } else if (arg.startsWith("--trim=")) {
                app.trimDb = Double.parseDouble(arg.substring("--trim=".length()));
            } else if (arg.equals("--pan=hauptwerk")) {
                app.panLaw = MeasuredPanTable.hauptwerk();
            } else if (arg.startsWith("--organ=")) {
                app.organFile = arg.substring("--organ=".length());
            } else {
                app.files.add(arg);
            }
        }
        if (app.organFile != null) {
            app.runOrgan(Path.of(app.organFile));
        } else {
            app.runSample();
        }
    }

    private void runOrgan(Path definition) throws Exception {
        long t0 = System.nanoTime();
        Organ organ = OrganParser.parse(definition);
        long t1 = System.nanoTime();
        OrganParser.LoadedSamples samples = OrganParser.loadSamples(organ, OrganParser.sampleSetsRootOf(definition));
        long t2 = System.nanoTime();

        System.out.printf("Organ: %s%n", organ.name());
        System.out.printf("  %d keyboards, %d divisions, %d stops, %d ranks, %d pipes, %d key actions%n",
                organ.keyboards().size(), organ.divisions().size(), organ.stops().size(),
                organ.ranks().size(), organ.pipes().size(), organ.keyActions().size());
        System.out.printf("  parsed in %d ms; loaded %d samples in %d ms%n",
                (t1 - t0) / 1_000_000, samples.byId().size(), (t2 - t1) / 1_000_000);
        organ.warnings().forEach(w -> System.out.println("  warning: " + w));
        samples.warnings().forEach(w -> System.out.println("  warning: " + w));

        VoiceEngine engine = new VoiceEngine(4096, organ.pipes().size(), SAMPLE_RATE, 0.05f);
        engine.setOutputTrimDecibels(trimDb);
        engine.warmUp();
        AudioOutput output = startOutput(engine);
        Console console = new Console(organ, samples.byId(), engine, panLaw);
        for (int ch = 0; ch < Console.CHANNELS; ch++) {
            Organ.Keyboard kb = console.keyboardOn(ch);
            if (kb != null) {
                System.out.printf("  MIDI channel %d -> %s (notes %d-%d)%n",
                        ch + 1, kb.name(), kb.firstNote(), kb.firstNote() + kb.keyCount() - 1);
            }
        }
        System.out.println("  (a controller on another channel takes over the first keyboard not yet played)");
        console.setChannelListener((ch, kb) ->
                System.out.printf("Auto-assigned MIDI channel %d -> %s%n", ch + 1, kb.name()));

        List<Organ.Stop> stops = organ.stops();
        List<Organ.KeyAction> couplers = organ.keyActions().stream().filter(a -> a.control() != null).toList();

        if (demo) {
            int[] chord = {48, 52, 55, 60};
            Organ.Stop first = stops.get(0);
            Organ.Stop second = stops.size() > 1 ? stops.get(1) : first;
            console.setEngaged(first.control(), true);
            System.out.println("Demo: chord on '" + first.name() + "'");
            for (int note : chord) {
                console.keyDown(0, note);
            }
            Thread.sleep(2000);
            System.out.printf("  pipes held %d, voices %d%n", console.soundingPipes(), engine.activeVoices());
            console.setEngaged(second.control(), true);
            System.out.println("Demo: switched to '" + second.name() + "' with the chord held");
            Thread.sleep(2000);
            System.out.printf("  pipes held %d, voices %d%n", console.soundingPipes(), engine.activeVoices());
            for (int note : chord) {
                console.keyUp(0, note);
            }
            Thread.sleep(2000);
            System.out.printf("  after release: pipes held %d, voices %d%n", console.soundingPipes(), engine.activeVoices());
        } else {
            List<MidiDevice> inputs = openMidiInputs(organReceiver(console));
            ConsoleWindow window = null;
            if (!GraphicsEnvironment.isHeadless()) {
                window = ConsoleWindow.open(console, OrganParser.sampleSetsRootOf(definition), () -> {
                    inputs.forEach(MidiDevice::close);
                    console.allNotesOff();
                    output.stop();
                    System.exit(0);
                });
                window.warnings().forEach(w -> System.out.println("  warning: " + w));
            }
            printRegistration(console, stops, couplers);
            System.out.println("Type a stop number to toggle it, c<number> for a coupler, Enter to list, q to quit.");
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            for (String line; (line = in.readLine()) != null && !line.trim().equals("q"); ) {
                line = line.trim();
                try {
                    if (line.startsWith("c")) {
                        console.toggle(couplers.get(Integer.parseInt(line.substring(1)) - 1).control());
                    } else if (!line.isEmpty()) {
                        console.toggle(stops.get(Integer.parseInt(line) - 1).control());
                    }
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    System.out.println("No such stop or coupler: " + line);
                }
                if (window != null) {
                    window.repaint();
                }
                printRegistration(console, stops, couplers);
            }
            if (window != null) {
                SwingUtilities.invokeLater(window::dispose);
            }
            inputs.forEach(MidiDevice::close);
            console.allNotesOff();
        }
        output.stop();
        System.out.println("Dropped notes: " + engine.droppedNotes());
    }

    private static void printRegistration(Console console, List<Organ.Stop> stops, List<Organ.KeyAction> couplers) {
        for (int i = 0; i < stops.size(); i++) {
            Organ.Stop s = stops.get(i);
            System.out.printf("  %s %2d  %-28s (%s)%n", console.isEngaged(s.control()) ? "[x]" : "[ ]",
                    i + 1, s.name(), s.division().name());
        }
        for (int i = 0; i < couplers.size(); i++) {
            Organ.KeyAction a = couplers.get(i);
            System.out.printf("  %s c%-2d %s%n", console.isEngaged(a.control()) ? "[x]" : "[ ]", i + 1, a.name());
        }
    }

    private void runSample() throws Exception {
        Sample attack;
        ReleaseSet release;
        if (!files.isEmpty()) {
            attack = WavReader.read(Path.of(files.get(0)));
            release = files.size() > 1 ? ReleaseSet.of(WavReader.read(Path.of(files.get(1)))) : null;
        } else {
            attack = TestPipe.sustain(SAMPLE_RATE);
            release = ReleaseSet.of(TestPipe.release(SAMPLE_RATE));
        }

        VoiceEngine engine = new VoiceEngine(1024, Console.CHANNELS * 128, SAMPLE_RATE, 0.05f);
        engine.setOutputTrimDecibels(trimDb);
        engine.warmUp();
        AudioOutput output = startOutput(engine);

        List<MidiDevice> inputs = demo ? List.of() : openMidiInputs(sampleReceiver(engine, attack, release));
        if (inputs.isEmpty()) {
            System.out.println("Playing a test chord.");
            int[] chord = {48, 60, 64, 67};
            for (int note : chord) {
                engine.noteOn(note, attack, release, 0.15f, pitchFor(note));
            }
            Thread.sleep(2500);
            for (int note : chord) {
                engine.noteOff(note);
            }
            Thread.sleep(1500);
        } else {
            System.out.println("Listening on " + inputs.size() + " MIDI input(s). Press Enter to quit.");
            System.in.read();
            inputs.forEach(MidiDevice::close);
        }
        output.stop();
        System.out.println("Dropped notes: " + engine.droppedNotes());
    }

    private AudioOutput startOutput(VoiceEngine engine) throws Exception {
        if (useAsio) {
            AsioOutput asio = new AsioOutput(asioDriver);
            asio.start(SAMPLE_RATE, PERIOD_FRAMES, engine::render);
            System.out.printf("ASIO: %s, %d-frame buffer (%.1f ms), reported output latency %.1f ms%n",
                    asio.driverDescription(), asio.bufferFrames(), asio.bufferFrames() * 1000.0 / SAMPLE_RATE,
                    asio.outputLatencyFrames() * 1000.0 / SAMPLE_RATE);
            return asio;
        }
        JavaSoundOutput output = new JavaSoundOutput();
        output.start(SAMPLE_RATE, PERIOD_FRAMES, engine::render);
        return output;
    }

    private static float pitchFor(int note) {
        return (float) Math.pow(2.0, (note - ROOT_NOTE) / 12.0);
    }

    /** Calls onNote(channel, note, down) for note-on/off messages. */
    private interface NoteHandler {
        void onNote(int channel, int note, boolean down);
    }

    private static Receiver organReceiver(Console console) {
        return noteReceiver((ch, note, down) -> {
            if (down) {
                console.keyDown(ch, note);
            } else {
                console.keyUp(ch, note);
            }
        });
    }

    private static Receiver sampleReceiver(VoiceEngine engine, Sample attack, ReleaseSet release) {
        return noteReceiver((ch, note, down) -> {
            int key = (ch << 7) | note;
            if (down) {
                engine.noteOn(key, attack, release, 0.15f, pitchFor(note));
            } else {
                engine.noteOff(key);
            }
        });
    }

    private static Receiver noteReceiver(NoteHandler handler) {
        return new Receiver() {
            @Override
            public void send(MidiMessage message, long timeStamp) {
                if (!(message instanceof ShortMessage m)) {
                    return;
                }
                boolean on = m.getCommand() == ShortMessage.NOTE_ON && m.getData2() > 0;
                boolean off = m.getCommand() == ShortMessage.NOTE_OFF
                        || (m.getCommand() == ShortMessage.NOTE_ON && m.getData2() == 0);
                if (on || off) {
                    handler.onNote(m.getChannel(), m.getData1(), on);
                }
            }

            @Override
            public void close() {
            }
        };
    }

    private static List<MidiDevice> openMidiInputs(Receiver receiver) {
        List<MidiDevice> opened = new ArrayList<>();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            try {
                MidiDevice device = MidiSystem.getMidiDevice(info);
                if (device instanceof Sequencer || device.getMaxTransmitters() == 0) {
                    continue;
                }
                device.open();
                device.getTransmitter().setReceiver(receiver);
                opened.add(device);
                System.out.println("MIDI in: " + info.getName());
            } catch (MidiUnavailableException e) {
                System.out.println("Skipping " + info.getName() + ": " + e.getMessage());
            }
        }
        return opened;
    }
}
