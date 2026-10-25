import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.imageio.ImageIO;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The README's screenshots, made by the program they are of.
 *
 * <p>Run after the JAR, with it on the class path:
 *
 * <pre>
 *     java -cp jrock.jar:out-automation .github/build/Screenshots.java screenshots linux
 * </pre>
 *
 * <p>WHY A BUILD STEP. A screenshot pasted into a README is a claim about what the program
 * looks like, and it stops being true the moment anything moves - a renamed button, a
 * reworded line, a window that grew a row. These are taken by DRIVING the real program
 * through the real automation API and then painting its window, so a picture that is wrong
 * means the build is wrong. Nothing is posed: every scene below is the program in a state
 * it reaches by being asked.
 *
 * <p>WHY PAINTING AND NOT A SCREEN CAPTURE. java.awt.Robot photographs the screen, which
 * needs a screen - and on a macOS runner it needs the screen-recording permission nobody
 * can grant a CI job. printAll() asks the component to draw itself into an image, which
 * works under xvfb and on every runner, and draws exactly what the window would show.
 *
 * <p>WHY ON THREE OPERATING SYSTEMS. Swing takes its look from the platform, so the same
 * window is not the same picture on Windows, on macOS and on GNOME - and the README can
 * then show one program rather than one platform, with the file names saying which is
 * which.
 *
 * <p>WHERE THE REPLIES COME FROM. Two scenes need an answer in the log, and an answer needs
 * a model to have said something. So the answers below are RECORDED: each was asked of the
 * real endpoint once, from a folder with a real key, with the same question and the same
 * page attached - and the reply text and its usage block were copied here verbatim (see
 * RECORDED). This runs a Chat Completions endpoint of its own on the loopback address that
 * replays them, and points the throwaway folder's {@code __JRock/jrock-config.txt} at it.
 *
 * <p>So the pictures show what the model really answered, while CI needs no credential,
 * reaches no network and cannot cost anything - and what is exercised is JRock's own send,
 * logging, message files and stats, which is all the pictures are of. Re-record with
 * {@code --include} and {@code --cli} in a folder that has a key, and paste the reply and
 * the usage numbers back in.
 */
public final class Screenshots {

    /** How long a scene waits for the stub to answer. Local, but a cold runner is slow. */
    private static final long REPLY_TIMEOUT_MILLIS = 60_000;

    /**
     * The model that gave the recorded answers, which is the one the stub serves and the
     * one the window therefore names: the picture says who said it, truthfully.
     */
    private static final String MODEL = "openai.gpt-5.4";

    private static Path out;
    private static String os;
    private static int taken;

    /**
     * THE AGENTS THAT CANNOT SHARE A PROCESS, one scene each.
     *
     * <p>Five of the seven start a JRock of their own - {@code JRock.main(args)} - and then
     * take it as an automation, and a process has one JRock and one automation. This tool
     * is already holding both by the time it has a conversation to photograph, so an agent
     * started beside it would be told the automation is taken, and two agents started
     * beside each other would tell each other the same. The timer and the paper docs agent
     * are the two that drive nothing: they are in the run above, where they cost nothing.
     *
     * <p>So these are asked for one at a time, with {@code --agent <key>}, and each gets a
     * process, a JRock and a demo folder of its own. Which is also truer to what they are:
     * an agent is a program you start beside JRock, not a panel inside it.
     *
     * <p>Key, class, and the name of the picture.
     */
    private static final String[][] AGENTS = {
        { "push-to-talk", "JRockPushToTalk",  "13-the-push-to-talk-agent" },
        { "geo",          "JRockGeoLocation", "14-the-geo-location-agent" },
        { "weather",      "JRockWeather",     "15-the-weather-agent" },
        { "thunderbird",  "JRockThunderbird", "16-the-thunderbird-agent" },
        { "browser",      "JRockBrowser",     "17-the-browser-agent" },
    };

    /** How long an agent gets to start its JRock and put its own window up. */
    private static final int AGENT_TIMEOUT_MS = 120_000;

    /** Answers served so far, which is how the second turn differs from the first. */
    private static int answered;

