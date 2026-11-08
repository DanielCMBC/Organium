package organium.audio;

/**
 * 16-bit PCM sample held in RAM, with an optional sustain loop and release marker.
 * Stored as short[] rather than float[] to halve memory use; organ sample sets are large.
 */
public final class Sample {
    public final short[] data;      // interleaved when channels == 2
    public final int channels;      // 1 or 2
    public final int sampleRate;
    public final int frames;
    public final int loopStart;     // first frame of the sustain loop, -1 if not looped
    public final int loopEnd;       // frame after the last loop frame (exclusive)
    public final int releaseOffset; // frame where an embedded release begins, -1 if none

    public Sample(short[] data, int channels, int sampleRate,
                  int loopStart, int loopEnd, int releaseOffset) {
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("Only mono and stereo samples are supported");
        }
        this.data = data;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.frames = data.length / channels;
        boolean validLoop = loopStart >= 0 && loopEnd > loopStart && loopEnd <= frames;
        this.loopStart = validLoop ? loopStart : -1;
        this.loopEnd = validLoop ? loopEnd : -1;
        this.releaseOffset = releaseOffset;
    }

    public boolean isLooped() {
        return loopStart >= 0;
    }
}
