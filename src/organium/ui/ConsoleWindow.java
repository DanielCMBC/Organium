package organium.ui;

import organium.organ.Console;
import organium.organ.Organ;
import organium.organ.Organ.Display;
import organium.organ.Organ.DisplayPage;
import organium.organ.Organ.ImageInstance;
import organium.organ.Organ.ImageSet;
import organium.organ.Organ.SwitchImage;
import organium.organ.Organ.Switch;
import organium.organ.Organ.TextInstance;
import organium.organ.Organ.TextStyle;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The organ's console screen: its display pages, one at a time. Each page is a static background with
 * every switch's current image composited over it at absolute coordinates, and text (such as stop
 * names) drawn on top.
 *
 * Everything that doesn't change is prepared for every page before the window opens, at the organ's
 * design size. Images not tied to a switch are flattened, in layer order, into the page's background
 * surface. Each switch's engaged and disengaged bitmaps are converted to screen-compatible images, and
 * positions, fonts and text baselines are stored in flat arrays.
 * (A plain image layered above a switch is flattened under it; no organ seen so far overlaps them.)
 *
 * The console is scaled proportionally to fit the window ({@link ConsoleScale}): the window opens as
 * large as the screen's working area allows, up to the design size, and can be resized or maximised;
 * the console is centred with bars on two sides. For the current scale, the visible page's background,
 * sprites and fonts are prepared once more as a {@link View}, so a paint only blits the background and
 * one sprite per switch and draws the page's strings. The paint code allocates nothing itself; Java2D's
 * drawImage allocates 32-96 bytes per call internally (measured on JDK 25). At scale 1 the view uses
 * the design images themselves. While the window is being resized, the last view is drawn stretched
 * until the size has settled and the view is rebuilt. All of this runs on the event thread.
 *
 * Pressing the mouse inside a clickable switch's bounding box on the visible page toggles it through
 * {@link Console}, which rebuilds the key routing table, then repaints; the mouse position is mapped
 * back to design coordinates, so hit boxes are the organ's own. Page Down/Page Up cycle through the
 * pages; 1-9 jump to a page by its position in page ID order.
 */
@SuppressWarnings("serial") // Swing components, never serialized
public final class ConsoleWindow extends JFrame {
    private static final FontRenderContext TEXT_RENDERING = new FontRenderContext(null, true, false);

    private final String organName;
    private final Page[] pages;      // in page ID order
    private final List<String> warnings;
    private final Surface surface;
    private final GraphicsConfiguration screen;
    private int active;              // index into pages of the page on screen; event thread only

