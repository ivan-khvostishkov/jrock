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
 * replays them, and points the throwaway folder's {@code JRock/jrock-config.txt} at it.
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
        Path jrockDir = work.resolve("JRock");
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

        // A prompt with a question in it, which is what anybody's first look is.
        JRock.automationSetPrompt("Read this invoice and tell me what is due, and when.\n");
        settle();
        shoot(window, "02-a-question-in-the-prompt");

        // An include: the token in the prompt and the Markdown reference above it, with
        // the log line that says what was attached. The page is made here rather than
        // shipped, so the picture is of this build's own behaviour.
        Path png = work.resolve("a-page.png");
        ImageIO.write(invoicePage(), "png", png.toFile());
        String included = JRock.automationInclude(png);
        if (included != null) System.out.println("include: " + included);
        settle();
        shoot(window, "03-a-page-included-as-an-image");

        // The turn itself: the question and the page sent, and the answer in the log
        // under its own timestamped header, rendered as the Markdown it arrives as.
        send("04-a-question-and-its-answer", window);

        // A second turn with History on, so the log shows a conversation rather than one
        // exchange, and Dialog only on, which hides the gray machinery around it.
        tick(window, "History");
        JRock.automationSetPrompt("Who do I pay, and how? Answer from the page only.\n");
        send(null, window);
        tick(window, "Dialog only");
        settle();
        shoot(window, "05-two-turns-with-history-and-dialog-only");

        // The agents, each in its own window. The timer needs no JRock and no key at all;
        // the paper docs agent shows the two folders it reads.
        shootAgent("JRockTimer", new String[0], "06-the-timer-agent");
        shootAgent("JRockPaperDocs", new String[] { "--folder", work.toString(),
                "--incoming", work.toString() }, "07-the-papers-agent");

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

    /** Paints one window into screenshots/&lt;os&gt;-&lt;name&gt;.png. */
    private static void shoot(Window window, String name) {
        try {
            BufferedImage[] shot = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                int w = Math.max(1, window.getWidth());
                int h = Math.max(1, window.getHeight());
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                // printAll and not paint: paint can be answered with a double-buffered
                // repaint later, while printAll draws the whole hierarchy now.
                window.printAll(g);
                g.dispose();
                shot[0] = image;
            });
            Path file = out.resolve(os + "-" + name + ".png");
            ImageIO.write(shot[0], "png", file.toFile());
            taken++;
            System.out.println("shot " + file.getFileName() + "  "
                    + shot[0].getWidth() + "x" + shot[0].getHeight());
        } catch (Exception ex) {
            System.out.println(name + ": not shot (" + ex + ")");
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
     * <p>Copied from {@code JRock/messages/*-assistant.txt}, which holds a reply verbatim,
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
