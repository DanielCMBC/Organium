package organium;

import organium.organ.spatial.MeasuredPanTable;
import organium.organ.spatial.PanLaw;

/**
 * Checks the rules of MeasuredPanTable on Hauptwerk's table: centre, antisymmetry, Z ignored,
 * range, the Y = 0 row, clamping outside the domain, and continuity inside it. Also times pan().
 */
public final class PanTableCheck {
    private static int fails;

    private static void check(boolean ok, String what) {
        if (!ok) {
            fails++;
        }
        System.out.println((ok ? "OK    " : "FAIL  ") + what);
    }

    public static void main(String[] args) {
        PanLaw t = MeasuredPanTable.hauptwerk();
        double worstCentre = 0;
        double worstSym = 0;
        double worstZ = 0;
        double maxAbs = 0;
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < 200_000; i++) {
            double x = rnd.nextDouble() * 10 - 5;
            double y = rnd.nextDouble() * 14 - 5;
            double z = rnd.nextDouble() * 8 - 4;
            worstCentre = Math.max(worstCentre, Math.abs(t.pan(0, y, z)));
            worstSym = Math.max(worstSym, Math.abs(t.pan(x, y, 0) + t.pan(-x, y, 0)));
            worstZ = Math.max(worstZ, Math.abs(t.pan(x, y, z) - t.pan(x, y, 0)));
            maxAbs = Math.max(maxAbs, Math.abs(t.pan(x, y, z)));
        }
        check(worstCentre == 0, "X = 0 is exactly centred (max |p| " + worstCentre + ")");
        check(worstSym == 0, "p(-X, Y) = -p(X, Y) exactly (max error " + worstSym + ")");
        check(worstZ == 0, "Z has no effect (max difference " + worstZ + ")");
        check(maxAbs < 1, "|p| < 1, so neither channel's gain goes negative (max " + maxAbs + ")");
        check(t.pan(1, 0, 0) != t.pan(1, 1e-9, 0), "Y = 0 uses its own row (p(1, 0) = " + t.pan(1, 0, 0)
                + ", p(1, 1e-9) = " + t.pan(1, 1e-9, 0) + ")");
        check(t.pan(9, 3, 0) == t.pan(4, 3, 0) && t.pan(1, 30, 0) == t.pan(1, 8, 0) && t.pan(1, -30, 0) == t.pan(1, -4, 0),
                "outside the domain, coordinates clamp to its edges");
        double jump = 0;
        for (double y : new double[]{-3.3, -0.7, 0.3, 1.2, 2.6, 4.4}) {
            for (double x = 0; x < 4; x += 0.001) {
                jump = Math.max(jump, Math.abs(t.pan(x + 0.001, y, 0) - t.pan(x, y, 0)));
            }
        }
        check(jump < 0.01, "continuous along X (largest change per mm " + jump + ")");

        double sink = 0;
        for (int i = 0; i < 2_000_000; i++) {
            sink += t.pan(i * 1e-6, 3 - i * 1e-6, 0);
        }
        long t0 = System.nanoTime();
        int n = 5_000_000;
        for (int i = 0; i < n; i++) {
            sink += t.pan((i % 4000) * 1e-3, (i % 1200) * 1e-2 - 4, 0);
        }
        long t1 = System.nanoTime();
        System.out.printf("pan() cost: %.1f ns per pipe (load time only), checksum %.3f%n", (t1 - t0) / (double) n, sink);
        System.out.println(fails == 0 ? "PAN TABLE CHECK PASSED" : fails + " PAN TABLE CHECK(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
