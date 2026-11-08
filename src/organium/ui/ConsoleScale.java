package organium.ui;

/**
 * How a console designed at a fixed pixel size is shown in an area of another size: scaled
 * proportionally (the original aspect ratio is kept) to the largest size that fits entirely, and
 * centred, with the leftover area on two sides as bars. Converts between the two coordinate
 * systems so that hit testing keeps using the organ's own design coordinates.
 *
 * Mutable so the window can update it in place on every resize without allocating; used on the
 * event thread only.
 */
final class ConsoleScale {
    private final int designWidth;
    private final int designHeight;
    private double scale = 1;
    private int offsetX;
    private int offsetY;
    private int width;
    private int height;

    ConsoleScale(int designWidth, int designHeight) {
        if (designWidth <= 0 || designHeight <= 0) {
            throw new IllegalArgumentException("Console size must be positive: " + designWidth + " x " + designHeight);
        }
        this.designWidth = designWidth;
        this.designHeight = designHeight;
        width = designWidth;
        height = designHeight;
    }

    /** The largest scale at which a {@code designWidth x designHeight} console fits entirely in the area. */
    static double fitScale(int designWidth, int designHeight, int areaWidth, int areaHeight) {
        return Math.max(0, Math.min((double) areaWidth / designWidth, (double) areaHeight / designHeight));
    }

    /** Fits the console into an area of the given size (scaling up as well as down) and centres it. */
    void fit(int areaWidth, int areaHeight) {
        scale = fitScale(designWidth, designHeight, areaWidth, areaHeight);
        width = scaled(designWidth);
        height = scaled(designHeight);
        offsetX = Math.max(0, (areaWidth - width) / 2);
        offsetY = Math.max(0, (areaHeight - height) / 2);
    }

    /** A design-space coordinate or length at the current scale, rounded to whole pixels. */
    int scaled(int design) {
        return (int) Math.round(design * scale);
    }

    double scale() {
        return scale;
    }

    int offsetX() {
        return offsetX;
    }

    int offsetY() {
        return offsetY;
    }

    /** Width of the scaled console in screen pixels. */
    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** True if the screen point lies on the scaled console (not on a bar). */
    boolean contains(int screenX, int screenY) {
        return screenX >= offsetX && screenX < offsetX + width && screenY >= offsetY && screenY < offsetY + height;
    }

    /** The design-space x under the centre of a screen pixel (the inverse of drawing at the current scale). */
    int toDesignX(int screenX) {
        return (int) Math.floor((screenX - offsetX + 0.5) / scale);
    }

    int toDesignY(int screenY) {
        return (int) Math.floor((screenY - offsetY + 0.5) / scale);
    }
}
