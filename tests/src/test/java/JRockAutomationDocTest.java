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
    @DisplayName("automationExportDocx places the image a ![...](name) in the Markdown names")
    void exportsMarkdownWithItsPicturesAsADocx() throws Exception {
        awaitReadyCount(1);
        Path png = workingDirectory().resolve("logo.png");
        assertThat(ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB),
                "png", png.toFile())).isTrue();

        assertThat(JRock.automationBegin("export test")).isNull();
        try {
            assertThat(JRock.automationInclude(png.toString(), "imgref")).isNull();
            String prompt = JRock.automationPromptText();
            java.util.regex.Matcher ref =
                    java.util.regex.Pattern.compile("!\\[[^\\]]*\\]\\(([^)]+)\\)").matcher(prompt);
            assertThat(ref.find()).describedAs("a reference in " + prompt).isTrue();

            Path docx = workingDirectory().resolve("letter.docx");
            assertThat(JRock.automationExportDocx(
                    "# Letter\n\n![](" + ref.group(1) + ")\n\nDear reader,\n", docx.toString()))
                    .isNull();

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
    @DisplayName("automationInclude \"pdfhtml\" converts without asking, then references each page image")
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
            assertThat(JRock.automationInclude(pdf.toString(), "pdfhtml")).isNull();

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
