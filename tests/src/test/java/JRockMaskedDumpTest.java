import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Masks the raw response shown in the log, the way JRock does.
 * <p>
 * No GUI: this is the masking on its own. It exists because a response can carry binary
 * written out as text - a generated image, a recording, a signature - and one megabyte of
 * base64 on a line buries every field around it, which are the fields worth reading. The
 * blob goes, its length and the shape of the response stay.
 *
 * @see JRockGuiFixture for the tests that drive the real window
 */
class JRockMaskedDumpTest {

    /** 92 characters of base64, which is what a payload looks like at any size. */
    private static final String BLOB =
            "iVBORw0KGgoAAAANSUhEUgAABYAAAAMACAIAAAASU1SbAAAAiXpUWHRSYXcgcHJvZmlsZSB0eXBlIGlwdGMAAAiZTYwx";

    @Test
    @DisplayName("a long base64 value is replaced by its length, and the fields beside it survive")
    void masksBase64Payloads() throws Exception {
        // The shape an image comes back in: base64 under "data", with the one field
        // that says what it is - the mime type - sitting next to it.
        String masked = maskLongBlobs("{\"audio\":{\"data\":\"" + BLOB + "\","
                + "\"extra_content\":{\"mime_type\":\"image/png\"}}}");

        assertThat(masked).isEqualTo("{\"audio\":{\"data\":\"<blob masked 92 chars>\","
                + "\"extra_content\":{\"mime_type\":\"image/png\"}}}");
    }

    @Test
    @DisplayName("every blob in the response goes, not just the first")
    void masksAllOfThem() throws Exception {
        // A generated image can arrive with a signature of the model's own beside it:
        // two blobs in one response, and masking one still leaves the dump unreadable.
        String masked = maskLongBlobs("{\"data\":\"" + BLOB + "\",\"sig\":\"" + BLOB + "\"}");

        assertThat(masked).isEqualTo(
                "{\"data\":\"<blob masked 92 chars>\",\"sig\":\"<blob masked 92 chars>\"}");
    }

    @Test
    @DisplayName("reply text, short values and field names are left alone")
    void keepsWhatIsReadable() throws Exception {
        // Prose, however long: spaces and punctuation are outside the base64 alphabet.
        String prose = "{\"content\":\"A perfectly ordinary reply, which is well over eighty "
                + "characters long and still has every right to be shown in full.\"}";
        assertThat(maskLongBlobs(prose)).isEqualTo(prose);

        // Short enough to read: the response id and an include hash stay as they are.
        String ids = "{\"id\":\"chatcmpl-abc123\",\"hash\":\"0123456789abcdef\"}";
        assertThat(maskLongBlobs(ids)).isEqualTo(ids);

        // A key is never masked, however long: the field name is what makes the
        // masked line mean anything.
        String longKey = "{\"" + BLOB + "\":1}";
        assertThat(maskLongBlobs(longKey)).isEqualTo(longKey);

        // A quote inside a value doesn't end it, so what follows is still read as a
        // value and not mistaken for a key.
        String quoted = "{\"content\":\"he said \\\"" + BLOB + "\\\"\"}";
        assertThat(maskLongBlobs(quoted)).isEqualTo(quoted);
    }

    @Test
    @DisplayName("a truncated response is left whole rather than cut at the break")
    void keepsAnUnterminatedTail() throws Exception {
        // A connection dropped mid-blob. There is no closing quote to find, and the
        // dump is the only evidence of what arrived: it is kept as it came.
        String cut = "{\"data\":\"" + BLOB;
        assertThat(maskLongBlobs(cut)).isEqualTo(cut);
    }

    @Test
    @DisplayName("the whole masking pass: reply out, blobs out, id shortened")
    void masksTheResponseAsLogged() throws Exception {
        String reply = "Here is your cat.";
        String response = "{\"id\":\"chatcmpl-abcdefghijklmnopqrstuvwxyz\",\"choices\":[{\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"" + reply + "\","
                + "\"audio\":{\"data\":\"" + BLOB + "\"}}}]}";

        assertThat(maskResponse(response, reply)).isEqualTo(
                "{\"id\":\"chatcmpl-abcd<...>\",\"choices\":[{\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"<output masked>\","
                + "\"audio\":{\"data\":\"<blob masked 92 chars>\"}}}]}");
    }

    private static String maskLongBlobs(String json) throws Exception {
        Method m = JRock.class.getDeclaredMethod("maskLongBlobs", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, json);
    }

    private static String maskResponse(String json, String reply) throws Exception {
        Method m = JRock.class.getDeclaredMethod("maskResponse", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, json, reply);
    }
}
