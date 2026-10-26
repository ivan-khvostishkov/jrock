import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpServer;

/**
 * The README's browser and phone screenshots, taken by driving a real browser.
 *
 * <pre>
 *     java .github/build/WebShots.java screenshots            (after the jar is built)
 *     java .github/build/WebShots.java screenshots "C:\...\msedge.exe"
 * </pre>
 *
 * <p>WHY A BROWSER AND NOT THE WINDOW. {@code Screenshots.java} paints JRock's own Swing
 * window, which is the right picture of the desktop build and no picture at all of the
 * browser one: what the web page is FOR is the page - the checksum panel, the Run button,
 * the footer log, the {@code /files} browser - with CheerpJ's canvas in the middle of it.
 * And a phone is not a narrow desktop: the layout, the keyboard handling and the long-tap
 * menu only exist at that size. So this starts a server, starts a browser, and talks to it
 * over the DEVTOOLS PROTOCOL - navigate, click, emulate a phone, capture - which is the
 * only way to reach those states from a build.
 *
 * <p>NOTHING IS INSTALLED AND NOTHING IS MOCKED. The page is served from this repository
 * by a {@code com.sun.net.httpserver} on the loopback address, the jar beside it is the
 * one the build just made, and the browser is whichever Chromium is already on the machine
 * (CI runners have one; locally, Edge or Chrome, or name it as the second argument). The
 * protocol is JSON over a WebSocket, which {@code java.net.http} has had since JDK 11 -
 * so this is one more single-file Java tool with no dependencies, like everything else
 * here.
 *
 * <p>NO KEY AND NO MODEL. JRock comes up in the browser with no credential, which is
 * exactly what a first visit looks like: the session report says so, and nothing is sent.
 * The scenes are about the page and the window, not about an answer.
 */
public final class WebShots {

    /** How long CheerpJ gets to fetch its runtime and start the JVM. Cold cache, CI. */
    private static final long START_TIMEOUT_MS = 240_000;

    /**
     * How long a page SERVED BY THIS TOOL gets before it is given up on.
     *
     * <p>Short on purpose. A loopback page has never come up on a build runner - CheerpJ
     * initialises, says "Running JRock ...", and no canvas ever appears, in library mode
     * and as a jar alike - while the deployed page comes up in the same browser on the
     * same machine every time. The attempt is kept, because it is the one that would
     * photograph THIS build and it costs little; the four minutes of waiting are not.
     */
    private static final long OWN_PAGE_MS = 60_000;

    /**
     * How many distinct colours in the window's client area mean it has been painted. A
     * blank one is the flat grey of an unfilled panel - one colour, two with a border -
     * and a finished one has text, buttons and a log in it.
     */
    private static final int MIN_COLOURS = 60;

    /** How long one DevTools command may take. A screenshot of a big canvas is slow. */
    private static final long COMMAND_TIMEOUT_MS = 180_000;

    /** The phone this emulates: an iPhone-sized viewport, as the README's section is. */
    private static final int PHONE_W = 390, PHONE_H = 844;

    /**
     * The window the /files scene is photographed in, and why it is not the other one.
     *
     * <p>{@code #display-wrap} is the dark panel the page shows before JRock starts -
     * light text on dark is what that panel is designed for - and it stretches to
     * whatever height the window has. The Files button is offered BEFORE a run and not
     * during one, so there is no Swing canvas to cover that panel: in a 1280x813 window
     * the dialog ends up floating in the middle of three quarters of a page of
     * near-black, which in print is 180 cm of ink around the thing the picture is of.
     *
     * <p>So this scene gets a window the dialog nearly fills. Nothing is restyled and
     * nothing is faked: it is the page as somebody with a short window sees it.
     */
    private static final int FILES_W = 1280, FILES_H = 540;

    private static Path out;
    private static int taken;

