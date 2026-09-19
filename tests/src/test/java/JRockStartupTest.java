import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reads the startup report out of the log pane the way a person would.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockStartupTest extends JRockGuiFixture {

    @Test
    @DisplayName("the startup report ends with Ready., after the other session lines")
    void reportsReadyAmongTheStartupLines() {
        awaitReadyCount(1);

        List<String> lines = logLines();

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
}
