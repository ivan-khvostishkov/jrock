// A JRock agent (it loops, and is started with JRock): a page off the web, read by
// looking at it. It drives a browser of its own through the Chrome DevTools Protocol,
// photographs the whole page, and asks its own JRock to turn that photograph into
// Markdown - which is what the push-to-talk agent gets back, with the address the
// browser actually ended on.
//
// Run it the way JRock itself runs - no build step, no Maven, one file:
//
//     java -cp jrock.jar JRockBrowser.java [--working-dir <dir>] [--prompts-dir <dir>]
//     java -cp jrock.jar JRockBrowser.java --shot https://arstechnica.com/
//
// --shot does the browser half and nothing else: it prints the address it ended on and
// the PNG it wrote, with no JRock, no model and no window. It is the way to see what the
// browser did without spending a request on it.
//
// SIX HOSTS, AND NOTHING ELSE. duckduckgo.com, businessinsider.com, zdnet.com,
// techcrunch.com, scmp.com, arstechnica.com - a list in this file, not a setting, not a
// line in a request, and not something a model can add to. The web is the one thing an
// agent reaches that nobody in the room controls, so what it may reach is written down
// where it can be read.
//
// The check is made TWICE, and the second time is the one that matters:
//
//   1. before anything is asked of the browser, on the address as given;
//   2. after the page has loaded, on the address the browser is ACTUALLY ON - which is
//      not the same thing, because a redirect is a thing a site may do at any moment.
//      Off the list, the page is abandoned unphotographed and the answer says where it
//      went. That is the same thought as a browser's own origin checks: trust what you
//      ended up with, not what you asked for.
//
// THE PICTURE IS FOR READING; THE DOM IS FOR NAVIGATING. Those are two different jobs
// and they are done with two different things. A model reads the page off a photograph of
// it, which is what a person does. But a photograph cannot be clicked - so when the next
// request is "open the one about the Cray-1 replica", this agent reads the ANCHORS out of
// the live DOM, matches that description against their text, and navigates to the one
// that matches. The model never needs a URL to follow a link, and never gets one.
//
// ADDRESSES ARE FOR PROGRAMS, NOT FOR SAYING OUT LOUD. The answer carries the address
// the browser ended on, because the agent asking is the one that will later say "the AI
// link on https://www.zdnet.com/news/" and has to have got that from somewhere. What it
// must not do is READ one to the operator: a URL spoken aloud is a sentence nobody can
// check, remember or correct. So the answer also carries the site as plain domain.tld and
// the page's title, which is what a person is told - "on zdnet.com, the AI section" - and
// the push-to-talk appendix says so in as many words. The whole address stays between the
// two programs, this window, and this agent's own JRock log.
//
// A SCREENSHOT RATHER THAN THE DOM FOR READING, which is the part worth explaining. The HTML of a
// news page is mostly not the news: it is navigation, consent, advertising, tracking and
// three kilobytes of class names per headline. What a reader sees is the page laid out,
// and a model reading a picture of it gets the same thing - the headline that is biggest
// is the one that matters, the sidebar looks like a sidebar. It also means no HTML
// parsing, no scripts of the page's own running anywhere near this agent's own data, and
// one artifact - a PNG - that a person can open and check for themselves.
//
// What it does, per request:
//
//   1. starts a browser of its own, once, with a WINDOW and a profile of its own that
//      is KEPT between runs - ONE TAB PER SITE, so six tabs is the most there can ever
//      be and a second page on a site reuses the tab it already has;
//   2. navigates its one tab, and waits for the page to stop changing: readyState
//      complete AND the same measurements twice, which is what tells a page that has
//      finished loading from one that is still filling itself in. TEN SECONDS at the
//      most, whatever state it is in by then;
//   3. reads the address again and checks it again;
//   4. photographs the page in A4-shaped pieces that overlap, top to bottom;
//   5. includes those pieces in its own JRock, loads jrock-prompt-page-to-markdown.txt,
//      and sends it. The answer is the page as Markdown;
//   6. LEAVES THE PAGE OPEN in that window.
//
// IT CLICKS NOTHING. Not a consent wall, not a cookie banner, not a sign-in. Those are
// the operator's to answer, and an agent answering them on their behalf is an agent
// agreeing to things in their name. So the first read of a site may come back as a
// picture of a consent wall - and the window is still sitting on it, so the operator
// deals with it once, by hand, and asks again. The profile is kept, so the second
// attempt is the page itself. The same goes for a paywall or a login: whatever the
// operator can get to in that window, this agent can then read.
//
// The profile is this agent's own, not the operator's main browser profile - what is
// signed in there is what they signed in to HERE, deliberately, in this window.
//
// WHY A4 PIECES. A page is as long as it likes; a screenshot of an endless feed is a
// picture nothing can read. So the page is cut into overlapping pieces, each one the
// proportions of a sheet of paper (1:1.414, A4), which is a shape a model reads well
// because it is the shape nearly everything it has ever read was on. They overlap by a
// tenth, so a headline cut in half by one piece is whole in the next, and the last piece
// is whatever is left. Each piece is a clip of its own out of the browser, so no single
// enormous image is ever made - which is also the one thing a browser refuses to do
// beyond about sixteen thousand pixels.
//
// Needs: a Chromium - Edge, Chrome, Brave or Vivaldi - which the window says if it
// cannot find one. Nothing else: the DevTools Protocol is JSON over a WebSocket, and a
// WebSocket client has been in the JDK since 11 (java.net.http.WebSocket), so this is
// one file with no dependencies like everything else here.

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JRockBrowser {

    // The next port after the calendar's, on the loopback interface only.
    private static final int PORT = 47474;

    // THE LIST. Hosts, and their subdomains, and nothing else ever.
    private static final String[] ALLOWED = {
        "duckduckgo.com",
        "businessinsider.com",
        "zdnet.com",
        "techcrunch.com",
        "scmp.com",
        "arstechnica.com",
    };

    // Where a search goes. DuckDuckGo's own HTML form, which needs no JavaScript to show
    // results and is on the list like everything else.
    private static final String SEARCH = "https://duckduckgo.com/?q=";

    // The prompt that turns the photograph into Markdown, beside this file.
    private static final String PROMPT = "jrock-prompt-page-to-markdown.txt";

    // What this agent remembers: where the browser is, and the two numbers worth
    // changing. Beside this file, like every other agent's settings.
    private static final String SETTINGS = "jrock-browser.txt";

    // How long a page gets to settle before it is photographed as it stands.
    private static final long LOAD_TIMEOUT_MS = 10_000;
    private static final long SETTLE_POLL_MS = 400;

    // The window the browser renders in.
    private static final int VIEW_WIDTH = 1240;
    private static final int VIEW_HEIGHT = 1000;

    // A piece of the page: as wide as the page and A4-shaped, overlapping the one above
    // it by a tenth. Twelve of them is a long article; a page longer than that is read
    // down to there and said to have been.
    private static final double A4 = 1.41421356;
    private static final double OVERLAP = 0.10;
    private static final int SLICES_MAX = 12;

    // How long JRock gets: a page's worth of picture is a slow request.
    private static final long READY_TIMEOUT_MS = 120_000;
    private static final long SEND_TIMEOUT_MS = 600_000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final ArrayBlockingQueue<String> ANSWERS = new ArrayBlockingQueue<>(256);
    private static final StringBuilder PARTIAL = new StringBuilder();

    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> daemon(r, "jrock-browser-worker"));

    private static volatile Path browserProgram;      // the Chromium, once found
    private static volatile int debugPort = 9222;
    // A window, because the operator has to be able to click in it. Only a profile that
    // nobody ever needs to touch could be headless, and the whole point of this one is
    // that they do.
    private static volatile boolean headless = false;
    private static volatile Process browser;
    private static volatile WebSocket socket;
    private static volatile Path profileDir;
    // ONE TAB PER SITE, kept. Asking for another page on a site it already has reuses
    // that site's tab, so there is never a second arstechnica.com window to wonder about -
    // and because the only sites there are are the six on the list, the number of tabs
    // cannot grow past six however many pages are read.
    //
    // Per site rather than one in total, because the operator may have answered a consent
    // wall on one and be signed in on another, and a single tab would throw one away to
    // show the other.
    private static final java.util.Map<String, String[]> TABS = new java.util.LinkedHashMap<>();
    // The site of the page read last, which is the one "follow the link that says..."
    // means. Nothing else would: a description with no page to look at is not a request.
    private static volatile String lastSite;

    private static javax.swing.JFrame window;
    private static javax.swing.JTextField askField;
    private static javax.swing.JTextArea result;
    private static javax.swing.JLabel status;
    private static javax.swing.JLabel listening;
    private static volatile ServerSocket server;

    public static void main(String[] args) {
        String shot = null;
        String linksOn = null;
        String wanted = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--shot") && i + 1 < args.length) shot = args[++i];
            else if (args[i].equals("--links") && i + 1 < args.length) linksOn = args[++i];
            else if (args[i].equals("--wanted") && i + 1 < args.length) wanted = args[++i];
        }
        readSettings();

        // --shot: the browser half, printed. No JRock, no model, no window - which makes
        // it the way to find out whether a page can be read at all.
        if (shot != null) {
            try {
                Page page = fetch(shot, System.out::println);
                System.out.println("Final URL: " + page.url);
                System.out.println("Page " + page.width + "x" + page.height + " in "
                        + page.pieces.size() + " piece(s)"
                        + (page.cut ? ", and there was more below" : "") + ":");
                for (Path piece : page.pieces) System.out.println("  " + piece);
            } catch (Stop stop) {
                System.out.println("Stopped: " + stop.getMessage());
            } finally {
                // --shot is one look and out, so the browser goes with it. In the window
                // it is kept, with the page still on it.
                closeBrowser();
            }
            return;
        }

        // --links: the navigating half, printed. Which links a page offers, and which one
        // a description resolves to - no JRock, no model, no window of ours.
        if (linksOn != null) {
            try {
                WebSocket live = browserSocket(System.out::println);
                String tab = tab(live, site(address(linksOn)));
                String landed = fetchInto(live, tab, address(linksOn), System.out::println);
                List<Link> found = linksOn(live, tab);
                System.out.println("Links this agent may follow on " + site(landed) + ": "
                        + found.size());
                int shown = 0;
                for (Link link : found) {
                    if (shown++ >= 15) break;
                    System.out.println("  " + link.text + "  ->  " + link.href);
                }
                if (wanted != null) {
                    Link best = null;
                    int bestScore = 0;
                    for (Link link : found) {
                        int score = score(link.text, wanted);
                        if (score > bestScore) {
                            best = link;
                            bestScore = score;
                        }
                    }
                    System.out.println("\n\"" + wanted + "\" resolves to: "
                            + (best == null || bestScore == 0 ? "nothing"
                               : "\"" + best.text + "\" (" + bestScore + ") -> " + best.href));
                }
            } catch (Stop stop) {
                System.out.println("Stopped: " + stop.getMessage());
            } finally {
                closeBrowser();
            }
            return;
        }

        // JRock of its own, taken as an automation: the prompt read-only, the mic muted,
        // and the window there to watch the work.
        JRock.main(args);
        String notReady = JRock.automationAwaitReady(READY_TIMEOUT_MS);
        String problem = JRock.automationWindow() == null ? notReady
                : JRock.automationBegin("browser agent");
        if (problem == null) problem = notReady;
        if (problem != null) {
            String why = problem;
            System.out.println("Stopped: " + why);
            onEdt(() -> javax.swing.JOptionPane.showMessageDialog(JRock.automationWindow(),
                    "The browser agent cannot start:\n\n" + why,
                    "Browser agent", javax.swing.JOptionPane.ERROR_MESSAGE));
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(JRockBrowser::closeBrowser,
                "jrock-browser-shutdown"));

        onEdt(JRockBrowser::showWindow);
        String said = listen();
        later(() -> listening.setText(said));
        System.out.println("Browser agent is ready. " + said);
    }

    // ---- Answering -------------------------------------------------------------
    // One request line into the text that goes back. On the worker thread.
    private static String answer(String request) {
        String line = request == null ? "" : request.trim();
        if (line.isEmpty()) throw new Stop("nothing was asked.");
        if (!line.startsWith("#")) line = "#" + line;
        String[] parts = line.split("\\s+", 2);
        String what = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";

        switch (what) {
            case "#hosts":
                return hosts();
            case "#page":
            case "#site":
                if (rest.isEmpty()) throw new Stop("#page wants a site or an address.");
                return read(address(rest), null);
            case "#search":
                if (rest.isEmpty()) throw new Stop("#search wants something to search for.");
                return read(SEARCH + encode(rest), null);
            case "#follow":
                if (rest.isEmpty()) {
                    throw new Stop("#follow wants words to look for, and the page to look "
                            + "on, as #follow the AI link on https://www.zdnet.com/news/");
                }
                return follow(rest);
            case "#links":
                return links(rest);
            default:
                throw new Stop("\"" + line + "\" is not something this agent answers. Ask "
                        + "#page <site or address>, #search <words>, #follow <what to "
                        + "open>, #links or #hosts.");
        }
    }

    // THE THREE WAYS A PAGE CAN BE NAMED, which is the whole of the naming rule:
    //
    //   - an address, "https://www.zdnet.com/news/" - what one program says to another;
    //   - a domain, "zdnet.com" - what a person says, and what may be said back to them;
    //   - a name, "South China Morning Post" or just "zdnet" - what a person actually
    //     says out loud, which arrives here unchanged because the model is told to pass
    //     the operator's own words through rather than invent an address for them.
    //
    // All three are taken. The third is why this table exists: the operator says the name
    // of a paper, and nothing in between has to know what its domain is.
    private static final String[][] NAMES = {
        { "duckduckgo.com", "duckduckgo", "duck duck go", "ddg" },
        { "businessinsider.com", "business insider", "businessinsider", "insider", "bi" },
        { "zdnet.com", "zdnet", "zd net" },
        { "techcrunch.com", "techcrunch", "tech crunch", "crunch" },
        { "scmp.com", "scmp", "south china morning post", "south china", "morning post" },
        { "arstechnica.com", "arstechnica", "ars technica", "ars" },
    };

    // THE FRONT PAGE OF A SITE, where asking for it plainly does not work.
    //
    // businessinsider.com sends a reader in Germany to businessinsider.de, which is not
    // on the list and so comes back refused - the right answer to a redirect off the
    // list, and a useless one to somebody who asked for Business Insider. A trailing
    // question mark is enough to stop it: the redirect is decided for the bare path and
    // not for one carrying a query, so "/?" lands on the English front page. Every link
    // from there stays on .com, so this is needed for the front page only.
    //
    // One line per site that needs one, with the reason, rather than a rule nobody could
    // guess at. Everything else is https://<domain>/.
    private static final String[][] FRONT = {
        { "businessinsider.com", "https://www.businessinsider.com/?" },
    };

    private static String address(String given) {
        String asked = given.trim();
        if (asked.toLowerCase(Locale.ROOT).startsWith("http")) return asked;
        String spoken = asked.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9. ]", " ")
                .replaceAll("\\s+", " ").trim();
        for (String[] site : NAMES) {
            for (int i = 1; i < site.length; i++) {
                if (spoken.equals(site[i])) return front(site[0]);
            }
        }
        // A bare host, with or without a path after it.
        String host = asked.split("[/?#\\s]", 2)[0];
        String rest = asked.length() > host.length() ? asked.substring(host.length()) : "/";
        if (!rest.startsWith("/")) rest = "/" + rest;
        // A bare host IS the front page, however it was spelled.
        if (rest.equals("/")) return front(host);
        return "https://" + host + rest;
    }

    // The front page of one site: its own, where it has one, and the plain one otherwise.
    private static String front(String host) {
        String bare = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        for (String[] site : FRONT) {
            if (bare.equals(site[0])) return site[1];
        }
        return "https://" + host + "/";
    }

    private static String hosts() {
        StringBuilder out = new StringBuilder("Pages can be read from these hosts, and "
                + "their subdomains, and nowhere else:");
        for (String host : ALLOWED) out.append("\n- ").append(host);
        return out.append("\nEach can be asked for by domain or by name - \"zdnet\", "
                + "\"ars technica\", \"South China Morning Post\". A search goes to "
                + "DuckDuckGo. The list is in the agent's own source: nothing in a "
                + "request can add to it.").toString();
    }

    // A page, read: photographed, and then read out of the photograph by a model.
    //
    // What comes back names the SITE and the page's title, never the address. The
    // address is in this window and in the log, for a person.
    private static String read(String url, String how) {
        Page page = fetch(url, note -> later(() -> say(note)));
        later(() -> say("Reading the page from its picture\u2026"));
        String markdown = toMarkdown(page);
        return "Site: " + page.site
                + (page.title.isEmpty() ? "" : "\nPage: " + page.title)
                + (how == null ? "" : "\nOpened: " + how)
                + "\nURL: " + page.url + "   (for your next request - never read an address "
                + "out loud)\n\n" + markdown
                + (page.cut ? "\n\n(The page went on past " + SLICES_MAX + " pieces, so "
                        + "this is the top of it.)" : "")
                + "\n\n(The browser window is still on this page. If what came back is a "
                + "consent wall, a sign-in or a paywall rather than the page, deal with it "
                + "in that window once and ask again - nothing here clicks anything.)";
    }

    // ---- The list --------------------------------------------------------------
    // One address, checked. https only - a page fetched over plain http can be rewritten
    // by anything between here and there, and every host on the list speaks https.
    private static String checkedHost(String url, String when) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            throw new Stop("\"" + url + "\" is not an address.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https")) {
            throw new Stop("only https addresses are read" + when + ", and that one is "
                    + (scheme.isEmpty() ? "missing its scheme" : scheme) + ".");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) throw new Stop("\"" + url + "\" names no host.");
        for (String allowed : ALLOWED) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return host;
        }
        throw new Stop(host + " is not a host this agent reads" + when + ". The list is: "
                + String.join(", ", ALLOWED) + ".");
    }

    // ---- The browser -----------------------------------------------------------
    // What one page came back as: where it ended up, and the pieces of its picture.
    private static final class Page {
        final String url;        // for the window and the log, and for nothing else
        final String site;       // domain.tld, which is what anybody is told
        final String title;
        final List<Path> pieces;
        final int width, height;
        final boolean cut;

        Page(String url, String site, String title, List<Path> pieces, int width,
             int height, boolean cut) {
            this.url = url; this.site = site; this.title = title;
            this.pieces = pieces;
            this.width = width; this.height = height; this.cut = cut;
        }
    }

    // Navigate, settle, check again, photograph. Every step says what it did through
    // note, because this is the slow part and silence in a slow part reads as a hang.
    private static Page fetch(String url, java.util.function.Consumer<String> note) {
        String asked = checkedHost(url, " ");
        note.accept("Asking the browser for " + url + " (" + asked + " is on the list).");
        WebSocket live = browserSocket(note);
        String site = site(url);
        String tab = tab(live, site);
        note.accept("Using the tab for " + site + ".");

        String landed = fetchInto(live, tab, url, note);

        String metrics = call(live, tab, "Page.getLayoutMetrics", "{}");
        int width = Math.max(VIEW_WIDTH, (int) jsonNumber(metrics, "width"));
        int whole = Math.max(VIEW_HEIGHT, (int) jsonNumber(metrics, "height"));

        // A4-shaped pieces, overlapping, top to bottom. Each is a clip of its own, so
        // the browser is never asked for one enormous image.
        int slice = (int) Math.round(width * A4);
        int step = Math.max(1, (int) Math.round(slice * (1 - OVERLAP)));
        List<Path> pieces = new ArrayList<>();
        int top = 0;
        boolean cut = false;
        while (top < whole) {
            if (pieces.size() >= SLICES_MAX) {
                cut = true;
                break;
            }
            int height = Math.min(slice, whole - top);
            String shot = call(live, tab, "Page.captureScreenshot",
                    "{\"format\":\"png\",\"captureBeyondViewport\":true,\"clip\":{\"x\":0,"
                    + "\"y\":" + top + ",\"width\":" + width + ",\"height\":" + height
                    + ",\"scale\":1}}");
            String data = jsonString(shot, "data");
            if (data.isEmpty()) throw new Stop("the browser took no picture of it.");
            try {
                Path file = Files.createTempFile("jrock-page-"
                        + (pieces.size() + 1) + "-", ".png");
                Files.write(file, Base64.getDecoder().decode(data));
                pieces.add(file);
            } catch (IOException | IllegalArgumentException ex) {
                throw new Stop("a piece of the picture could not be written: "
                        + ex.getMessage());
            }
            if (height < slice) break;    // that was the last of the page
            top += step;
        }
        note.accept("Photographed " + width + "x" + whole + " as " + pieces.size()
                + (pieces.size() == 1 ? " piece" : " overlapping A4 pieces")
                + (cut ? ", and there was more page below." : "."));
        String title = evaluate(live, tab, "document.title");
        // The tab is LEFT on the page, which is the point: a consent wall or a sign-in is
        // the operator's to deal with in that window, and then to ask again.
        lastSite = site(landed);
        return new Page(landed, lastSite, title, pieces, width, whole, cut);
    }

    // ---- Following a link, by what it says -------------------------------------
    // The description the operator gave, matched against the anchors of the page that is
    // open, and the best one navigated to.
    //
    // The DOM is read for this and only for this: the text of every link and where it
    // goes. No page script runs anywhere near this agent, nothing of the page's HTML is
    // parsed for meaning, and the addresses stay inside - the model asked for "the one
    // about the Cray-1 replica" and gets the page, not a URL.
    //
    // Links off the six hosts are dropped BEFORE anything is matched, so "follow the
    // advertisement" cannot resolve to one. What is left is scored on the words of the
    // description, and a tie goes to the shorter link text - a headline that says exactly
    // the words asked for beats a nav item that happens to contain them.
    private static String follow(String asked) {
        WebSocket live = browserSocket(note -> later(() -> say(note)));
        // The page to look on, when the request names one: "the AI link on
        // https://www.zdnet.com/news/" is how an agent says which page it means, and it
        // is the only thing that can say it when several tabs are open. Without one, the
        // page read last is meant.
        String page = addressIn(asked);
        String description = page.isEmpty() ? asked.trim()
                : asked.replace(page, " ").replaceAll("\\s+", " ")
                        .replaceAll("(?i)\\bon the page\\b|\\bon\\b\\s*$", "").trim();
        if (description.isEmpty()) {
            throw new Stop("that said which page but not what to open on it.");
        }
        String tab;
        String here;
        if (page.isEmpty()) {
            tab = lastTab(live);
            here = evaluate(live, tab, "location.href");
        } else {
            checkedHost(page, " for the page to look on");
            tab = tab(live, site(page));
            here = evaluate(live, tab, "location.href");
            // That tab may be somewhere else, or nowhere: put it on the page the request
            // names before reading its links.
            if (!page.equals(here)) {
                later(() -> say("Opening " + page + " to look for links on it\u2026"));
                fetchInto(live, tab, page, note -> later(() -> say(note)));
                here = evaluate(live, tab, "location.href");
            }
        }
        if (here.isEmpty() || here.startsWith("about:")) {
            throw new Stop("no page is open yet, so there is nothing to follow. Ask for a "
                    + "site or a search first.");
        }
        checkedHost(here, " for the page it is on");
        List<Link> found = linksOn(live, tab);
        if (found.isEmpty()) {
            throw new Stop("that page has no links this agent may follow.");
        }
        Link best = null;
        int bestScore = 0;
        for (Link link : found) {
            int score = score(link.text, description);
            if (score == bestScore && best != null && score > 0 && link.top < best.top
                    && link.text.length() == best.text.length()) {
                best = link;
                continue;
            }
            if (score > bestScore || (score == bestScore && best != null && score > 0
                    && link.text.length() < best.text.length())) {
                best = link;
                bestScore = score;
            }
        }
        if (best == null || bestScore < SCORE_FLOOR) {
            StringBuilder out = new StringBuilder("Nothing on that page clearly matches \""
                    + description + "\"" + (best == null ? "" : " - the closest is \""
                    + best.text + "\", which is not close enough to open uninvited")
                    + ". What it does offer:");
            int shown = 0;
            for (Link link : found) {
                if (shown++ >= 20) break;
                out.append("\n- ").append(link.text);
            }
            return out.toString();
        }
        Link going = best;
        later(() -> say("Following \"" + description + "\" to the link that reads \""
                + going.text + "\" (" + going.href + ")."));
        return read(going.href, going.text);
    }

    // What a page offers, as the words on its links - no addresses, because this answer
    // goes to a model that may read it out.
    private static String links(String about) {
        WebSocket live = browserSocket(note -> later(() -> say(note)));
        // Named a page? Then that one. Otherwise the page read last.
        String named = addressIn(about);
        String words = named.isEmpty() ? about.trim()
                : about.replace(named, " ")
                        .replaceAll("(?i)\\bon the page\\b|\\bon\\b\\s*$", "")
                        .replaceAll("\\s+", " ").trim();
        String tab;
        if (named.isEmpty()) {
            tab = lastTab(live);
        } else {
            checkedHost(named, " for the page to look on");
            tab = tab(live, site(named));
            if (!named.equals(evaluate(live, tab, "location.href"))) {
                fetchInto(live, tab, named, note -> later(() -> say(note)));
            }
        }
        about = words;
        String here = evaluate(live, tab, "location.href");
        if (here.isEmpty() || here.startsWith("about:")) {
            throw new Stop("no page is open yet. Ask for a site or a search first.");
        }
        List<Link> found = linksOn(live, tab);
        if (found.isEmpty()) return "That page has no links this agent may follow.";
        StringBuilder out = new StringBuilder("On " + site(here) + ", these can be opened "
                + "by asking for them in words (the addresses are for your next request, "
                + "not for reading out):");
        int shown = 0;
        for (Link link : found) {
            if (!about.isBlank() && score(link.text, about) == 0) continue;
            if (shown++ >= 30) break;
            out.append("\n- ").append(link.text).append("  ->  ").append(link.href);
        }
        if (shown == 0) out.append("\n- nothing matching \"" + about + "\".");
        return out.toString();
    }

    // The address in a request line, if it has one: what an agent writes when it says
    // which page it means.
    private static String addressIn(String line) {
        Matcher m = Pattern.compile("https://[^\\s<>\"']+").matcher(line);
        return m.find() ? m.group().replaceAll("[.,;:)\\]]+$", "") : "";
    }

    private static final class Link {
        final String text, href;
        final int top;

        Link(String text, String href, int top) {
            this.text = text; this.href = href; this.top = top;
        }
    }

    // Every link on the page worth offering: the ones with words on them, pointing at a
    // host on the list, each address once, in the order they come down the page.
    private static List<Link> linksOn(WebSocket live, String tab) {
        String script =
                "(function(){var out=[],seen={},as=document.querySelectorAll('a[href]');"
                + "for(var i=0;i<as.length&&out.length<400;i++){var a=as[i];"
                + "var t=(a.innerText||a.textContent||'').replace(/\\s+/g,' ').trim();"
                + "if(t.length<3)continue;var h=a.href;"
                + "if(!/^https:/i.test(h))continue;if(seen[h])continue;seen[h]=1;"
                + "var r=a.getBoundingClientRect();"
                + "out.push(t.substring(0,160)+'\u001f'+h+'\u001f'"
                + "+Math.round(r.top+(window.scrollY||0)));}"
                + "return out.join('\u001e');})()";
        String answer = evaluate(live, tab, script);
        List<Link> links = new ArrayList<>();
        Set<String> texts = new LinkedHashSet<>();
        for (String row : answer.split("\u001e")) {
            if (row.isBlank()) continue;
            String[] parts = row.split("\u001f", 3);
            if (parts.length < 2) continue;
            String text = parts[0].trim();
            String href = parts[1].trim();
            try {
                checkedHost(href, "");      // off the list: not a link this agent has
            } catch (Stop off) {
                continue;
            }
            if (!texts.add(text.toLowerCase(Locale.ROOT))) continue;
            int top = 0;
            try {
                top = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            } catch (NumberFormatException ex) {
                top = 0;
            }
            links.add(new Link(text, href, top));
        }
        return links;
    }

    // How well a link's words answer a description. Words rather than letters: "the one
    // about the Cray-1 replica" has to find "A Cray-1 supercomputer replica from 30
    // obsolete Mac Minis", and a letter-by-letter comparison of those two finds nothing.
    private static final Set<String> NOISE = new LinkedHashSet<>(java.util.Arrays.asList(
            "the", "a", "an", "one", "about", "on", "of", "for", "to", "and", "with",
            "article", "story", "piece", "page", "link", "open", "read", "please", "that",
            "this", "it", "me", "news", "item", "section", "part", "tab", "go"));

    // Below this, a match is a word that happened to appear rather than a link about
    // what was asked for - and the list of what the page does offer is the better answer.
    private static final int SCORE_FLOOR = 10;

    private static int score(String text, String description) {
        Set<String> wanted = words(description);
        if (wanted.isEmpty()) return 0;
        Set<String> have = words(text);
        if (have.isEmpty()) return 0;
        int score = 0;
        int hits = 0;
        for (String word : wanted) {
            if (have.contains(word)) {
                score += 2 + Math.min(6, word.length());
                hits++;
            } else if (word.length() > 4) {
                // A near miss on a long word - plurals, a hyphen written differently.
                for (String one : have) {
                    if (one.length() > 4 && (one.startsWith(word) || word.startsWith(one))) {
                        score += 2;
                        hits++;
                        break;
                    }
                }
            }
        }
        if (hits == 0) return 0;
        // HOW MUCH OF THE LINK was asked for, not just how much of the question was
        // found. "AI" is entirely the thing somebody asking for AI meant; "best AI
        // chatbots of 2026" is mostly something else that happens to contain it. Without
        // this, a long headline with one matching word beats the section link named after
        // it.
        score += (int) Math.round(10.0 * hits / have.size());
        // The whole description inside the link's own words is as close as it gets.
        if (text.toLowerCase(Locale.ROOT).contains(description.trim()
                .toLowerCase(Locale.ROOT))) {
            score += 20;
        }
        return score;
    }

    private static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        StringBuilder word = new StringBuilder();
        for (char c : (text == null ? "" : text).toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
                continue;
            }
            if (word.length() > 1 && !NOISE.contains(word.toString())) out.add(word.toString());
            word.setLength(0);
        }
        if (word.length() > 1 && !NOISE.contains(word.toString())) out.add(word.toString());
        return out;
    }

    // domain.tld out of an address: what anybody is told about where a page came from.
    private static String site(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return "(unknown)";
            host = host.toLowerCase(Locale.ROOT);
            for (String allowed : ALLOWED) {
                if (host.equals(allowed) || host.endsWith("." + allowed)) return allowed;
            }
            return host;
        } catch (IllegalArgumentException ex) {
            return "(unknown)";
        }
    }

    // Navigate one tab and wait for the page to stop changing, then check WHERE IT
    // ACTUALLY IS - which is not the address that was asked for, because a redirect is a
    // thing a site may do at any moment. Returns the address it ended on.
    private static String fetchInto(WebSocket live, String tab, String url,
                                    java.util.function.Consumer<String> note) {
        long started = System.currentTimeMillis();
        String failed = jsonString(call(live, tab, "Page.navigate",
                "{\"url\":" + quote(url) + "}"), "errorText");
        if (!failed.isEmpty()) throw new Stop("the browser could not open it: " + failed);

        // Loaded is not the same as finished: a page that is still filling itself in
        // keeps changing size. Both have to be true - complete, and the same twice - or
        // ten seconds have to have passed.
        String state = "";
        String measured = "";
        int same = 0;
        while (System.currentTimeMillis() - started < LOAD_TIMEOUT_MS) {
            sleep(SETTLE_POLL_MS);
            state = evaluate(live, tab, "document.readyState");
            String now = evaluate(live, tab, "String(document.documentElement."
                    + "scrollHeight) + '/' + String(document.body ? "
                    + "document.body.innerHTML.length : 0)");
            same = now.equals(measured) ? same + 1 : 0;
            measured = now;
            if ("complete".equals(state) && same >= 2) break;
        }
        note.accept("Loaded in " + (System.currentTimeMillis() - started)
                + " ms (readyState " + state
                + (same >= 2 ? ", settled)." : ", still changing when time ran out)."));

        String landed = evaluate(live, tab, "location.href");
        if (landed.isEmpty()) landed = url;
        String host = checkedHost(landed, " after a redirect");
        note.accept("Still on " + host + " (" + landed + ").");
        return landed;
    }

    // The tab for one site: the one it already has, or a new one. Everything the operator
    // does in it - a consent answered, a sign-in - stays done for every page on that site
    // after it.
    private static String tab(WebSocket live, String site) {
        synchronized (TABS) {
            String[] have = TABS.get(site);
            if (have != null) {
                try {
                    // Still there? A tab the operator closed is one to make again.
                    if ("1".equals(evaluate(live, have[1], "'1'"))) return have[1];
                } catch (Stop gone) {
                    TABS.remove(site);
                }
            }
            String made = call(live, null, "Target.createTarget", "{\"url\":\"about:blank\"}");
            String id = jsonString(made, "targetId");
            String attached = jsonString(call(live, null, "Target.attachToTarget",
                    "{\"targetId\":\"" + id + "\",\"flatten\":true}"), "sessionId");
            if (attached.isEmpty()) throw new Stop("the browser would not open a tab.");
            call(live, attached, "Page.enable", "{}");
            call(live, attached, "Runtime.enable", "{}");
            call(live, attached, "Emulation.setDeviceMetricsOverride",
                    "{\"width\":" + VIEW_WIDTH + ",\"height\":" + VIEW_HEIGHT
                    + ",\"deviceScaleFactor\":1,\"mobile\":false}");
            TABS.put(site, new String[] { id, attached });
            return attached;
        }
    }

    // The tab a follow-up acts on: the site of the page read last.
    private static String lastTab(WebSocket live) {
        String site = lastSite;
        if (site == null) {
            throw new Stop("no page has been read yet, so there is nothing to follow. Ask "
                    + "for a site or a search first.");
        }
        String[] have;
        synchronized (TABS) {
            have = TABS.get(site);
        }
        if (have == null) {
            throw new Stop("the tab for " + site + " is not open any more. Ask for the page "
                    + "again.");
        }
        return have[1];
    }

    // The browser, started once and kept: a second page in the same minute should not
    // wait for a cold start.
    //
    // Its profile is a folder beside this agent's own files and is NOT deleted. That is
    // what makes "deal with the consent wall once and ask again" true across restarts -
    // the cookie it writes, and any sign-in the operator does in that window, are still
    // there tomorrow. It is also not the operator's main browser profile: what is signed
    // in here is what they signed in to here.
    private static WebSocket browserSocket(java.util.function.Consumer<String> note) {
        WebSocket live = socket;
        Process running = browser;
        if (live != null && running != null && running.isAlive()) return live;
        closeBrowser();

        Path program = program();
        if (program == null) {
            throw new Stop("no Chromium browser was found. Edge, Chrome, Brave or Vivaldi "
                    + "- one of them has to be installed, because the DevTools Protocol is "
                    + "what this agent reads pages with.");
        }
        Path keep = profileDir == null
                ? Paths.get("").toAbsolutePath().resolve("jrock-browser-profile")
                : profileDir;
        try {
            Files.createDirectories(keep);
            profileDir = keep;
        } catch (IOException ex) {
            throw new Stop("the browser's profile folder could not be made at " + keep
                    + ": " + ex.getMessage());
        }
        List<String> command = new ArrayList<>(List.of(program.toString(),
                "--remote-debugging-port=" + debugPort,
                "--user-data-dir=" + profileDir,
                "--no-first-run", "--no-default-browser-check", "--disable-sync",
                "--disable-extensions", "--disable-component-update",
                "--disable-background-networking", "--no-service-autorun",
                "--window-size=" + VIEW_WIDTH + "," + VIEW_HEIGHT));
        if (headless) command.add("--headless=new");
        command.add("about:blank");
        note.accept("Starting " + program.getFileName() + (headless ? " (no window)"
                : " with its window") + " on port " + debugPort + ", profile " + profileDir
                + "\u2026");
        try {
            browser = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException ex) {
            throw new Stop("the browser would not start: " + ex.getMessage());
        }
        String version = await("/json/version");
        String url = jsonString(version, "webSocketDebuggerUrl");
        if (url.isEmpty()) throw new Stop("the browser gave no debugging socket.");
        try {
            socket = HTTP.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new Answers()).get(15, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new Stop("the browser's debugging socket would not open: " + ex);
        }
        note.accept("Browser ready: " + jsonString(version, "Browser") + ".");
        return socket;
    }

    private static void closeBrowser() {
        WebSocket live = socket;
        socket = null;
        synchronized (TABS) {
            TABS.clear();
        }
        lastSite = null;
        if (live != null) {
            try {
                live.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (RuntimeException ignored) {
                // Going anyway.
            }
        }
        Process running = browser;
        browser = null;
        if (running != null) {
            running.destroy();
            try {
                if (!running.waitFor(5, TimeUnit.SECONDS)) running.destroyForcibly();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                running.destroyForcibly();
            }
        }
        ANSWERS.clear();
    }

    // A Chromium: the one in the settings file, then the ones each of them installs as,
    // on PATH and in the usual places. Looked for when it is wanted, so installing one
    // later needs nothing from here.
    private static Path program() {
        Path told = browserProgram;
        if (told != null && Files.isRegularFile(told)) return told;
        String[] names = { "msedge.exe", "chrome.exe", "brave.exe", "vivaldi.exe",
                           "google-chrome", "chromium", "brave-browser", "microsoft-edge" };
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(Pattern.quote(java.io.File.pathSeparator))) {
                if (dir.isBlank()) continue;
                for (String name : names) {
                    Path at = Paths.get(dir).resolve(name);
                    if (Files.isRegularFile(at)) return at;
                }
            }
        }
        for (String at : new String[] {
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/microsoft-edge" }) {
            Path candidate = Paths.get(at);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    // ---- The DevTools Protocol -------------------------------------------------
    // One call, and the answer to it. Every page command goes inside the session the tab
    // was attached with; the ones about tabs themselves have no session.
    private static String call(WebSocket live, String session, String method, String params) {
        int id = NEXT_ID.incrementAndGet();
        live.sendText("{\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params
                + (session == null ? "" : ",\"sessionId\":\"" + session + "\"") + "}", true);
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            String answer;
            try {
                answer = ANSWERS.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Stop("interrupted while the browser was answering.");
            }
            if (answer == null) continue;
            if (answer.startsWith("{\"id\":" + id + ",")
                    || answer.startsWith("{\"id\":" + id + "}")) {
                String failed = jsonString(answer, "message");
                if (!failed.isEmpty() && answer.contains("\"error\"")) {
                    throw new Stop(method + " was refused: " + failed);
                }
                return answer;
            }
        }
        throw new Stop(method + " got no answer from the browser.");
    }

    private static String evaluate(WebSocket live, String session, String expression) {
        String answer = call(live, session, "Runtime.evaluate",
                "{\"expression\":" + quote(expression) + ",\"returnByValue\":true}");
        return jsonString(answer, "value");
    }

    // Answers arrive in pieces and events arrive uninvited: the pieces are joined, and
    // only the replies - the ones with an id - are kept.
    private static final class Answers implements WebSocket.Listener {
        @Override public CompletionStage<?> onText(WebSocket live, CharSequence data,
                                                   boolean last) {
            PARTIAL.append(data);
            if (last) {
                String whole = PARTIAL.toString();
                PARTIAL.setLength(0);
                if (whole.startsWith("{\"id\":")) ANSWERS.offer(whole);
            }
            live.request(1);
            return null;
        }
    }

    private static String await(String path) {
        for (int tries = 0; tries < 60; tries++) {
            try {
                HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + debugPort + path))
                        .timeout(Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (answer.statusCode() == 200) return answer.body();
            } catch (IOException | InterruptedException ex) {
                // Not up yet.
            }
            sleep(250);
        }
        throw new Stop("the browser did not answer on 127.0.0.1:" + debugPort
                + path + " - is something else using that port?");
    }

    // The pieces of CDP's JSON this needs. The /json endpoints are pretty-printed and the
    // socket's answers are not, so the space after a colon is skipped rather than assumed
    // away: assuming it away is a parser that finds nothing and says the browser is
    // broken.
    private static String jsonString(String json, String key) {
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) return "";
        int i = at + key.length() + 2;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ':')) i++;
        if (i >= json.length() || json.charAt(i) != '"') return "";
        StringBuilder out = new StringBuilder();
        for (i = i + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n': out.append('\n'); break;
                    case 'r': break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            try {
                                out.append((char) Integer.parseInt(
                                        json.substring(i + 1, i + 5), 16));
                            } catch (NumberFormatException ex) {
                                out.append('?');
                            }
                            i += 4;
                        }
                        break;
                    default: out.append(next);
                }
                continue;
            }
            if (c == '"') break;
            out.append(c);
        }
        return out.toString();
    }

    private static double jsonNumber(String json, String key) {
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) return -1;
        int from = at + key.length() + 2;
        while (from < json.length()
                && (json.charAt(from) == ' ' || json.charAt(from) == ':')) {
            from++;
        }
        int to = from;
        while (to < json.length() && "-0123456789.eE".indexOf(json.charAt(to)) >= 0) to++;
        try {
            return Double.parseDouble(json.substring(from, to));
        } catch (NumberFormatException | StringIndexOutOfBoundsException ex) {
            return -1;
        }
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private static String encode(String words) {
        try {
            return java.net.URLEncoder.encode(words, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return words;
        }
    }

    // ---- The page, as Markdown -------------------------------------------------
    // The picture, through this agent's own JRock: the prompt, the include, the send, and
    // the reply read out of JRock/messages/ - the same file the window shows, which is
    // what makes the answer checkable afterwards.
    private static String toMarkdown(Page page) {
        Path prompt = ownDirectory().resolve(PROMPT);
        if (!Files.isRegularFile(prompt)) {
            throw new Stop("there is no " + prompt + ", and that prompt is what turns the "
                    + "picture into Markdown.");
        }
        check(JRock.automationClearPrompt());
        check(JRock.automationLoadPrompt(prompt.toString()));
        String text = JRock.automationPromptText();
        if (text == null) throw new Stop("the prompt could not be read.");
        // The prompt ends with a bare @img line as a placeholder for a person reading it;
        // the include writes the real token, so the placeholder comes out first.
        StringBuilder without = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.trim().equals("@img")) continue;
            without.append(line).append('\n');
        }
        check(JRock.automationSetPrompt(without.toString().replaceAll("\\s+$", "") + "\n\n"));
        // Every piece, in the order they come down the page: the prompt says they overlap
        // and that they are one page, so the answer is one page's worth of Markdown.
        for (Path piece : page.pieces) {
            check(JRock.automationInclude(piece.toString(), "img"));
        }
        String[] sent = JRock.automationSend(SEND_TIMEOUT_MS);
        if (!"1".equals(sent[0])) {
            throw new Stop("the page was not read: " + (sent[3] == null ? "no reason given"
                    : sent[3]));
        }
        String file = JRock.automationMessageFile("assistant", sent[2]);
        if (file == null) throw new Stop("the reply was not written to JRock/messages/.");
        try {
            String markdown = new String(Files.readAllBytes(Paths.get(file)),
                    StandardCharsets.UTF_8).trim();
            if (markdown.isEmpty()) throw new Stop("the reply was empty.");
            return markdown;
        } catch (IOException ex) {
            throw new Stop("the reply could not be read: " + ex.getMessage());
        }
    }

    private static void check(String problem) {
        if (problem != null) throw new Stop(problem);
    }

    // ---- Listening -------------------------------------------------------------
    // One line in, the answer out, the connection closed - the shape every other agent
    // here answers in. Loopback only: nothing off this machine can ask.
    private static String listen() {
        try {
            server = new ServerSocket(PORT, 8, InetAddress.getLoopbackAddress());
        } catch (IOException ex) {
            return "Not listening on " + PORT + ": " + ex.getMessage()
                    + " - the window still works.";
        }
        Thread t = daemon(() -> {
            while (true) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(20_000);
                    serve(client);
                } catch (IOException ex) {
                    if (server == null || server.isClosed()) return;
                }
            }
        }, "jrock-browser-listener");
        t.start();
        return "Listening on 127.0.0.1:" + PORT + " for #page, #search and #hosts.";
    }

    private static void serve(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) >= 0 && c != '\n'; ) {
            if (c != '\r') line.append((char) c);
            if (line.length() > 2000) break;
        }
        String request = line.toString().trim();
        later(() -> say("Asked: " + request));
        String out;
        try {
            out = answered(request);
        } catch (Stop stop) {
            out = "Stopped: " + stop.getMessage();
        } catch (RuntimeException ex) {
            out = "Stopped: " + ex;
        }
        OutputStream os = client.getOutputStream();
        os.write(out.getBytes(StandardCharsets.UTF_8));
        os.flush();
        String shown = out;
        later(() -> show(shown));
    }

    // One request at a time, on the worker: a browser and a JRock are each one thing.
    private static String answered(String request) {
        try {
            return WORKER.submit(() -> answer(request)).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Stop("interrupted.");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable why = ex.getCause();
            if (why instanceof Stop) throw (Stop) why;
            throw new Stop(why == null ? String.valueOf(ex) : String.valueOf(why));
        }
    }

    // ---- The window ------------------------------------------------------------
    private static final java.awt.Color BACKGROUND = new java.awt.Color(0xF4F5F7);
    private static final java.awt.Color MUTED = new java.awt.Color(0x6B7280);

    private static void showWindow() {
        window = new javax.swing.JFrame("JRock - Browser (six hosts, read-only)");
        window.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                ServerSocket s = server;
                server = null;
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) { }
                }
                daemon(() -> {
                    closeBrowser();
                    JRock.automationEnd("browser agent closed");
                }, "jrock-browser-end").start();
            }
        });

        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);
        java.awt.Font small = base.deriveFont(java.awt.Font.PLAIN, 12f);
        java.awt.Font fixed = new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12);

        askField = new javax.swing.JTextField(46);
        askField.setFont(small);
        askField.setToolTipText("An address on one of the six hosts, or words to search for");
        javax.swing.JButton page = new javax.swing.JButton("Read page");
        javax.swing.JButton search = new javax.swing.JButton("Search");
        page.addActionListener(e -> ask("#page " + askField.getText().trim()));
        search.addActionListener(e -> ask("#search " + askField.getText().trim()));
        askField.addActionListener(e -> {
            String typed = askField.getText().trim();
            ask(typed.startsWith("http") ? "#page " + typed : "#search " + typed);
        });
        javax.swing.JButton follow = new javax.swing.JButton("Follow\u2026");
        follow.addActionListener(e -> ask("#follow " + askField.getText().trim()));
        javax.swing.JButton what = new javax.swing.JButton("Links");
        what.addActionListener(e -> ask("#links"));
        javax.swing.JButton which = new javax.swing.JButton("Hosts");
        which.addActionListener(e -> ask("#hosts"));

        javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(page);
        buttons.add(search);
        buttons.add(follow);
        buttons.add(what);
        buttons.add(which);

        javax.swing.JPanel askRow = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        askRow.setOpaque(false);
        askRow.add(new javax.swing.JLabel("Address or search:"), java.awt.BorderLayout.WEST);
        askRow.add(askField, java.awt.BorderLayout.CENTER);
        askRow.add(buttons, java.awt.BorderLayout.EAST);

        javax.swing.JLabel allowed = new javax.swing.JLabel("<html><div style='width:680px'>"
                + "Only these hosts, and their subdomains: <b>" + String.join(", ", ALLOWED)
                + "</b>. The address is checked again after the page has loaded, so a "
                + "redirect off the list is a page that does not come back. Nothing is "
                + "clicked for you: the browser's window stays on the page, so a consent "
                + "wall or a sign-in is yours to answer there - once, and its profile is "
                + "kept.</div></html>");
        allowed.setFont(small);
        allowed.setForeground(MUTED);
        allowed.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 2, 8, 2));

        result = new javax.swing.JTextArea(18, 72);
        result.setFont(fixed);
        result.setEditable(false);
        result.setLineWrap(true);
        result.setWrapStyleWord(true);
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(result);
        scroll.setBorder(javax.swing.BorderFactory.createTitledBorder(
                "What an agent is told"));

        status = new javax.swing.JLabel(" ");
        status.setFont(small);
        status.setForeground(MUTED);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 2, 6, 2));

        listening = new javax.swing.JLabel(" ");
        listening.setFont(small);
        listening.setForeground(MUTED);
        listening.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 2, 0, 2));

        javax.swing.JPanel north = new javax.swing.JPanel();
        north.setLayout(new javax.swing.BoxLayout(north, javax.swing.BoxLayout.Y_AXIS));
        north.setOpaque(false);
        for (javax.swing.JComponent row : new javax.swing.JComponent[] {
                askRow, allowed, status }) {
            row.setAlignmentX(0f);
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                    row.getPreferredSize().height));
            north.add(row);
        }

        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout());
        content.setBackground(BACKGROUND);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 12, 16));
        content.add(north, java.awt.BorderLayout.NORTH);
        content.add(scroll, java.awt.BorderLayout.CENTER);
        content.add(listening, java.awt.BorderLayout.SOUTH);
        window.setContentPane(content);

        window.pack();
        window.setMinimumSize(window.getSize());
        java.awt.Window jrock = JRock.automationWindow();
        if (jrock != null) {
            java.awt.Rectangle at = jrock.getBounds();
            window.setLocation(Math.max(0, at.x - window.getWidth()), at.y);
        } else {
            window.setLocationRelativeTo(null);
        }
        window.setVisible(true);
        say("Ready. A page is read by photographing it in A4 pieces and asking this JRock "
                + "what it says. The browser's own window is left on the page: consent "
                + "walls and sign-ins are yours to answer there, once.");
    }

    // The window asking the same thing an agent would, so what the operator can try is
    // what a model can ask for.
    private static void ask(String request) {
        say("Asked: " + request);
        daemon(() -> {
            String out;
            try {
                out = answered(request);
            } catch (Stop stop) {
                out = "Stopped: " + stop.getMessage();
            }
            String shown = out;
            later(() -> show(shown));
        }, "jrock-browser-ask").start();
    }

    private static void show(String text) {
        result.setText(text);
        result.setCaretPosition(0);
    }

    private static void say(String text) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        status.setText("<html><div style='width:680px'>" + safe + "</div></html>");
        System.out.println(text);
        JRock.automationNote("Browser agent: " + text);
    }

    // ---- Settings --------------------------------------------------------------
    // Three lines at most, beside this agent: which browser, which debugging port, and
    // whether its window shows. The six hosts are NOT here - they are the one thing this
    // agent will not take from a file.
    private static void readSettings() {
        Path file = ownDirectory().resolve(SETTINGS);
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.startsWith("browser=")) {
                    String at = text.substring("browser=".length()).trim();
                    if (!at.isEmpty()) browserProgram = Paths.get(at);
                } else if (text.startsWith("port=")) {
                    try {
                        debugPort = Integer.parseInt(text.substring("port=".length()).trim());
                    } catch (NumberFormatException ex) {
                        System.out.println("Not a port: " + text);
                    }
                } else if (text.startsWith("headless=")) {
                    headless = !"false".equalsIgnoreCase(
                            text.substring("headless=".length()).trim());
                }
            }
        } catch (IOException ex) {
            System.out.println("Could not read " + file + ": " + ex.getMessage());
        }
    }

    // ---- Plumbing --------------------------------------------------------------
    private static Path ownDirectory() {
        String source = System.getProperty("jdk.launcher.sourcefile");
        if (source != null && !source.isBlank()) {
            Path parent = Paths.get(source).toAbsolutePath().normalize().getParent();
            if (parent != null) return parent;
        }
        try {
            java.security.CodeSource code =
                    JRockBrowser.class.getProtectionDomain().getCodeSource();
            if (code != null && code.getLocation() != null) {
                Path at = Paths.get(code.getLocation().toURI()).toAbsolutePath().normalize();
                Path parent = Files.isDirectory(at) ? at : at.getParent();
                if (parent != null) return parent;
            }
        } catch (RuntimeException | java.net.URISyntaxException ignore) {
            // The working directory it is, then.
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Stop("interrupted.");
        }
    }

    private static Thread daemon(Runnable what, String name) {
        Thread t = new Thread(what, name);
        t.setDaemon(true);
        return t;
    }

    private static void onEdt(Runnable what) {
        try {
            javax.swing.SwingUtilities.invokeAndWait(what);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException ex) {
            System.out.println("Failed on the event thread: " + ex.getCause());
        }
    }

    private static void later(Runnable what) {
        javax.swing.SwingUtilities.invokeLater(what);
    }

    // What went wrong, said in one sentence that finishes "Stopped: ".
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop(String why) {
            super(why);
        }
    }

    private JRockBrowser() {
    }
}
