import java.awt.Container;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * The installer, installed, photographed - and then Explorer's own right-click menus with
 * the entries it wrote in them.
 *
 * <p>Run on Windows, from the root of an unpacked release (or of this repository, which
 * has the same layout: {@code jrock.jar} beside {@code samples/}):
 *
 * <pre>
 *     javac -encoding UTF-8 -d out-installer installer/InstallJRock.java
 *     java -cp "jrock.jar;out-installer" .github/build/InstallerShots.java screenshots windows
 * </pre>
 *
 * <p>WHY THIS IS A TOOL AND NOT A SCREENSHOT TAKEN BY HAND. Three of the README's
 * pictures are of things no other picture can show - the wizard's own catalogue of agents
 * and flows with the ticks in it, and the two Explorer menus that are the whole point of
 * installing on Windows - and all three went stale the moment a label changed, because a
 * person had to remember to take them again. The wizard is Swing, so it can be driven the
 * way {@code Screenshots.java} drives JRock: by pressing its real buttons and ticking its
 * real boxes. The Explorer menus are the shell's, so they are driven by the keyboard
 * through {@link java.awt.Robot} - Escape and Shift+F10, which is the context menu key
 * combination Windows has had since NT, rather than a mouse click at a guessed pixel.
 *
 * <p>IT REALLY INSTALLS, because an Explorer menu with JRock's entries in it is a menu on
 * a machine where JRock is installed; there is nothing to photograph otherwise. So it
 * installs into a folder of its own, under the system's temporary directory by default,
 * and the entries it writes are {@code HKEY_CURRENT_USER} entries of that installation's
 * own name - which {@code --clean} removes again at the end. On a build runner that is
 * thrown away afterwards anyway; at a desk it leaves nothing behind.
 *
 * <p>Nothing here fails a build. A page that is not where it was, an Explorer that will
 * not start, a Robot a window manager will not let near the keyboard: each is a line in
 * the log and one picture fewer, because the step exists for the pictures it CAN take.
 */
public final class InstallerShots {

    /** The flows whose ticks come off: six languages in one menu is a menu nobody reads. */
    private static final List<String> UNWANTED = java.util.Arrays.asList(
            "Translate To Arabic", "Translate To Chinese",
            "Translate To French", "Translate To Russian");

    /** How long any one wait gets before it is given up on. */
    private static final int PATIENCE_MS = 30_000;

    private static Path out;
    private static String os;
    private static int taken;

    public static void main(String[] args) throws Exception {
        out = Paths.get(args.length > 0 ? args[0] : "screenshots").toAbsolutePath();
        os = args.length > 1 ? args[1] : System.getProperty("os.name")
                .toLowerCase().replaceAll("[^a-z0-9]+", "");
        Path home = args.length > 2 ? Paths.get(args[2]).toAbsolutePath()
                : Paths.get(System.getProperty("java.io.tmpdir")).resolve("JRockShots");
        boolean clean = java.util.Arrays.asList(args).contains("--clean");
        Files.createDirectories(out);

        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            System.out.println("not Windows: the installer's pictures are of Windows, and "
                    + "Explorer has no menus anywhere else");
            System.exit(0);
        }

        // A folder with something in it to right-click, which is what the file menu is a
        // picture of. The name is the kind of thing that is actually in such a folder.
        Path documents = home.getParent().resolve("JRockShotsDemo");
        Files.createDirectories(documents);
        Files.write(documents.resolve("invoice-2026-0042.pdf"),
                ("%PDF-1.4\n% a stand-in, for a menu to be opened on\n").getBytes(
                        StandardCharsets.UTF_8));

        System.out.println("installing into " + home);
        JFrame wizard = startWizard();
        if (wizard == null) System.exit(0);

        drive(wizard, home);
        explorerMenus(documents);
        if (clean) clean(home);

