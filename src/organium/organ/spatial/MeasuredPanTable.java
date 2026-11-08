package organium.organ.spatial;

/**
 * Pan law looked up in a table of measured values, interpolated bilinearly. This is an empirical
 * model of Hauptwerk's behaviour, not Hauptwerk's formula, and can be replaced by any PanLaw.
 *
 * Rules, each taken from the measurements:
 * <ul>
 * <li>Antisymmetric in X: the table holds X >= 0 and p(-X, Y) = -p(X, Y). X = 0 is centred.</li>
 * <li>Z is ignored (no effect measured from -3.2 to +3.2 m).</li>
 * <li>Y = 0 is a row of its own, used only for Y exactly 0: the measured values there don't follow
 *     their neighbours. For Y != 0, only the rows on the same side of zero are interpolated.</li>
 * <li>Outside the table, coordinates are clamped to its edges (|X| to the last column, Y to the first
 *     or last row on its side).</li>
 * </ul>
 */
public final class MeasuredPanTable implements PanLaw {
    private final double[] xs;
    private final double[] negY;    // ascending, all < 0
    private final double[][] negP;
    private final double[] zeroP;   // row for Y == 0
    private final double[] posY;    // ascending, all > 0
    private final double[][] posP;

    /** @param p p[iy][ix] at (xs[ix], ys[iy]); xs ascending from 0, ys ascending and containing 0 */
    public MeasuredPanTable(double[] xs, double[] ys, double[][] p) {
        if (xs.length < 2 || xs[0] != 0) {
            throw new IllegalArgumentException("X nodes must start at 0");
        }
        int zero = -1;
        for (int i = 0; i < ys.length; i++) {
            if (ys[i] == 0) {
                zero = i;
            }
            if (i > 0 && ys[i] <= ys[i - 1]) {
                throw new IllegalArgumentException("Y nodes must be ascending");
            }
        }
        if (zero < 1 || zero > ys.length - 2) {
            throw new IllegalArgumentException("Y nodes need a 0 row with rows on both sides");
        }
        this.xs = xs.clone();
        negY = java.util.Arrays.copyOfRange(ys, 0, zero);
        posY = java.util.Arrays.copyOfRange(ys, zero + 1, ys.length);
        negP = new double[zero][];
        posP = new double[ys.length - zero - 1][];
        for (int i = 0; i < ys.length; i++) {
            if (p[i].length != xs.length) {
                throw new IllegalArgumentException("Row " + i + " has " + p[i].length + " values, expected " + xs.length);
            }
        }
        for (int i = 0; i < zero; i++) {
            negP[i] = p[i].clone();
        }
        zeroP = p[zero].clone();
        for (int i = zero + 1; i < ys.length; i++) {
            posP[i - zero - 1] = p[i].clone();
        }
    }

    /** The table measured from Hauptwerk 4.2.1. */
    public static MeasuredPanTable hauptwerk() {
        return new MeasuredPanTable(HauptwerkPanData.X, HauptwerkPanData.Y, HauptwerkPanData.P);
    }

    @Override
    public double pan(double x, double y, double z) {
        double sign = x < 0 ? -1 : 1;
        double ax = Math.abs(x);
        if (y == 0) {
            return sign * row(zeroP, ax);
        }
        double[] ys = y < 0 ? negY : posY;
        double[][] rows = y < 0 ? negP : posP;
        if (ys.length == 1) {
            return sign * row(rows[0], ax);
        }
        int i = lower(ys, y);
        double t = fraction(ys, i, y);
        return sign * (row(rows[i], ax) * (1 - t) + row(rows[i + 1], ax) * t);
    }

    private double row(double[] values, double ax) {
        int i = lower(xs, ax);
        double t = fraction(xs, i, ax);
        return values[i] * (1 - t) + values[i + 1] * t;
    }

    /** Index i of the interval [nodes[i], nodes[i+1]] containing v, clamped to the first/last interval. */
    private static int lower(double[] nodes, double v) {
        int i = 0;
        while (i < nodes.length - 2 && v > nodes[i + 1]) {
            i++;
        }
        return i;
    }

    /** Position of v in [nodes[i], nodes[i+1]], clamped to [0, 1] (clamps outside the table). */
    private static double fraction(double[] nodes, int i, double v) {
        double t = (v - nodes[i]) / (nodes[i + 1] - nodes[i]);
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }
}
