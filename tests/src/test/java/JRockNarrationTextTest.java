import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What <em>Narrate selected text</em> hands the Windows voice: the selection with its
 * Markdown taken out, and the language to pick a voice in.
 * <p>
 * No GUI and no speech: the voice itself is Windows' own and is not what is under test
 * here. What is, is that a voice is never asked to read "number number" for a heading
 * or twelve hex digits for an include token, and that a Russian answer asks for a
 * Russian voice rather than an English one spelling it out.
 *
 * @see JRockGuiFixture for the tests that drive the real window
 */
class JRockNarrationTextTest {

    @Test
    @DisplayName("Markdown markers are dropped, the words kept, include tokens left out")
    void stripsMarkdownForTheVoice() {
        String markdown = String.join("\n",
                "## Заголовок",
                "**Привет**, мир! Это [ссылка](https://example.org) и `код`.",
                "@txt 1f3a9c0b7e42",
                "![](0123456789ab)",
                "> - quoted *item*",
                "---",
                "| a | b |",
                "|---|:-:|",
                "| 1 | 2 |");
        assertThat(JRock.narrationText(markdown)).isEqualTo(String.join("\n",
                "Заголовок",
                "Привет, мир! Это ссылка и код.",
                "",
                "quoted item",
                "a, b",
                "1, 2"));
    }

    @Test
    @DisplayName("the voice's language follows the script most letters are written in")
    void picksTheLanguageFromTheScript() {
        assertThat(JRock.narrationLanguage("Hello, world")).isNull();
        assertThat(JRock.narrationLanguage("Привет, мир")).isEqualTo("ru");
        // A few English words in a Russian answer do not make it English...
        assertThat(JRock.narrationLanguage("Файл README открыт в редакторе")).isEqualTo("ru");
        // ...nor a Russian word an English one.
        assertThat(JRock.narrationLanguage("The word for peace is мир")).isNull();
        assertThat(JRock.narrationLanguage("Καλημέρα")).isEqualTo("el");
        // Kana is what tells Japanese from Chinese, however many kanji come with it.
        assertThat(JRock.narrationLanguage("日本語のテキスト")).isEqualTo("ja");
        assertThat(JRock.narrationLanguage("中文文本")).isEqualTo("zh");
        assertThat(JRock.narrationLanguage("1234 !?")).isNull();
    }
}