    public static void main(String[] args) throws Exception {
        List<String> plain = new ArrayList<>();
        String deployed = null;
        int attach = 0;
        for (int i = 0; i < args.length; i++) {
            if ("--url".equals(args[i]) && i + 1 < args.length) deployed = args[++i];
            else if ("--attach".equals(args[i]) && i + 1 < args.length) {
                attach = Integer.parseInt(args[++i]);
            } else plain.add(args[i]);
        }
        out = Paths.get(plain.size() > 0 ? plain.get(0) : "screenshots").toAbsolutePath();
        Files.createDirectories(out);
        String browser = plain.size() > 1 ? plain.get(1) : findBrowser();
        if (browser == null) {
            System.out.println("no Chromium-based browser found: name one as the second "
                    + "argument (Edge and Chrome both work)");
            System.exit(1);
        }

        // --attach: A BROWSER SOMEBODY ELSE IS RUNNING, which is how a phone is checked.
        // Chrome on Android speaks the same DevTools protocol as Chrome on a desktop, and
        // adb will forward its socket to a port on the runner - so the same code that
        // drives a browser here can ask a browser THERE whether CheerpJ got as far as
        // putting a display on the page. That is a smoke test with an answer in it,
        // rather than a screenshot nobody reads: the job fails if the canvas never
        // appears, and the page's own log says how far it got.
        //
        // Nothing is navigated and nothing is served: the phone has already been sent to
        // the address, and this attaches to what it is showing.
        if (attach > 0) {
            try (Devtools dev = Devtools.connect(attach)) {
                dev.awaitCanvas();
                shoot(dev, plain.size() > 1 ? plain.get(1) : "attached-the-page");
                System.out.println("the canvas is there: CheerpJ started and JRock has a "
                        + "display on this device");
            } catch (Exception failed) {
                System.out.println("ATTACHED CHECK FAILED: " + failed.getMessage());
                System.exit(1);
            }
            System.out.println("wrote " + taken + " screenshot(s) to " + out);
            System.exit(0);
        }

        // --url: A PAGE SOMEBODY ELSE IS SERVING, which is how this tool takes pictures
        // at all. Served from here, over loopback and plain HTTP, CheerpJ initialises and
        // then the JVM exits before a canvas is painted - with this jar and with 5.2.0,
        // headless and headed, with and without cross-origin isolation. The deployed site
        // does work, so pointed at that this does the rest: navigate, press Run, wait for
        // the canvas, emulate a phone, hold a finger down.
        //
        // What it costs is that the pictures are of what is deployed and not of the jar
        // just built. That is the trade, and it is the right way round: a true picture of
        // an older build beats no picture of this one.
        HttpServer site = null;
        String address;
        if (deployed != null) {
            address = deployed;
            System.out.println("driving " + address + " - a deployed page, not this build");
        } else {
            Path page = Paths.get("jrock-web/index.html").toAbsolutePath();
            Path jar = Paths.get("jrock.jar").toAbsolutePath();
            if (!Files.isRegularFile(page) || !Files.isRegularFile(jar)) {
                System.out.println("run this from the repository root, after the jar is "
                        + "built: " + page + " and " + jar + " are what the browser is "
                        + "given - or name a deployed page with --url");
                System.exit(1);
            }
            site = serve(page, jar);
            address = "http://127.0.0.1:" + site.getAddress().getPort() + "/";
            System.out.println("serving " + page.getParent() + " at " + address);
        }

        int port = freePort();
        Path profile = Files.createTempDirectory("jrock-webshots-profile");
        Process chromium = browser(browser, port, profile);
        try {
            Devtools dev = Devtools.connect(port);
            try {
                // The page this build serves is driven through library mode; a
                // deployed one is clicked, because the hash hook only exists in a page
                // as new as this tool.
                boolean driven = deployed == null;
                try {
                    desktop(dev, address, driven);
                    filesBrowser(dev, address);
                    phone(dev, address, driven);
                } catch (IOException notComingUp) {
                    // ONE SENTENCE, NOT A STACK TRACE. A page that will not come up is
                    // something the caller handles - the step tries the deployed address
                    // next - and a job that then succeeds should not have an exception
                    // and a stack trace printed in the middle of its log as though
                    // something had broken. What did not happen is in the message, and
                    // the page's own log is in it too.
                    System.out.println("this page did not come up: "
                            + notComingUp.getMessage());
                }
            } finally {
                dev.close();
            }
        } finally {
            // THE CHILDREN FIRST, which is what makes the deletion below work. A browser
            // is a tree of processes, and destroying the one that was launched leaves its
            // renderers and its GPU process running with the profile's files open - so
            // the tree cannot be removed, and on Windows most of it stays. Descendants
            // are collected BEFORE the parent dies, because a dead parent has none.
            List<ProcessHandle> children = chromium.descendants()
                    .collect(java.util.stream.Collectors.toList());
            chromium.destroy();
            chromium.waitFor(10, TimeUnit.SECONDS);
            for (ProcessHandle child : children) {
                child.destroyForcibly();
            }
            if (chromium.isAlive()) chromium.destroyForcibly();
            if (site != null) site.stop(0);
            // THE PROFILE GOES. It is a browser profile with CheerpJ's whole runtime
            // cached in it - 150 MB and more - and a tool that left one behind per run
            // would fill a disk in an afternoon, on a runner or on a desk.
            deleteTree(profile);
        }
        System.out.println("wrote " + taken + " screenshot(s) to " + out);
        System.exit(0);
    }

