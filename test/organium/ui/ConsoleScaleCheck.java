package organium.ui;

/**
 * Checks ConsoleScale's fitting and coordinate mapping: the console always fits entirely, keeps its
 * aspect ratio, is centred, and screen points map back to the design pixel that was drawn there.
 *
 * Usage: ConsoleScaleCheck   (in package organium.ui to reach the package-private class)
 */
public final class ConsoleScaleCheck {
    private static int failures;

    public static void main(String[] args) {
        // Melcer's 1692 x 1200 console in this machine's window, a small window, a wide one, a tall one, 4K
        int[][] cases = {{1692, 1200, 1426, 1011}, {1692, 1200, 800, 600}, {1692, 1200, 2500, 900},
                {1692, 1200, 900, 2000}, {1692, 1200, 3840, 2120}, {1692, 1200, 1692, 1200}, {1024, 768, 1023, 767}};
        for (int[] c : cases) {
            ConsoleScale f = new ConsoleScale(c[0], c[1]);
            f.fit(c[2], c[3]);
            String what = c[0] + "x" + c[1] + " in " + c[2] + "x" + c[3];
            check(f.width() <= c[2] && f.height() <= c[3], what + ": fits (" + f.width() + "x" + f.height() + ")");
            check(f.width() == c[2] || f.height() == c[3] || Math.abs(f.width() - c[2]) <= 1 || Math.abs(f.height() - c[3]) <= 1,
                    what + ": fills one dimension");
            double aspect = (double) f.width() / f.height();
            double design = (double) c[0] / c[1];
            check(Math.abs(aspect - design) / design < 2.0 / Math.min(f.width(), f.height()),
                    what + ": aspect " + aspect + " vs " + design);
            check(Math.abs(f.offsetX() * 2 + f.width() - c[2]) <= 1 && Math.abs(f.offsetY() * 2 + f.height() - c[3]) <= 1,
                    what + ": centred");
            // every screen pixel on the console maps to a design pixel inside the console, and the
            // screen position of a design pixel maps back to it
            for (int sx = f.offsetX(); sx < f.offsetX() + f.width(); sx += 7) {
                int dx = f.toDesignX(sx);
                check(dx >= 0 && dx < c[0], what + ": x " + sx + " -> " + dx);
            }
            for (int sy = f.offsetY(); sy < f.offsetY() + f.height(); sy += 7) {
                int dy = f.toDesignY(sy);
                check(dy >= 0 && dy < c[1], what + ": y " + sy + " -> " + dy);
            }
            for (int d = 0; d < c[0]; d += 13) {
                int centre = f.offsetX() + (int) Math.floor((d + 0.5) * f.scale());
                // exact when enlarged; when shrunk a screen pixel covers 1/scale design pixels
                int tolerance = f.scale() >= 1 ? 0 : (int) Math.ceil(1 / f.scale());
                check(Math.abs(f.toDesignX(centre) - d) <= tolerance,
                        what + ": design x " + d + " round trip " + f.toDesignX(centre));
            }
            check(!f.contains(f.offsetX() - 1, f.offsetY()) && !f.contains(f.offsetX(), f.offsetY() + f.height()),
                    what + ": bars are outside");
            System.out.printf("  %-26s scale %.4f, console %4d x %4d at (%d, %d)%n", what, f.scale(), f.width(), f.height(),
                    f.offsetX(), f.offsetY());
        }
        ConsoleScale zero = new ConsoleScale(100, 100);
        zero.fit(0, 0);
        check(zero.width() == 0 && !zero.contains(0, 0), "zero-size area: nothing to hit");
        System.out.println(failures == 0 ? "CONSOLE SCALE CHECK PASSED" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            if (failures <= 20) {
                System.out.println("FAIL " + what);
            }
        }
    }
}
