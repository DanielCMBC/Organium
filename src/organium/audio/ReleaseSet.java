package organium.audio;

import java.util.Arrays;

/**
 * A pipe's release samples, chosen by how long the pipe sounded: sample sets record separate
 * releases for short (staccato) and medium notes, because a pipe released before its sound has
 * fully built up into the room leaves a different reverberant tail.
 *
 * Immutable and built at load time; {@link #select} runs on the audio thread without allocating.
 */
public final class ReleaseSet {
    /** For the release used when the pipe sounded longer than every limit. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private final Sample[] samples;    // ascending by limit
    private final int[] maxSoundedMs;  // release i applies if the pipe sounded for less than this

    /** @param maxSoundedMs per sample, or {@link #UNLIMITED} */
    public ReleaseSet(Sample[] samples, int[] maxSoundedMs) {
        if (samples.length == 0 || samples.length != maxSoundedMs.length) {
            throw new IllegalArgumentException("Need one limit per release sample, and at least one sample");
        }
        Integer[] order = new Integer[samples.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(maxSoundedMs[a], maxSoundedMs[b]));
        this.samples = new Sample[samples.length];
        this.maxSoundedMs = new int[samples.length];
        for (int i = 0; i < order.length; i++) {
            this.samples[i] = samples[order[i]];
            this.maxSoundedMs[i] = maxSoundedMs[order[i]];
        }
    }

    public static ReleaseSet of(Sample release) {
        return new ReleaseSet(new Sample[]{release}, new int[]{UNLIMITED});
    }

    /** The release for a pipe that sounded {@code soundedMs}; the longest one if none matches. */
    public Sample select(long soundedMs) {
        for (int i = 0; i < maxSoundedMs.length; i++) {
            if (soundedMs < maxSoundedMs[i]) {
                return samples[i];
            }
        }
        return samples[samples.length - 1];
    }

    public int size() {
        return samples.length;
    }
}
