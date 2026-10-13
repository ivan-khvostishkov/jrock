import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How far back a start reads the log for its includes.
 * <p>
 * A push-to-talk JRock records every turn and attaches it, so a month of talking is
 * hundreds of audio includes in one log - and reading all of them back at every start is
 * work done for files no question will ever mention again. Startup reads the last few
 * requests instead (the folder's History limit, or ten), and the prompt menu's
 * <em>Reload all includes</em> reads the whole log.
 * <p>
 * What has to be true of that cut is the point of this test: it keeps the gray lines
 * ABOVE the oldest request it keeps. An include is recorded by a gray line written while
 * the prompt was being composed, so the lines that say where a request's files are sit
 * immediately before it - cutting at the request itself would keep the token and throw
 * away the path, which is the one way this optimisation could lose something.
 * <p>
 * No window and no model: the cut is a pure function over a timeline.
 *
 * @see JRockReloadIncludesTest for the reloading itself, against the real window
 */
class JRockReloadWindowTest {

    /** The roles as the log records them, which is what the cut counts. */
    private static final String HUMAN = "HUMAN OPERATOR";
    private static final String ASSISTANT = "OPERATOR'S ASSISTANT";

    @Test
    @DisplayName("the cut keeps the last N requests, with the include lines above each")
    void keepsTheLastRequestsAndTheirIncludeLines() throws Exception {
        List<String[]> timeline = new ArrayList<>();
        for (int turn = 1; turn <= 4; turn++) {
            timeline.add(gray("Image: 120 x 80, included as @img hash" + turn));
            timeline.add(message(HUMAN, "look at @img hash" + turn));
            timeline.add(message(ASSISTANT, "answer " + turn));
        }

        List<String[]> kept = cut(timeline, 2);

        // The two last turns, and the gray line that records each of their includes -
        // six entries, starting with the include line of the third turn.
        assertThat(texts(kept)).describedAs("what the last two requests bring with them")
                .containsExactly(
                        "Image: 120 x 80, included as @img hash3",
                        "look at @img hash3",
                        "answer 3",
                        "Image: 120 x 80, included as @img hash4",
                        "look at @img hash4",
                        "answer 4");
    }

    @Test
    @DisplayName("a run of gray lines above the oldest kept request is kept whole")
    void keepsTheWholeRunOfGrayLinesAboveIt() throws Exception {
        List<String[]> timeline = Arrays.asList(
                message(HUMAN, "first"),
                message(ASSISTANT, "answered"),
                gray("Audio: 3.2 s, included as @audio aaaa"),
                gray("Reference: ![](note.png) -> @img bbbb"),
                message(HUMAN, "and @audio aaaa with ![](note.png)"),
                message(ASSISTANT, "answered again"));

        assertThat(texts(cut(timeline, 1)))
                .describedAs("the last request, and both lines recording its files")
                .containsExactly(
                        "Audio: 3.2 s, included as @audio aaaa",
                        "Reference: ![](note.png) -> @img bbbb",
                        "and @audio aaaa with ![](note.png)",
                        "answered again");
    }

    @Test
    @DisplayName("asking for more requests than there are, or for all of them, keeps the lot")
    void keepsEverythingWhenThereIsNoCutToMake() throws Exception {
        List<String[]> timeline = Arrays.asList(
                gray("Image: 1 x 1, included as @img aaaa"),
                message(HUMAN, "one"),
                message(ASSISTANT, "two"));

        assertThat(texts(cut(timeline, 9))).describedAs("fewer requests than asked for")
                .hasSize(3);
        assertThat(texts(cut(timeline, 0))).describedAs("0 requests, which means all of them")
                .hasSize(3);
        assertThat(texts(cut(Arrays.asList(), 5))).describedAs("an empty log").isEmpty();
    }

    /** A gray line: no role, which is how the timeline marks one. */
    private static String[] gray(String text) {
        return new String[] { null, text };
    }

    private static String[] message(String role, String text) {
        return new String[] { role, text };
    }

    private static List<String> texts(List<String[]> steps) {
        List<String> out = new ArrayList<>();
        for (String[] step : steps) out.add(step[1]);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String[]> cut(List<String[]> timeline, int requests) throws Exception {
        Method m = JRock.class.getDeclaredMethod("lastRequests", List.class, int.class);
        m.setAccessible(true);
        return (List<String[]>) m.invoke(null, timeline, requests);
    }
}
