import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.assertj.swing.finder.JOptionPaneFinder;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Drives <em>Fetch URL...</em> in the prompt's context menu against a web server of
 * this test's own: a page, JSON, plain text, a picture, and something that is none of these.
 * <p>
 * A real server on 127.0.0.1 rather than a stubbed transport, because what the feature
 * turns on is the response - its status, its {@code Content-Type}, its charset and its
 * bytes - and a stub that produced those would be a stub of the one thing under test.
 * It listens on a port the OS picks, serves five fixed paths, and is taken down after
 * each test; nothing leaves the machine.
 * <p>
 * Offline in the sense the rest of the suite is: no API key is set, so JRock skips its
 * model-list fetch, and nothing here sends a message to a model.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockFetchUrlTest extends JRockGuiFixture {

    /** A download from localhost, hashed and included; a slow CI runner needs the rest. */
    private static final long FETCH_TIMEOUT_SECONDS = 30;

    /**
     * A page deliberately NOT served as UTF-8, with a character that proves it: JRock
     * saves an included page as UTF-8 because that is how it reads one back, so a
     * charset of the server's choosing has to survive the round trip.
     */
    private static final String PAGE =
            "<html><body><h1>Für Elise</h1><p>Eine Kleinigkeit.</p></body></html>";

    /** JSON, which is UTF-8 by RFC 8259 and served without a charset to say so. */
    private static final String JSON = "{\"title\":\"Für Elise\",\"key\":\"a minor\"}";

    /** Plain text in a charset that is neither the default nor Latin-1: Cyrillic. */
    private static final String PLAIN = "Привет, мир.\nЭто просто текст.\n";

    /** Deliberately not square,so the dimensions in the log can only be this image's. */
    private static final int IMAGE_WIDTH = 120;
    private static final int IMAGE_HEIGHT = 80;

    private HttpServer server;
    private String base;

    @BeforeEach
    void startTheWebServer() throws Exception {
        // Port 0: the OS hands out a free one, so two runs at once cannot collide.
        // Bound to the loopback address explicitly - this server exists for one test
        // process and should not be reachable from anywhere else.
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/a/article", exchange ->
                respond(exchange, "text/html; charset=ISO-8859-1",
                        PAGE.getBytes(StandardCharsets.ISO_8859_1)));
        server.createContext("/pics/cat.png", exchange ->
                respond(exchange, "image/png", png()));
        // Text that is not a page: sent as text all the same, each under the extension
        // its media type calls for.
        server.createContext("/data/notes.json", exchange ->
                respond(exchange, "application/json",
                        JSON.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/notes/readme", exchange ->
                respond(exchange, "text/plain; charset=windows-1251",
                        PLAIN.getBytes(java.nio.charset.Charset.forName("windows-1251"))));
        // A media type JRock cannot include: not text, not one of the four image types
        // it can send. The whole response is served anyway, so what refuses it is the
        // type and not a failed request.
        server.createContext("/docs/report.pdf", exchange ->
                respond(exchange, "application/pdf",
                        "%PDF-1.4 not includable".getBytes(StandardCharsets.US_ASCII)));
        server.start();
        base = "http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort();
    }

    @AfterEach
    void stopTheWebServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @DisplayName("an HTML page is saved as .html and inserted as a link above an @txt token")
    void insertsAPageAsText() throws Exception {
        awaitReadyCount(1);

        String url = base + "/a/article";
        fetchUrl(url);
        awaitLogLine("new @txt token(s) for " + url, FETCH_TIMEOUT_SECONDS);

        // Named after the URL's last path segment, with the extension its media type
        // called for - and holding the page, re-encoded as UTF-8.
        Path saved = urlsDirectory().resolve("article.html");
        assertThat(saved).describedAs("the saved page").exists();
        assertThat(new String(Files.readAllBytes(saved), StandardCharsets.UTF_8))
                .describedAs("the saved page, read back as UTF-8")
                .isEqualTo(PAGE);

        // Two lines in the prompt, in that order: where it came from, then what was
        // sent. At the caret, which is where the starter prompt below it stays.
        String hash = hashOf(promptArea().text(), "txt");
        assertThat(promptArea().text()).describedAs("the prompt")
                .startsWith("[](" + url + ")\n@txt " + hash + "\n");

        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Fetching URL: " + url)
                .contains("Decoded as ISO-8859-1, saved as UTF-8.")
                .contains("Saved ")
                .contains("bytes of text/html to " + saved)
                .contains("Included @txt " + hash + " from " + saved)
                .contains("With a Markdown reference above it: [](" + url + ")");
    }

    @Test
    @DisplayName("an image URL is saved with its own extension and inserted as @img")
    void insertsAnImageAsAPicture() throws Exception {
        awaitReadyCount(1);

        String url = base + "/pics/cat.png";
        fetchUrl(url);
        awaitLogLine("new @img token(s) for " + url, FETCH_TIMEOUT_SECONDS);

        // The URL already named it ".png" and the media type agrees, so the extension
        // is not added twice.
        Path saved = urlsDirectory().resolve("cat.png");
        assertThat(saved).describedAs("the saved image").exists();
        assertThat(Files.readAllBytes(saved)).describedAs("the saved image's bytes")
                .isEqualTo(png());

        String hash = hashOf(promptArea().text(), "img");
        assertThat(promptArea().text()).describedAs("the prompt")
                .startsWith("[](" + url + ")\n@img " + hash + "\n");

        // Included as an image, which is what the dimensions in the log show: they are
        // read out of the header of the file JRock saved.
        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("bytes of image/png to " + saved)
                .contains("Image: " + IMAGE_WIDTH + " x " + IMAGE_HEIGHT)
                .doesNotContain("Decoded as ");   // bytes kept byte for byte
    }

    @Test
    @DisplayName("a JSON URL is saved as .json and inserted as @txt")
    void insertsJsonAsText() throws Exception {
        awaitReadyCount(1);

        String url = base + "/data/notes.json";
        fetchUrl(url);
        awaitLogLine("new @txt token(s) for " + url, FETCH_TIMEOUT_SECONDS);

        // Already named ".json", which the media type agrees with: not added twice.
        Path saved = urlsDirectory().resolve("notes.json");
        assertThat(saved).describedAs("the saved JSON").exists();
        assertThat(new String(Files.readAllBytes(saved), StandardCharsets.UTF_8))
                .describedAs("the saved JSON, read back as UTF-8")
                .isEqualTo(JSON);

        String hash = hashOf(promptArea().text(), "txt");
        assertThat(promptArea().text()).describedAs("the prompt")
                .startsWith("[](" + url + ")\n@txt " + hash + "\n");
        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Decoded as UTF-8, saved as UTF-8.")
                .contains("bytes of application/json to " + saved);
    }

    @Test
    @DisplayName("a plain-text URL is saved as .txt, re-encoded as UTF-8, and inserted as @txt")
    void insertsPlainTextAsText() throws Exception {
        awaitReadyCount(1);

        String url = base + "/notes/readme";
        fetchUrl(url);
        awaitLogLine("new @txt token(s) for " + url, FETCH_TIMEOUT_SECONDS);

        Path saved = urlsDirectory().resolve("readme.txt");
        assertThat(saved).describedAs("the saved text").exists();
        assertThat(new String(Files.readAllBytes(saved), StandardCharsets.UTF_8))
                .describedAs("the saved text, read back as UTF-8")
                .isEqualTo(PLAIN);

        String hash = hashOf(promptArea().text(), "txt");
        assertThat(promptArea().text()).describedAs("the prompt")
                .startsWith("[](" + url + ")\n@txt " + hash + "\n");
        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Decoded as windows-1251, saved as UTF-8.")
                .contains("bytes of text/plain to " + saved);
    }

    @Test
    @DisplayName("any other media type is refused, and nothing is saved or inserted")
    void refusesAnythingElse() throws Exception {
        awaitReadyCount(1);

        String promptBefore = promptArea().text();
        String url = base + "/docs/report.pdf";
        fetchUrl(url);

        // The refusal names the type the server answered with, in the log and in a
        // dialog of its own - which is dismissed here, the application being left as
        // this test found it.
        awaitLogLine("application/pdf", FETCH_TIMEOUT_SECONDS);
        JOptionPaneFixture refusal =
                JOptionPaneFinder.findOptionPane().withTimeout(DIALOG_TIMEOUT_MS).using(robot);
        refusal.requireTitle("Unsupported media type");
        assertThat(refusal.target().getMessage().toString())
                .describedAs("the refusal dialog's message")
                .contains("application/pdf")
                .contains("nothing was saved");
        press(refusal.okButton());

        // Nothing was written: no file, and the directory is not even created for one.
        assertThat(urlsDirectory()).describedAs("JRock/urls/").doesNotExist();
        // And nothing was inserted, the prompt being what it was before.
        assertThat(promptArea().text()).describedAs("the prompt").isEqualTo(promptBefore);
        assertThat(logPane().text()).describedAs("the log pane's text")
                .doesNotContain("@txt ")
                .doesNotContain("@img ");
    }

    /**
     * Opens <em>Fetch URL...</em> from the prompt's context menu, gives it the address
     * and approves it.
     * <p>
     * Through the menu item rather than by calling the fetch, because the item is part
     * of what was added: a feature reachable only from code is not reachable at all. The
     * dialog holds one text component, so it is found by that; see
     * {@link #enterText} for why the address is set rather than typed.
     */
    private void fetchUrl(String url) {
        chooseInThePromptMenu("Fetch URL...");
        JOptionPaneFixture dialog =
                JOptionPaneFinder.findOptionPane().withTimeout(DIALOG_TIMEOUT_MS).using(robot);
        dialog.requireTitle("Fetch URL");
        enterText(dialog.textBox(), url);
        press(dialog.okButton());
    }

    /** Where JRock keeps what it downloads. */
    private Path urlsDirectory() {
        return workingDirectory().resolve("JRock").resolve("urls");
    }

    /** The hash JRock gave the include, read back out of the token it inserted. */
    private static String hashOf(String prompt, String kind) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("@" + kind + " ([0-9a-f]{12})").matcher(prompt);
        assertThat(m.find()).describedAs("an @" + kind + " token in " + prompt).isTrue();
        return m.group(1);
    }

    /** A real PNG, produced by the JDK, so its header states a size to be read back. */
    private static byte[] png() throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        assertThat(ImageIO.write(
                new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB),
                "png", bytes)).describedAs("the JDK wrote the PNG").isTrue();
        return bytes.toByteArray();
    }

    /** One response: the media type under test, and the body as it stands. */
    private static void respond(HttpExchange exchange, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