    private ConsoleWindow(Console console, Loader loaded, Runnable onClose) {
        super(console.organ().name());
        organName = console.organ().name();
        pages = loaded.pages;
        warnings = loaded.warnings;
        active = loaded.firstPage;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose.run();
            }
        });
        screen = loaded.screen;
        surface = new Surface(console, loaded.width, loaded.height);
        bindPageKeys(surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW));
        add(surface);
        pack(); // around the design size; shrunk below if the screen is smaller
        fitToScreen(loaded.width, loaded.height);
        showPage(active);
        setVisible(true);
    }

    /** Sizes the window so the whole console fits in the screen's working area, never above design size, and centres it. */
    private void fitToScreen(int designWidth, int designHeight) {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        Rectangle bounds = gc.getBounds();
        Insets taskbar = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        Rectangle area = new Rectangle(bounds.x + taskbar.left, bounds.y + taskbar.top,
                bounds.width - taskbar.left - taskbar.right, bounds.height - taskbar.top - taskbar.bottom);
        Insets frame = getInsets();
        int frameWidth = frame.left + frame.right;
        int frameHeight = frame.top + frame.bottom;
        double scale = Math.min(1, ConsoleScale.fitScale(designWidth, designHeight,
                area.width - frameWidth, area.height - frameHeight));
        setSize((int) Math.round(designWidth * scale) + frameWidth, (int) Math.round(designHeight * scale) + frameHeight);
        setLocation(area.x + Math.max(0, (area.width - getWidth()) / 2), area.y + Math.max(0, (area.height - getHeight()) / 2));
    }

    /**
     * Loads every display page from {@code <sampleSetsRoot>/OrganInstallationPackages} on the calling
     * thread, then shows the organ's default page. {@code onClose} runs on the event thread when the
     * user closes the window.
     */
    public static ConsoleWindow open(Console console, Path sampleSetsRoot, Runnable onClose)
            throws InterruptedException, InvocationTargetException {
        Loader loaded = new Loader(console.organ(), sampleSetsRoot.resolve("OrganInstallationPackages"));
        ConsoleWindow[] window = new ConsoleWindow[1];
        SwingUtilities.invokeAndWait(() -> window[0] = new ConsoleWindow(console, loaded, onClose));
        return window[0];
    }

    /** Images that could not be loaded or are not defined; the rest of each page is still drawn. */
    public List<String> warnings() {
        return warnings;
    }

    private void bindPageKeys(InputMap keys) {
        bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), "nextPage",
                () -> showPage((active + 1) % pages.length));
        bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), "previousPage",
                () -> showPage((active + pages.length - 1) % pages.length));
        for (int n = 1; n <= Math.min(9, pages.length); n++) {
            int index = n - 1;
            bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, 0), "page" + n, () -> showPage(index));
            bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD0 + n, 0), "page" + n, () -> showPage(index));
        }
    }

    private void bind(InputMap keys, KeyStroke key, String name, Runnable action) {
        keys.put(key, name);
        surface.getActionMap().put(name, new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void showPage(int index) {
        active = index;
        setTitle(pages.length > 1 ? organName + " - " + pages[index].name : organName);
        surface.prepareActivePage();
        surface.repaint();
    }

    /** Draws the visible page; switch states are read from the console on every paint. */
    private final class Surface extends JComponent {
        private static final int RESCALE_DELAY_MS = 150; // after the last resize event

        private final Console console;
        private final ConsoleScale fit;
        private final View[] views = new View[pages.length]; // per page, each at views[i].scale; built on demand
        private final Timer rescale;

        Surface(Console console, int width, int height) {
            this.console = console;
            fit = new ConsoleScale(width, height);
            setOpaque(true);
            setPreferredSize(new Dimension(width, height));
            rescale = new Timer(RESCALE_DELAY_MS, e -> {
                prepareActivePage();
                repaint();
            });
            rescale.setRepeats(false);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    fit.fit(getWidth(), getHeight());
                    View v = views[active];
                    if (v == null || v.scale != fit.scale()) {
                        rescale.restart(); // until it fires, paint stretches the last view
                    }
                    repaint();
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && fit.contains(e.getX(), e.getY())) {
                        press(fit.toDesignX(e.getX()), fit.toDesignY(e.getY()));
                    }
                }
            });
        }

        /** Makes sure the visible page has a view at the current scale; views at other scales are dropped. */
        void prepareActivePage() {
            if (fit.scale() <= 0) {
                return; // not laid out yet, or no room
            }
            View v = views[active];
            if (v != null && v.scale == fit.scale()) {
                return;
            }
            for (int i = 0; i < views.length; i++) {
                if (views[i] != null && views[i].scale != fit.scale()) {
                    views[i] = null; // frees the images of a view at an old scale
                }
            }
            views[active] = new View(pages[active], fit, screen);
        }

        /** Toggles the topmost clickable switch under the point (design coordinates) on the visible page. */
        private void press(int x, int y) {
            Page p = pages[active];
            for (int i = p.switches.length - 1; i >= 0; i--) {
                if (p.clickable[i] && x >= p.left[i] && x < p.right[i] && y >= p.top[i] && y < p.bottom[i]) {
                    console.toggle(p.switches[i]);
                    repaint(); // whole page: an exclusive group may have released other switches
                    return;
                }
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            int ox = fit.offsetX();
            int oy = fit.offsetY();
            g2.setColor(Color.BLACK); // the bars beside the console
            if (ox > 0) {
                g2.fillRect(0, 0, ox, getHeight());
                g2.fillRect(ox + fit.width(), 0, getWidth() - ox - fit.width(), getHeight());
            }
            if (oy > 0) {
                g2.fillRect(0, 0, getWidth(), oy);
                g2.fillRect(0, oy + fit.height(), getWidth(), getHeight() - oy - fit.height());
            }
            View v = views[active];
            if (v == null) {
                prepareActivePage(); // a paint before the first resize event
                v = views[active];
                if (v == null) {
                    return;
                }
            }
            Page p = pages[active];
            double stretch = fit.scale() / v.scale; // 1 except while a resize is settling
            g2.translate(ox, oy);
            if (stretch != 1) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.scale(stretch, stretch);
            }
            g2.drawImage(v.background, 0, 0, null);
            for (int i = 0; i < p.switches.length; i++) {
                BufferedImage sprite = console.isEngaged(p.switches[i]) ? v.engaged[i] : v.disengaged[i];
                if (sprite != null) {
                    g2.drawImage(sprite, v.x[i], v.y[i], null);
                }
            }
            if (p.text.length > 0) {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                Font font = null;
                Color color = null;
                for (int i = 0; i < p.text.length; i++) { // sorted by style, so fonts rarely change
                    if (v.textFont[i] != font) {
                        font = v.textFont[i];
                        g2.setFont(font);
                    }
                    if (p.textColor[i] != color) {
                        color = p.textColor[i];
                        g2.setColor(color);
                    }
                    g2.drawString(p.text[i], v.textX[i], v.textY[i]);
                }
            }
            if (stretch != 1) {
                g2.scale(1 / stretch, 1 / stretch);
            }
            g2.translate(-ox, -oy);
        }
    }

    /**
     * A page prepared for drawing at one scale: background and sprites resampled to their scaled
     * pixel size, positions relative to the console's top left corner, fonts at the scaled size. At
     * scale 1 it uses the page's design images, coordinates and fonts unchanged.
     */
    private static final class View {
        final double scale;
        final BufferedImage background;
        final BufferedImage[] engaged;
        final BufferedImage[] disengaged;
        final int[] x;
        final int[] y;
        final Font[] textFont;
        final float[] textX;
        final float[] textY;

        View(Page p, ConsoleScale fit, GraphicsConfiguration screen) {
            scale = fit.scale();
            int n = p.switches.length;
            engaged = new BufferedImage[n];
            disengaged = new BufferedImage[n];
            x = new int[n];
            y = new int[n];
            background = resample(p.background, fit.width(), fit.height(), screen);
            Map<BufferedImage, BufferedImage> done = new HashMap<>(); // sprites shared between switches
            for (int i = 0; i < n; i++) {
                x[i] = fit.scaled(p.left[i]);
                y[i] = fit.scaled(p.top[i]);
                engaged[i] = sprite(p.engaged[i], p.left[i], p.top[i], fit, screen, done);
                disengaged[i] = sprite(p.disengaged[i], p.left[i], p.top[i], fit, screen, done);
            }
            int m = p.text.length;
            textFont = new Font[m];
            textX = new float[m];
            textY = new float[m];
            Map<Font, Font> fonts = new HashMap<>();
            for (int i = 0; i < m; i++) {
                textFont[i] = scale == 1 ? p.textFont[i]
                        : fonts.computeIfAbsent(p.textFont[i], f -> f.deriveFont((float) (f.getSize2D() * scale)));
                textX[i] = (float) (p.textX[i] * scale);
                textY[i] = (float) (p.textY[i] * scale);
            }
        }

        /** The sprite at the scale, sized so its edges land where the scaled design edges do. */
        private static BufferedImage sprite(BufferedImage img, int left, int top, ConsoleScale fit,
                                            GraphicsConfiguration screen, Map<BufferedImage, BufferedImage> done) {
            if (img == null) {
                return null;
            }
            int w = fit.scaled(left + img.getWidth()) - fit.scaled(left);
            int h = fit.scaled(top + img.getHeight()) - fit.scaled(top);
            if (w == img.getWidth() && h == img.getHeight()) {
                return img;
            }
            BufferedImage cached = done.get(img);
            if (cached != null && cached.getWidth() == w && cached.getHeight() == h) {
                return cached;
            }
            BufferedImage out = resample(img, w, h, screen);
            done.put(img, out);
            return out;
        }

        /**
         * Resamples to {@code w x h} with bilinear filtering, halving in steps first when shrinking to
         * half or less, so every source pixel contributes. Returns the image itself if the size matches.
         */
        static BufferedImage resample(BufferedImage src, int w, int h, GraphicsConfiguration screen) {
            w = Math.max(1, w);
            h = Math.max(1, h);
            if (src.getWidth() == w && src.getHeight() == h) {
                return src;
            }
            boolean opaque = src.getTransparency() == Transparency.OPAQUE;
            BufferedImage current = src;
            int cw = src.getWidth();
            int ch = src.getHeight();
            while (cw / 2 >= w || ch / 2 >= h) {
                cw = Math.max(w, cw / 2);
                ch = Math.max(h, ch / 2);
                current = draw(current, cw, ch, opaque
                        ? screen.createCompatibleImage(cw, ch, Transparency.OPAQUE)
                        : new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB_PRE));
            }
            return draw(current, w, h, screen.createCompatibleImage(w, h,
                    opaque ? Transparency.OPAQUE : Transparency.TRANSLUCENT));
        }

        private static BufferedImage draw(BufferedImage src, int w, int h, BufferedImage dst) {
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setComposite(AlphaComposite.Src);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return dst;
        }
    }

    /**
     * One display page, ready to draw. Switch arrays are in drawing order: by layer, then definition
     * order. Text arrays hold each string's baseline start, sorted by text style.
     */
    private static final class Page {
        final String name;
        final BufferedImage background;
        final Switch[] switches;
        final BufferedImage[] engaged;
        final BufferedImage[] disengaged;
        final int[] left;
        final int[] top;
        final int[] right;
        final int[] bottom;
        final boolean[] clickable;
        final String[] text;
        final Font[] textFont;
        final Color[] textColor;
        final int[] textX;
        final int[] textY;

        Page(String name, BufferedImage background, List<SwitchImage> drawn, List<TextInstance> texts, Loader loader) {
            this.name = name;
            this.background = background;

            int n = drawn.size();
            switches = new Switch[n];
            engaged = new BufferedImage[n];
            disengaged = new BufferedImage[n];
            left = new int[n];
            top = new int[n];
            right = new int[n];
            bottom = new int[n];
            clickable = new boolean[n];
            for (int i = 0; i < n; i++) {
                SwitchImage si = drawn.get(i);
                ImageInstance image = si.image();
                ImageSet set = image.imageSet();
                switches[i] = si.control();
                engaged[i] = loader.sprite(set, si.engagedElement());
                disengaged[i] = loader.sprite(set, si.disengagedElement());
                BufferedImage any = engaged[i] != null ? engaged[i] : disengaged[i];
                left[i] = image.x();
                top[i] = image.y();
                right[i] = image.x() + (set.width() > 0 ? set.width() : any != null ? any.getWidth() : 0);
                bottom[i] = image.y() + (set.height() > 0 ? set.height() : any != null ? any.getHeight() : 0);
                clickable[i] = si.clickable();
            }

            List<TextInstance> sorted = texts.stream().sorted(Comparator.comparingInt(t -> t.style().id())).toList();
            int m = sorted.size();
            text = new String[m];
            textFont = new Font[m];
            textColor = new Color[m];
            textX = new int[m];
            textY = new int[m];
            for (int i = 0; i < m; i++) {
                TextInstance t = sorted.get(i);
                Font font = loader.font(t.style());
                double width = font.getStringBounds(t.text(), TEXT_RENDERING).getWidth();
                text[i] = t.text();
                textFont[i] = font;
                textColor[i] = loader.color(t.style());
                textX[i] = t.style().centred() ? (int) Math.round(t.x() - width / 2) : t.x();
                textY[i] = t.y() + Math.round(font.getLineMetrics(t.text(), TEXT_RENDERING).getAscent());
            }
        }
    }

    /** Builds every page, sharing decoded images, fonts and colours between them. */
    private static final class Loader {
        final Page[] pages;
        final int firstPage;
        final int width;
        final int height;
        final List<String> warnings;
        final GraphicsConfiguration screen;

        private final Path packages;
        private final Set<String> fontFamilies;
        private final Map<Path, BufferedImage> files = new HashMap<>();
        private final Map<String, BufferedImage> sprites = new HashMap<>();
        private final Map<Integer, Font> fonts = new HashMap<>();
        private final Map<Integer, Color> colors = new HashMap<>();
        private final List<String> problems = new ArrayList<>();

        Loader(Organ organ, Path packages) {
            this.packages = packages;
            GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
            screen = env.getDefaultScreenDevice().getDefaultConfiguration();
            fontFamilies = Set.of(env.getAvailableFontFamilyNames());
            Display display = organ.display();

            List<DisplayPage> listed = display.pages().stream()
                    .sorted(Comparator.comparingInt(DisplayPage::id)).toList();
            if (listed.isEmpty()) { // no page table: one page per page ID the images use
                Set<Integer> ids = new TreeSet<>();
                display.images().forEach(i -> ids.add(i.pageId()));
                listed = ids.stream().map(id -> new DisplayPage(id, "Page " + id)).toList();
            }
            if (listed.isEmpty()) {
                listed = List.of(new DisplayPage(display.defaultPageId(), "Console"));
            }

            int w = display.width();
            int h = display.height();
            if (w <= 0 || h <= 0) { // not specified: fit everything on any page
                for (ImageInstance image : display.images()) {
                    BufferedImage img = sprite(image.imageSet(), image.defaultElement());
                    if (img != null) {
                        w = Math.max(w, image.x() + Math.max(img.getWidth(), image.fillWidth()));
                        h = Math.max(h, image.y() + Math.max(img.getHeight(), image.fillHeight()));
                    }
                }
                w = Math.max(w, 1);
                h = Math.max(h, 1);
            }
            width = w;
            height = h;

            pages = new Page[listed.size()];
            int first = 0;
            for (int i = 0; i < pages.length; i++) {
                DisplayPage page = listed.get(i);
                pages[i] = page(page, display);
                if (page.id() == display.defaultPageId()) {
                    first = i;
                }
            }
            firstPage = first;
            warnings = List.copyOf(problems);
        }

        private Page page(DisplayPage page, Display display) {
            Map<Integer, SwitchImage> switchOfImage = new HashMap<>();
            for (SwitchImage si : display.switchImages()) {
                if (si.image().pageId() == page.id()) {
                    switchOfImage.putIfAbsent(si.image().id(), si);
                }
            }
            List<ImageInstance> onPage = display.images().stream()
                    .filter(i -> i.pageId() == page.id())
                    .sorted(Comparator.comparingInt(ImageInstance::layer)) // stable: keeps definition order
                    .toList();
            if (onPage.isEmpty()) {
                problems.add("Display page '" + page.name() + "' has no images");
            }

            List<ImageInstance> plain = new ArrayList<>();
            List<SwitchImage> drawn = new ArrayList<>();
            for (ImageInstance image : onPage) {
                SwitchImage si = switchOfImage.get(image.id());
                if (si != null) {
                    drawn.add(si);
                } else {
                    plain.add(image);
                }
            }
            List<TextInstance> texts = display.texts().stream().filter(t -> t.pageId() == page.id()).toList();
            return new Page(page.name(), flatten(plain), drawn, texts, this);
        }

        private BufferedImage flatten(List<ImageInstance> images) {
            BufferedImage surface = screen.createCompatibleImage(width, height, Transparency.OPAQUE);
            Graphics2D g = surface.createGraphics();
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, width, height);
            for (ImageInstance image : images) {
                BufferedImage img = sprite(image.imageSet(), image.defaultElement());
                if (img == null) {
                    continue;
                }
                if (image.fillWidth() > 0 && image.fillHeight() > 0) {
                    g.setClip(image.x(), image.y(), image.fillWidth(), image.fillHeight());
                    for (int y = image.y(); y < image.y() + image.fillHeight(); y += img.getHeight()) {
                        for (int x = image.x(); x < image.x() + image.fillWidth(); x += img.getWidth()) {
                            g.drawImage(img, x, y, null);
                        }
                    }
                    g.setClip(null);
                } else {
                    g.drawImage(img, image.x(), image.y(), null);
                }
            }
            g.dispose();
            return surface;
        }

        /** The style's font if installed, otherwise the platform's sans-serif font, at the style's pixel size. */
        Font font(TextStyle style) {
            return fonts.computeIfAbsent(style.id(), id -> {
                String family = style.fontName();
                if (family == null || !fontFamilies.contains(family)) {
                    problems.add("Font '" + family + "' (text style '" + style.name() + "') is not installed; using sans-serif");
                    family = Font.SANS_SERIF;
                }
                return new Font(family, Font.PLAIN, style.sizePixels()); // user space units are pixels
            });
        }

        Color color(TextStyle style) {
            return colors.computeIfAbsent(style.id(), id -> new Color(style.rgb()));
        }

        /** Element {@code index} of the set as a screen-compatible image with its mask applied, or null if unavailable. */
        BufferedImage sprite(ImageSet set, int index) {
            String key = set.id() + ":" + index;
            if (sprites.containsKey(key)) {
                return sprites.get(key);
            }
            BufferedImage result = null;
            String file = set.elements().get(index);
            if (file == null) {
                problems.add("Image set '" + set.name() + "' has no image " + index);
            } else {
                BufferedImage img = read(set, file);
                BufferedImage mask = set.maskPath() != null ? read(set, set.maskPath()) : null;
                if (img != null) {
                    result = compatible(img, mask);
                }
            }
            sprites.put(key, result);
            return result;
        }

        private BufferedImage read(ImageSet set, String file) {
            Path path = packages.resolve(String.format("%06d", set.packageId())).resolve(file);
            if (files.containsKey(path)) {
                return files.get(path);
            }
            BufferedImage img = null;
            try {
                if (!Files.isRegularFile(path)) {
                    problems.add("Missing image file " + path);
                } else if ((img = ImageIO.read(path.toFile())) == null) {
                    problems.add("Unsupported image format: " + path);
                }
            } catch (IOException e) {
                problems.add("Failed to read " + path + ": " + e.getMessage());
            }
            files.put(path, img);
            return img;
        }

        /** Copies into the screen's pixel format; mask pixels that aren't black become transparent. */
        private BufferedImage compatible(BufferedImage img, BufferedImage mask) {
            int w = img.getWidth();
            int h = img.getHeight();
            if (mask == null) {
                BufferedImage out = screen.createCompatibleImage(w, h, img.getColorModel().getTransparency());
                Graphics2D g = out.createGraphics();
                g.drawImage(img, 0, 0, null);
                g.dispose();
                return out;
            }
            BufferedImage out = screen.createCompatibleImage(w, h, Transparency.BITMASK);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    boolean drawn = x >= mask.getWidth() || y >= mask.getHeight()
                            || (mask.getRGB(x, y) & 0xFFFFFF) == 0;
                    out.setRGB(x, y, drawn ? img.getRGB(x, y) | 0xFF000000 : 0);
                }
            }
            return out;
        }
    }
}
