import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * How long JRock takes to come up, and what it spends that time on.
 *
 * <pre>
 *     java -XX:StartFlightRecording=settings=profile,filename=startup.jfr,dumponexit=true \
 *          -cp out StartupProfile [workingDir]
 * </pre>
 *
 * <p>WHY THIS EXISTS, AND WHAT IT IS A PROXY FOR. The window is blank for several seconds
 * in a browser tab, which is CheerpJ interpreting bytecode that a desktop JVM would have
 * compiled. CheerpJ's own cost is not ours to fix - but the WORK is: every class JRock
 * loads is a class CheerpJ has to fetch and interpret, and every widget it builds before
 * the first paint is time the reader looks at an empty rectangle. So this measures the
 * work on a desktop JVM, where it can be measured properly, on the understanding that
 * whatever is heavy here is heavier there by a large constant.
 *
 * <p>CLASSES LOADED is therefore reported as prominently as the milliseconds. On a desktop
 * JVM loading a class is cheap enough to ignore; under CheerpJ it is a fetch and a parse,
 * and the count is the number that moves the browser's blank seconds.
 *
 * <p>THE MODEL LIST IS NOT FETCHED. The working folder gets an empty
 * {@code JRock/bedrock-key.txt}, which is how the test suite stops the startup model-list
 * call: with no key there is nobody to ask. A profile that included an HTTP round trip to
 * Bedrock would measure the network and call it startup.
 *
 * <p>The milestones are printed as a table and written to {@code startup-milestones.txt}
 * beside the recording, so a CI run uploads both and they can be read together.
 */
public final class StartupProfile {

    /**
  * Longest to wait for the automation API, which is NOT the headline measurement and is
  * kept short on purpose. "Ready" means a key is configured and the model list is in
  * hand - a question about credentials and the network, not about how long a window takes
  * to appear. The blank-window metric is the window being shown and the event thread
  * draining, both of which happen long before.
  */
    private static final long READY_MS = 5_000;

    /** Longest to wait for the window to become visible. */
    private static final long SHOWING_MS = 60_000;

    private static final List<String> lines = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Instant jvm = jvmStart();
        long t0 = System.nanoTime();

        Path dir = args.length > 0 ? Path.of(args[0])
                : Files.createTempDirectory("jrock-startup-");
        // "__JRock", which is where this version keeps its own files: a folder called
        // "JRock" is renamed to it on startup, and the key written into the old name is
        // then looked for under the new one and not found.
        Files.createDirectories(dir.resolve("__JRock"));
        // Empty, not absent: an absent key file is a reason to open the Configure dialog,
        // and a dialog on top of the window is not the startup being measured. Empty also
        // means no model list is fetched, so no HTTP round trip is counted as startup.
        Files.write(dir.resolve("__JRock").resolve("bedrock-key.txt"), new byte[0]);

        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

        long beforeClasses = classes.getTotalLoadedClassCount();
        say("working folder", dir.toString());
        say("classes loaded before JRock", beforeClasses);

        // 1. main() - the window is built and shown inside this call.
        long a = System.nanoTime();
        JRock.main(new String[] { "--working-dir", dir.toString() });
        long afterMain = System.nanoTime();

        // 2. the EDT drained, which is the headline: everything queued by the build and
        //    by the first layout has run by the time this gets its turn, so it is the
        //    last moment before a reader has a window to look at. Not "painted" - nothing
        //    outside Swing can be told that - but the nearest thing that can be asked.
        JFrame window = JRock.automationWindow();
        // Showing first, and then the queue empty. A window that exists is not a window
        // on screen: isShowing goes true when it is displayable and visible, which is the
        // moment a reader has something to look at - and then the queue has to drain
        // before what they are looking at is finished.
        long until = System.currentTimeMillis() + SHOWING_MS;
        while (window != null && !window.isShowing()
                && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        long afterShowing = System.nanoTime();
        CountDownLatch drained = new CountDownLatch(1);
        SwingUtilities.invokeLater(drained::countDown);
        drained.await();
        long afterDrain = System.nanoTime();

        // 3. and ready, reported after it and separately, being a different question.
        String notReady = JRock.automationAwaitReady(READY_MS);
        long afterReady = System.nanoTime();

        say("", "");
        say("JVM start to main()", ms(jvm, t0));
        say("main() - builds the window", ms(a, afterMain));
        say("then to the window SHOWING", ms(afterMain, afterShowing));
        say("then to the EDT draining", ms(afterShowing, afterDrain));
        say("TOTAL, JVM start to a window on screen", ms(jvm, afterDrain));
        say("", "");
        say("afterwards: to automation ready", ms(afterDrain, afterReady));
        if (notReady != null) {
            say("  not ready, which is expected here", notReady);
        }
        say("", "");
        say("classes loaded in total", classes.getTotalLoadedClassCount());
        say("classes loaded by JRock", classes.getTotalLoadedClassCount() - beforeClasses);
        say("JIT compilation time, ms", jit.isCompilationTimeMonitoringSupported()
                ? jit.getTotalCompilationTime() : -1);
        say("heap used, MB",
                memory.getHeapMemoryUsage().getUsed() / (1024 * 1024));
        say("window showing", window != null && window.isShowing());

        Path report = Path.of("startup-milestones.txt");
        Files.write(report, lines);
        System.out.println("wrote " + report.toAbsolutePath());

        // THE WORKING FOLDER GOES, when this made it. A profile run is a thing done
        // repeatedly while chasing a number down, and each one left a folder with a
        // JRock session in it behind. Not when the folder was given as an argument: that
        // one is the caller's, and theirs to keep.
        if (args.length == 0) deleteTree(dir);

        // JRock leaves non-daemon threads running, so the JVM will not end by itself -
        // and the recording is written by the shutdown hook this triggers.
        System.exit(0);
    }

    /** Deletes a tree, best effort: JRock may still hold its own log file open. */
    private static void deleteTree(Path folder) {
        if (folder == null || !Files.exists(folder)) return;
        try (java.util.stream.Stream<Path> tree = Files.walk(folder)) {
            for (Path each : tree.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList())) {
                try {
                    Files.deleteIfExists(each);
                } catch (java.io.IOException held) {
                    // Open, and the process is about to end anyway.
                }
            }
        } catch (java.io.IOException unreadable) {
            System.out.println("could not clean up " + folder);
        }
    }

    /**
     * When this JVM started, which is the only honest zero for a startup measurement:
     * everything before main() - the JVM itself, the module graph, the class path - is
     * time the reader waits too.
     */
    private static Instant jvmStart() {
        try {
            return ProcessHandle.current().info().startInstant().orElse(Instant.now());
        } catch (RuntimeException noInfo) {
            return Instant.now();
        }
    }

    private static String ms(Instant from, long nanosAt) {
        long sinceStart = Duration.between(from, Instant.now()).toMillis();
        long sinceNanos = (System.nanoTime() - nanosAt) / 1_000_000;
        return (sinceStart - sinceNanos) + " ms";
    }

    private static String ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000 + " ms";
    }

    private static void say(String what, Object value) {
        String line = what.isEmpty() ? "" : String.format("%-40s %s", what, value);
        lines.add(line);
        System.out.println(line);
    }

    private StartupProfile() {
    }
}