    // ---- the scenes ---------------------------------------------------------

    /**
     * JRock running in a browser tab, with the page's own chrome left showing.
     *
     * <p>The checkbox is unticked first, because it is the whole point of the picture:
     * the page hides its header and footer five seconds after launch to give the display
     * the tab, and a screenshot of that is a screenshot of a Swing window with no browser
     * around it - which the desktop pictures already are.
     */
    private static void desktop(Devtools dev, String address, boolean driven)
            throws Exception {
        // The plain address, and Run pressed here rather than a "?folder" in it: a folder
        // in the address starts JRock the moment the jar has loaded (which is what that
        // feature is for), and this scene wants the checkbox unticked BEFORE it starts -
        // the page reads it once, as the run begins.
        dev.navigate(address + (driven ? "#shots" : ""));
        dev.untick("hideChrome");
        dev.click("runBtn");
        if (driven && dev.driven(OWN_PAGE_MS)) {
            // DRIVEN, not clicked: the page ran the jar as a library and handed the class
            // over, so from here it is the automation API - await the window, then put a
            // question in the prompt, which is what makes this a picture of the program
            // being used rather than of a program that has started.
            dev.drive("automationAwaitReady(120000)");
            dev.drive("automationSetPrompt('Read this invoice and tell me what is due,"
                    + " and when.\\n')");
        } else if (driven) {
            // LOADED AGAIN WITHOUT THE HASH, because a page that could not hand the class
            // over cannot run the jar either: CheerpJ gives a page one library thread,
            // and a jar started after that thread in the same load never paints. A reload
            // is a page with neither, which is the page that runs JRock the ordinary way.
            System.out.println("  reloading without #shots, to run the jar instead");
            dev.navigate(address);
            dev.untick("hideChrome");
            dev.click("runBtn");
            dev.awaitCanvas(OWN_PAGE_MS);
        } else {
            dev.awaitCanvas();
        }
        shoot(dev, "web-01-jrock-in-a-browser-tab");
    }

    /**
     * The /files browser, with something in it to show.
     *
     * <p>The folder has to exist before it can be browsed, and the scene above is what
     * makes it: JRock ran in {@code /files} and wrote its log, its prompt and its
     * settings into {@code /files/__JRock}. So this RELOADS the page - a session where nothing has been started,
     * which is the only time the button is offered - and opens the browser on a folder
     * with a real {@code __JRock/} in it.
     *
     * <p>In a window of its own, which is the shape of the picture rather than its
     * contents: see FILES_H.
     */
    private static void filesBrowser(Devtools dev, String address) throws Exception {
        dev.window(FILES_W, FILES_H);
        dev.navigate(address);
        dev.click("filesBtn");
        dev.awaitRows();
        dev.enter("__JRock");   // what the scene above wrote, in /files itself
        dev.awaitRows();
        shoot(dev, "web-02-the-files-browser");
    }

    /** The same page on a phone-sized screen, with touch events instead of a mouse. */
    private static void phone(Devtools dev, String address, boolean driven)
            throws Exception {
        dev.phone(PHONE_W, PHONE_H);
        dev.navigate(address + (driven ? "#shots" : ""));
        dev.untick("hideChrome");
        dev.click("runBtn");
        if (driven && dev.driven(OWN_PAGE_MS)) {
            dev.drive("automationAwaitReady(120000)");
        } else {
            if (driven) {
                System.out.println("  reloading without #shots, to run the jar instead");
                dev.navigate(address);
                dev.untick("hideChrome");
                dev.click("runBtn");
            }
            dev.awaitCanvas(driven ? OWN_PAGE_MS : START_TIMEOUT_MS);
        }
        shoot(dev, "mobile-01-jrock-on-a-phone");

        // A long press on the prompt, which the page turns into a right-click for Swing
        // (see installTouchRightClick in index.html) - the one gesture a phone has for
        // everything the Ctrl shortcuts do, and worth a picture of its own.
        int[] where = dev.canvasPoint(0.5, 0.84);
        if (where != null) {
            dev.touch("touchStart", where[0], where[1]);
            Thread.sleep(900);
            dev.touch("touchEnd", where[0], where[1]);
            Thread.sleep(1500);
            shoot(dev, "mobile-02-the-long-tap-menu");
        }
    }