    public static void main(String[] args) throws Exception {
        out = Paths.get(args.length > 0 ? args[0] : "screenshots").toAbsolutePath();
        os = args.length > 1 ? args[1] : System.getProperty("os.name")
                .toLowerCase().replaceAll("[^a-z0-9]+", "");
        Files.createDirectories(out);

        // A presentable path, because the window puts the folder in its title bar and the
        // session report PRINTS THE WHOLE THING - which makes the path part of the picture.
        // So: a fixed name rather than createTempDirectory's random digits, emptied first
        // so a second run starts from nothing, and the parent is an argument so a runner
        // can ask for a clean one. /tmp/jrock-demo/Demo reads like a demo; macOS's own
        // temp folder (/var/folders/zz/.../T) reads like a crash dump, and Windows puts
        // the account name in its own, which is nobody's business in a README.
        Path under = args.length > 2 ? Paths.get(args[2])
                : Paths.get(System.getProperty("java.io.tmpdir"));
        Path work = under.resolve("jrock-demo").resolve("Demo");
        deleteTree(work.getParent());
        Path jrockDir = work.resolve("__JRock");
        Files.createDirectories(jrockDir);

        HttpServer stub = startStub();
        String endpoint = "http://" + stub.getAddress().getHostString() + ":"
                + stub.getAddress().getPort() + "/v1/chat/completions";
        // The settings file this folder is read from: the format is a name ending in $ on
        // one line and its value on the next.
        Files.write(jrockDir.resolve("jrock-config.txt"),
                ("' Written by .github/build/Screenshots.java - a throwaway demo folder.\n\n"
                 + "endpoint$\n" + endpoint + "\n\n"
                 + "model$\n" + MODEL + "\n\n"
                 // The include scene is about the Markdown reference above a picture, so
                 // this folder is set to write them - the setting a person would tick in
                 // Configure. No automation can force one include to be read differently
                 // from the rest any more, which is the point: the picture in the README
                 // is of JRock as this folder is configured.
                 + "include-markdown-references$\ntrue\n\n").getBytes(StandardCharsets.UTF_8));
        // A key has to be there for a send to be attempted at all. This one is a word:
        // the endpoint above takes whatever arrives without looking at it.
        Files.write(jrockDir.resolve("bedrock-key.txt"),
                "not-a-credential-the-stub-endpoint-ignores-it\n".getBytes(StandardCharsets.UTF_8));

        // ONE AGENT, IN A PROCESS OF ITS OWN (see AGENTS), and then nothing else: the
        // agent starts the JRock here, so this tool must not.
        String asked = argument(args, "--agent");
        if (asked != null) {
            oneAgent(asked, work);
            System.out.println("wrote " + taken + " screenshot(s) to " + out);
            System.exit(0);
        }

        JRock.main(new String[] { "--working-dir", work.toString() });
        String notReady = JRock.automationAwaitReady(120_000);
        if (JRock.automationWindow() == null) {
            System.out.println("no window: " + notReady);
            System.exit(1);
        }
        String problem = JRock.automationBegin("screenshots");
        if (problem != null) {
            System.out.println("could not drive JRock: " + problem);
            System.exit(1);
        }

        JFrame window = JRock.automationWindow();
        settle();
        shoot(window, "01-window-at-startup");

        // A QUESTION AND THE PAGE IT IS ABOUT, in one picture rather than two. A prompt
        // with a question in it and the same prompt with an include token added differ by
        // one line, which is not worth a second picture of the same window; what is worth
        // showing is the token, the Markdown reference written above it, and the log lines
        // saying what was attached and what it was downscaled to. The page is made here
        // rather than shipped, so the picture is of this build's own behaviour.
        JRock.automationSetPrompt("Read this invoice and tell me what is due, and when.\n");
        Path png = work.resolve("a-page.png");
        ImageIO.write(invoicePage(), "png", png.toFile());
        String included = JRock.automationInclude(png);
        if (included != null) System.out.println("include: " + included);
        settle();
        shoot(window, "02-a-question-and-a-page-attached");

        // The turn itself: the question and the page sent, and the answer in the log
        // under its own teal header with the time on it, the words exactly as they
        // arrived - the log colours the roles and renders nothing, which is why a model's
        // own ** shows in it - and the usage and cost of the send on the line below.
        send("03-the-answer-in-the-log", window);

        // A second turn with History on, so the log shows a conversation rather than one
        // exchange, and Dialog only on, which hides the gray machinery around it.
        tick(window, "History");
        JRock.automationSetPrompt("Who do I pay, and how? Answer from the page only.\n");
        send(null, window);
        tick(window, "Dialog only");
        settle();
        shoot(window, "04-two-turns-history-and-dialog-only");

        // And off again. A tick is part of the window's state, so leaving it on would
        // put every later scene behind a log with its machinery hidden - which is one
        // scene's subject, not a setting for the rest of them.
        untick(window, "Dialog only");

        // THE SETTINGS, one picture per tab. The ticks, the two include modes, the DPI,
        // the speaker and the microphone live in this dialog and nowhere else, so a
        // reader who has seen only the window has seen none of them. Opened by pressing
        // the real button, which is what makes these pictures of the dialog rather than
        // of a panel built for the occasion.
        shootTab(window, 0, "05-configure-general");
        shootTab(window, 1, "06-configure-files");
        shootTab(window, 2, "07-configure-audio");

        // THE FOLDER'S OWN MENU, standing open: what is in the folder, both merges,
        // backup and restore, the cleanup. The label it hangs from names the folder, and
        // every item in it acts on that folder.
        shootPopup(window, folderLabel(window), "08-the-jrock-folder-menu");

        // And its first item, which lists every file and subfolder that folder can hold
        // with what each one is for - the whole of what JRock keeps, in one dialog.
        shootFolderDialog(window, "09-what-is-in-the-jrock-folder");

        // THE LOG'S MENU, over the answer it acts on: select the last answer, save or
        // print the selection, the two Markdown exports, narrate it, open an address or a
        // path out of it.
        // The last answer selected first - by the call Ctrl+Shift+A makes - because
        // half of this menu acts on a selection, and a menu photographed without one is
        // a picture of five greyed-out items.
        String noAnswer = JRock.automationSelectLastAnswer();
        if (noAnswer != null) System.out.println("select: " + noAnswer);
        shootPopup(window, logPane(window), "10-the-log-menu");
        JRock.automationClearLogSelection();

        // The agents, each in its own window. The timer needs no JRock and no key at all;
        // the paper docs agent shows the two folders it reads.
        shootAgent("JRockTimer", new String[0], "11-the-timer-agent");
        shootAgent("JRockPaperDocs", new String[] { "--folder", work.toString(),
                "--incoming", work.toString() }, "12-the-papers-agent");

        System.out.println("wrote " + taken + " screenshot(s) to " + out);
        System.exit(0);
    }

