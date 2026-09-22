// BuildJar - deterministic (reproducible) JAR packer for JRock, run with:
//     java BuildJar.java
//
// A plain `jar` invocation is NOT reproducible on JDK 11 (entry timestamps and
// order vary), and `jar --date` only exists on JDK 19+. So this utility repacks
// the compiled classes into a JAR (a JAR is just a ZIP) deterministically:
//   - entries sorted by name
//   - every entry's timestamp fixed to a constant (SOURCE_DATE_EPOCH)
//   - fixed DEFLATE level, fixed metadata (no volatile fields)
//   - a hand-written manifest (no auto-generated Created-By/Build-Jdk lines)
//
// The result is byte-for-byte identical from the same source on any machine/OS,
// given the same JDK build (which the CI pins). Also emits SHA-256 and MD5
// checksum files and a source ZIP.
//
// Prerequisite: the classes are already compiled into the "out" directory
// (the workflow runs `javac -encoding UTF-8 -d out JRock.java` first).

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BuildJar {

    private static final String OUT_DIR    = "out";
    private static final String JAR        = "jrock.jar";
    private static final String SRC        = "JRock.java";
    private static final String SRC_ZIP    = "jrock-src.zip";
    private static final String MAIN_CLASS = "JRock";

    // Shipped alongside the source: the sample prompts. They are part of what JRock
    // is for rather than part of how it is built, and a download that has the
    // application but none of them starts from an empty prompt.
    private static final String SAMPLES_DIR = "automation-samples";

    // Fixed timestamp so output never depends on build time or machine.
    // 2026-01-01T00:00:00Z. (Reproducible-builds convention: SOURCE_DATE_EPOCH.)
    private static final long SOURCE_DATE_EPOCH_MS = 1767225600L * 1000L;

    public static void main(String[] args) throws Exception {
        Path outDir = Paths.get(OUT_DIR);
        if (!Files.isDirectory(outDir)) {
            System.err.println("Expected compiled classes in '" + OUT_DIR
                    + "'. Run: javac -encoding UTF-8 -d " + OUT_DIR + " " + SRC);
            System.exit(1);
        }

        // Collect .class files as forward-slash relative paths, sorted.
        List<Path> classFiles = new ArrayList<>();
        try (var stream = Files.walk(outDir)) {
            stream.filter(p -> p.toString().endsWith(".class"))
                  .forEach(classFiles::add);
        }
        List<String[]> entries = new ArrayList<>(); // {relName, absPath}
        for (Path p : classFiles) {
            String rel = outDir.relativize(p).toString().replace('\\', '/');
            entries.add(new String[] { rel, p.toString() });
        }
        entries.sort(Comparator.comparing(e -> e[0]));

        // Hand-written manifest (CRLF-terminated, ends with a blank line).
        String manifest = "Manifest-Version: 1.0\r\n"
                        + "Main-Class: " + MAIN_CLASS + "\r\n"
                        + "\r\n";

        // Build the JAR deterministically.
        buildZip(Paths.get(JAR), () -> {
            List<Object[]> zipEntries = new ArrayList<>(); // {name, bytes}
            zipEntries.add(new Object[] { "META-INF/MANIFEST.MF",
                    manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8) });
            for (String[] e : entries) {
                zipEntries.add(new Object[] { e[0], Files.readAllBytes(Paths.get(e[1])) });
            }
            return zipEntries;
        });

        byte[] jarBytes = Files.readAllBytes(Paths.get(JAR));
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(jarBytes));
        String md5 = hex(MessageDigest.getInstance("MD5").digest(jarBytes));
        Files.write(Paths.get(JAR + ".sha256"), (sha + "  " + JAR + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(Paths.get(JAR + ".md5"), (md5 + "  " + JAR + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        System.out.println("Built " + JAR + " (" + jarBytes.length + " bytes)");
        System.out.println("SHA-256: " + sha);
        System.out.println("MD5:     " + md5);

        // The sample prompts, as forward-slash names under automation-samples/ and
        // sorted for the same reason the classes are: the zip has to be reproducible
        // too, and a directory listing's order is the file system's business.
        List<String[]> samples = new ArrayList<>();   // {relName, absPath}
        Path samplesDir = Paths.get(SAMPLES_DIR);
        if (Files.isDirectory(samplesDir)) {
            try (var stream = Files.walk(samplesDir)) {
                stream.filter(Files::isRegularFile).forEach(p -> samples.add(new String[] {
                        SAMPLES_DIR + "/"
                                + samplesDir.relativize(p).toString().replace('\\', '/'),
                        p.toString() }));
            }
            samples.sort(Comparator.comparing(e -> e[0]));
        } else {
            System.out.println("No " + SAMPLES_DIR + "/ directory - none packed");
        }

        // Zip the source file too, with the samples beside it.
        buildZip(Paths.get(SRC_ZIP), () -> {
            List<Object[]> zipEntries = new ArrayList<>();
            zipEntries.add(new Object[] { SRC, Files.readAllBytes(Paths.get(SRC)) });
            for (String[] e : samples) {
                zipEntries.add(new Object[] { e[0], Files.readAllBytes(Paths.get(e[1])) });
            }
            return zipEntries;
        });
        System.out.println("Wrote " + SRC_ZIP + " (" + SRC + " + " + samples.size()
                + " sample(s))");
    }

    // Functional supplier of the entries (name -> bytes) to write, in order.
    private interface Entries { List<Object[]> get() throws IOException; }

    // Writes a deterministic ZIP: caller-provided order, fixed timestamps, fixed
    // DEFLATE level. Uses a fixed Deflater so compressed bytes are stable.
    private static void buildZip(Path target, Entries entries) throws IOException {
        FileTime fixed = FileTime.fromMillis(SOURCE_DATE_EPOCH_MS);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
            zos.setLevel(Deflater.BEST_COMPRESSION);   // fixed level
            for (Object[] e : entries.get()) {
                String name = (String) e[0];
                byte[] data = (byte[]) e[1];
                ZipEntry ze = new ZipEntry(name);
                ze.setMethod(ZipEntry.DEFLATED);
                ze.setTime(SOURCE_DATE_EPOCH_MS);
                ze.setCreationTime(fixed);
                ze.setLastModifiedTime(fixed);
                ze.setLastAccessTime(fixed);
                CRC32 crc = new CRC32();
                crc.update(data);
                ze.setCrc(crc.getValue());
                zos.putNextEntry(ze);
                zos.write(data);
                zos.closeEntry();
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