        System.out.println("wrote " + taken + " screenshot(s) to " + out);
        System.exit(0);
    }

    /** The installer, started in this JVM, and its window once it is up. */
    private static JFrame startWizard() {
        try {
            Class<?> type = Class.forName("InstallJRock");
            java.lang.reflect.Method main = type.getMethod("main", String[].class);
            new Thread(() -> {
                try {
                    main.invoke(null, (Object) new String[0]);
                } catch (ReflectiveOperationException ex) {
                    System.out.println("the installer would not start: " + ex.getCause());
                }
            }, "the installer").start();
        } catch (ReflectiveOperationException ex) {
            System.out.println("InstallJRock is not on the class path: " + ex);
            return null;
        }
        for (int waited = 0; waited < PATIENCE_MS; waited += 100) {
            for (Window w : Window.getWindows()) {
                if (w instanceof JFrame && w.isShowing()) return (JFrame) w;
            }
            pause(100);
        }
        System.out.println("the installer's window never appeared");
        return null;
    }

    /**
     * Through the wizard, page by page, doing on each page what that page is for: the
     * install folder on the one that asks for it, the catalogue on the one that offers
     * it, and Next on all of them.
     *
     * <p>The pages are recognised by what is ON them rather than by their order or their
     * titles - a wizard that grows a page should not silently photograph the wrong one.
     */
    private static void drive(JFrame wizard, Path home) {
        for (int page = 0; page < 14; page++) {
            if (!wizard.isShowing()) break;
            settle();

            javax.swing.JTextField folder = field(wizard, "JRockAssistant");
            if (folder != null) {
                String path = home.toString();
                onEdt(() -> folder.setText(path));
                System.out.println("  page " + (page + 1) + ": installing into " + path);
                settle();
            }

            javax.swing.JRadioButton choose = radio(wizard,
                    "Choose which, and where each agent works");
            if (choose != null && !choose.isSelected()) {
                onEdt(choose::doClick);
                settle();
                int off = 0;
                for (String unwanted : UNWANTED) {
                    javax.swing.JCheckBox box = check(wizard, unwanted);
                    if (box != null && box.isSelected()) {
                        onEdt(box::doClick);
                        off++;
                    }
                }
                settle();
                System.out.println("  page " + (page + 1) + ": the catalogue, with "
                        + off + " translation flow(s) left out");
                shoot(wizard, "13-the-installer-agents-and-flows");
            }

            // The last page says what it did, which is the other picture worth having:
            // every folder, every entry, every file, named.
            if (button(wizard, "Next >") == null || !button(wizard, "Next >").isEnabled()) {
                shoot(wizard, "14-the-installer-finished");
                break;
            }
            javax.swing.JButton next = button(wizard, "Next >");
            String before = heading(wizard);
            onEdt(next::doClick);
            settle();
            if (dismissWarning()) {
                System.out.println("  page " + (page + 1) + " would not let go: "
                        + heading(wizard));
                break;
            }
            if (heading(wizard) != null && heading(wizard).equals(before)
                    && page > 0 && button(wizard, "Next >") != null) {
                // The same page twice means Next did nothing, and pressing it again will
                // do nothing again.
                shoot(wizard, "14-the-installer-finished");
                break;
            }
        }
        if (wizard.isShowing()) onEdt(wizard::dispose);
    }

    /**
     * Explorer, on a folder with a file in it, with its own right-click menu open - the
     * background's, and then the file's.
     *
     * <p>BY THE KEYBOARD, not by the mouse. Shift+F10 opens the context menu for whatever
     * is selected, which is a place this tool knows: nothing (the folder's own menu) or
     * the first item (the file's). A right-click needs a pixel inside a window whose
     * position nothing here chose, and a pixel that lands on the wrong thing is a picture
     * of the wrong menu.
     */
    private static void explorerMenus(Path folder) {
        try {
            new ProcessBuilder("explorer.exe", folder.toString()).start();
            java.awt.Robot robot = new java.awt.Robot();
            robot.setAutoDelay(60);
            pause(6_000);                       // the shell takes its time to paint

            // Nothing selected: the folder's own menu, which is where "JRock here!" is.
            robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
            pause(400);
            contextMenu(robot);
            screen(robot, "15-explorer-the-folder-menu");
            escape(robot);

            // The first item selected: the same menu for a file, which is where the flows
            // and the agents are, each on the file that was clicked.
            robot.keyPress(java.awt.event.KeyEvent.VK_HOME);
            robot.keyRelease(java.awt.event.KeyEvent.VK_HOME);
            pause(600);
            contextMenu(robot);
            screen(robot, "16-explorer-the-file-menu");
            escape(robot);
        } catch (IOException | java.awt.AWTException | RuntimeException ex) {
            System.out.println("no Explorer pictures here (" + ex + ")");
        }
    }

    private static void contextMenu(java.awt.Robot robot) {
        robot.keyPress(java.awt.event.KeyEvent.VK_SHIFT);
        robot.keyPress(java.awt.event.KeyEvent.VK_F10);
        robot.keyRelease(java.awt.event.KeyEvent.VK_F10);
        robot.keyRelease(java.awt.event.KeyEvent.VK_SHIFT);
        pause(1_500);
    }

    private static void escape(java.awt.Robot robot) {
        robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
        robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
        pause(400);
    }

    /** The whole screen, which on a build runner holds one window and nothing else. */
    private static void screen(java.awt.Robot robot, String name) {
        java.awt.Dimension size = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        write(robot.createScreenCapture(new java.awt.Rectangle(size)), name);
    }

    /** One window, read back off the screen: see captured() in Screenshots.java. */
    private static void shoot(Window window, String name) {
        try {
            javax.swing.JRootPane root = window instanceof javax.swing.RootPaneContainer
                    ? ((javax.swing.RootPaneContainer) window).getRootPane() : null;
            java.awt.Component of = root != null ? root : window;
            java.awt.Rectangle[] where = new java.awt.Rectangle[1];
            SwingUtilities.invokeAndWait(() -> {
                window.toFront();
                if (of.isShowing() && of.getWidth() > 0) {
                    where[0] = new java.awt.Rectangle(of.getLocationOnScreen(), of.getSize());
                }
            });
            if (where[0] == null) {
                System.out.println(name + ": the window is not on the screen");
                return;
            }
            settle();
            write(new java.awt.Robot().createScreenCapture(where[0]), name);
        } catch (Exception | Error ex) {
            System.out.println(name + ": not shot (" + ex + ")");
        }
    }

    private static void write(BufferedImage image, String name) {
        try {
            Path file = out.resolve(os + "-" + name + ".png");
            ImageIO.write(image, "png", file.toFile());
            taken++;
            System.out.println("shot " + file.getFileName() + "  "
                    + image.getWidth() + "x" + image.getHeight());
        } catch (IOException ex) {
            System.out.println(name + ": not written (" + ex + ")");
        }
    }

    /** The warning a page puts up when it will not let go, closed again. */
    private static boolean dismissWarning() {
        for (Window w : Window.getWindows()) {
            if (w instanceof javax.swing.JDialog && w.isShowing()) {
                javax.swing.JButton ok = button((Container) w, "OK");
                if (ok != null) onEdt(ok::doClick);
                else onEdt(w::dispose);
                settle();
                return true;
            }
        }
        return false;
    }

    /** Whatever the wizard is calling the page it is on, for telling pages apart. */
    private static String heading(Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel) {
                String text = ((javax.swing.JLabel) child).getText();
                if (text != null && !text.isEmpty() && child.getFont() != null
                        && child.getFont().getSize() > 14) {
                    return text;
                }
            }
            if (child instanceof Container) {
                String found = heading((Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static javax.swing.JButton button(Container root, String text) {
        List<java.awt.Component> all = new ArrayList<>();
        walk(root, all);
        for (java.awt.Component c : all) {
            if (c instanceof javax.swing.JButton
                    && text.equals(((javax.swing.JButton) c).getText())) {
                return (javax.swing.JButton) c;
            }
        }
        return null;
    }

    private static javax.swing.JCheckBox check(Container root, String label) {
        List<java.awt.Component> all = new ArrayList<>();
        walk(root, all);
        for (java.awt.Component c : all) {
            if (c instanceof javax.swing.JCheckBox
                    && label.equals(((javax.swing.JCheckBox) c).getText())) {
                return (javax.swing.JCheckBox) c;
            }
        }
        return null;
    }

    private static javax.swing.JRadioButton radio(Container root, String label) {
        List<java.awt.Component> all = new ArrayList<>();
        walk(root, all);
        for (java.awt.Component c : all) {
            if (c instanceof javax.swing.JRadioButton
                    && label.equals(((javax.swing.JRadioButton) c).getText())) {
                return (javax.swing.JRadioButton) c;
            }
        }
        return null;
    }

    /** The first text field whose value names the thing wanted - the install folder. */
    private static javax.swing.JTextField field(Container root, String contains) {
        List<java.awt.Component> all = new ArrayList<>();
        walk(root, all);
        for (java.awt.Component c : all) {
            if (c instanceof javax.swing.JTextField) {
                String text = ((javax.swing.JTextField) c).getText();
                if (text != null && text.contains(contains)) return (javax.swing.JTextField) c;
            }
        }
        return null;
    }

    private static void walk(Container root, List<java.awt.Component> into) {
        for (java.awt.Component child : root.getComponents()) {
            into.add(child);
            if (child instanceof Container) walk((Container) child, into);
        }
    }

    /**
     * The entries this installation wrote, removed again.
     *
     * <p>Its own and no others: every key the installer writes carries the installation
     * folder's name, so the ones to delete are the ones naming THIS folder. An
     * installation somebody actually uses is left exactly as it was.
     */
    private static void clean(Path home) {
        String mark = home.getFileName().toString();
        for (String root : new String[] {
                "HKCU\\Software\\Classes\\Directory\\Background\\shell",
                "HKCU\\Software\\Classes\\Directory\\shell",
                "HKCU\\Software\\Classes\\*\\shell" }) {
            for (String key : keys(root)) {
                if (!key.contains(mark)) continue;
                run("reg", "delete", root + "\\" + key, "/f");
            }
        }
        System.out.println("the entries naming " + mark + " are gone again; "
                + home + " is left where it is");
    }

    private static List<String> keys(String root) {
        List<String> found = new ArrayList<>();
        String listing = run("reg", "query", root);
        for (String line : listing.split("\\r?\\n")) {
            int at = line.lastIndexOf('\\');
            if (line.startsWith("HKEY") && at >= 0) found.add(line.substring(at + 1).trim());
        }
        return found;
    }

    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            return output;
        } catch (IOException | InterruptedException ex) {
            return "";
        }
    }

    private static void onEdt(Runnable task) {
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (Exception ex) {
            System.out.println("on the event thread: " + ex);
        }
    }

    /** Every pending event, and then a moment for whatever those events started. */
    private static void settle() {
        onEdt(() -> { });
        pause(400);
        onEdt(() -> { });
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private InstallerShots() {
    }
}
