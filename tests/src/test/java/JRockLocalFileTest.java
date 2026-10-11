import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the log's <em>Open local file</em> will take as a name, and what greys it out.
 * <p>
 * The whole selection is the name - a path holds spaces, and nothing in running text says
 * where one ends - so this is the rule that decides whether the item is live: what the
 * text looks like, on this machine. Nothing is opened here and no file is touched; whether
 * the name is really there is the click's question, not the menu's.
 */
class JRockLocalFileTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Test
    @DisplayName("a path for this machine is taken whole, quotes and stray spaces aside")
    void takesALocalPath() throws Exception {
        String path = WINDOWS ? "C:\\work\\Quarterly report.docx" : "/work/Quarterly report.docx";

        // Spaces in the name are the reason the whole selection is the name.
        assertThat(localPathIn(path)).isEqualTo(path);
        // A selection dragged over the line takes the newline after it, and often a
        // space or two; a path quoted in the text is still that path.
        assertThat(localPathIn("  " + path + "\n")).isEqualTo(path);
        assertThat(localPathIn("\"" + path + "\"")).isEqualTo(path);

        // A folder is a name like any other: the click opens the file manager at it.
        String folder = WINDOWS ? "C:\\work" : "/work";
        assertThat(localPathIn(folder)).isEqualTo(folder);

        if (WINDOWS) {
            // Forward slashes are a Windows path too, and so is a UNC share.
            assertThat(localPathIn("C:/work/report.docx")).isEqualTo("C:/work/report.docx");
            assertThat(localPathIn("\\\\nas\\share\\report.docx"))
                    .isEqualTo("\\\\nas\\share\\report.docx");
        }
    }

    @Test
    @DisplayName("anything that is not a local name greys the item out")
    void refusesWhatIsNotALocalPath() throws Exception {
        assertThat(localPathIn(null)).isNull();
        assertThat(localPathIn("")).isNull();
        assertThat(localPathIn("   \n  ")).isNull();

        // An address is the other menu item's business.
        assertThat(localPathIn("https://example.org/a/report.docx")).isNull();
        assertThat(localPathIn("file:///C:/work/report.docx")).isNull();

        // A relative name says nothing about where it is relative to.
        assertThat(localPathIn("report.docx")).isNull();
        assertThat(localPathIn("includes/report.docx")).isNull();

        // More than one line is never one name, however it was selected.
        assertThat(localPathIn((WINDOWS ? "C:\\a.txt\nC:\\b.txt" : "/a.txt\n/b.txt"))).isNull();

        // A path for the OTHER kind of machine: the item says "local", and means it.
        assertThat(localPathIn(WINDOWS ? "/usr/share/report.docx" : "C:\\work\\report.docx"))
                .isNull();

        if (WINDOWS) {
            // Characters no name on Windows can hold, and a second colon.
            assertThat(localPathIn("C:\\work\\*.docx")).isNull();
            assertThat(localPathIn("C:\\work\\re|port.docx")).isNull();
            assertThat(localPathIn("C:\\work\\C:\\report.docx")).isNull();
            // A sentence that merely starts with a drive is not a path either: the
            // quotation marks inside it are not name characters.
            assertThat(localPathIn("C:\\work said \"no\"")).isNull();
        }
    }

    private static String localPathIn(String selection) throws Exception {
        Method m = JRock.class.getDeclaredMethod("localPathIn", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, selection);
    }
}
