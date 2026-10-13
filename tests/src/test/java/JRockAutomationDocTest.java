import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two automation calls JRockDocInventory's first pass is made of: a PDF included as
 * an HTML folder with its page images referenced, and a reply exported as a DOCX with
 * the pictures it refers to.
 * <p>
 * Through the API as an automation calls it, on a thread of its own - the calls block -
 * against the real window. Nothing is sent: no model is involved in either.
 */
class JRockAutomationDocTest extends JRockGuiFixture {

    @Test
    @DisplayName("automationExportPromptDocx places the image a ![...](name) in the prompt names")
    void exportsMarkdownWithItsPicturesAsADocx() throws Exception {
        awaitReadyCount(1);
        Path png = workingDirectory().resolve("logo.png");
        assertThat(ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB),
                "png", png.toFile())).isTrue();

        assertThat(JRock.automationBegin("export test")).isNull();
        try {
            // THE FOLDER'S SETTING, not an argument: nothing can force one include to
            // be read differently from the rest any more, so what this test needs is the
            // setting a person would tick in Configure - "Write a Markdown reference".
            field("includeMarkdownRefs").set(null, true);
            assertThat(JRock.automationInclude(png)).isNull();
            String prompt = JRock.automationPromptText();
            java.util.regex.Matcher ref =
                    java.util.regex.Pattern.compile("!\\[[^\\]]*\\]\\(([^)]+)\\)").matcher(prompt);
            assertThat(ref.find()).describedAs("a reference in " + prompt).isTrue();

            // THROUGH THE PROMPT, which is where an automation's own text goes: the API
            // takes no Markdown from anywhere any more, exporting only what the window is
            // showing - the log's selection, or this.
            Path docx = workingDirectory().resolve("letter.docx");
            assertThat(JRock.automationSetPrompt(
                    "# Letter\n\n![](" + ref.group(1) + ")\n\nDear reader,\n")).isNull();
            assertThat(JRock.automationExportPromptDocx(docx.toString())).isNull();

            Map<String, byte[]> parts = unzip(Files.readAllBytes(docx));
            assertThat(parts).containsKey("word/media/image1.png");
            assertThat(parts.get("word/media/image1.png")).isEqualTo(Files.readAllBytes(png));
            assertThat(new String(parts.get("word/document.xml"), "UTF-8"))
                    .contains("Letter").contains("Dear reader,");
        } finally {
            JRock.automationEnd("done");
        }
    }

    @Test
    @DisplayName("a PDF set to xpdf HTML converts without asking, then references each page image")
    void includesAPdfAsHtmlWithImageReferences() throws Exception {
        awaitReadyCount(1);
        Path pdf = A4Pdf.writeTwoPages(workingDirectory().resolve("scan.pdf"));

        assertThat(JRock.automationBegin("pdfhtml test")).isNull();
        try {
            // NOTHING IS PRESSED HERE, and the call returning is the assertion: the
            // include's two questions - convert into a new folder, or include a folder
            // already there - are answered by JRock itself while an automation holds the
            // window, because an automation that stops for a dialog is not an automation
            // (the doc inventory flow is usually driven by an agent, with nobody at the
            // keyboard). A modal question would block this call for ever, so a test that
            // presses no button and still gets its answer is a test that nothing asked.
            // Likewise: a PDF as an xpdf HTML folder, with the references that make each
            // page image referable - two ticks in Configure, set here as the dialog sets
            // them.
            field("pdfIncludeMode").set(null, "html");
            field("includeMarkdownRefs").set(null, true);
            assertThat(JRock.automationInclude(pdf)).isNull();

            assertThat(workingDirectory().resolve("scan.pdf.html").resolve("page1.html"))
                    .isRegularFile();
            // And the log says a question was answered on the operator's behalf, which is
            // the other half of the bargain: nobody was asked, and it is written down.
            assertThat(logPane().text()).contains("answered by the automation");
            String prompt = JRock.automationPromptText();
            // Every page image with its reference above its token. Both pages of this PDF
            // have the same blank background, and an image already included is not
            // included twice, so there may be one.
            int images = countOccurrences(prompt, "@img ");
            assertThat(images).isGreaterThanOrEqualTo(1);
            assertThat(countOccurrences(prompt, "![Image: ")).isEqualTo(images);
            assertThat(countOccurrences(prompt, "@txt ")).isGreaterThanOrEqualTo(3);
        } finally {
            JRock.automationEnd("done");
        }
    }

    @Test
    @DisplayName("narration reads the log's selection, so nothing is said that is not written down")
    void narratesWhatIsWrittenDownAndNothingElse() throws Exception {
        awaitReadyCount(1);
        assertThat(JRock.automationBegin("narrate test")).isNull();
        try {
            String words = "Something went wrong. Should I try again?";

            // With nothing selected there is nothing to read: narration takes no text any
            // more, so an agent cannot speak words that appear nowhere. (What it says it
            // cannot do depends on the machine - a build runner has no voice at all - so
            // what is asserted is the refusal, not its wording.)
            assertThat(JRock.automationNarrate())
                    .describedAs("narrating with nothing selected").isNotNull();

            // The three steps an agent takes instead of handing over a string.
            // Named, because a note takes its author now: the name is written in
            // front of the words, and the words alone are what gets selected.
            assertThat(JRock.automationNote(words)).isNull();
            assertThat(JRock.automationSelectInLog(words)).isNull();
            assertThat(logPane().text())
                    .describedAs("what was to be said is in the transcript").contains(words);
            assertThat(logPane().target().getSelectedText())
                    .describedAs("and it is what is selected").contains(words);

            // And the selection is let go of afterwards, or the log would stop following
            // the conversation it has just read out.
            assertThat(JRock.automationClearLogSelection()).isNull();
            assertThat(logPane().target().getSelectedText())
                    .describedAs("the selection after clearing it").isNull();

            // A line that was never logged cannot be selected, so it cannot be said.
            assertThat(JRock.automationSelectInLog("something nobody ever wrote"))
                    .describedAs("selecting text that is not in the log").isNotNull();
        } finally {
            JRock.automationEnd("done");
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                parts.put(entry.getName(), out.toByteArray());
            }
        }
        return parts;
    }
}
