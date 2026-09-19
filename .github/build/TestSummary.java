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
 * Turns Surefire's XML reports and JaCoCo's CSV into a GitHub Actions job summary,
 * so a run shows how many tests passed, what each one was called, and how much of
 * the application they covered - on the run's own page, instead of only in the raw
 * console log or a downloadable zip.
 *
 * <p>GitHub has no built-in JUnit report view. The alternative to this is a
 * third-party marketplace action; a ~150-line tool using nothing but the JDK keeps
 * CI dependency-free, which is the same reason .github/build/BuildJar.java exists.
 *
 * <p>Usage, from the repository root:
 *
 * <pre>
 *   java .github/build/TestSummary.java [reports-dir] [jacoco-csv]
 * </pre>
 *
 * Both arguments are optional; by default the JaCoCo CSV is found next to the
 * reports directory, where a "mvn test" in tests/ leaves it. Markdown goes to the
 * file named by GITHUB_STEP_SUMMARY, or to stdout when that variable isn't set, so
 * the same command is useful locally.
 *
 * <p>Exit code is always 0: this reports results, it does not judge them. Failing
 * the build on a failed test is Surefire's job, and it has already done it. Nor is
 * there a coverage threshold: the number is here to be looked at, and a build that
 * breaks on it only teaches people to write tests that move it.
 */
public class TestSummary {

    private static final String DEFAULT_REPORTS_DIR = "tests/target/surefire-reports";

    /** Per-class rows shown in the coverage breakdown; the rest are one summary line. */
    private static final int COVERAGE_ROWS = 15;

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

    /** One class's counters, as JaCoCo's CSV reports them. */
    private static final class Coverage {
        final String className;
        final long instructionsMissed, instructionsCovered;
        final long branchesMissed, branchesCovered;
        final long linesMissed, linesCovered;
        final long methodsMissed, methodsCovered;

        Coverage(String className, long[] c) {
            this.className = className;
            this.instructionsMissed = c[0]; this.instructionsCovered = c[1];
            this.branchesMissed     = c[2]; this.branchesCovered     = c[3];
            this.linesMissed        = c[4]; this.linesCovered        = c[5];
            // c[6]/c[7] are complexity, which says nothing a reader of a summary needs.
            this.methodsMissed      = c[8]; this.methodsCovered      = c[9];
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : DEFAULT_REPORTS_DIR);
        // target/surefire-reports and target/site/jacoco are siblings under target/.
        Path csv = args.length > 1 ? Paths.get(args[1])
                : dir.toAbsolutePath().getParent().resolve(Paths.get("site", "jacoco", "jacoco.csv"));

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

        write(render(dir, results) + renderCoverage(readCoverage(csv)));
    }

    /**
     * Reads JaCoCo's CSV export, one row per class. Returns an empty list when the
     * file isn't there - a run whose tests died before the agent wrote anything still
     * gets a test summary, just without a coverage section.
     */
    private static List<Coverage> readCoverage(Path csv) throws IOException {
        List<Coverage> classes = new ArrayList<>();
        if (!Files.isRegularFile(csv)) return classes;

        List<String> rows = Files.readAllLines(csv, StandardCharsets.UTF_8);
        for (String row : rows.subList(Math.min(1, rows.size()), rows.size())) {  // skip header
            String[] f = row.split(",");
            // GROUP,PACKAGE,CLASS then 10 counters. Anything shorter isn't a data row.
            if (f.length < 13) continue;
            long[] counters = new long[10];
            try {
                for (int i = 0; i < 10; i++) counters[i] = Long.parseLong(f[i + 3].trim());
            } catch (NumberFormatException notADataRow) {
                continue;
            }
            classes.add(new Coverage(f[2], counters));
        }
        return classes;
    }

    private static String renderCoverage(List<Coverage> classes) {
        if (classes.isEmpty()) return "";

        long[] totals = new long[8];
        for (Coverage c : classes) {
            totals[0] += c.instructionsMissed; totals[1] += c.instructionsCovered;
            totals[2] += c.branchesMissed;     totals[3] += c.branchesCovered;
            totals[4] += c.linesMissed;        totals[5] += c.linesCovered;
            totals[6] += c.methodsMissed;      totals[7] += c.methodsCovered;
        }

        StringBuilder md = new StringBuilder();
        md.append("\n### Coverage\n\n");
        md.append("`").append(percent(totals[5], totals[4])).append("` of lines, across ")
          .append(classes.size()).append(classes.size() == 1 ? " class" : " classes")
          .append(".\n\n");

        md.append("| | Covered | Total | |\n");
        md.append("|---|---:|---:|---:|\n");
        row(md, "Lines",        totals[5], totals[4]);
        row(md, "Branches",     totals[3], totals[2]);
        row(md, "Instructions", totals[1], totals[0]);
        row(md, "Methods",      totals[7], totals[6]);

        // Least-covered first: on a summary page the useful question is what ISN'T
        // tested, and that ordering answers it without reading the whole table.
        List<Coverage> byMissedLines = new ArrayList<>(classes);
        byMissedLines.sort(Comparator
                .comparingLong((Coverage c) -> -c.linesMissed)
                .thenComparing(c -> c.className));

        md.append("\n<details><summary>Per class, least covered first</summary>\n\n");
        md.append("| Class | Lines | Missed | Branches |\n");
        md.append("|---|---:|---:|---:|\n");
        int shown = Math.min(COVERAGE_ROWS, byMissedLines.size());
        for (Coverage c : byMissedLines.subList(0, shown)) {
            md.append("| `").append(cell(c.className)).append("` | ")
              .append(percent(c.linesCovered, c.linesMissed)).append(" | ")
              .append(c.linesMissed).append(" | ")
              .append(percent(c.branchesCovered, c.branchesMissed)).append(" |\n");
        }
        if (shown < byMissedLines.size()) {
            long rest = 0;
            for (Coverage c : byMissedLines.subList(shown, byMissedLines.size())) {
                rest += c.linesMissed;
            }
            md.append("| _and ").append(byMissedLines.size() - shown)
              .append(" more_ | | ").append(rest).append(" | |\n");
        }
        md.append("\nThe full line-by-line HTML report is in the `coverage-report` artifact.\n");
        md.append("\n</details>\n");
        return md.toString();
    }

    private static void row(StringBuilder md, String metric, long covered, long missed) {
        md.append("| ").append(metric).append(" | ").append(covered)
          .append(" | ").append(covered + missed)
          .append(" | ").append(percent(covered, missed)).append(" |\n");
    }

    /** "22.4%" - or "n/a" for something with nothing to cover, like an interface. */
    private static String percent(long covered, long missed) {
        long total = covered + missed;
        if (total == 0) return "n/a";
        // Locale.ROOT: a decimal comma inside a comma-free table cell is just confusing.
        return String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * covered / total);
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
