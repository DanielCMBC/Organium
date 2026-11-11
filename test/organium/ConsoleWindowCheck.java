package organium;

import organium.audio.VoiceEngine;
import organium.io.OrganParser;
import organium.organ.Console;
import organium.organ.Organ;
import organium.ui.ConsoleWindow;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Checks console scaling on a real organ's console, through the operating system's mouse: the window
 * opens inside the screen's working area with the console's aspect ratio; at several window sizes
 * (as opened, small, wide, tall, maximised) clicking the centre of stop knobs spread over the
 * console, including its bottom row, toggles exactly those stops; the event thread stays responsive
 * while the console is rescaled; and painting allocates nothing.
 *
 * No samples are loaded and no audio is started: only the switches are exercised.
 *
 * Usage: ConsoleWindowCheck <organ.xml> [screenshot dir]
 * The mouse pointer moves and clicks on its own while this runs.
 */
public final class ConsoleWindowCheck {
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path def = Path.of(args[0]);
        Path shots = args.length > 1 ? Path.of(args[1]) : null;
        if (shots != null) {
            Files.createDirectories(shots);
        }
        Organ organ = OrganParser.parse(def);
        VoiceEngine engine = new VoiceEngine(64, organ.pipes().size(), 48000);
        Console console = new Console(organ, Map.of(), engine);
        ConsoleWindow window = ConsoleWindow.open(console, OrganParser.sampleSetsRootOf(def), () -> { });
        Robot robot = new Robot();
        robot.setAutoDelay(15);
        settle(robot);

        Organ.Display d = organ.display();
        int designW = d.width();
        int designH = d.height();
        List<Organ.SwitchImage> targets = targets(d);
        System.out.printf("console %d x %d, %d clickable switches on the default page; testing %d%n", designW, designH,
                d.switchImages().stream().filter(s -> s.clickable() && s.image().pageId() == d.defaultPageId()).count(),
                targets.size());

        // opened size: inside the working area, never larger than the design
        GraphicsConfiguration gc = window.getGraphicsConfiguration();
        Rectangle b = gc.getBounds();
        Insets taskbar = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        Rectangle area = new Rectangle(b.x + taskbar.left, b.y + taskbar.top, b.width - taskbar.left - taskbar.right,
                b.height - taskbar.top - taskbar.bottom);
        Rectangle opened = onEdt(window::getBounds);
        System.out.println("screen working area " + area.width + " x " + area.height + "; window opened at " + opened);
        check(area.contains(opened), "window opens inside the screen's working area");
        JComponent surface = onEdt(() -> (JComponent) window.getContentPane().getComponent(0));
        Rectangle content = onEdt(surface::getBounds);
        check(content.width <= designW && content.height <= designH, "not larger than the design size");
        double aspect = (double) content.width / content.height;
        check(Math.abs(aspect - (double) designW / designH) < 0.003, "content keeps the aspect ratio (" + aspect + ")");

        String[] names = {"opened", "small-800x600", "wide-1400x600", "tall-700x1000", "maximised"};
        for (String name : names) {
            switch (name) {
                case "small-800x600" -> resize(window, 800, 600);
                case "wide-1400x600" -> resize(window, 1400, 600);
                case "tall-700x1000" -> resize(window, 700, Math.min(1000, area.height));
                case "maximised" -> SwingUtilities.invokeAndWait(() -> window.setExtendedState(Frame.MAXIMIZED_BOTH));
                default -> { }
            }
            long stall = worstEventThreadStall(1000);
            settle(robot);
            Rectangle surf = onEdt(surface::getBounds);
            Point origin = onEdt(surface::getLocationOnScreen);
            double s = Math.min((double) surf.width / designW, (double) surf.height / designH);
            int ox = (surf.width - (int) Math.round(designW * s)) / 2;
            int oy = (surf.height - (int) Math.round(designH * s)) / 2;
            int ok = 0;
            for (Organ.SwitchImage si : targets) {
                Organ.ImageInstance img = si.image();
                int w = img.imageSet().width() > 0 ? img.imageSet().width() : 10;
                int h = img.imageSet().height() > 0 ? img.imageSet().height() : 10;
                int x = origin.x + ox + (int) Math.round((img.x() + w / 2.0) * s);
                int y = origin.y + oy + (int) Math.round((img.y() + h / 2.0) * s);
                if (!onEdt(window::getBounds).contains(x, y)) {
                    check(false, name + ": knob " + si.control().name() + " outside the window, not clicked");
                    continue;
                }
                boolean before = console.isEngaged(si.control());
                boolean[] othersBefore = states(console, targets);
                robot.mouseMove(x, y);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                settle(robot);
                boolean after = console.isEngaged(si.control());
                boolean[] othersAfter = states(console, targets);
                int changed = 0;
                for (int i = 0; i < targets.size(); i++) {
                    changed += othersBefore[i] != othersAfter[i] ? 1 : 0;
                }
                if (after != before && changed == 1) {
                    ok++;
                } else {
                    check(false, name + ": clicking " + si.control().name() + " at (" + x + ", " + y + ") changed it "
                            + before + " -> " + after + ", " + changed + " tested switches changed");
                }
            }
            System.out.printf("  %-14s surface %4d x %4d, scale %.3f: %d/%d knobs toggled by mouse; "
                    + "longest event-thread stall while rescaling %d ms%n", name, surf.width, surf.height, s, ok,
                    targets.size(), stall);
            check(stall < 500, name + ": event thread stalled " + stall + " ms");
            if (shots != null) {
                Rectangle wb = onEdt(window::getBounds);
                ImageIO.write(robot.createScreenCapture(wb), "png", shots.resolve("console-" + name + ".png").toFile());
            }
        }

