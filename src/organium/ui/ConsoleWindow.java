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
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.event.ActionEvent;
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
 * The organ's console screen: its display pages, one at a time, drawn at the organ's fixed pixel
 * size. Each page is a static background with every switch's current image composited over it at
 * absolute coordinates, and text (such as stop names) drawn on top.
 *
 * Everything that doesn't change is prepared for every page before the window opens. Images not tied
 * to a switch are flattened, in layer order, into the page's background surface. Each switch's
 * engaged and disengaged bitmaps are converted to screen-compatible images, and positions, fonts and
 * text baselines are stored in flat arrays. A paint then only blits the background and one sprite per
 * switch and draws the page's strings, and allocates nothing.
 * (A plain image layered above a switch is flattened under it; no organ seen so far overlaps them.)
 *
 * Pressing the mouse inside a clickable switch's bounding box on the visible page toggles it through
 * {@link Console}, which rebuilds the key routing table, then repaints. Page Down/Page Up cycle
 * through the pages; 1-9 jump to a page by its position in page ID order.
 */
@SuppressWarnings("serial") // Swing components, never serialized
public final class ConsoleWindow extends JFrame {
    private static final FontRenderContext TEXT_RENDERING = new FontRenderContext(null, true, false);

    private final String organName;
    private final Page[] pages;      // in page ID order
    private final List<String> warnings;
    private final Surface surface;
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
        surface = new Surface(console, loaded.width, loaded.height);
        bindPageKeys(surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW));
        setResizable(false); // before pack(), so the frame is sized around the fixed surface
        add(surface);
        pack();
        setLocationRelativeTo(null);
        showPage(active);
        setVisible(true);
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
        surface.repaint();
    }

    /** Draws the visible page; switch states are read from the console on every paint. */
    private final class Surface extends JComponent {
        private final Console console;

        Surface(Console console, int width, int height) {
            this.console = console;
            setOpaque(true);
            setPreferredSize(new Dimension(width, height));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        press(e.getX(), e.getY());
                    }
                }
            });
        }

        /** Toggles the topmost clickable switch under the point on the visible page. */
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
            Page p = pages[active];
            g.drawImage(p.background, 0, 0, null);
            for (int i = 0; i < p.switches.length; i++) {
                BufferedImage sprite = console.isEngaged(p.switches[i]) ? p.engaged[i] : p.disengaged[i];
                if (sprite != null) {
                    g.drawImage(sprite, p.left[i], p.top[i], null);
                }
            }
            if (p.text.length == 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            Font font = null;
            Color color = null;
            for (int i = 0; i < p.text.length; i++) { // sorted by style, so fonts rarely change
                if (p.textFont[i] != font) {
                    font = p.textFont[i];
                    g2.setFont(font);
                }
                if (p.textColor[i] != color) {
                    color = p.textColor[i];
                    g2.setColor(color);
                }
                g2.drawString(p.text[i], p.textX[i], p.textY[i]);
            }
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

        private final Path packages;
        private final GraphicsConfiguration screen;
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
