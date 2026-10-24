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

    /** How long one DevTools command may take. A screenshot of a big canvas is slow. */
    private static final long COMMAND_TIMEOUT_MS = 180_000;

    /** The phone this emulates: an iPhone-sized viewport, as the README's section is. */
    private static final int PHONE_W = 390, PHONE_H = 844;

    private static Path out;
    private static int taken;

    public static void main(String[] args) throws Exception {
        out = Paths.get(args.length > 0 ? args[0] : "screenshots").toAbsolutePath();
        Files.createDirectories(out);
        Path page = Paths.get("jrock-web/index.html").toAbsolutePath();
        Path jar = Paths.get("jrock.jar").toAbsolutePath();
        if (!Files.isRegularFile(page) || !Files.isRegularFile(jar)) {
            System.out.println("run this from the repository root, after the jar is built: "
                    + page + " and " + jar + " are what the browser is given");
            System.exit(1);
        }
        String browser = args.length > 1 ? args[1] : findBrowser();
        if (browser == null) {
            System.out.println("no Chromium-based browser found: name one as the second "
                    + "argument (Edge and Chrome both work)");
            System.exit(1);
        }

        HttpServer site = serve(page, jar);
        String address = "http://127.0.0.1:" + site.getAddress().getPort() + "/";
        System.out.println("serving " + page.getParent() + " at " + address);

        int port = freePort();
        Path profile = Files.createTempDirectory("jrock-webshots-profile");
        Process chromium = browser(browser, port, profile);
        try {
            Devtools dev = Devtools.connect(port);
            try {
                desktop(dev, address);
                filesBrowser(dev, address);
                phone(dev, address);
            } finally {
                dev.close();
            }
        } finally {
            chromium.destroy();
            chromium.waitFor(10, TimeUnit.SECONDS);
            site.stop(0);
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
    private static void desktop(Devtools dev, String address) throws Exception {
        // The plain address, and Run pressed here rather than a "?folder" in it: a folder
        // in the address starts JRock the moment the jar has loaded (which is what that
        // feature is for), and this scene wants the checkbox unticked BEFORE it starts -
        // the page reads it once, as the run begins.
        dev.navigate(address);
        dev.untick("hideChrome");
        dev.click("runBtn");
        dev.awaitCanvas();
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
     */
    private static void filesBrowser(Devtools dev, String address) throws Exception {
        dev.navigate(address);
        dev.click("filesBtn");
        dev.awaitRows();
        dev.enter("__JRock");   // what the scene above wrote, in /files itself
        dev.awaitRows();
        shoot(dev, "web-02-the-files-browser");
    }

    /** The same page on a phone-sized screen, with touch events instead of a mouse. */
    private static void phone(Devtools dev, String address) throws Exception {
        dev.phone(PHONE_W, PHONE_H);
        dev.navigate(address);
        dev.untick("hideChrome");
        dev.click("runBtn");
        dev.awaitCanvas();
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
            // CROSS-ORIGIN ISOLATION, which is what SharedArrayBuffer needs and what
            // CheerpJ needs to start a JVM at all. Without these two headers the page
            // loads, the jar loads, CheerpJ initialises - and the JVM exits with code 1
            // before anything is painted, which is a long way from the real reason.
            exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
            exchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy",
                    "credentialless");
            exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy",
                    "cross-origin");
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

    /** Deletes a tree, best effort: a profile left behind is this tool's own mess. */
    private static void deleteTree(Path folder) {
        if (!Files.exists(folder)) return;
        try (java.util.stream.Stream<Path> tree = Files.walk(folder)) {
            for (Path each : tree.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList())) {
                try {
                    Files.deleteIfExists(each);
                } catch (IOException held) {
                    // the browser may still have a handle on one file; the rest goes
                }
            }
        } catch (IOException unreadable) {
            System.out.println("could not clean up " + folder + ": " + unreadable);
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
            await("(function(){var c=document.querySelector('#display-wrap canvas');"
                    + "return !!c && c.width > 200 && c.height > 200;})()",
                    START_TIMEOUT_MS, "CheerpJ to put JRock's window on the page");
            // The canvas exists as soon as the display is made; what goes in it is a
            // window painting itself, a model list being fetched and a log being read.
            Thread.sleep(20_000);
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
