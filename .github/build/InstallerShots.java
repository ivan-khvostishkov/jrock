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

    /** The whole tool's deadline. A hang here held a runner for sixteen minutes. */
    private static final long DEADLINE_MS = 5 * 60 * 1000L;

    private static Path out;
    private static String os;
    private static int taken;

    /** Where the wizard says it is installing, printed for the step that follows. */
    private static String installedInto;

    public static void main(String[] args) throws Exception {
        out = Paths.get(args.length > 0 ? args[0] : "screenshots").toAbsolutePath();
        os = args.length > 1 ? args[1] : System.getProperty("os.name")
                .toLowerCase().replaceAll("[^a-z0-9]+", "");
        // Only the folder to right-click in hangs off this now; the installation goes
        // wherever the wizard's own page says, which is what a person would get.
        Path home = args.length > 2 ? Paths.get(args[2]).toAbsolutePath()
                : Paths.get(System.getProperty("java.io.tmpdir")).resolve("JRockShots");
        boolean clean = java.util.Arrays.asList(args).contains("--clean");
        Files.createDirectories(out);

        // THE WATCHDOG, and the reason for it. Everything below drives somebody else's
        // window: a page that opens a dialog, a shell that will not come up, a Robot a
        // window manager will not let near the keyboard. None of that should cost more
        // than the pictures it was going to take, and a step that hangs costs a whole
        // build - so after five minutes this prints where it got to and leaves with
        // whatever it has.
        Thread watchdog = new Thread(() -> {
            pause(DEADLINE_MS);
            System.out.println("the five minutes are up: leaving with " + taken
                    + " picture(s). Where it got to is the last line above this one.");
            System.out.flush();
            Runtime.getRuntime().halt(0);
        }, "the watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        // The wizard is Swing and installs on any desktop, so it is driven on all of
        // them: what comes out is a real installation, which is what everything
        // photographed after this runs out of. Only Explorer is Windows'.
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");

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
        // 880x749 does not fit a runner's 1024x768 once the taskbar has taken its share,
        // and what is behind the taskbar is what a picture read off the screen shows (see
        // fitOnScreen in Screenshots.java). Pages four and nine both had their last rows
        // under it.
        fitOnScreen(wizard);

        drive(wizard, home);
        if (windows) explorerMenus(documents);
        else System.out.println("no Explorer here, so no menus of its to photograph");
        if (clean && windows) clean(home);

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

            // WHERE IT INSTALLS IS THE WIZARD'S BUSINESS, and this only reports it.
            // Setting that field looked like it worked - the log said "installing into
            // ..." - and the wizard installed into its own default anyway, so everything
            // afterwards looked in a folder that was never made: no installed jar, and no
            // agents to photograph. A tool that tells a wizard what to do and does not
            // check is a tool that lies in its log.
            //
            // So the path is READ, and printed in a form both bash and the JVM can use
            // (forward slashes, drive letter and all), for the step to pick up.
            javax.swing.JTextField folder = field(wizard, "JRockAssistant");
            if (folder != null && installedInto == null) {
                installedInto = folder.getText().trim().replace('\\', '/');
                System.out.println("INSTALLED=" + installedInto);
                settle();
            }

            // NOT THROUGH AN EDITOR. Page six offers to open the settings and the key
            // file for review, which a person wants and an unattended run does not: two
            // Notepads that nobody closes, on top of every picture taken after them.
            for (String opens : new String[] { "Open jrock-config.txt to review it",
                                               "Open bedrock-key.txt to paste the key into" }) {
                javax.swing.JCheckBox box = check(wizard, opens);
                if (box != null && box.isSelected()) {
                    SwingUtilities.invokeLater(box::doClick);
                    pause(300);
                    System.out.println("  page " + (page + 1) + ": unticked \"" + opens
                            + "\"");
                }
            }

            javax.swing.JRadioButton choose = radio(wizard,
                    "Choose which, and where each agent works");
            if (choose != null && !choose.isSelected()) {
                SwingUtilities.invokeLater(choose::doClick);
                pause(800);
                int off = 0;
                for (String unwanted : UNWANTED) {
                    javax.swing.JCheckBox box = check(wizard, unwanted);
                    if (box != null && box.isSelected()) {
                        SwingUtilities.invokeLater(box::doClick);
                        pause(200);
                        off++;
                    }
                }
                settle();
                System.out.println("  page " + (page + 1) + ": the catalogue, with "
                        + off + " translation flow(s) left out");
                shoot(wizard, "18-the-installer-agents-and-flows");
            }

            // A DIALOG FIRST, ALWAYS. Page six opens the settings and the key file in
            // whatever shows a .txt and then waits on a modal "paste your key, then press
            // OK" - and while that is up, Next is DISABLED. This used to look at Next
            // before looking for a dialog, read "disabled" as "there is no next page",
            // photograph page six under the name of the last page and stop: which is why
            // the wizard never reached the Explorer entries, and why the menu in those
            // pictures had no JRock in it.
            if (dismissDialog()) continue;

            javax.swing.JButton next = button(wizard, "Next >");
            if (next == null) {
                // No Next at all is the last page, which says what it did: every folder,
                // every entry, every file, named.
                shoot(wizard, "19-the-installer-finished");
                break;
            }
            if (!next.isEnabled()) {
                // Disabled is "not yet", so it is waited on - a page doing real work
                // (copying samples, writing entries) disables it while it runs.
                boolean enabled = false;
                for (int waited = 0; waited < PATIENCE_MS && !enabled; waited += 500) {
                    pause(500);
                    if (dismissDialog()) continue;
                    javax.swing.JButton again = button(wizard, "Next >");
                    enabled = again != null && again.isEnabled();
                }
                if (!enabled) {
                    System.out.println("  page " + (page + 1) + ": Next stayed disabled");
                    shoot(wizard, "19-the-installer-stopped-here");
                    break;
                }
                next = button(wizard, "Next >");
            }

            // PRESSED, NOT AWAITED. A page's own action can put a modal dialog up -
            // a warning it will not go past, a chooser - and a modal dialog opened
            // inside invokeAndWait never gives the call back: the dialog is waiting for
            // an answer and the thread that could answer it is waiting for the dialog.
            // That is a hang with no timeout in it, and it held a build runner for
            // sixteen minutes before this comment was written. So the click is posted,
            // and what happened is then watched for from here.
            String before = heading(wizard);
            System.out.println("  page " + (page + 1) + ": " + before);
            SwingUtilities.invokeLater(next::doClick);

            boolean moved = false;
            for (int waited = 0; waited < PATIENCE_MS; waited += 200) {
                pause(200);
                if (dismissDialog()) {        // a warning or a chooser, answered
                    moved = true;
                    break;
                }
                if (!wizard.isShowing()) {
                    moved = true;
                    break;
                }
                String now = heading(wizard);
                if (now != null && !now.equals(before)) {
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                // Named for what it is: a page that would not go on. Calling this "the
                // installer finished" put a picture of page two in the README's place for
                // the last page, which is a lie a file name should not be able to tell.
                System.out.println("  page " + (page + 1) + " did not move on; stopping "
                        + "here with what there is");
                shoot(wizard, "19-the-installer-stopped-here");
                break;
            }
        }
        if (wizard.isShowing()) {
            SwingUtilities.invokeLater(wizard::dispose);
            pause(600);
        }
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
            // THE WHOLE MENU, NOT WINDOWS 11'S SHORT ONE. Eleven shows seven items and
            // puts everything an application added behind "Show more options" - so the
            // first picture of this was a menu with no JRock in it at all. The classic
            // menu is one registry value away (an empty default under that CLSID), which
            // is the documented switch, and it is taken out again by --clean.
            run("reg", "add", LEGACY_MENU_KEY, "/f", "/ve");
            // And nothing else of this build's on the screen behind it: the installer's
            // own text editors, and whatever JRock the start script left up. This is a
            // runner, and these windows are ours.
            run("taskkill", "/f", "/im", "notepad.exe");
            run("taskkill", "/f", "/im", "javaw.exe");
            // Explorer reads the menu switch once, at start.
            run("taskkill", "/f", "/im", "explorer.exe");
            pause(2_000);
            new ProcessBuilder("explorer.exe", folder.toString()).start();
            java.awt.Robot robot = new java.awt.Robot();
            robot.setAutoDelay(60);
            pause(9_000);                       // the shell takes its time to paint

            // THE WINDOW, SIZED BY THE SHELL ITSELF. Win+Up went nowhere twice - the
            // keystroke needs the window focused and a runner's desktop does not hand
            // focus about reliably - and an Explorer window the size it feels like leaves
            // this build's own console windows showing on both sides of the picture.
            // Shell.Application lists Explorer's windows and each one has Left, Top,
            // Width and Height to be set, which is the shell doing it rather than a
            // keystroke asking it to.
            java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "(New-Object -ComObject Shell.Application).Windows() | ForEach-Object "
                    // A little wider and taller than the screen on purpose: sized to
                    // exactly the screen, Explorer left a ten-pixel strip of whatever was
                    // behind it showing down the right-hand edge, because what it sets is
                    // the window and what shows is the window's shadow and border.
                    + "{ $_.Left = 0; $_.Top = 0; $_.Width = " + (screen.width + 24)
                    + "; $_.Height = " + (screen.height + 24) + " }");
            pause(1_500);
            robot.mouseMove(screen.width / 2, screen.height / 2);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            pause(600);

            // MAXIMIZED, because the picture is the whole screen and a runner's desktop
            // has the rest of this build on it: consoles, a Notepad the installer opened,
            // JRock's own window. A maximized Explorer covers all of it, and the crop
            // below takes the taskbar off the bottom.
            robot.keyPress(java.awt.event.KeyEvent.VK_WINDOWS);
            robot.keyPress(java.awt.event.KeyEvent.VK_UP);
            robot.keyRelease(java.awt.event.KeyEvent.VK_UP);
            robot.keyRelease(java.awt.event.KeyEvent.VK_WINDOWS);
            pause(1_500);

            // Nothing selected: the folder's own menu, which is where "JRock here!" is.
            robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
            pause(400);
            contextMenu(robot);
            screen(robot, "20-explorer-the-folder-menu");
            escape(robot);

            // The first item selected: the same menu for a file, which is where the flows
            // and the agents are, each on the file that was clicked.
            robot.keyPress(java.awt.event.KeyEvent.VK_HOME);
            robot.keyRelease(java.awt.event.KeyEvent.VK_HOME);
            pause(600);
            contextMenu(robot);
            screen(robot, "21-explorer-the-file-menu");
            escape(robot);
        } catch (IOException | java.awt.AWTException | RuntimeException ex) {
            System.out.println("no Explorer pictures here (" + ex + ")");
        }
    }

    /** Windows 11's switch back to the classic context menu: an empty default value. */
    private static final String LEGACY_MENU_KEY =
            "HKCU\\Software\\Classes\\CLSID\\"
            + "{86ca1aa0-34aa-4e8b-a509-50c905bae2a2}\\InprocServer32";

    /** The window made to fit the screen it is on - see Screenshots.java for why. */
    private static void fitOnScreen(Window window) {
        onEdt(() -> {
            java.awt.GraphicsConfiguration where = window.getGraphicsConfiguration();
            if (where == null) return;
            java.awt.Rectangle screen = where.getBounds();
            java.awt.Insets taken = java.awt.Toolkit.getDefaultToolkit()
                    .getScreenInsets(where);
            int room = screen.width - taken.left - taken.right;
            int high = screen.height - taken.top - taken.bottom;
            int w = Math.min(window.getWidth(), room);
            int h = Math.min(window.getHeight(), high);
            if (w != window.getWidth() || h != window.getHeight()) {
                System.out.println("  the wizard's " + window.getWidth() + "x"
                        + window.getHeight() + " does not fit " + room + "x" + high
                        + ": resized to " + w + "x" + h);
            }
            window.setBounds(screen.x + taken.left, screen.y + taken.top, w, h);
        });
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

    /**
     * The whole screen, taskbar and all.
     *
     * <p>It used to crop the taskbar off, which cost the bottom of the MENU: these menus
     * are twenty items tall and the one thing that must be whole in the picture is the
     * list of entries. A taskbar in a picture of Explorer reads as Windows; a context
     * menu with its last five items sliced off reads as a mistake.
     */
    private static void screen(java.awt.Robot robot, String name) {
        java.awt.Dimension size = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        write(robot.createScreenCapture(new java.awt.Rectangle(size)), name);
    }

    /** One window, read back off the screen: see captured() in Screenshots.java. */
    private static void shoot(Window window, String name) {
        try {
            parkPointer();
            javax.swing.JRootPane root = window instanceof javax.swing.RootPaneContainer
                    ? ((javax.swing.RootPaneContainer) window).getRootPane() : null;
            java.awt.Component of = root != null ? root : window;
            java.awt.Rectangle[] where = new java.awt.Rectangle[1];
            boolean[] wasOnTop = new boolean[1];
            SwingUtilities.invokeAndWait(() -> {
                // Above anything the installer itself started (see captured() in
                // Screenshots.java for what this costs when it is left out).
                wasOnTop[0] = window.isAlwaysOnTop();
                if (window.isAlwaysOnTopSupported()) window.setAlwaysOnTop(true);
                window.toFront();
                window.requestFocus();
                if (of.isShowing() && of.getWidth() > 0) {
                    where[0] = new java.awt.Rectangle(of.getLocationOnScreen(), of.getSize());
                }
            });
            if (where[0] == null) {
                System.out.println(name + ": the window is not on the screen");
                return;
            }
            settle();
            BufferedImage shot = new java.awt.Robot().createScreenCapture(where[0]);
            SwingUtilities.invokeAndWait(() -> {
                if (window.isAlwaysOnTopSupported()) window.setAlwaysOnTop(wasOnTop[0]);
            });
            write(shot, name);
        } catch (Exception | Error ex) {
            System.out.println(name + ": not shot (" + ex + ")");
        }
    }

    /**
     * The mouse out of the way, into the corner it came from.
     *
     * <p>A pointer resting on a control is a control with a TOOLTIP a second later, and a
     * tooltip wider than the window it belongs to is drawn in a window of its own, half
     * off the edge of the picture. Nothing about a tooltip is worth photographing: what
     * it says is in the README already.
     */
    private static void parkPointer() {
        try {
            new java.awt.Robot().mouseMove(2, 2);
            pause(400);
        } catch (java.awt.AWTException | RuntimeException ex) {
            // No Robot, no pointer to park: the picture is whatever it is.
        }
    }

    /** How round the corners of a picture are, as in Screenshots.java. */
    private static final int CORNER = 16;

    private static void write(BufferedImage raw, String name) {
        try {
            // Corners off, transparent, so these sit beside the window pictures without
            // one of them looking like a crop of something bigger.
            BufferedImage image = new BufferedImage(raw.getWidth(), raw.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setClip(new java.awt.geom.RoundRectangle2D.Float(
                    0, 0, raw.getWidth(), raw.getHeight(), CORNER, CORNER));
            g.drawImage(raw, 0, 0, null);
            g.dispose();

            Path file = out.resolve(os + "-" + name + ".png");
            ImageIO.write(image, "png", file.toFile());
            taken++;
            System.out.println("shot " + file.getFileName() + "  "
                    + image.getWidth() + "x" + image.getHeight());
        } catch (IOException ex) {
            System.out.println(name + ": not written (" + ex + ")");
        }
    }

    /**
     * Whatever dialog a page has put up, answered - and answered the way that gets the
     * wizard moving again: OK or Yes for something it is telling us, Cancel or Close for
     * something it is asking us to pick, and dispose for anything with no button this
     * tool knows. Posted rather than awaited, for the reason the Next button is.
     */
    private static boolean dismissDialog() {
        for (Window w : Window.getWindows()) {
            if (!(w instanceof javax.swing.JDialog) || !w.isShowing()) continue;
            javax.swing.JButton answer = null;
            for (String label : new String[] { "OK", "Yes", "Cancel", "Close" }) {
                answer = button((Container) w, label);
                if (answer != null) break;
            }
            final javax.swing.JButton press = answer;
            System.out.println("    a dialog: \"" + title(w) + "\", answered with "
                    + (press == null ? "nothing (disposed)" : press.getText()));
            SwingUtilities.invokeLater(press == null ? w::dispose : press::doClick);
            pause(600);
            return true;
        }
        return false;
    }

    private static String title(Window w) {
        return w instanceof javax.swing.JDialog ? ((javax.swing.JDialog) w).getTitle() : "";
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
        run("reg", "delete", LEGACY_MENU_KEY, "/f");
        System.out.println("the entries naming " + mark + " are gone again, and so is the "
                + "classic-menu switch; " + home + " is left where it is");
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
