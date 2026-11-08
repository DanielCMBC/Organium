package organium.audio;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded queue carrying note events from MIDI/GUI threads to the audio thread.
 * Producers serialize among themselves with a lock; the audio thread never locks,
 * never allocates, and only reads slots published by a release store on {@code tail}.
 */
final class CommandQueue {
    static final int NOTE_ON = 1;
    static final int NOTE_OFF = 2;
    static final int ALL_NOTES_OFF = 3;

    interface Sink {
        void noteOn(int key, Sample attack, ReleaseSet releases, float gainL, float gainR, float pitch);
        void noteOff(int key);

        void allNotesOff();
    }

    private final int mask;
    private final int[] type;
    private final int[] key;
    private final Sample[] attack;
    private final ReleaseSet[] release;
    private final float[] gainL;
    private final float[] gainR;
    private final float[] pitch;
    private final AtomicLong head = new AtomicLong(); // next slot to read, written by consumer
    private final AtomicLong tail = new AtomicLong(); // next slot to write, written by producers

    CommandQueue(int capacityPowerOfTwo) {
        if (Integer.bitCount(capacityPowerOfTwo) != 1) {
            throw new IllegalArgumentException("Capacity must be a power of two");
        }
        mask = capacityPowerOfTwo - 1;
        type = new int[capacityPowerOfTwo];
        key = new int[capacityPowerOfTwo];
        attack = new Sample[capacityPowerOfTwo];
        release = new ReleaseSet[capacityPowerOfTwo];
        gainL = new float[capacityPowerOfTwo];
        gainR = new float[capacityPowerOfTwo];
        pitch = new float[capacityPowerOfTwo];
    }

    synchronized boolean offer(int cmd, int k, Sample a, ReleaseSet r, float gl, float gr, float p) {
        long t = tail.get();
        if (t - head.get() > mask) {
            return false; // full; the audio thread has fallen badly behind
        }
        int i = (int) (t & mask);
        type[i] = cmd;
        key[i] = k;
        attack[i] = a;
        release[i] = r;
        gainL[i] = gl;
        gainR[i] = gr;
        pitch[i] = p;
        tail.setRelease(t + 1);
        return true;
    }

    /** Audio thread only. */
    void drain(Sink sink) {
        long h = head.getPlain();
        long t = tail.get();
        while (h < t) {
            int i = (int) (h & mask);
            switch (type[i]) {
                case NOTE_ON -> sink.noteOn(key[i], attack[i], release[i], gainL[i], gainR[i], pitch[i]);
                case NOTE_OFF -> sink.noteOff(key[i]);
                default -> sink.allNotesOff();
            }
            attack[i] = null;
            release[i] = null;
            h++;
        }
        head.setRelease(h);
    }
}
