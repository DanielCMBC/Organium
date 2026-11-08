package organium;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists every MIDI device Java sees and prints each message arriving from every input for a while,
 * decoded (channel numbered from 1, as keyboards show it). Opens inputs exactly as App does.
 *
 * Usage: MidiMonitor [seconds]
 */
public final class MidiMonitor {
    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        List<MidiDevice> opened = new ArrayList<>();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            MidiDevice device = MidiSystem.getMidiDevice(info);
            String kind = device instanceof Sequencer ? "sequencer"
                    : device.getMaxTransmitters() != 0 ? "input" : "output";
            System.out.printf("device: %-30s %-10s (%s, %s)%n", info.getName(), kind, info.getVendor(), info.getDescription());
            if (!kind.equals("input")) {
                continue;
            }
            try {
                device.open();
                String name = info.getName();
                device.getTransmitter().setReceiver(new Receiver() {
                    @Override
                    public void send(MidiMessage message, long timeStamp) {
                        System.out.println(name + ": " + describe(message));
                    }

                    @Override
                    public void close() {
                    }
                });
                opened.add(device);
                System.out.println("  listening on " + name);
            } catch (MidiUnavailableException e) {
                System.out.println("  cannot open " + info.getName() + ": " + e.getMessage());
            }
        }
        System.out.println("Play some keys now (" + seconds + " s)...");
        Thread.sleep(seconds * 1000L);
        opened.forEach(MidiDevice::close);
        System.out.println("done");
        System.exit(0);
    }

    private static String describe(MidiMessage message) {
        if (message instanceof ShortMessage m) {
            String what = switch (m.getCommand()) {
                case ShortMessage.NOTE_ON -> m.getData2() > 0 ? "note on " : "note off (on, velocity 0)";
                case ShortMessage.NOTE_OFF -> "note off";
                case ShortMessage.CONTROL_CHANGE -> "control change";
                case ShortMessage.PROGRAM_CHANGE -> "program change";
                case ShortMessage.PITCH_BEND -> "pitch bend";
                default -> String.format("status 0x%02X", m.getStatus());
            };
            if (m.getStatus() >= 0xF0) {
                return String.format("system message 0x%02X", m.getStatus());
            }
            return String.format("%s channel %d, data %d %d", what, m.getChannel() + 1, m.getData1(), m.getData2());
        }
        return message.getClass().getSimpleName() + " (" + message.getLength() + " bytes)";
    }
}
