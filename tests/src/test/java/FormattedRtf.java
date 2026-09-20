import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a small RTF document, by hand, and states the Markdown it has to become.
 * <p>
 * Hand-rolled rather than saved out of a word processor, for the same reason
 * {@link A4Pdf} is: the input has to be exactly the thing under test. Every control
 * word here is one JRock's conversion has an opinion about - a font size that makes a
 * heading, {@code \b} and {@code \i}, a Word-style bullet ({@code \listtext} with
 * {@code \'b7} in Symbol), a non-ASCII character written the way RTF writes one
 * ({@code \'fc} in code page 1252), and an asterisk that is text and not markup.
 * <p>
 * {@link #MARKDOWN} is the whole expected output, not a fragment: what a conversion
 * leaves out or adds matters as much as what it gets right.
 * <p>
 * NOT named *Test, so Surefire doesn't try to run it.
 */
final class FormattedRtf {

    /**
     * The document. Body text is {@code \fs24} (12 pt), so the 24 pt title and the
     * 16 pt subheading are 12 and 4 points above the body - an h1 and an h2 by
     * JRock's thresholds, and neither of them merely "large".
     */
    private static final String RTF = String.join("\n",
            "{\\rtf1\\ansi\\ansicpg1252\\deff0",
            "{\\fonttbl{\\f0\\fswiss Calibri;}{\\f1\\fnil Symbol;}}",
            "\\pard\\b\\fs48 Quarterly Report\\b0\\fs24\\par",
            "\\pard\\b\\fs32 Overview\\b0\\fs24\\par",
            "\\pard Prepared by the \\b finance\\b0  team in M\\'fcnchen, \\i in March\\i0 .\\par",
            "\\pard{\\listtext\\f1\\'b7\\tab}Revenue up\\par",
            "\\pard{\\listtext\\f1\\'b7\\tab}Costs flat\\par",
            "\\pard A literal * and 2*3 stay text.\\par",
            "}");

    /**
     * The Markdown {@link #RTF} converts to, byte for byte.
     * <p>
     * Note what is <em>not</em> here: no {@code **} inside the headings (the bold they
     * are set in is what identified them, so saying it twice is noise), and the
     * asterisks of the last line are escaped - they were characters someone typed, not
     * emphasis. The umlaut is a character, written out as UTF-8.
     */
    static final String MARKDOWN = String.join("\n",
            "# Quarterly Report",
            "",
            "## Overview",
            "",
            "Prepared by the **finance** team in München, *in March*.",
            "",
            "- Revenue up",
            "- Costs flat",
            "",
            "A literal \\* and 2\\*3 stay text.",
            "");

    private FormattedRtf() { }

    /** Writes the document to {@code target} and returns it. */
    static Path write(Path target) throws IOException {
        // ASCII, deliberately: an RTF file is ASCII by definition - its own \'hh
        // escapes are how it carries anything else - so the platform default encoding
        // must not get a say in what the reader is handed.
        Files.write(target, RTF.getBytes(StandardCharsets.US_ASCII));
        return target;
    }
}
