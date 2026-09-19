import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Turns Surefire's XML reports into a GitHub Actions job summary, so a run shows
 * how many tests passed and what each one was called - on the run's own page,
 * instead of only in the raw console log or a downloadable zip.
 *
 * <p>GitHub has no built-in JUnit report view. The alternative to this is a
 * third-party marketplace action; a ~150-line tool using nothing but the JDK keeps
 * CI dependency-free, which is the same reason .github/build/BuildJar.java exists.
 *
 * <p>Usage, from the repository root:
 *
 * <pre>
 *   java .github/build/TestSummary.java [reports-dir]
 * </pre>
 *
 * Markdown goes to the file named by GITHUB_STEP_SUMMARY, or to stdout when that
 * variable isn't set, so the same command is useful locally.
 *
 * <p>Exit code is always 0: this reports results, it does not judge them. Failing
 * the build on a failed test is Surefire's job, and it has already done it.
 */
public class TestSummary {

    private static final String DEFAULT_REPORTS_DIR = "tests/target/surefire-reports";

    /** One row of the table. */
    private static final class Result {
        final String name;
        final String className;
        final String time;
        final String status;     // "passed", "failed", "error" or "skipped"
        final String detail;     // failure text, or null

        Result(String name, String className, String time, String status, String detail) {
            this.name = name;
            this.className = className;
            this.time = time;
            this.status = status;
            this.detail = detail;
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : DEFAULT_REPORTS_DIR);

        List<Result> results = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            List<Path> reports;
            try (Stream<Path> files = Files.list(dir)) {
                reports = files
                        .filter(p -> p.getFileName().toString().endsWith(".xml"))
                        .sorted()
                        .collect(java.util.stream.Collectors.toList());
            }
            for (Path report : reports) {
                collect(report, results);
            }
        }

        // Stable, readable order: failures first so they're the first thing seen.
        results.sort(Comparator
                .comparingInt((Result r) -> r.status.equals("passed") ? 1 : 0)
                .thenComparing(r -> r.className)
                .thenComparing(r -> r.name));

        write(render(dir, results));
    }

    /** Reads one Surefire XML report, appending a Result per {@code <testcase>}. */
    private static void collect(Path report, List<Result> into) throws Exception {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        NodeList cases = builder.parse(report.toFile()).getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element testCase = (Element) cases.item(i);

            // A testcase carries at most one of these children; no child means it
            // passed. "error" is an unexpected exception, "failure" a failed
            // assertion - worth telling apart, they usually mean different things.
            String status = "passed";
            String detail = null;
            for (String kind : new String[] { "failure", "error", "skipped" }) {
                NodeList found = testCase.getElementsByTagName(kind);
                if (found.getLength() == 0) continue;
                Element first = (Element) found.item(0);
                status = kind.equals("skipped") ? "skipped" : kind;
                String message = first.getAttribute("message");
                String body = first.getTextContent();
                detail = !message.isEmpty() ? message : (body == null ? "" : body.trim());
                break;
            }

            into.add(new Result(
                    testCase.getAttribute("name"),
                    testCase.getAttribute("classname"),
                    testCase.getAttribute("time"),
                    status,
                    detail));
        }
    }

    private static String render(Path dir, List<Result> results) {
        long passed  = results.stream().filter(r -> r.status.equals("passed")).count();
        long failed  = results.stream().filter(r -> r.status.equals("failed")
                                                 || r.status.equals("failure")).count();
        long errored = results.stream().filter(r -> r.status.equals("error")).count();
        long skipped = results.stream().filter(r -> r.status.equals("skipped")).count();

        StringBuilder md = new StringBuilder();
        if (results.isEmpty()) {
            md.append("### No tests were run\n\n")
              .append("No Surefire XML reports were found in `").append(dir).append("`.\n");
            return md.toString();
        }

        boolean allGood = failed == 0 && errored == 0;
        md.append("### ").append(allGood ? "All tests passed" : "Test failures")
          .append("\n\n");

        md.append(passed).append(" passed");
        if (failed > 0)  md.append(" &middot; ").append(failed).append(" failed");
        if (errored > 0) md.append(" &middot; ").append(errored).append(" errored");
        if (skipped > 0) md.append(" &middot; ").append(skipped).append(" skipped");
        md.append(" &middot; ").append(results.size())
          .append(results.size() == 1 ? " test total\n\n" : " tests total\n\n");

        md.append("| | Test | Class | Time |\n");
        md.append("|---|---|---|---|\n");
        for (Result r : results) {
            md.append("| ").append(icon(r.status))
              .append(" | ").append(cell(r.name))
              .append(" | `").append(cell(r.className))
              .append("` | ").append(cell(r.time)).append("s |\n");
        }

        // The point of putting failures here rather than leaving them in the log:
        // the reason is visible without opening anything.
        for (Result r : results) {
            if (r.detail == null || r.detail.isEmpty()) continue;
            md.append("\n<details><summary>").append(icon(r.status)).append(' ')
              .append(cell(r.name)).append("</summary>\n\n```\n")
              .append(r.detail).append("\n```\n\n</details>\n");
        }

        return md.toString();
    }

    private static String icon(String status) {
        switch (status) {
            case "passed":  return ":white_check_mark:";
            case "skipped": return ":fast_forward:";
            case "error":   return ":boom:";
            default:        return ":x:";
        }
    }

    /** Keeps a value from breaking out of its table cell. */
    private static String cell(String text) {
        if (text == null) return "";
        return text.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    /** Appends to the job summary when running in Actions, prints it otherwise. */
    private static void write(String markdown) throws IOException {
        String target = System.getenv("GITHUB_STEP_SUMMARY");
        if (target == null || target.isEmpty()) {
            PrintStream out = new PrintStream(System.out, true, "UTF-8");
            out.print(markdown);
            return;
        }
        Files.write(Paths.get(target), markdown.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }
}
