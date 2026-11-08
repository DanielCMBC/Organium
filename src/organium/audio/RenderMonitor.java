package organium.audio;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps a renderer and measures every audio callback: how long rendering took and how long it has
 * been since the previous callback. Nothing is allocated on the audio thread; another thread reads
 * the counters while audio runs.
 *
 * A callback that renders for longer than one buffer period cannot have been delivered on time
 * (an overrun). A gap between callbacks longer than 1.5 periods means the driver did not call back
 * in time, for example because the thread was descheduled or the JVM was stopped for garbage
 * collection; at least one buffer was likely late or repeated.
 */
public final class RenderMonitor implements AudioOutput.Renderer {
    /** Upper edges of the render-time histogram, as a fraction of the buffer period. */
    private static final double[] BUCKETS = {0.1, 0.2, 0.3, 0.5, 0.75, 1.0};

    private final AudioOutput.Renderer inner;
    private final int sampleRate;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong overruns = new AtomicLong();
    private final AtomicLong gaps = new AtomicLong();
    private final AtomicLong maxRenderNanos = new AtomicLong();  // since the last window reset
    private final AtomicLong maxGapNanos = new AtomicLong();     // since the last window reset
    private final AtomicLong worstRenderNanos = new AtomicLong();
    private final AtomicLong worstGapNanos = new AtomicLong();
    private final AtomicLong totalRenderNanos = new AtomicLong();
    private final AtomicLong[] histogram = new AtomicLong[BUCKETS.length + 1];
    private volatile long audioThreadId = -1;
    private volatile long periodNanos;
    private volatile long worstGapEndNanos;      // System.nanoTime() of the callback that ended the worst gap
    private volatile long renderBeforeWorstGapNanos;
    private long lastStart;
    private long lastRenderNanos;

    public RenderMonitor(AudioOutput.Renderer inner, int sampleRate) {
        this.inner = inner;
        this.sampleRate = sampleRate;
        for (int i = 0; i < histogram.length; i++) {
            histogram[i] = new AtomicLong();
        }
    }

    @Override
    public void render(float[] out, int frames) {
        long start = System.nanoTime();
        inner.render(out, frames);
        long end = System.nanoTime();
        long period = frames * 1_000_000_000L / sampleRate;
        if (lastStart == 0) {
            audioThreadId = Thread.currentThread().threadId();
            periodNanos = period;
        } else {
            long gap = start - lastStart;
            raise(maxGapNanos, gap);
            if (gap > worstGapNanos.get()) { // only this thread writes it
                worstGapNanos.set(gap);
                worstGapEndNanos = start;
                renderBeforeWorstGapNanos = lastRenderNanos;
            }
            if (gap * 2 > period * 3) {
                gaps.incrementAndGet();
            }
        }
        lastStart = start;
        long took = end - start;
        lastRenderNanos = took;
        raise(maxRenderNanos, took);
        raise(worstRenderNanos, took);
        totalRenderNanos.addAndGet(took);
        if (took > period) {
            overruns.incrementAndGet();
        }
        int b = 0;
        while (b < BUCKETS.length && took > BUCKETS[b] * period) {
            b++;
        }
        histogram[b].incrementAndGet();
        calls.incrementAndGet();
    }

    private static void raise(AtomicLong max, long v) {
        long m = max.get();
        while (v > m && !max.compareAndSet(m, v)) {
            m = max.get();
        }
    }

    /** Buffer period in nanoseconds, known after the first callback (0 before). */
    public long periodNanos() {
        return periodNanos;
    }

    /** Thread that runs the callbacks (-1 before the first callback). */
    public long audioThreadId() {
        return audioThreadId;
    }

    public long calls() {
        return calls.get();
    }

    public long overruns() {
        return overruns.get();
    }

    public long gaps() {
        return gaps.get();
    }

    public long worstRenderNanos() {
        return worstRenderNanos.get();
    }

    public long worstGapNanos() {
        return worstGapNanos.get();
    }

    /** {@link System#nanoTime()} at the callback that ended the worst gap (0 if none yet). */
    public long worstGapEndNanos() {
        return worstGapEndNanos;
    }

    /** How long the callback just before the worst gap rendered: a long one means rendering itself ran late. */
    public long renderBeforeWorstGapNanos() {
        return renderBeforeWorstGapNanos;
    }

    public long totalRenderNanos() {
        return totalRenderNanos.get();
    }

    /** Longest render since the previous call of this method. */
    public long takeMaxRenderNanos() {
        return maxRenderNanos.getAndSet(0);
    }

    /** Longest gap between callbacks since the previous call of this method. */
    public long takeMaxGapNanos() {
        return maxGapNanos.getAndSet(0);
    }

    /** Render-time histogram: counts up to 10, 20, 30, 50, 75, 100 % of the period, then over. */
    public long[] histogram() {
        long[] h = new long[histogram.length];
        for (int i = 0; i < h.length; i++) {
            h[i] = histogram[i].get();
        }
        return h;
    }

    public static String[] histogramLabels() {
        return new String[]{"<=10%", "<=20%", "<=30%", "<=50%", "<=75%", "<=100%", ">100%"};
    }
}
