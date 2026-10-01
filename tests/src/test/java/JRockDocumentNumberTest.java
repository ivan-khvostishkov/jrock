import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The number the prompt menu's Insert document number types in.
 * <p>
 * No GUI: the menu item only puts this string where the caret is.
 *
 * @see JRockGuiFixture for the tests that drive the real window
 */
class JRockDocumentNumberTest {

    @Test
    @DisplayName("a document number is ten digits, never starting with 0, and fresh each time")
    void isTenDigitsWithNoLeadingZero() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String number = JRock.documentNumber();
            assertThat(number).matches("[1-9][0-9]{9}");
            seen.add(number);
        }
        // One in nine billion each: a thousand draws repeating is a broken generator.
        assertThat(seen).hasSizeGreaterThan(990);
    }
}
