import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.JMenuItem;
import javax.swing.JTextPane;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.edt.GuiQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How an address is cut out of the text around it: for Fetch URL's field, and for
 * <em>Open URL in new tab</em> on a selection in the log.
 * <p>
 * The first two read a string and return one. The third drives the real log pane, and
 * checks the menu item is offered for an address and greyed out for anything else -
 * without pressing it, which would open a browser on the machine running the tests.
 */
class JRockUrlTextTest extends JRockGuiFixture {

    private static final String OPEN_URL = "Open URL in new tab";

    @Test
    @DisplayName("Fetch URL drops the dashes, spaces and punctuation pasted around an address")
    void cleansAPastedAddress() {
        assertThat(JRock.cleanUrl(" - https://example.com/ ,")).isEqualTo("https://example.com/");
        assertThat(JRock.cleanUrl("— https://example.com/a?b=1.")).isEqualTo("https://example.com/a?b=1");
        assertThat(JRock.cleanUrl("<https://example.com/>;")).isEqualTo("https://example.com/");
        assertThat(JRock.cleanUrl("\"example.com/page\"")).isEqualTo("example.com/page");
        // A bracket the address opened is its own; one it did not is the sentence's.
        assertThat(JRock.cleanUrl("(https://en.wikipedia.org/wiki/Java_(language)).")).isEqualTo(
                "https://en.wikipedia.org/wiki/Java_(language)");
        assertThat(JRock.cleanUrl(" - , ")).isEmpty();
    }

    @Test
    @DisplayName("a selection holding an address, or inside one, points at that address")
    void findsTheAddressAtASelection() {
        String text = "See - https://example.com/ ; and www.example.org/x, then more.";
        int url = text.indexOf("https://");
        int urlEnd = text.indexOf(" ;") ;

        // The selection holds the address, with garbage either side.
        assertThat(JRock.urlAtSelection(text, url - 2, urlEnd + 2)).isEqualTo("https://example.com/");
        // The whole line holds two: the first one is taken.
        assertThat(JRock.urlAtSelection(text, 0, text.length())).isEqualTo("https://example.com/");
        // Strictly inside it.
        int xample = text.indexOf("xample.co");
        assertThat(JRock.urlAtSelection(text, xample, xample + "xample.co".length()))
                .isEqualTo("https://example.com/");
        // From inside it to past its end.
        assertThat(JRock.urlAtSelection(text, xample, urlEnd + 2)).isEqualTo("https://example.com/");
        // A www. address is given its scheme, and its comma is left behind.
        int www = text.indexOf("www.");
        assertThat(JRock.urlAtSelection(text, www + 4, www + 11)).isEqualTo("https://www.example.org/x");
        // Text that is no address, and text that only overlaps one, point at none.
        int then = text.indexOf("then");
        assertThat(JRock.urlAtSelection(text, then, then + 4)).isNull();
        assertThat(JRock.urlAtSelection(text, 0, xample)).isNull();
    }

    @Test
    @DisplayName("the log's Open URL in new tab is offered for an address, and only for one")
    void offersOpenUrlForASelectedAddressOnly() throws Exception {
        awaitReadyCount(1);
        String line = "Docs are at - https://example.com/docs ; read them.";
        logGray(line);
        awaitLogLine(line, 10);

        // The document's own text: JTextPane.getText() writes the platform's line
        // separator, and on Windows its extra CR would shift every offset after it.
        final JTextPane pane = (JTextPane) logPane().target();
        String text = GuiActionRunner.execute(new GuiQuery<String>() {
            @Override
            protected String executeInEDT() throws Exception {
                return pane.getDocument().getText(0, pane.getDocument().getLength());
            }
        });
        int at = text.indexOf("xample.com/do");
        assertThat(openUrlEnabledFor(at, at + "xample.com/do".length()))
                .describedAs("a selection inside the address").isTrue();
        int read = text.indexOf("read them");
        assertThat(openUrlEnabledFor(read, read + "read them".length()))
                .describedAs("a selection with no address in it").isFalse();
    }

    /** Writes a gray line into the running application's log. */
    private static void logGray(String line) throws Exception {
        Object ui = field("ui").get(null);
        java.lang.reflect.Field logField = ui.getClass().getDeclaredField("log");
        logField.setAccessible(true);
        Object log = logField.get(ui);
        java.lang.reflect.Method gray = log.getClass().getDeclaredMethod("gray", String.class);
        gray.setAccessible(true);
        gray.invoke(log, line);
    }

    /**
     * Selects [start, end) of the log, opens its context menu, and says whether the
     * item is enabled - then closes the menu without pressing anything.
     */
    private boolean openUrlEnabledFor(final int start, final int end) {
        final JTextPane pane = (JTextPane) logPane().target();
        GuiActionRunner.execute(new GuiQuery<Void>() {
            @Override
            protected Void executeInEDT() {
                pane.select(start, end);
                pane.dispatchEvent(new java.awt.event.MouseEvent(pane,
                        java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                        0, 4, 4, 1, true));      // popupTrigger = true
                return null;
            }
        });
        JMenuItem item = robot.finder().find(new GenericTypeMatcher<JMenuItem>(JMenuItem.class) {
            @Override
            protected boolean isMatching(JMenuItem candidate) {
                return OPEN_URL.equals(candidate.getText()) && candidate.isShowing();
            }
        });
        return GuiActionRunner.execute(new GuiQuery<Boolean>() {
            @Override
            protected Boolean executeInEDT() {
                boolean enabled = item.isEnabled();
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return enabled;
            }
        });
    }
}
