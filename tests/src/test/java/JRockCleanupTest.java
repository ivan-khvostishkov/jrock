import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What <em>Cleanup JRock files...</em> would delete, and - the point of the test - what it
 * would not.
 * <p>
 * The selection is tested rather than the dialogs: a cleanup is three message boxes around
 * one question, "which of these is older than N days", and that question is
 * {@code olderThan}. It is asked of four places, and the dangerous one is the working
 * directory, which holds the operator's own documents and their own folders. A regression
 * there deletes somebody's files, so the assertion that matters here is a negative one.
 * <p>
 * No window: nothing in here starts the application.
 *
 * @see JRockConfigureTest for the settings this sits beside
 */
class JRockCleanupTest {

    /** Thirty days back and ten days back: the fixture's "old" and the default cut. */
    private static final long DAY = 24L * 60 * 60 * 1000;

    @TempDir
    Path root;

    @Test
    @DisplayName("the sweep takes what JRock wrote, in all four places, and nothing of the operator's")
    void sweepsJRocksOwnFilesAndLeavesTheOperatorsAlone() throws Exception {
        long now = System.currentTimeMillis();
        long old = now - 30 * DAY;
        long cut = now - 10 * DAY;

        Path jrock = Files.createDirectories(root.resolve("__JRock"));
        Path includes = Files.createDirectories(jrock.resolve("includes"));
        Path urls = Files.createDirectories(jrock.resolve("urls"));
        Path wav = Files.createDirectories(jrock.resolve("wav"));

        // JRock's own material, old and new, in each of the places it writes to.
        aged(includes.resolve("scan.pdf.gs.001.png"), old, 100);
        aged(includes.resolve("quarterly.docx.image1.png"), old, 60);
        aged(includes.resolve("yesterday.rtf.md"), now, 50);
        aged(urls.resolve("article.html"), old, 10);
        aged(urls.resolve("today.json"), now, 10);
        aged(wav.resolve("narration-2609-0101.wav"), old, 20);
        aged(wav.resolve("recording-2610-2359.wav"), now, 20);
        aged(root.resolve("jrock-backup-2609010101.zip"), old, 400);
        aged(root.resolve("jrock-backup-2610230901.zip"), now, 400);

        // An xpdf reading of a PDF: a folder of pages, which is one reading and goes whole.
        Path reading = Files.createDirectories(includes.resolve("report.pdf.html"));
        aged(reading.resolve("page1.html"), old, 200);
        aged(reading.resolve("page1.png"), old, 300);
        Files.setLastModifiedTime(reading, FileTime.fromMillis(old));

        // And the operator's own things, all of them old enough to be swept if anything
        // here were careless: a document beside the backups, and a folder of scans.
        Path ownFile = aged(root.resolve("2026-06-14-DHL-Parcel.pdf"), old, 999);
        Path ownFolder = Files.createDirectories(root.resolve("My Scans"));
        Path inOwnFolder = aged(ownFolder.resolve("scan0167.pdf"), old, 999);
        Files.setLastModifiedTime(ownFolder, FileTime.fromMillis(old));

        // 1. __JRock/includes/ is the one place a FOLDER counts, because a folder there is
        //    one conversion's output.
        assertThat(names(includes, cut, null, true))
                .describedAs("swept from __JRock/includes/")
                .containsExactlyInAnyOrder("quarterly.docx.image1.png",
                        "report.pdf.html", "scan.pdf.gs.001.png");

        // 2. The other two folders of JRock's own: by age, files only.
        assertThat(names(urls, cut, null, false))
                .describedAs("swept from __JRock/urls/").containsExactly("article.html");
        assertThat(names(wav, cut, null, false))
                .describedAs("swept from __JRock/wav/").containsExactly("narration-2609-0101.wav");

        // 3. THE WORKING DIRECTORY, which is the one that matters: the old backup zip and
        //    nothing else. Not the operator's PDF - it is old, and it is not a backup -
        //    and not their folder, which is not even eligible to be listed.
        assertThat(names(root, cut, "jrock-backup-", false))
                .describedAs("swept from the working directory")
                .containsExactly("jrock-backup-2609010101.zip");

        // 4. A folder's size is everything under it, which is what the question shows
        //    before anything is deleted.
        Method sizeOfTree = JRock.class.getDeclaredMethod("sizeOfTree", Path.class);
        sizeOfTree.setAccessible(true);
        assertThat((Long) sizeOfTree.invoke(null, reading))
                .describedAs("the bytes under one xpdf reading").isEqualTo(500L);
        assertThat((Long) sizeOfTree.invoke(null, ownFile))
                .describedAs("a plain file's own size").isEqualTo(999L);

        // 5. And nothing above has touched anything: the sweep only lists.
        assertThat(ownFile).describedAs("the operator's document").isRegularFile();
        assertThat(inOwnFolder).describedAs("a file in the operator's folder").isRegularFile();
        assertThat(ownFolder).describedAs("the operator's folder").isDirectory();
    }

    @Test
    @DisplayName("a folder JRock has not written to yet sweeps as empty rather than failing")
    void sweepsAFolderThatIsNotThereAsEmpty() throws Exception {
        long cut = System.currentTimeMillis();
        assertThat(names(root.resolve("__JRock").resolve("wav"), cut, null, false))
                .describedAs("a wav/ nothing has written to").isEmpty();
    }

    /** The names {@code olderThan} picks out of dir, which is the whole of the selection. */
    @SuppressWarnings("unchecked")
    private List<String> names(Path dir, long cut, String prefix, boolean withFolders)
            throws Exception {
        Method olderThan = JRock.class.getDeclaredMethod("olderThan", Path.class, long.class,
                String.class, boolean.class, Class.forName("JRock$LogView"));
        olderThan.setAccessible(true);
        List<Path> found =
                (List<Path>) olderThan.invoke(null, dir, cut, prefix, withFolders, null);
        List<String> names = new ArrayList<>();
        for (Path p : found) names.add(p.getFileName().toString());
        return names;
    }

    /** A file of that many bytes, last modified then. */
    private static Path aged(Path file, long when, int bytes) throws IOException {
        Files.write(file, new byte[bytes]);
        Files.setLastModifiedTime(file, FileTime.fromMillis(when));
        assertThat(Files.exists(file, LinkOption.NOFOLLOW_LINKS)).isTrue();
        return file;
    }
}