    /** Presses Send, waits for the answer, and paints the window when asked to. */
    private static void send(String name, JFrame window) {
        JRock.Sent sent = JRock.automationSend(REPLY_TIMEOUT_MILLIS);
        if (!sent.ok()) {
            System.out.println("no reply: " + sent.failure());
            System.exit(1);
        }
        settle();
        if (name != null) shoot(window, name);
    }

    /**
     * Ticks one of the window's checkboxes, by its label, as a click would.
     *
     * <p>Not through the automation API, which has no call for these: the ticks are a
     * view of the log rather than something a step does to a conversation. A missing one
     * is reported and skipped - a picture that cannot be taken should not fail a build
     * whose other pictures are fine.
     */
    private static void tick(Container root, String label) {
        JCheckBox box = checkBox(root, label);
        if (box == null) {
            System.out.println("no '" + label + "' tick in the window");
            return;
        }
        onEdt(() -> {
            if (!box.isSelected()) box.doClick();
        });
    }

    /** The same, the other way: a tick left on belongs to one scene, not to the rest. */
    private static void untick(Container root, String label) {
        JCheckBox box = checkBox(root, label);
        if (box == null) {
            System.out.println("no '" + label + "' tick in the window");
            return;
        }
        onEdt(() -> {
            if (box.isSelected()) box.doClick();
        });
    }

