import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.assertj.swing.finder.JOptionPaneFinder;
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
    @DisplayName("automationInclude \"pdfhtml\" asks before converting, then references each page image")
    void includesAPdfAsHtmlWithImageReferences() throws Exception {
        awaitReadyCount(1);
        Path pdf = A4Pdf.writeTwoPages(workingDirectory().resolve("scan.pdf"));

        assertThat(JRock.automationBegin("pdfhtml test")).isNull();
        try {
            CompletableFuture<String> included = CompletableFuture.supplyAsync(
                    () -> JRock.automationInclude(pdf.toString(), "pdfhtml"));
            // The same question the include dialog's filter asks, and the same answer.
            press(JOptionPaneFinder.findOptionPane().withTimeout(DIALOG_TIMEOUT_MS)
                    .using(robot).yesButton());
            assertThat(included.get(60, TimeUnit.SECONDS)).isNull();

            assertThat(workingDirectory().resolve("scan").resolve("page1.html")).isRegularFile();
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
