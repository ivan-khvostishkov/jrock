import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.swing.timing.Pause.pause;
import static org.assertj.swing.timing.Timeout.timeout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;
import javax.swing.JTextPane;

import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.core.Robot;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.edt.GuiTask;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.fixture.JTextComponentFixture;
import org.assertj.swing.timing.Condition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Launches the real application and reads its log pane the way a person would.
 * <p>
 * Deliberately in the DEFAULT package: JRock is itself in the default package, and
 * a class there cannot be imported from a named one.
 * <p>
 * Nothing is stubbed - this is the real application, doing what it really does at
 * startup. Only two things are arranged: no Bedrock API key (set blank in the POM),
 * which makes the startup model-list fetch report "(skipped)" instead of calling AWS,
 * so the test needs no network and no credentials; and a throwaway working directory,
 * so the app cannot restore a log written by an earlier run.
 */
class JRockStartupTest {

    /** Generous: the app is starting a JVM's worth of Swing on a cold CI runner. */
    private static final long WINDOW_TIMEOUT_MS = 30_000;

    /**
     * Longer than JRock's own 30s model-list timeout, so the test still passes on a
     * machine that does have BEDROCK_API_KEY set in the environment and therefore
     * really does call AWS before reporting Ready.
     */
    private static final long READY_TIMEOUT_SECONDS = 90;

    private Robot robot;
    private FrameFixture window;
    private Path throwawayHome;

    @BeforeEach
    void startTheApplication() throws Exception {
        // Give the app an empty directory of its own to keep its JRock/ folder in.
        //
        // This is about isolation, not tidiness: startup RESTORES a previous log from
        // disk, so a run that reused a directory would begin with the last run's
        // "Ready." already in the pane - and this test would then pass without the
        // app ever having written one. A fresh directory each time means the line
        // under test can only have come from this run.
        throwawayHome = Files.createTempDirectory("jrock-test-home");
        Field workingDir = JRock.class.getDeclaredField("workingDir");
        workingDir.setAccessible(true);
        workingDir.set(null, throwawayHome);

        robot = BasicRobot.robotWithCurrentAwtHierarchy();

        // The real entry point. main() shows the window on the EDT itself, so there
        // is nothing to arrange here - we just wait for the window to turn up.
        JRock.main(new String[0]);

        window = WindowFinder.findFrame(new GenericTypeMatcher<JFrame>(JFrame.class) {
            @Override
            protected boolean isMatching(JFrame frame) {
                // The title is "<working directory> - JRock", so match on the suffix
                // rather than on a folder name that depends on where tests run.
                return frame.getTitle() != null
                        && frame.getTitle().endsWith("JRock")
                        && frame.isShowing();
            }
        }).withTimeout(WINDOW_TIMEOUT_MS).using(robot);
    }

    @Test
    @DisplayName("the startup report ends with Ready., after the other session lines")
    void reportsReadyAmongTheStartupLines() {
        JTextComponentFixture logPane = logPane();

        // "Ready." is logged from the done() of the model-list SwingWorker, so it
        // arrives some time after the window does. Poll rather than sleep.
        pause(new Condition("the log to report \"Ready.\"") {
            @Override
            public boolean test() {
                return logPane.text().contains("Ready.");
            }
        }, timeout(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // Split on \R, every line terminator, not on "\n": what a Swing text
        // component hands back is not guaranteed to use the separator that went in.
        List<String> lines = Arrays.asList(logPane.text().split("\\R", -1));

        // A line of its own, not merely a substring somewhere.
        assertThat(lines).describedAs("the log pane's lines").contains("Ready.");

        // ...among the other lines of the session report, and after them: this is a
        // report that ends in Ready., not a pane that only ever said Ready.
        int workingDir = lineStartingWith(lines, "Working directory: ");
        assertThat(workingDir).describedAs("the \"Working directory: \" line").isNotNegative();
        assertThat(lineStartingWith(lines, "JRock version "))
                .describedAs("the \"JRock version \" line").isNotNegative();
        assertThat(lineStartingWith(lines, "Available models (mantle): "))
                .describedAs("the resolved \"Available models (mantle): \" line").isNotNegative();
        assertThat(lines.indexOf("Ready."))
                .describedAs("\"Ready.\" comes after the rest of the session report")
                .isGreaterThan(workingDir);
    }

    /** Index of the first line with the given prefix, or -1. */
    private static int lineStartingWith(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    /**
     * The log pane is the only non-editable text component in the window; the prompt
     * below it is an editable JTextArea, so the type alone already separates them.
     */
    private JTextComponentFixture logPane() {
        return window.textBox(new GenericTypeMatcher<JTextPane>(JTextPane.class) {
            @Override
            protected boolean isMatching(JTextPane pane) {
                return !pane.isEditable();
            }
        });
    }

    @AfterEach
    void stopTheApplication() throws IOException {
        if (window != null) {
            final java.awt.Frame frame = window.target();
            // dispose(), NOT a window-closing event: the app sets EXIT_ON_CLOSE, and a
            // close event would call System.exit and take the test JVM with it. Skipping
            // this altogether is not an option either - a live window keeps AWT's
            // non-daemon threads up and the forked JVM would never exit.
            GuiActionRunner.execute(new GuiTask() {
                @Override
                protected void executeInEDT() {
                    frame.dispose();
                }
            });
            window = null;
        }
        if (robot != null) {
            // Releases the screen lock without disposing windows, which we just did
            // ourselves for the reason above.
            robot.cleanUpWithoutDisposingWindows();
            robot = null;
        }
        deleteThrowawayHome();
    }

    /** Removes only the directory this test created itself, contents and all. */
    private void deleteThrowawayHome() throws IOException {
        if (throwawayHome == null || !Files.exists(throwawayHome)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(throwawayHome)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover file in the OS temp directory is not worth failing a
                    // passing test over.
                }
            });
        }
        throwawayHome = null;
    }
}