    private static JCheckBox checkBox(Container root, String label) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JCheckBox && label.equals(((JCheckBox) child).getText())) {
                return (JCheckBox) child;
            }
            if (child instanceof Container) {
                JCheckBox found = checkBox((Container) child, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The Configure dialog on one of its tabs, opened by pressing the real button.
     *
     * <p>The press is POSTED rather than waited for: the dialog is modal, so the click
     * does not return until it is closed again, and a tool that waited for it would be
     * waiting for itself. Everything after the press therefore polls - for the dialog to
     * be showing, and then for it to be gone - and all of it runs inside the modal loop's
     * own event pumping, which answers invokeAndWait exactly as the outer loop does.
     */
    private static void shootTab(JFrame window, int tab, String name) {
        javax.swing.JButton configure = button(window, "Configure");
        if (configure == null) {
            System.out.println(name + ": no Configure button in the window");
            return;
        }
        SwingUtilities.invokeLater(configure::doClick);
        javax.swing.JDialog dialog = awaitDialog(20_000);
        if (dialog == null) {
            System.out.println(name + ": the Configure dialog did not open");
            return;
        }
        javax.swing.JTabbedPane tabs = tabbedPane(dialog);
        if (tabs != null && tab < tabs.getTabCount()) {
            final int index = tab;
            onEdt(() -> tabs.setSelectedIndex(index));
        } else if (tab > 0) {
            // A tab this build has not got is reported, not photographed again: the
            // browser leaves out Audio, having neither a speaker nor a microphone to
            // choose, and three pictures of one tab would be three copies of it.
            System.out.println(name + ": no tab " + tab + " in this build's dialog");
            close(dialog);
            return;
        }
        settle();
        shoot(dialog, name);
        close(dialog);
    }

    /** Cancel, and then waits for the dialog to be gone before the next scene. */
    private static void close(javax.swing.JDialog dialog) {
        javax.swing.JButton cancel = button(dialog, "Cancel");
        if (cancel != null) SwingUtilities.invokeLater(cancel::doClick);
        else SwingUtilities.invokeLater(dialog::dispose);
        for (int waited = 0; waited < 10_000 && dialog.isShowing(); waited += 100) {
            settle();
        }
    }

    /**
     * One of the window's popup menus, open, over the window it belongs to.
     *
     * <p>These are not setComponentPopupMenu menus - JRock attaches them with a listener
     * of its own, because a touch screen under CheerpJ never delivers a Swing popup
     * trigger (see attachPopup in JRock.java). So this dispatches a real popup trigger,
     * pressed and released on the component, and what opens is what a right-click opens.
     */
    private static void shootPopup(JFrame window, java.awt.Component on, String name) {
        if (on == null) {
            System.out.println(name + ": nothing of that kind to right-click");
            return;
        }
        // SETTLED BEFORE THE CLICK, and CLICKED WHERE THE COMPONENT CAN BE SEEN. The
        // log pane is as tall as the whole transcript and sits inside a viewport, so
        // after the log has scrolled, the pane's own (14, 14) is somewhere above the top
        // of the window - and a menu asked for there opens off the window, in a
        // heavyweight popup of its own, which is not in a picture of the window. It cost
        // two runs of this tool and a picture with a selection and no menu in it. The
        // viewport's view rectangle is where the component actually is on screen.
        settle();
        java.awt.Point at = new java.awt.Point(14, 14);
        if (on.getParent() instanceof javax.swing.JViewport) {
            java.awt.Rectangle seen = ((javax.swing.JViewport) on.getParent()).getViewRect();
            at = new java.awt.Point(seen.x + 14, seen.y + 14);
        }
        rightClick(on, at.x, at.y);
        settle();
        shoot(window, name);
        dismissMenu();
    }

    /** The folder menu's first item, pressed, and the dialog it opens. */
    private static void shootFolderDialog(JFrame window, String name) {
        java.awt.Component label = folderLabel(window);
        if (label == null) {
            System.out.println(name + ": no folder label in the window");
            return;
        }
        rightClick(label, 14, 14);
        settle();
        javax.swing.JMenuItem item = menuItem("What is in the JRock folder...");
        if (item == null) {
            System.out.println(name + ": the folder menu has no contents item");
            dismissMenu();
            return;
        }
        SwingUtilities.invokeLater(item::doClick);
        javax.swing.JDialog dialog = awaitDialog(20_000);
        if (dialog == null) {
            System.out.println(name + ": the folder dialog did not open");
            dismissMenu();
            return;
        }
        settle();
        shoot(dialog, name);
        close(dialog);
        dismissMenu();
    }

    /** Whatever menu is open, closed again, so it is not in the next scene. */
    private static void dismissMenu() {
        onEdt(() -> javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath());
        settle();
    }

    /** A popup trigger on a component, pressed and released, as a right-click is. */
    private static void rightClick(java.awt.Component on, int x, int y) {
        onEdt(() -> {
            long when = System.currentTimeMillis();
            int[] ids = { java.awt.event.MouseEvent.MOUSE_PRESSED,
                          java.awt.event.MouseEvent.MOUSE_RELEASED };
            for (int id : ids) {
                on.dispatchEvent(new java.awt.event.MouseEvent(on, id, when++,
                        java.awt.event.InputEvent.BUTTON3_DOWN_MASK, x, y, 1, true,
                        java.awt.event.MouseEvent.BUTTON3));
            }
        });
    }

    /** The first dialog of this application's own that is showing, or null. */
    private static javax.swing.JDialog awaitDialog(int millis) {
        for (int waited = 0; waited < millis; waited += 100) {
            for (Window w : Window.getWindows()) {
                if (w instanceof javax.swing.JDialog && w.isShowing()) {
                    return (javax.swing.JDialog) w;
                }
            }
            settle();
        }
        return null;
    }

    /** The menu item with this label in whichever popup is open, or null. */
    private static javax.swing.JMenuItem menuItem(String label) {
        for (Window w : Window.getWindows()) {
            javax.swing.JMenuItem found = menuItemIn(w, label);
            if (found != null) return found;
        }
        return null;
    }

    private static javax.swing.JMenuItem menuItemIn(Container root, String label) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JMenuItem
                    && label.equals(((javax.swing.JMenuItem) child).getText())) {
                return (javax.swing.JMenuItem) child;
            }
            if (child instanceof Container) {
                javax.swing.JMenuItem found = menuItemIn((Container) child, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The label beside Configure that names the folder, and holds that folder's menu. */
    private static java.awt.Component folderLabel(Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel) {
                String text = ((javax.swing.JLabel) child).getText();
                if (text != null && text.endsWith("JRock/")) return child;
            }
            if (child instanceof Container) {
                java.awt.Component found = folderLabel((Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The log pane: the largest styled text area in the window, which is the transcript. */
    private static java.awt.Component logPane(Container root) {
        java.awt.Component[] best = new java.awt.Component[1];
        logPane(root, best);
        return best[0];
    }

    private static void logPane(Container root, java.awt.Component[] best) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JTextPane
                    && (best[0] == null || area(child) > area(best[0]))) {
                best[0] = child;
            }
            if (child instanceof Container) logPane((Container) child, best);
        }
    }

    private static long area(java.awt.Component c) {
        return (long) c.getWidth() * c.getHeight();
    }

    private static javax.swing.JButton button(Container root, String text) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JButton
                    && text.equals(((javax.swing.JButton) child).getText())) {
                return (javax.swing.JButton) child;
            }
            if (child instanceof Container) {
                javax.swing.JButton found = button((Container) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static javax.swing.JTabbedPane tabbedPane(Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JTabbedPane) {
                return (javax.swing.JTabbedPane) child;
            }
            if (child instanceof Container) {
                javax.swing.JTabbedPane found = tabbedPane((Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The value after a flag, or null: "--agent geo" gives "geo". */
    private static String argument(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return null;
    }

    /**
     * One agent, started on a thread of its own because an agent's main does not return -
     * it is a loop - and photographed once its window is up beside the JRock it started.
     */
    private static void oneAgent(String key, Path work) {
        String[] found = null;
        for (String[] candidate : AGENTS) {
            if (candidate[0].equals(key)) found = candidate;
        }
        final String[] row = found;
        if (row == null) {
            System.out.println("no agent called \"" + key + "\": the keys are "
                    + java.util.Arrays.stream(AGENTS).map(a -> a[0])
                            .collect(java.util.stream.Collectors.joining(", ")));
            return;
        }
        String[] agentArgs = { "--working-dir", work.toString() };
        try {
            Class<?> type = Class.forName(row[1]);
            java.lang.reflect.Method main = type.getMethod("main", String[].class);
            Thread running = new Thread(() -> {
                try {
                    main.invoke(null, (Object) agentArgs);
                } catch (ReflectiveOperationException ex) {
                    System.out.println(row[1] + " stopped: " + ex.getCause());
                }
            }, row[1]);
            running.setDaemon(true);
            running.start();
        } catch (ReflectiveOperationException ex) {
            System.out.println(row[1] + ": not on the class path (" + ex + ")");
            return;
        }

        // Its JRock first - the agent starts one and the window of its own usually waits
        // for it - and then whichever window is not that one.
        String notReady = JRock.automationAwaitReady(AGENT_TIMEOUT_MS);
        if (notReady != null) System.out.println(row[1] + ": " + notReady);
        Window own = null;
        for (int waited = 0; waited < AGENT_TIMEOUT_MS && own == null; waited += 200) {
            settle();
            for (Window w : Window.getWindows()) {
                if (!(w instanceof JFrame) || !w.isShowing()) continue;
                if (w.equals(JRock.automationWindow())) continue;
                String title = ((JFrame) w).getTitle();
                if (title != null && !title.isEmpty()) own = w;
            }
        }
        if (own == null) {
            System.out.println(row[1] + ": no window of its own appeared");
            return;
        }
        settle();
        shoot(own, row[2]);
    }

    /** One agent, started in this JVM, with its window painted and left open. */
    private static void shootAgent(String agent, String[] args, String name) {
        try {
            Class<?> type = Class.forName(agent);
            type.getMethod("main", String[].class).invoke(null, (Object) args);
            settle();
            Window found = null;
            for (Window w : Window.getWindows()) {
                if (w instanceof JFrame && w.isShowing()
                        && !w.equals(JRock.automationWindow())
                        && ((JFrame) w).getTitle() != null) {
                    found = w;
                }
            }
            if (found == null) {
                System.out.println(agent + ": no window of its own appeared");
                return;
            }
            shoot(found, name);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // An agent that cannot start on this runner is a missing picture, not a
            // failed build: the point of the step is the pictures it CAN take.
            System.out.println(agent + ": not shot (" + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage() + ")");
        }
    }

    /** The height of the title bar this file draws itself. See paint. */
    private static final int TITLE_HEIGHT = 30;

    /** Paints one window into screenshots/&lt;os&gt;-&lt;name&gt;.png. */
    private static void shoot(Window window, String name) {
        try {
            BufferedImage contents = captured(window);
            if (contents == null) {
                BufferedImage[] painted = new BufferedImage[1];
                SwingUtilities.invokeAndWait(() -> painted[0] = paint(window));
                contents = painted[0];
            }
            BufferedImage[] shot = new BufferedImage[1];
            final BufferedImage body = contents;
            SwingUtilities.invokeAndWait(() -> shot[0] = withCaption(window, body));
            Path file = out.resolve(os + "-" + name + ".png");
            ImageIO.write(shot[0], "png", file.toFile());
            taken++;
            System.out.println("shot " + file.getFileName() + "  "
                    + shot[0].getWidth() + "x" + shot[0].getHeight());
        } catch (Exception ex) {
            System.out.println(name + ": not shot (" + ex + ")");
        }
    }

    /**
     * The window's own pixels, read back off the screen.
     *
     * <p>WHY NOT JUST PAINT IT, which is what this did for three versions and what every
     * example of screenshotting Swing does. Because a Swing text area breaks its lines
     * using the font metrics of the SCREEN and then draws them with the font metrics of
     * whatever Graphics it is given, and an offscreen image's are not the screen's: no
     * LCD subpixel antialiasing, slightly wider advances, and a line that fitted when it
     * was measured no longer fits when it is drawn. The last word or two of every long
     * line in the Help window and the folder dialog was cut off at the component's edge
     * - in the picture only. On screen those dialogs are perfect, which a Robot capture
     * of one beside a painted copy of it is what proved; printAll, paint, double
     * buffering off, the desktop's own rendering hints, none of it made any difference,
     * because none of them changes which metrics the line breaks were computed with.
     *
     * <p>So the screen is asked instead, for exactly the rectangle the ROOT PANE occupies
     * on it - which keeps the one property that made painting attractive in the first
     * place: the operating system's title bar, borders and shadows are not in the
     * picture, because they are not inside that rectangle. What comes back is what a
     * person sitting in front of the machine sees, to the pixel.
     *
     * <p>Null when there is no screen to read (a truly headless JVM) or the window is not
     * on it, and then the caller paints as before - a picture with a clipped line in it
     * beats no picture at all. CI runs this under xvfb, which has a screen and a window
     * manager of its own, so there it reads back the same as a desktop does.
     */
    private static BufferedImage captured(Window window) {
        try {
            javax.swing.JRootPane root = window instanceof javax.swing.RootPaneContainer
                    ? ((javax.swing.RootPaneContainer) window).getRootPane() : null;
            java.awt.Component of = root != null ? root : window;
            java.awt.Rectangle[] where = new java.awt.Rectangle[1];
            SwingUtilities.invokeAndWait(() -> {
                window.toFront();
                if (of.isShowing() && of.getWidth() > 0 && of.getHeight() > 0) {
                    where[0] = new java.awt.Rectangle(of.getLocationOnScreen(),
                            of.getSize());
                }
            });
            if (where[0] == null) return null;
            settle();
            BufferedImage shot = new java.awt.Robot().createScreenCapture(where[0]);
            // A scaled display gives back more pixels than the rectangle asked for, which
            // is a sharper picture of the same thing and fine; what is not fine is a
            // caption drawn to a different width, so withCaption measures the image.
            return shot;
        } catch (Exception | Error ex) {
            System.out.println("  (read back from the screen: " + ex + ")");
            return null;
        }
    }

    /**
     * One window as a picture: its contents painted by Swing, under a title bar drawn
     * here.
     *
     * <p>THE CONTENTS ARE THE ROOT PANE, not the window - and that one word is the whole
     * of a bug that made every runner's pictures wrong in a different way. An image the
     * size of the WINDOW includes the system's title bar and borders, which Swing does not
     * paint, and each platform's peer then filled that band with something else: macOS
     * left it unpainted, so every picture had a black strip across the top; the X11 peer
     * drew the window manager's own decoration into it, which landed on top of the first
     * row of buttons and sliced them in half; and the Windows peer drew the real
     * non-client area, which on a Windows Server runner is the themeless grey of a machine
     * with no desktop - nothing like the Windows anybody reading the README is using.
     *
     * <p>The root pane is exactly the part of a window Swing owns and paints, so painting
     * that is the whole of the application and none of the operating system - and the
     * three runners now differ only in what Swing draws differently, which is the one
     * thing taking three pictures is for.
     *
     * <p>WHY A TITLE BAR IS DRAWN BACK ON. The title carries real behaviour worth showing
     * - the working folder's name, the per-folder icon generated from it (see the README's
     * "Per-folder window title &amp; icon"), and which window an agent's is - and the
     * pictures of the agents are of windows with nothing else to identify them. It is
     * drawn plainly and identically everywhere, to look like what it is: a caption this
     * file adds, rather than an imitation of somebody's desktop.
     */
    private static BufferedImage paint(Window window) {
        javax.swing.JRootPane root = window instanceof javax.swing.RootPaneContainer
                ? ((javax.swing.RootPaneContainer) window).getRootPane() : null;
        int w = Math.max(1, root != null ? root.getWidth() : window.getWidth());
        int h = Math.max(1, root != null ? root.getHeight() : window.getHeight());

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // PAINTED AS THE SCREEN PAINTS IT, which printAll does not. printAll puts the
        // hierarchy into printing mode, and Swing's text views lay themselves out again
        // for a printer's font metrics when they are in it - so a wrapped text area broke
        // its lines at one width and had them drawn at another, and every long line in the
        // Help window and the folder dialog lost its last word under the scrollbar. It
        // looked exactly like a bug in those dialogs, and cost an afternoon and a revert
        // to find: a Robot capture of the same dialog showed the text whole, which is what
        // proved the picture wrong rather than the program.
        //
        // paint() instead, with double buffering turned off for the duration - which is
        // the reason printAll was here in the first place, since paint() on a buffered
        // component can otherwise be answered from a back buffer that has nothing in it.
        javax.swing.RepaintManager repaints = javax.swing.RepaintManager.currentManager(
                root != null ? root : window);
        boolean buffered = repaints.isDoubleBufferingEnabled();
        repaints.setDoubleBufferingEnabled(false);
        try {
            if (root != null) root.paint(g); else window.paint(g);
        } finally {
            repaints.setDoubleBufferingEnabled(buffered);
        }
        g.dispose();
        return image;
    }

    /** The caption this file draws, above the window's own pixels. */
    private static BufferedImage withCaption(Window window, BufferedImage contents) {
        int w = contents.getWidth();
        BufferedImage image = new BufferedImage(w, contents.getHeight() + TITLE_HEIGHT,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        titleBar(g, window, w);
        g.drawImage(contents, 0, TITLE_HEIGHT, null);
        g.dispose();
        return image;
    }

    /** The caption above the window: its own icon, and its own title. */
    private static void titleBar(Graphics2D g, Window window, int w) {
        g.setColor(new Color(0xEC, 0xEF, 0xF1));
        g.fillRect(0, 0, w, TITLE_HEIGHT);
        g.setColor(new Color(0xCF, 0xD8, 0xDC));
        g.drawLine(0, TITLE_HEIGHT - 1, w, TITLE_HEIGHT - 1);

        int textAt = 10;
        // The window's own icon, which JRock makes from the name of the folder it works
        // in - the smallest of the sizes it set, scaled to the bar.
        java.util.List<java.awt.Image> icons = window.getIconImages();
        if (icons != null && !icons.isEmpty()) {
            java.awt.Image icon = icons.get(0);
            for (java.awt.Image candidate : icons) {
                if (candidate.getWidth(null) > 0
                        && candidate.getWidth(null) < icon.getWidth(null)) {
                    icon = candidate;
                }
            }
            int side = TITLE_HEIGHT - 12;
            g.drawImage(icon, 8, 6, side, side, null);
            textAt = 8 + side + 8;
        }

        String title = window instanceof JFrame ? ((JFrame) window).getTitle() : null;
        if (title != null && !title.isEmpty()) {
            g.setColor(new Color(0x37, 0x47, 0x4F));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            int baseline = (TITLE_HEIGHT + g.getFontMetrics().getAscent()) / 2 - 1;
            g.drawString(title, textAt, baseline);
        }
    }

    /** Lets the window finish what it was asked to do, then lets it paint. */
    private static void settle() {
        try {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Empties the demo folder of a previous run.
     *
     * <p>Guarded on the name: this deletes a tree, and the only tree it may delete is the
     * one this file makes. Anything else is left alone and said out loud rather than
     * removed, because a build step that can be talked into deleting a directory is worse
     * than a build step that misses a picture.
     */
    private static void deleteTree(Path folder) throws IOException {
        if (!folder.getFileName().toString().equals("jrock-demo")) {
            System.out.println("not deleting " + folder + ": not the demo folder");
            return;
        }
        if (!Files.exists(folder)) return;
        try (java.util.stream.Stream<Path> tree = Files.walk(folder)) {
            for (Path each : tree.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList())) {
                Files.deleteIfExists(each);
            }
        }
    }

    private static void onEdt(Runnable task) {
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (Exception interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- the stand-in endpoint ---------------------------------------------

    /**
     * A Chat Completions endpoint on the loopback address: the model list and one answer
     * per send.
     *
     * <p>Port 0, so the OS hands out a free one and two runs at once cannot collide.
     */
    private static HttpServer startStub() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/models", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "{\"object\":\"list\",\"data\":[{\"id\":\"" + MODEL + "\"}]}");
        });
        server.createContext("/v1/chat/completions", exchange -> {
            // The request is read and dropped: these pictures are of what comes back. The
            // answers are served in the order they were recorded in, one per send.
            exchange.getRequestBody().readAllBytes();
            respond(exchange, completion(
                    RECORDED[Math.min(answered++, RECORDED.length - 1)]));
        });
        server.start();
        return server;
    }

    /**
     * What {@code openai.gpt-5.4} answered, on 12 October 2026, asked these two questions
     * with this page attached through {@code bedrock-mantle.us-east-1}.
     *
     * <p>Copied from {@code __JRock/messages/*-assistant.txt}, which holds a reply verbatim,
     * with the token counts from the usage block of the same response - so the answer in
     * the picture and the stats under it are one real turn rather than two guesses.
     *
     * <p>Questions picked to have answers that do not go stale: asked what today's date
     * means for the due date, the model answers with today's date in it, and a recording
     * of that would be visibly wrong in the next build's picture.
     */
    private static final Answer[] RECORDED = {
        new Answer("The invoice says:\n\n"
                 + "- Amount due: 1,240.00 EUR\n"
                 + "- Due date: 30 November 2026\n\n"
                 + "Invoice number: 2026-0042", 1657, 40),
        new Answer("The page only says:\n\n"
                 + "- **Total due:** 1,240.00 EUR\n"
                 + "- **Payable by:** 30 November 2026\n\n"
                 + "It does **not specify who to pay or how to pay**.", 1658, 49),
    };

    /** One recorded reply: the text, and what the service charged for it. */
    private static final class Answer {
        final String text;
        final int promptTokens;
        final int completionTokens;

        Answer(String text, int promptTokens, int completionTokens) {
            this.text = text;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }
    }

    /** A chat-completion response of the shape that arrives, around one recorded answer. */
    private static String completion(Answer answer) {
        return "{\"id\":\"chatcmpl-demostub0000000000\",\"object\":\"chat.completion\","
             + "\"model\":\"" + MODEL + "\","
             + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":"
             + "{\"role\":\"assistant\",\"content\":\"" + escaped(answer.text) + "\"}}],"
             + "\"usage\":{\"prompt_tokens\":" + answer.promptTokens
             + ",\"completion_tokens\":" + answer.completionTokens
             + ",\"total_tokens\":" + (answer.promptTokens + answer.completionTokens)
             + "}}";
    }

    private static String escaped(String text) {
        StringBuilder json = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') json.append('\\').append(c);
            else if (c == '\n') json.append("\\n");
            else if (c < 0x20) json.append(String.format("\\u%04x", (int) c));
            else json.append(c);
        }
        return json.toString();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream to = exchange.getResponseBody()) {
            to.write(bytes);
        }
    }

    // ---- the page that gets included ---------------------------------------

    /** A scanned-looking page, made here so the include scene attaches a real image. */
    private static BufferedImage invoicePage() {
        BufferedImage page = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, page.getWidth(), page.getHeight());
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 48));
        g.drawString("INVOICE 2026-0042", 120, 200);
        g.drawString("Total due: 1,240.00 EUR", 120, 320);
        g.drawString("Payable by 30 November 2026", 120, 440);
        g.dispose();
        return page;
    }

    private Screenshots() {
    }
}