    private static void shoot(Devtools dev, String name) throws Exception {
        byte[] png = dev.screenshot();
        Path file = out.resolve(name + ".png");
        Files.write(file, png);
        taken++;
        System.out.println("shot " + file.getFileName() + "  " + png.length + " bytes");
    }

    // ---- the page, served from this repository ------------------------------

    /**
     * The two files the page needs, on a port of the OS's choosing.
     *
     * <p>Served rather than opened as {@code file://} because the page fetches the jar
     * with {@code fetch()} and hashes it, and a file URL is a different origin to itself
     * in every browser - and because this is how it is deployed.
     */
    private static HttpServer serve(Path page, Path jar) throws IOException {
        HttpServer site = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Path file = path.endsWith("jrock.jar") ? jar : page;
            String type = file == jar ? "application/java-archive" : "text/html";
            byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", type);
            // NO CROSS-ORIGIN ISOLATION HEADERS, and that is the experiment this
            // version is. They were added on the theory that CheerpJ needs
            // SharedArrayBuffer and SharedArrayBuffer needs the page isolated - and the
            // page still never started a JVM, in library mode or as a jar.
            //
            // What they did do was make this server differ from the one page that WORKS.
            // The deployed site comes up in the same browser on the same runner every
            // time, and it sends none of these; a page with COEP set loads cross-origin
            // subresources under different rules, and CheerpJ's runtime comes from a CDN.
            // So the headers go, which leaves the two servers differing only in the
            // scheme and the host - and if the page comes up now, that was the whole of
            // it.
            //
            // If it does not, the next difference to try is HTTPS: a self-signed
            // certificate and --ignore-certificate-errors on the browser.
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream to = exchange.getResponseBody()) {
                to.write(body);
            }
        });
        site.start();
        return site;
    }

    // ---- the browser --------------------------------------------------------

    /** Where a Chromium lives on each desktop, in the order worth trying. */
    private static String findBrowser() {
        List<String> candidates = new ArrayList<>(java.util.Arrays.asList(
                System.getenv("CHROME_PATH"),
                "/usr/bin/google-chrome", "/usr/bin/chromium-browser", "/usr/bin/chromium",
                "/usr/bin/microsoft-edge",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"));
        for (String candidate : candidates) {
            if (candidate != null && Files.isRegularFile(Paths.get(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The browser, headless, with a profile of its own and the debugging port open.
     *
     * <p>A fresh profile every time, deliberately: CheerpJ keeps {@code /files} in the
     * browser's own storage, so a profile that had been used before would put a folder
     * from a previous run into the picture.
     */
    private static Process browser(String binary, int port, Path profile) throws IOException {
        // NOT HEADLESS, and this is the one thing about this tool worth knowing. Headless
        // Chromium runs the page, fetches the jar, initialises CheerpJ - and then the JVM
        // exits with code 1 before a window is painted, on this version of JRock and on
        // the one before it alike, so it is the mode and not the program. A screenshot
        // needs a browser that is really drawing.
        //
        // Which costs nothing on CI, where the Linux runner already starts things under
        // xvfb for exactly this reason (see the screenshots workflow), and costs a window
        // appearing for a minute or two at a desk - the same bargain Screenshots.java
        // makes with its Swing windows.
        List<String> command = java.util.Arrays.asList(binary,
                "--remote-debugging-port=" + port,
                "--user-data-dir=" + profile,
                "--window-size=1280,900",
                "--hide-scrollbars",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                // Headless Chromium throttles a background tab's timers, and CheerpJ's
                // startup is nothing but timers.
                "--disable-background-timer-throttling",
                "--disable-renderer-backgrounding",
                "about:blank");
        System.out.println("starting " + binary + " on port " + port);
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /**
     * Deletes a tree, and says so when it could not: a profile left behind is this
     * tool's own mess, and one left behind SILENTLY is a disk filling up without a
     * reason anybody can find.
     *
     * <p>Three passes with a pause between them, because a handle is released shortly
     * after the process holding it dies rather than at the moment it does. One pass
     * removes the files whose owner has already gone and fails on the rest; by the third
     * there is usually nothing left to fail on.
     */
    private static void deleteTree(Path folder) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (!Files.exists(folder)) return;
            try (java.util.stream.Stream<Path> tree = Files.walk(folder)) {
                for (Path each : tree.sorted(java.util.Comparator.reverseOrder())
                        .collect(java.util.stream.Collectors.toList())) {
                    try {
                        Files.deleteIfExists(each);
                    } catch (IOException held) {
                        // Still open. The next pass will find it closed.
                    }
                }
            } catch (IOException unreadable) {
                System.out.println("could not read " + folder + ": " + unreadable);
                return;
            }
            if (!Files.exists(folder)) return;
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.out.println("::warning::left " + folder + " behind, which is "
                + size(folder) / (1024 * 1024) + " MB of browser profile to delete by "
                + "hand - something in it is still open");
    }

    /** How big a tree is, for saying what was left behind. */
    private static long size(Path folder) {
        try (java.util.stream.Stream<Path> tree = Files.walk(folder)) {
            return tree.filter(Files::isRegularFile).mapToLong(each -> {
                try {
                    return Files.size(each);
                } catch (IOException gone) {
                    return 0;
                }
            }).sum();
        } catch (IOException unreadable) {
            return 0;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ---- the DevTools protocol ----------------------------------------------

    /**
     * One page, driven over the DevTools protocol: JSON commands in, JSON results back,
     * on a WebSocket {@code java.net.http} opens.
     *
     * <p>Only as much of the protocol as these pictures need - navigate, evaluate,
     * dispatch a touch, emulate a screen, capture - and the JSON is read with a regular
     * expression rather than a parser, because every answer this asks for is one string
     * or one number at a known name. A build tool is allowed to know exactly what it is
     * talking to.
     */
    private static final class Devtools implements AutoCloseable {

        private final WebSocket socket;
        private final AtomicInteger ids = new AtomicInteger();
        private final Map<Integer, CompletableFuture<String>> waiting = new ConcurrentHashMap<>();
        private final StringBuilder partial = new StringBuilder();

        private Devtools(WebSocket socket) {
            this.socket = socket;
        }

        /** Connects to the first page target, waiting for the browser to open its port. */
        static Devtools connect(int port) throws Exception {
            String list = null;
            long until = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < until) {
                try {
                    list = get("http://127.0.0.1:" + port + "/json/list");
                    if (list.contains("webSocketDebuggerUrl")) break;
                } catch (IOException | InterruptedException notYet) {
                    // the browser is still starting
                }
                Thread.sleep(250);
            }
            if (list == null || !list.contains("webSocketDebuggerUrl")) {
                throw new IOException("the browser never opened its debugging port");
            }
            // THE PAGE, and not simply the first target in the list, which is what this
            // did at first: a browser also lists its own internals - a service worker, a
            // new-tab page, whatever an edition ships with - and driving one of those is
            // a session where Page.navigate appears to work and every element is missing.
            String url = pageTarget(list);
            if (url == null) {
                throw new IOException("no page target to drive among " + brief(list));
            }

            Devtools[] held = new Devtools[1];
            WebSocket.Listener listener = new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }

                @Override
                public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data,
                                                   boolean last) {
                    held[0].onFrame(data, last);
                    webSocket.request(1);
                    return null;
                }
            };
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(url), listener).get(30, TimeUnit.SECONDS);
            held[0] = new Devtools(socket);
            held[0].call("Page.enable", "{}");
            held[0].call("Runtime.enable", "{}");
            held[0].call("Log.enable", "{}");
            // Every worker and iframe this page makes, attached as it appears: the JVM
            // is in one of them (see onFrame).
            held[0].call("Target.setAutoAttach",
                    "{\"autoAttach\":true,\"waitForDebuggerOnStart\":false,"
                    + "\"flatten\":true}");
            return held[0];
        }

        /**
         * The WebSocket address of a target of type "page", or null.
         *
         * <p>The list is one JSON array of flat objects, so it is split on the boundary
         * between them and each piece asked what it is - which is all the parsing a
         * fixed, known answer needs.
         */
        private static String pageTarget(String list) {
            for (String target : list.split("\\},\\s*\\{")) {
                if (!target.contains("\"type\": \"page\"")
                        && !target.contains("\"type\":\"page\"")) {
                    continue;
                }
                String url = first(target, "\"webSocketDebuggerUrl\"\\s*:\\s*\"([^\"]+)\"");
                if (url != null) {
                    System.out.println("  driving " + first(target,
                            "\"url\"\\s*:\\s*\"([^\"]*)\"") + " (" + url + ")");
                    return url;
                }
            }
            return null;
        }

        /** A frame, or part of one: a big screenshot arrives in pieces. */
        private void onFrame(CharSequence data, boolean last) {
            partial.append(data);
            if (!last) return;
            String message = partial.toString();
            partial.setLength(0);
            String id = first(message, "^\\{\"id\":(\\d+)");
            if (id == null) {
                // A TARGET THAT HAS JUST ATTACHED, which is where the JVM lives: CheerpJ
                // runs Java in a Web Worker, and a worker's console belongs to the
                // worker's own session. Without enabling the two domains on each one, a
                // Java exception is reported to a console nobody is listening to.
                //
                // Sent without waiting for the answer, because this runs on the socket's
                // own thread: anything that waited here would be waiting for a message
                // this method is the one meant to read.
                String session = first(message, "\"sessionId\":\"([^\"]+)\"");
                if (message.contains("Target.attachedToTarget") && session != null) {
                    send("{\"id\":" + ids.incrementAndGet() + ",\"sessionId\":\"" + session
                            + "\",\"method\":\"Runtime.enable\",\"params\":{}}");
                    send("{\"id\":" + ids.incrementAndGet() + ",\"sessionId\":\"" + session
                            + "\",\"method\":\"Log.enable\",\"params\":{}}");
                    return;
                }
                // An event, and only one kind is wanted - the one that explains a failure.
                // CheerpJ reports a Java exception to the browser's console, so without
                // this a window that never appeared comes with no reason at all.
                if (message.contains("Runtime.consoleAPICalled")
                        || message.contains("Log.entryAdded")
                        || message.contains("Runtime.exceptionThrown")) {
                    for (String piece : message.split("\\\\n")) {
                        String text = first(piece, "\"value\":\"((?:[^\"\\\\]|\\\\.)*)\"");
                        if (text == null) {
                            text = first(piece, "\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");
                        }
                        if (text != null && !text.isBlank()) {
                            System.out.println("    [browser] " + text);
                        }
                    }
                }
                return;
            }
            CompletableFuture<String> answer = waiting.remove(Integer.parseInt(id));
            if (answer != null) answer.complete(message);
        }

        /** A message sent with no answer waited for (see onFrame). */
        private void send(String message) {
            socket.sendText(message, true);
        }

        /** One command, and its whole JSON answer. */
        private String call(String method, String params) throws Exception {
            int id = ids.incrementAndGet();
            CompletableFuture<String> answer = new CompletableFuture<>();
            waiting.put(id, answer);
            String message = "{\"id\":" + id + ",\"method\":\"" + method
                    + "\",\"params\":" + params + "}";
            socket.sendText(message, true).get(30, TimeUnit.SECONDS);
            return answer.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        void navigate(String address) throws Exception {
            System.out.println("  navigate " + address);
            call("Page.navigate", "{\"url\":\"" + address + "\"}");
            // Not Page.loadEventFired: the page's own work starts at load, and everything
            // below waits for a condition in the page itself rather than for an event.
            await("document.readyState === 'complete'", 60_000, "the page to load");
        }

        /** A desktop window of a stated size, for a scene that wants a shorter page. */
        void window(int width, int height) throws Exception {
            call("Emulation.setDeviceMetricsOverride", "{\"width\":" + width
                    + ",\"height\":" + height
                    + ",\"deviceScaleFactor\":1,\"mobile\":false}");
        }

        /** A phone-sized screen, with touch events and the mobile flag the page sees. */
        void phone(int width, int height) throws Exception {
            call("Emulation.setDeviceMetricsOverride", "{\"width\":" + width
                    + ",\"height\":" + height + ",\"deviceScaleFactor\":2,\"mobile\":true}");
            call("Emulation.setTouchEmulationEnabled",
                    "{\"enabled\":true,\"maxTouchPoints\":5}");
        }

        /**
         * Clicks one of the page's buttons, once it is a button that can be clicked.
         *
         * <p>ENABLED, and not merely there, which is the whole of the wait: Run JRock is
         * disabled until the page has fetched the jar and hashed it, and a click on a
         * disabled button is a click that does nothing at all - followed, four minutes
         * later, by "CheerpJ never started", which is a report about the wrong thing.
         */
        void click(String id) throws Exception {
            System.out.println("  click #" + id);
            await("(function(){var b=document.getElementById('" + id + "');"
                    + "return !!b && !b.disabled;})()", 60_000, "#" + id + " to be ready");
            evaluate("document.getElementById('" + id + "').click()");
        }

        /** The page's own footer log, which is where it says what went wrong. */
        String pageLog() throws Exception {
            String answer = evaluate("(document.getElementById('bottom')||{}).textContent"
                    + " || ''");
            String text = first(answer, "\"value\":\"((?:[^\"\\\\]|\\\\.)*)\"");
            return text == null ? "" : text.replace("\\n", "\n").replace("\\\"", "\"");
        }

        void untick(String id) throws Exception {
            evaluate("document.getElementById('" + id + "').checked = false");
        }

        /** Clicks the /files browser's row for one name, which opens that folder. */
        void enter(String name) throws Exception {
            System.out.println("  into " + name + "/");
            String expression = "(function(){var rows=document.querySelectorAll("
                    + "'#filesList .row button.name');for(var i=0;i<rows.length;i++){"
                    + "if(rows[i].textContent.replace(/\\/$/,'')===" + quoted(name)
                    + "){rows[i].click();return true;}}return false;})()";
            await(expression, 30_000, name + "/ in the list");
        }

        /** Waits for the Swing canvas, which is CheerpJ having started the JVM. */
        void awaitCanvas() throws Exception {
            awaitCanvas(START_TIMEOUT_MS);
        }

        void awaitCanvas(long millis) throws Exception {
            await("(function(){var c=document.querySelector('#display-wrap canvas');"
                    + "return !!c && c.width > 200 && c.height > 200;})()",
                    millis, "CheerpJ to put JRock's window on the page");
            awaitPainted(millis);
        }

        /**
         * Waits for the canvas to have a WINDOW in it and not just to exist.
         *
         * <p>The canvas is created when the display is made, which is seconds before Swing
         * has painted anything into it: a picture taken then is a title bar over an empty
         * grey rectangle. What is being waited for is the painting, and the canvas itself
         * is asked - how many distinct colours are in the band where the window's own
         * widgets go, which is one while it is blank and hundreds once there is a prompt,
         * a log and a row of buttons in it.
         *
         * <p>Then it has to STOP changing, because the middle of a start is as busy as the
         * end of one: the model list arrives, the log is read in, the layout settles. Two
         * readings the same, five seconds apart, is a window that has finished.
         *
         * <p>A canvas this cannot read - a WebGL context, where getImageData is not there
         * to call - leaves the count at -1, and then the wait is the flat twenty seconds
         * this had before. Slower than it needs to be and never wrong.
         */
        private void awaitPainted(long millis) throws Exception {
            String colours = "(function(){"
                    + "var c=document.querySelector('#display-wrap canvas');"
                    + "if(!c)return -1;"
                    + "var x;try{x=c.getContext('2d');}catch(e){return -1;}"
                    + "if(!x||!x.getImageData)return -1;"
                    // THE MIDDLE BAND ONLY, which is the whole trick: the window's TITLE
                    // BAR is painted the moment the frame exists, and "JRock - Bedrock
                    // (mantle)" in anti-aliased white on blue is hundreds of distinct
                    // colours that never change again. Counted from the top of the
                    // canvas, a blank window therefore looks exactly as painted as a
                    // finished one. The client area is flat grey until Swing fills it.
                    + "var top=Math.round(c.height*0.20);"
                    + "var h=Math.round(c.height*0.60),d;"
                    + "try{d=x.getImageData(0,top,c.width,h).data;}catch(e){return -1;}"
                    + "var seen={},n=0;"
                    // Every 40th pixel: enough to tell a painted window from a flat one,
                    // and a fraction of the work of reading every pixel of a big canvas.
                    + "for(var i=0;i<d.length;i+=160){"
                    + "var k=(d[i]<<16)|(d[i+1]<<8)|d[i+2];"
                    + "if(!seen[k]){seen[k]=1;n++;}}"
                    + "return n;})()";
            long until = System.currentTimeMillis() + millis;
            int previous = -2;
            while (System.currentTimeMillis() < until) {
                int now = number(evaluate(colours));
                if (now < 0) {
                    System.out.println("   the canvas cannot be read, so waiting a flat 20s");
                    Thread.sleep(20_000);
                    return;
                }
                // Hundreds once there is a prompt, a log and a row of buttons in it;
                // one or two while it is the flat grey of an empty client area. And
                // settled WITHIN A TWENTIETH rather than exactly: a count in the
                // hundreds moves by a pixel of anti-aliasing and never repeats itself.
                if (now > MIN_COLOURS && previous > 0
                        && Math.abs(now - previous) <= Math.max(2, now / 20)) {
                    System.out.println("   the window is painted and settled ("
                            + now + " colours in the client area)");
                    return;
                }
                System.out.println("   waiting: " + now + " colours so far");
                previous = now;
                Thread.sleep(5_000);
            }
            System.out.println("   the window never settled, so taking it as it is");
        }

        /** The integer a Runtime.evaluate reply carries, or -1 when it carries none. */
        private static int number(String reply) {
            String value = first(reply, "\"value\":(-?\\d+)");
            return value == null ? -1 : Integer.parseInt(value);
        }

        /** Waits for the /files browser to have listed something. */
        void awaitRows() throws Exception {
            await("document.querySelectorAll('#filesList .row').length > 0",
                    START_TIMEOUT_MS, "the /files browser to list a folder");
            Thread.sleep(500);
        }

        /** Where to put a finger, as a fraction of the canvas. Null if there is none. */
        int[] canvasPoint(double x, double y) throws Exception {
            String json = evaluate("(function(){var c=document.querySelector("
                    + "'#display-wrap canvas');if(!c)return '';var r=c.getBoundingClientRect();"
                    + "return Math.round(r.left + r.width * " + x + ") + ',' + "
                    + "Math.round(r.top + r.height * " + y + ");})()");
            String value = first(json, "\"value\":\"(\\d+,\\d+)\"");
            if (value == null) return null;
            String[] parts = value.split(",");
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        }

        void touch(String type, int x, int y) throws Exception {
            String points = "touchEnd".equals(type) ? "[]"
                    : "[{\"x\":" + x + ",\"y\":" + y + "}]";
            call("Input.dispatchTouchEvent",
                    "{\"type\":\"" + type + "\",\"touchPoints\":" + points + "}");
        }

        byte[] screenshot() throws Exception {
            String answer = call("Page.captureScreenshot",
                    "{\"format\":\"png\",\"captureBeyondViewport\":false}");
            String data = first(answer, "\"data\":\"([^\"]+)\"");
            if (data == null) throw new IOException("no image came back: " + brief(answer));
            return Base64.getDecoder().decode(data);
        }

        /**
         * One call into the running program, from JavaScript - which is the whole point
         * of the page's library mode: {@code window.jrock} IS the JRock class, so this
         * is its own automation API, the same calls a flow makes, reached through the
         * browser instead of through a JVM on this machine.
         *
         * <p>Nothing is clicked and no pixel is guessed at. And a Java exception comes
         * back as a JavaScript one with its message in it, where {@code cheerpjRunJar}
         * could only ever report an exit code.
         */
        String drive(String call) throws Exception {
            String answer = evaluate("window.jrock." + call);
            if (answer.contains("\"exceptionDetails\"")) {
                throw new IOException("window.jrock." + call + " threw: " + brief(answer)
                        + "\nthe page's own log said:\n" + pageLog());
            }
            return answer;
        }

        /**
         * Whether library mode handed the class over - and not an exception if it did
         * not: the page runs the jar itself in that case (see its DRIVEN branch), so
         * there is a window to photograph either way, and the only thing lost is being
         * able to set the prompt through the automation API.
         */
        boolean driven(long millis) throws Exception {
            long until = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < until) {
                if (evaluate("typeof window.jrock !== 'undefined'")
                        .contains("\"value\":true")) {
                    return true;
                }
                Thread.sleep(5_000);
            }
            System.out.println("  library mode did not hand the class over in "
                    + millis / 1000 + "s; the page's own log says:\n" + pageLog());
            return false;
        }

        private String evaluate(String expression) throws Exception {
            return call("Runtime.evaluate", "{\"expression\":" + quoted(expression)
                    + ",\"returnByValue\":true,\"awaitPromise\":true}");
        }

        /**
         * Polls one expression until it is true, or says what it was waiting for - and
         * what the page itself had to say about it, which is where the answer usually is:
         * the footer log holds "Could not load jrock.jar", "Initialising CheerpJ ..." or
         * "Running JRock ...", and which of those it got to says what failed.
         */
        private void await(String expression, long millis, String what) throws Exception {
            long until = System.currentTimeMillis() + millis;
            String last = "";
            while (System.currentTimeMillis() < until) {
                last = evaluate(expression);
                if (last.contains("\"value\":true")) return;
                // Five seconds, not half of one: everything here goes through the page's
                // own thread, and that thread is busy starting a JVM. Asking it ten times
                // a second is asking the thing we are waiting for to stop and answer.
                Thread.sleep(5_000);
            }
            throw new IOException("waited " + millis / 1000 + "s for " + what
                    + ", and it did not happen: " + brief(last)
                    + "\nthe page's own log said:\n" + pageLog());
        }

        @Override
        public void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    // ---- small things -------------------------------------------------------

    private static String quoted(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') json.append('\\').append(c);
            else if (c == '\n') json.append("\\n");
            else if (c < 0x20) json.append(String.format("\\u%04x", (int) c));
            else json.append(c);
        }
        return json.append('"').toString();
    }

    private static String first(String text, String pattern) {
        Matcher found = Pattern.compile(pattern).matcher(text);
        return found.find() ? found.group(1) : null;
    }

    private static String brief(String text) {
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private static String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> answer = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return answer.body();
    }

    private WebShots() {
    }
}
