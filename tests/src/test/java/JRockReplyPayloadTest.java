import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;
import javax.swing.JButton;

import org.assertj.swing.core.GenericTypeMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Sends a prompt to a chat-completions endpoint of this test's own, which answers with a
 * picture instead of text - and checks that the picture becomes an include of the
 * session: saved under {@code JRock/includes/}, hashed, logged, and named in the reply
 * itself, which is what the DOCX and Markdown exports place it from.
 * <p>
 * A real server on 127.0.0.1 rather than a stubbed transport, for the reason
 * {@link JRockFetchUrlTest} gives: what the feature turns on is the response body, and a
 * stub that produced one would be a stub of the thing under test. The endpoint and the
 * API key are set on JRock's own statics, so the request goes to this server and nothing
 * leaves the machine.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockReplyPayloadTest extends JRockGuiFixture {

    /** A local send, a reply, a file written and hashed; a slow runner needs the rest. */
    private static final long REPLY_TIMEOUT_SECONDS = 30;

    /** Deliberately not square, so the dimensions in the log can only be this image's. */
    private static final int IMAGE_WIDTH = 120;
    private static final int IMAGE_HEIGHT = 80;

    /** Sample rate and length of the WAV below - a tenth of a second of silence. */
    private static final int WAV_RATE = 8000;
    private static final int WAV_SAMPLES = 800;

    private HttpServer server;
    /** The body the server answers the next send with. */
    private volatile byte[] reply;

    @BeforeEach
    void startTheEndpoint() throws Exception {
        // Port 0: the OS hands out a free one, so two runs at once cannot collide, and
        // the loopback address explicitly: this endpoint exists for one test process.
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            // The request is read and dropped: what this test is about is the answer.
            exchange.getRequestBody().readAllBytes();
            respond(exchange, reply);
        });
        server.start();

        field("ENDPOINT").set(null, "http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "/v1/chat/completions");
        // A key has to be set for a send to be attempted at all; this one is never
        // checked, the server above taking whatever arrives.
        field("apiKey").set(null, "fake-bedrock-key-not-a-real-credential");
    }

    @AfterEach
    void stopTheEndpoint() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @DisplayName("a picture in the reply is saved as an include and named in the answer")
    void savesAPictureFromTheReply() throws Exception {
        awaitReadyCount(1);
        // With Markdown references on, which is what puts the "![Image: ...](file.png)"
        // line above the token - in the answer, where the exports read it.
        field("includeMarkdownRefs").set(null, true);
        reply = chatReply("\"mime_type\":\"image/png\"", png());

        send("Generate me an image of a cat");
        awaitTheWholeExchange();

        // Saved where an include's own processed copy is saved, under the extension its
        // media type called for, byte for byte as it arrived.
        Path saved = onlyFileIn(includesDirectory());
        assertThat(saved.getFileName().toString()).describedAs("the saved file's name")
                .endsWith(".png");
        assertThat(Files.readAllBytes(saved)).describedAs("the saved picture's bytes")
                .isEqualTo(png());

        String name = saved.getFileName().toString();
        String hash = hashOf(theReply(), "img");

        // In the answer: the reference above the token, the same two lines an include
        // puts in the prompt - which is what makes the reply exportable with the
        // picture in it. Read from the file JRock wrote the reply to, which holds it
        // verbatim; the pane's own text comes back through an editor kit, with this
        // platform's line endings put on it.
        assertThat(theReply()).describedAs("the reply, as written to JRock/messages/")
                .startsWith("![Image: " + IMAGE_WIDTH + " x " + IMAGE_HEIGHT + ", ")
                .endsWith("](" + name + ")\n@img " + hash);

        // The stats block times the call itself: a local server answers in a few
        // milliseconds, so the figure is only checked for being a figure in ms.
        assertThat(theLog()).describedAs("the log pane's text")
                .containsPattern("Round trip \\(RTT\\): [0-9,]+ ms");

        assertThat(theLog()).describedAs("the log pane's text")
                // The include's own lines, which a restart reads the mapping back from.
                .contains("Included @img " + hash + " from " + saved)
                .contains("With a Markdown reference above it: ![Image: ")
                // "content":null is no text, not the name of the next field along.
                .doesNotContain("extra_content\n")
                // And the base64 it arrived in is not in the transcript.
                .contains("<blob masked ")
                .doesNotContain(Base64.getEncoder().encodeToString(png()).substring(0, 60));

        // The hash is the file's, so the export can find the picture by it.
        assertThat(includes()).describedAs("the hash -> path map").containsEntry(hash, saved);
        assertThat(refTargets()).describedAs("what the reference names")
                .containsEntry(name, hash);
    }

    @Test
    @DisplayName("with Markdown references off the answer carries the token alone")
    void respectsTheMarkdownReferencesSetting() throws Exception {
        awaitReadyCount(1);
        field("includeMarkdownRefs").set(null, false);
        reply = chatReply("\"mime_type\":\"image/png\"", png());

        send("Generate me an image of a cat");
        awaitTheWholeExchange();

        String hash = hashOf(theLog(), "img");
        assertThat(theLog()).describedAs("the log pane's text")
                .contains("@img " + hash)
                .doesNotContain("![Image: ")
                .doesNotContain(REF_LOG_PREFIX);
    }

    @Test
    @DisplayName("a recording with no media type is read from its own bytes")
    void savesARecordingTheReplyDidNotName() throws Exception {
        awaitReadyCount(1);
        field("includeMarkdownRefs").set(null, true);
        // No mime_type and no format: the API's own audio output says what it is in the
        // request, not in the answer, so the bytes are all there is to go on.
        reply = chatReply(null, wav());

        send("Say something");
        awaitTheWholeExchange();

        Path saved = onlyFileIn(includesDirectory());
        assertThat(saved.getFileName().toString()).describedAs("the saved file's name")
                .endsWith(".wav");
        assertThat(Files.readAllBytes(saved)).describedAs("the saved recording's bytes")
                .isEqualTo(wav());

        // A recording is not a picture: the reference has no "!" on it, and the line
        // about the file is the one a WAV header can be read into.
        String hash = hashOf(theReply(), "audio");
        assertThat(theReply()).describedAs("the reply, as written to JRock/messages/")
                .startsWith("[Audio: ")
                .endsWith("](" + saved.getFileName() + ")\n@audio " + hash);
        assertThat(theLog()).describedAs("the log pane's text")
                .contains("[Audio: ")
                .doesNotContain("![Audio: ")
                .contains("8 kHz");
    }

    @Test
    @DisplayName("a reply with neither text nor a payload says so")
    void saysWhenThereIsNothingInTheReply() throws Exception {
        awaitReadyCount(1);
        reply = ("{\"id\":\"chatcmpl-nothing\",\"choices\":[{\"index\":0,\"finish_reason\":"
                + "\"stop\",\"message\":{\"role\":\"assistant\",\"content\":null}}],"
                + "\"usage\":{\"total_tokens\":3}}").getBytes(StandardCharsets.UTF_8);

        send("Anything?");
        awaitTheWholeExchange();

        assertThat(theReply()).describedAs("the reply, as written to JRock/messages/")
                .isEqualTo("(no text in the reply; see the raw response below)");
        assertThat(includesDirectory()).describedAs("JRock/includes/").doesNotExist();
    }

    /**
     * Waits for the end of the exchange, which is the stats block: the last thing a send
     * logs.
     * <p>
     * Not the include's own line, however much more to the point it reads. That one is
     * written while the reply is still being assembled, on the send's worker thread - so
     * a test that waited for it could look at the answer before there was one, and did:
     * it found no reply file at all on a loaded machine.
     */
    private void awaitTheWholeExchange() {
        awaitLogLine("--- stats ---", REPLY_TIMEOUT_SECONDS);
    }

    /** Puts the prompt in the window and presses Send, which is what Ctrl+Enter does. */
    private void send(String prompt) {
        enterText(promptArea(), prompt);
        press(window.button(new GenericTypeMatcher<JButton>(JButton.class) {
            @Override
            protected boolean isMatching(JButton button) {
                return button.getText() != null && button.getText().startsWith("Send");
            }
        }));
    }

    /**
     * A chat-completion response whose message carries binary instead of text.
     * <p>
     * Shaped like the ones that arrive: {@code content} is null, the payload sits under
     * the message's one binary field, and what it really is - when the service says at
     * all - is in a {@code mime_type} nested in an {@code extra_content} object of its
     * own, which is why the type is looked for anywhere inside the audio object.
     */
    private static byte[] chatReply(String mimeField, byte[] payload) {
        String type = mimeField == null ? ""
                : ",\"extra_content\":{\"service\":{" + mimeField + "}}";
        return ("{\"id\":\"chatcmpl-0123456789abcdefghij\",\"object\":\"chat.completion\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":"
                + "{\"audio\":{\"data\":\"" + Base64.getEncoder().encodeToString(payload)
                + "\"" + type + "},\"content\":null,\"role\":\"assistant\"}}],"
                + "\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":1120,"
                + "\"total_tokens\":1128}}").getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private Path includesDirectory() {
        return workingDirectory().resolve("JRock").resolve("includes");
    }

    /**
     * The reply as JRock wrote it, read from its own file under {@code JRock/messages/}.
     * <p>
     * That file holds the answer verbatim - the same string the log pane was given, in
     * UTF-8, with nothing normalized - which is what makes it the thing to assert two
     * adjacent lines against.
     */
    private String theReply() throws IOException {
        Path messages = workingDirectory().resolve("JRock").resolve("messages");
        try (java.util.stream.Stream<Path> files = Files.list(messages)) {
            Path assistant = files
                    .filter(p -> p.getFileName().toString().endsWith("-assistant.txt"))
                    .max(java.util.Comparator.naturalOrder())   // newest: the name is its time
                    .orElseThrow(() -> new AssertionError(
                            "no -assistant.txt under " + messages + ", so no reply was logged"));
            return new String(Files.readAllBytes(assistant), StandardCharsets.UTF_8);
        }
    }

    /** The one file the reply saved, the directory having held nothing before it. */
    private static Path onlyFileIn(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            java.util.List<Path> found = files.collect(java.util.stream.Collectors.toList());
            assertThat(found).describedAs("the files in " + dir).hasSize(1);
            return found.get(0);
        }
    }

    /**
     * The log pane's text with its line endings normalized.
     * <p>
     * The pane is a JTextPane, and {@code getText()} on one writes the document out
     * through its editor kit - which ends every line with the platform's separator, so
     * on Windows a two-line reply comes back with "\r\n" in it. The lines are what these
     * tests are about, not which separator this machine uses.
     */
    private String theLog() {
        return logPane().text().replace("\r\n", "\n");
    }

    private static String hashOf(String text, String kind) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("@" + kind + " ([0-9a-f]{12})").matcher(text);
        assertThat(m.find()).describedAs("an @" + kind + " token in the log").isTrue();
        return m.group(1);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Path> includes() throws Exception {
        return (java.util.Map<String, Path>) field("INCLUDES").get(null);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> refTargets() throws Exception {
        return (java.util.Map<String, String>) field("REF_TARGETS").get(null);
    }

    /** JRock's own wording for the reference line in the log, read from the application. */
    private static final String REF_LOG_PREFIX = refLogPrefix();

    private static String refLogPrefix() {
        try {
            return (String) field("REF_LOG_PREFIX").get(null);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A real PNG, produced by the JDK, so its header states a size to be read back. */
    private static byte[] png() throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        assertThat(ImageIO.write(
                new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB),
                "png", bytes)).describedAs("the JDK wrote the PNG").isTrue();
        return bytes.toByteArray();
    }

    /** A real WAV: 16-bit mono silence, with the header JRock reads a recording from. */
    private static byte[] wav() {
        int data = WAV_SAMPLES * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + data)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        b.putShort((short) 1).putShort((short) 1);          // PCM, mono
        b.putInt(WAV_RATE).putInt(WAV_RATE * 2);            // rate, bytes per second
        b.putShort((short) 2).putShort((short) 16);         // block align, bits
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data);
        return b.array();
    }
}
