import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pulls the reply text out of a chat-completion JSON, the way JRock does.
 * <p>
 * No GUI: this is the hand-rolled parser on its own. It exists because JSON gives a
 * server two equally valid ways to send the same reply - the characters themselves in
 * UTF-8, or {@code \\uXXXX} escapes - and a reader that understands only the first
 * shows non-English replies as gibberish.
 *
 * @see JRockGuiFixture for the tests that drive the real window
 */
class JRockReplyTextTest {

    /** German, Russian and an emoji: Latin-1 range, BMP, and beyond it. */
    private static final String MIXED = "Herzlichen Dank für Ihre Mühe! "
            + "Привет 😀";

    @Test
    @DisplayName("reply text survives as characters, as \\u escapes, and mixed")
    void decodesBothFormsOfNonAsciiText() throws Exception {
        // As the characters themselves, which is what most servers send.
        assertThat(extractContent(reply(MIXED))).isEqualTo(MIXED);

        // The same reply, every non-ASCII character escaped - including the emoji,
        // which is a surrogate PAIR of escapes. A server may legitimately do this,
        // and before the escapes were decoded this arrived as the literal "u00fc".
        assertThat(extractContent(reply(escapeNonAscii(MIXED)))).isEqualTo(MIXED);

        // Mixed in one string, since nothing says a server has to pick one.
        assertThat(extractContent(reply("für \\u041f\\u0440\\u0438\\u0432\\u0435\\u0442")))
                .isEqualTo("für Привет");
    }

    @Test
    @DisplayName("the ordinary JSON escapes are still handled, and a stray one is kept")
    void decodesTheStructuralEscapes() throws Exception {
        assertThat(extractContent(reply("line\\nnext\\ttabbed"))).isEqualTo("line\nnext\ttabbed");
        assertThat(extractContent(reply("he said \\\"hi\\\""))).isEqualTo("he said \"hi\"");

        // An escaped backslash followed by a "u" is text, not an escape: the reply
        // really does contain ü, as a code sample would.
        assertThat(extractContent(reply("\\\\u00fc"))).isEqualTo("\\u00fc");

        // Not four hex digits, so not a \\u escape - kept rather than swallowed.
        assertThat(extractContent(reply("\\u00zz"))).isEqualTo("u00zz");
    }

    /** A chat-completion response whose one choice carries {@code content}. */
    private static String reply(String content) {
        return "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"" + content + "\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":7}}";
    }

    /** Every non-ASCII character as a \\uXXXX escape, surrogates included. */
    private static String escapeNonAscii(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) sb.append(c);
            else sb.append(String.format("\\u%04x", (int) c));
        }
        return sb.toString();
    }

    private static String extractContent(String json) throws Exception {
        Method m = JRock.class.getDeclaredMethod("extractContent", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, json);
    }
}