        // painting allocates nothing once the view is prepared
        long[] bytes = new long[2];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Method paint = surface.getClass().getDeclaredMethod("paintComponent", java.awt.Graphics.class);
                paint.setAccessible(true);
                BufferedImage target = window.getGraphicsConfiguration().createCompatibleImage(surface.getWidth(),
                        surface.getHeight());
                Graphics2D g = target.createGraphics();
                Object[] arg = {g}; // reused, so the reflective call allocates no argument array
                com.sun.management.ThreadMXBean threads =
                        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                for (int i = 0; i < 300; i++) { // warm up
                    paint.invoke(surface, arg);
                }
                long tid = Thread.currentThread().threadId();
                long a0 = threads.getThreadAllocatedBytes(tid);
                for (int i = 0; i < 300; i++) {
                    paint.invoke(surface, arg);
                }
                bytes[0] = threads.getThreadAllocatedBytes(tid) - a0;
                // what Java2D itself allocates per blit, on the same surface
                BufferedImage probe = window.getGraphicsConfiguration().createCompatibleImage(40, 40);
                for (int i = 0; i < 2000; i++) {
                    g.drawImage(probe, 10, 10, null);
                }
                long b0 = threads.getThreadAllocatedBytes(tid);
                for (int i = 0; i < 3000; i++) {
                    g.drawImage(probe, 10, 10, null);
                }
                bytes[1] = (threads.getThreadAllocatedBytes(tid) - b0) / 3000;
                g.dispose();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        long blits = 1 + d.switchImages().stream().filter(s -> s.image().pageId() == d.defaultPageId()).count();
        double perPaint = bytes[0] / 300.0;
        System.out.printf("paint allocation: %.0f bytes per paint; Java2D allocates %d bytes per drawImage, "
                + "and a paint makes at most %d%n", perPaint, bytes[1], blits);
        check(perPaint <= blits * bytes[1] + 16, "paint allocates nothing beyond Java2D's own per-blit allocation");

        System.out.println(failures == 0 ? "CONSOLE WINDOW CHECK PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Clickable switches on the default page: the top-most, bottom-most, left-most, right-most and a few between. */
    private static List<Organ.SwitchImage> targets(Organ.Display d) {
        List<Organ.SwitchImage> all = new ArrayList<>(d.switchImages().stream()
                .filter(s -> s.clickable() && s.image().pageId() == d.defaultPageId()).toList());
        List<Organ.SwitchImage> picked = new ArrayList<>();
        Comparator<Organ.SwitchImage> byY = Comparator.comparingInt(s -> s.image().y());
        Comparator<Organ.SwitchImage> byX = Comparator.comparingInt(s -> s.image().x());
        for (Organ.SwitchImage s : List.of(all.stream().min(byY).orElseThrow(), all.stream().max(byY).orElseThrow(),
                all.stream().min(byX).orElseThrow(), all.stream().max(byX).orElseThrow())) {
            if (!picked.contains(s)) {
                picked.add(s);
            }
        }
        for (int i = 0; i < all.size() && picked.size() < 8; i += Math.max(1, all.size() / 4)) {
            if (!picked.contains(all.get(i))) {
                picked.add(all.get(i));
            }
        }
        // one switch per control: toggling a control flips every image of it
        List<Organ.SwitchImage> unique = new ArrayList<>();
        for (Organ.SwitchImage s : picked) {
            if (unique.stream().noneMatch(u -> u.control() == s.control())) {
                unique.add(s);
            }
        }
        return unique;
    }

    private static boolean[] states(Console console, List<Organ.SwitchImage> targets) {
        boolean[] s = new boolean[targets.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = console.isEngaged(targets.get(i).control());
        }
        return s;
    }

    private static void resize(JFrame window, int w, int h) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            window.setExtendedState(Frame.NORMAL);
            window.setSize(w, h);
        });
    }

    /** Polls the event thread for a while and returns the longest wait for it to run a task. */
    private static long worstEventThreadStall(long millis) throws Exception {
        long end = System.nanoTime() + millis * 1_000_000;
        long worst = 0;
        while (System.nanoTime() < end) {
            long t = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> { });
            worst = Math.max(worst, System.nanoTime() - t);
            Thread.sleep(5);
        }
        return worst / 1_000_000;
    }

    private static void settle(Robot robot) throws Exception {
        robot.waitForIdle();
        Thread.sleep(250); // past the rescale delay
        robot.waitForIdle();
    }

    private interface EdtCall<T> {
        T get() throws Exception;
    }

    private static <T> T onEdt(EdtCall<T> call) throws Exception {
        Object[] out = new Object[1];
        Exception[] failed = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                out[0] = call.get();
            } catch (Exception e) {
                failed[0] = e;
            }
        });
        if (failed[0] != null) {
            throw failed[0];
        }
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
