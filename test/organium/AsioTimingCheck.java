package organium;

import organium.audio.AudioOutput;
import organium.audio.asio.AsioOutput;

import java.util.Arrays;

/**
 * Checks that an ASIO driver calls back at the rate it was asked for: records the time of every
 * callback for a few seconds while rendering silence, then reports callbacks per second, the frame
 * rate they imply and the distribution of intervals between callbacks.
 *
 * Usage: AsioTimingCheck [seconds] [driver name] [sample rate] [buffer frames] [capture]
 *
 * With {@code capture}, another thread repeatedly grabs the screen and encodes it as PNG meanwhile,
 * to see whether that disturbs the driver's callbacks.
 */
public final class AsioTimingCheck {
    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        String driver = args.length > 1 && !args[1].equals("-") ? args[1] : AsioOutput.drivers().get(0);
        int rate = args.length > 2 ? Integer.parseInt(args[2]) : 48000;
        int period = args.length > 3 ? Integer.parseInt(args[3]) : 256;
        boolean capture = args.length > 4 && args[4].equals("capture");

        long[] times = new long[seconds * 4000];
        int[] frameCounts = new int[times.length];
        int[] count = {0};
        AudioOutput.Renderer silence = (out, frames) -> {
            int i = count[0];
            if (i < times.length) {
                times[i] = System.nanoTime();
                frameCounts[i] = frames;
                count[0] = i + 1;
            }
            Arrays.fill(out, 0, frames * 2, 0f);
        };

        AsioOutput asio = new AsioOutput(driver);
        asio.start(rate, period, silence);
        System.out.println("driver: " + asio.driverDescription() + ", buffer " + asio.bufferFrames() + " frames");
        int[] captures = {0};
        if (capture) {
            Thread t = new Thread(() -> {
                try {
                    java.awt.Robot robot = new java.awt.Robot();
                    java.awt.Rectangle screen = new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
                    while (true) {
                        javax.imageio.ImageIO.write(robot.createScreenCapture(screen), "png",
                                java.io.OutputStream.nullOutputStream());
                        captures[0]++;
                    }
                } catch (Exception e) {
                    System.out.println("capture failed: " + e);
                }
            }, "capture");
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(seconds * 1000L);
        asio.stop();
        if (capture) {
            System.out.println("screen captures meanwhile: " + captures[0]);
        }

        int n = Math.min(count[0], times.length);
        if (n < 3) {
            System.out.println("only " + n + " callbacks");
            return;
        }
        double span = (times[n - 1] - times[0]) / 1e9;
        long frames = 0;
        for (int i = 1; i < n; i++) {
            frames += frameCounts[i];
        }
        double expectedMs = 1000.0 * asio.bufferFrames() / rate;
        System.out.printf("callbacks: %d in %.2f s = %.1f per second (expected %.1f)%n",
                n, span, (n - 1) / span, rate / (double) asio.bufferFrames());
        System.out.printf("frames rendered per second: %.0f (asked for %d)%n", frames / span, rate);

        double[] gaps = new double[n - 1];
        for (int i = 1; i < n; i++) {
            gaps[i - 1] = (times[i] - times[i - 1]) / 1e6;
        }
        double[] sorted = gaps.clone();
        Arrays.sort(sorted);
        System.out.printf("interval ms: min %.3f, p10 %.3f, median %.3f, p90 %.3f, max %.3f (period %.3f)%n",
                sorted[0], sorted[sorted.length / 10], sorted[sorted.length / 2],
                sorted[sorted.length * 9 / 10], sorted[sorted.length - 1], expectedMs);
        int[] edges = {1, 2, 3, 4, 5, 6, 7, 8, 11, 16};
        StringBuilder h = new StringBuilder("interval histogram (ms upper edge: count):");
        int start = 0;
        for (int e : edges) {
            int c = 0;
            while (start < sorted.length && sorted[start] <= e) {
                start++;
                c++;
            }
            h.append(' ').append(e).append(':').append(c);
        }
        h.append(" >16:").append(sorted.length - start);
        System.out.println(h);
        StringBuilder first = new StringBuilder("first 24 intervals ms:");
        for (int i = 100; i < Math.min(124, gaps.length); i++) {
            first.append(String.format(" %.2f", gaps[i]));
        }
        System.out.println(first);
        System.exit(0); // AWT threads from the capture option would keep the JVM alive
    }
}
