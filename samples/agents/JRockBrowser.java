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
// SEVEN HOSTS AND ONE PAGE, AND NOTHING ELSE. duckduckgo.com, businessinsider.com,
// zdnet.com, techcrunch.com, scmp.com, arstechnica.com, theverge.com - and Wikipedia's
// Current events portal, which is allowed as a PAGE while its host is not allowed at
// all. A list in this file, not a setting, not a line in a request, and not something a
// model can add to. The web is the one thing an agent reaches that nobody in the room controls, so
// what it may reach is written down where it can be read.
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
//      is KEPT between runs - or adopts the browser already running with that profile,
//      tabs and all. ONE TAB PER SITE: a second page on a site reuses the tab it already
//      has, so the tabs cannot outnumber the sites on the list however many pages are
//      read. The browser's OWN tabs count as that site's tab - the one left open on
//      duckduckgo.com by the last run is the one a search goes into - because a tab is
//      several hundred megabytes and a browser that collects one per request is a
//      machine running out of memory. The EMPTY tabs are closed, and nothing else ever
//      is: a blank tab is a blank page in a window somebody is watching hands-free,
//      and closing one takes nothing away;
//   2. navigates that site's tab, and waits for the page to stop changing: readyState
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
        "theverge.com",
        "artificialintelligence-news.com",
        "analyticsindiamag.com",
        "venturebeat.com",
        "marktechpost.com",
        "testingcatalog.com",
        "news9live.com",
        "officechai.com",
        "latestly.com",
    };

    // PART OF A HOST, AND NOT THE HOST. The third kind of entry, between a whole host and
    // a single page: a path and everything under it.
    //
    // Which is the only honest way to allow some of these. medium.com is a million blogs
    // and one of them is wanted: "/data-science-in-your-pocket" is a publication, and the
    // rest of Medium is no more on this list than the rest of the web. mezha.ua writes in
    // Ukrainian everywhere except under /en. And documentation is a product's own corner
    // of a company's enormous help site - helpx.adobe.com is the whole of Adobe's help,
    // docs.aws.amazon.com is the whole of AWS, and what is wanted is InDesign on the
    // desktop, Lightroom on mobile, and Bedrock.
    //
    // Matched on the host and the start of the path, so everything below the prefix is in
    // and its neighbours are out. A query is allowed here, unlike on a single page: a
    // documentation page with ?topic= on it is still that page.
    private static final String[][] ALLOWED_PREFIXES = {
        { "medium.com", "/data-science-in-your-pocket" },
        { "mezha.ua", "/en" },
        { "helpx.adobe.com", "/indesign/desktop" },
        { "helpx.adobe.com", "/lightroom/mobile" },
        { "docs.aws.amazon.com", "/bedrock/latest/userguide" },
    };

    // ONE PAGE, NOT A HOST. Wikipedia's Current events portal is a news summary of the
    // day, written by people, and the one page of that site worth reading here - so it
    // is allowed as a PAGE and en.wikipedia.org is not on the host list at all.
    //
    // Which has a pleasing consequence rather than a cost: every link on it goes either
    // out to a newspaper (off the list, refused) or further into Wikipedia (not on the
    // list either, also refused). So this is a page that can be read and not travelled
    // from, and #follow on it says so instead of wandering into an encyclopaedia.
    //
    // Matched on scheme, host and path, with a fragment ignored because a fragment is
    // part of the same page - and with a QUERY REFUSED, because "?action=edit" is a
    // different page wearing the same path.
    private static final String[] ALLOWED_PAGES = {
        "https://en.wikipedia.org/wiki/Portal:Current_events",
    };

    // Where a search goes. DuckDuckGo's own HTML form, which needs no JavaScript to show
    // results and is on the list like everything else.
    private static final String SEARCH = "https://duckduckgo.com/?q=";

    // The prompt that turns the photograph into Markdown, beside this file.
    private static final String PROMPT = "jrock-prompt-page-to-markdown.txt";

    // What this agent remembers: where the browser is, and the two numbers worth
    // changing. Beside this file, like every other agent's settings.
    private static final String SETTINGS = "jrock-browser.txt";

    // Which browser was left running, written into the browser's own profile folder: the
    // debugging port and that browser's socket address. It is how a restarted agent finds
    // the browser it had before, with its tabs, instead of opening a second one - see
    // ownBrowser.
    private static final String SOCKET_MARK = "jrock-browser-socket.txt";

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
    private static javax.swing.JTextArea profileLine;
    // ONE TAB PER SITE, kept. Asking for another page on a site it already has reuses that
    // site's tab, so there is never a second arstechnica.com tab to wonder about - and
    // because the only sites there are are the ones on the list, the number of tabs cannot
    // grow past that however many pages are read.
    //
    // Per site rather than one in total, because the operator may have answered a consent
    // wall on one and be signed in on another, and a single tab would throw one away to
    // show the other.
    //
    // Site -> { the browser's id for the tab, the session it is attached with }. This is
    // what THIS RUN is using; the browser's own tabs outlive it, which is what adopt is
    // for.
    private static final java.util.Map<String, String[]> TABS = new java.util.LinkedHashMap<>();
    // Whether the browser on the debugging port is one this agent started or one it found
    // already running with its profile. A found one is not ours to kill: see closeBrowser.
    private static volatile boolean adoptedBrowser;
    // The site of the page read last, which is the one "follow the link that says..."
    // means. Nothing else would: a description with no page to look at is not a request.
    private static volatile String lastSite;

    private static javax.swing.JFrame window;
    private static javax.swing.JTextField askField;
    private static javax.swing.JTextArea result;
    private static javax.swing.JTextArea status;
    private static javax.swing.JLabel listening;
    private static volatile ServerSocket server;

    public static void main(String[] args) {
        String shot = null;
        String linksOn = null;
        String wanted = null;
        String lookUp = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--shot") && i + 1 < args.length) shot = args[++i];
            else if (args[i].equals("--links") && i + 1 < args.length) linksOn = args[++i];
            else if (args[i].equals("--wanted") && i + 1 < args.length) wanted = args[++i];
            else if (args[i].equals("--docs") && i + 1 < args.length) lookUp = args[++i];
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

        // --docs: the walk, printed. Which pages a question walks through in a product's
        // documentation and which one it stops on - no pictures, no model, no JRock and
        // no window, so the thing being checked is the walking and nothing else.
        if (lookUp != null) {
            try {
                Walk walk = walk(lookUp, System.out::println);
                System.out.println("\nIn: " + walk.set);
                System.out.println("Asked: " + walk.wanted);
                System.out.println("Walked: " + (walk.trail.isEmpty() ? "(nowhere - the "
                        + "front page is where it stopped)" : String.join(" -> ", walk.trail)));
                System.out.println("Stopped on: " + walk.landed);
                System.out.println("Page fetches: " + walk.fetched + " of "
                        + DOCS_FETCHES_MAX);
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
        openOnStartup();
    }

    // The browser, started and put on the search page before anything is asked of it.
    //
    // The first page of a session is often a consent wall or a sign-in rather than the
    // page, and those are the operator's to answer in that window - so the window is
    // there from the start, with something harmless in it, and whatever it wants
    // answering can be answered while nobody is waiting. The profile keeps the answer, so
    // the first real request lands on the page itself.
    //
    // On a thread of its own and quietly: starting a Chromium takes a second or two, a
    // machine with none should still have a working agent for everything else, and a
    // failure here is a line in the window rather than an agent that did not come up.
    private static void openOnStartup() {
        daemon(() -> {
            String home = front("duckduckgo.com");
            try {
                WebSocket live = browserSocket(note -> later(() -> say(note)));
                String tab = tab(live, site(home));
                // A TAB ALREADY ON IT IS LEFT ALONE. Adopting a browser that was already
                // running hands back the search tab it already has, and navigating it
                // again would throw away whatever is on it - results the operator is
                // reading, a consent dialogue they are halfway through answering - to put
                // the same site there a second time.
                String here = evaluate(live, tab, "location.href");
                if (!site(home).equals(site(here))) {
                    fetchInto(live, tab, home, note -> later(() -> say(note)));
                }
                later(() -> say("The browser is open on duckduckgo.com. Anything it asks "
                        + "for - a consent dialogue, a sign-in - can be answered in that "
                        + "window now, and it is remembered."));
            } catch (Stop stop) {
                later(() -> say("The browser is not open yet: " + stop.getMessage()));
            } catch (RuntimeException ex) {
                later(() -> say("The browser is not open yet: " + ex));
            }
        }, "jrock-browser-startup").start();
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
            case "#docs":
                if (rest.isEmpty()) {
                    throw new Stop("#docs wants a question with the product in it, as "
                            + "#docs how do I set a baseline grid in indesign");
                }
                return docs(rest);
            case "#links":
                return links(rest);
            default:
                throw new Stop("\"" + line + "\" is not something this agent answers. Ask "
                        + "#page <site or address>, #search <words>, #docs <question with "
                        + "the product in it>, #follow <what to open>, #links or #hosts.");
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
        { "theverge.com", "theverge", "the verge", "verge" },
        { "artificialintelligence-news.com", "artificial intelligence news", "ai news",
          "artificialintelligence news" },
        { "analyticsindiamag.com", "analytics india", "analytics india magazine",
          "analyticsindiamag" },
        { "venturebeat.com", "venturebeat", "venture beat" },
        { "marktechpost.com", "marktechpost", "mark tech post" },
        { "testingcatalog.com", "testingcatalog", "testing catalog" },
        { "news9live.com", "news9", "news 9", "news9live", "news nine" },
        { "officechai.com", "officechai", "office chai" },
        { "latestly.com", "latestly", "the latestly" },
        // The parts of hosts, by the full address each one starts at (see
        // ALLOWED_PREFIXES). A name for a documentation set is the product's name:
        // nobody says "helpx dot adobe dot com slash indesign slash desktop".
        { "https://medium.com/data-science-in-your-pocket", "data science in your pocket",
          "medium data science in your pocket", "data science in my pocket" },
        { "https://mezha.ua/en/", "mezha", "mezha ua", "mezha english" },
        { "https://helpx.adobe.com/indesign/desktop.html", "indesign", "adobe indesign",
          "indesign help", "indesign documentation", "indesign docs" },
        { "https://helpx.adobe.com/lightroom/mobile.html", "lightroom", "adobe lightroom",
          "lightroom mobile", "lightroom help", "lightroom documentation",
          "lightroom docs" },
        { "https://docs.aws.amazon.com/bedrock/latest/userguide/", "bedrock",
          "aws bedrock", "bedrock docs", "bedrock documentation", "bedrock user guide" },
        { "https://en.wikipedia.org/wiki/Portal:Current_events", "current events",
          "wikipedia current events", "wikipedia", "wiki current events", "wiki news",
          "portal current events", "what is happening in the world" },
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
                if (!spoken.equals(site[i])) continue;
                // A name may stand for a whole address (the one allowed page) or for a
                // domain (everything else).
                return site[0].startsWith("https://") ? site[0] : front(site[0]);
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
        out.append("\nAnd these PARTS of hosts - that path and everything under "
                + "it, and nothing else on those hosts:");
        for (String[] prefix : ALLOWED_PREFIXES) {
            out.append("\n- ").append(prefix[0]).append(prefix[1]);
        }
        out.append("\nAnd one page, whose host is otherwise not allowed at all:");
        for (String page : ALLOWED_PAGES) {
            out.append("\n- ").append(page)
                    .append(" - ask for it as \"current events\". Nothing can be followed "
                            + "from it: every link on it leads off the list.");
        }
        out.append("\nThree of those parts are documentation, and #docs looks a question "
                + "up in one of them by walking its own labels, up to "
                + DOCS_FETCHES_MAX + " page fetches deep:");
        for (String[] set : DOCS) out.append("\n- ").append(set[1]);
        return out.append("\nEach can be asked for by domain or by name - \"zdnet\", "
                + "\"ars technica\", \"venturebeat\", \"data science in your pocket\", "
                + "\"indesign\", \"lightroom\", \"bedrock\". A search goes to DuckDuckGo. "
                + "The list is in the agent's own source: nothing in a request can add "
                + "to it.").toString();
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
        // The one page, and the parts of hosts, that are allowed without the host being.
        if (allowedPage(uri) != null) return host;
        if (allowedPrefix(uri) != null) return host;
        throw new Stop(host + " is not a host this agent reads" + when + ", or not at that "
                + "path. The hosts are: " + String.join(", ", ALLOWED) + ". The parts of "
                + "hosts are: " + prefixList() + ". And the one page is "
                + ALLOWED_PAGES[0] + ".");
    }

    // Which allowed part of a host this address is under, as "host/prefix", or null when
    // it is under none of them.
    private static String allowedPrefix(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!scheme.equals("https")) return null;
        for (String[] allowed : ALLOWED_PREFIXES) {
            // The host exactly, or www. in front of it - and not a subdomain beyond that:
            // "x.medium.com" is somebody else's blog, which is the whole point here.
            if (!host.equals(allowed[0]) && !host.equals("www." + allowed[0])) continue;
            // The prefix itself, as a page ("/indesign/desktop.html") or as a folder
            // ("/indesign/desktop/anything"), and nothing that merely starts with the
            // same letters ("/indesign/desktop-beta").
            if (path.equals(allowed[1]) || path.equals(allowed[1] + ".html")
                    || path.startsWith(allowed[1] + "/")) {
                return allowed[0] + allowed[1];
            }
        }
        return null;
    }

    private static String prefixList() {
        StringBuilder out = new StringBuilder();
        for (String[] allowed : ALLOWED_PREFIXES) {
            if (out.length() > 0) out.append(", ");
            out.append(allowed[0]).append(allowed[1]);
        }
        return out.toString();
    }

    // Which allowed page this address is, or null when it is none of them.
    private static String allowedPage(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String query = uri.getQuery();
        if (query != null && !query.isEmpty()) return null;
        for (String allowed : ALLOWED_PAGES) {
            URI page = URI.create(allowed);
            String pagePath = page.getPath();
            while (pagePath.endsWith("/")) pagePath = pagePath.substring(0, pagePath.length() - 1);
            if (scheme.equals("https") && host.equals(page.getHost().toLowerCase(Locale.ROOT))
                    && path.equals(pagePath)) {
                return allowed;
            }
        }
        return null;
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
        return photograph(live, tab, landed, note);
    }

    // The second half of a fetch: the page ALREADY in that tab, measured and taken in
    // A4-shaped pieces.
    //
    // Its own method because a documentation walk navigates several pages and
    // photographs only the one it arrives at (see docs): the pictures are the expensive
    // part - a dozen of them per page, and a model call to read them - and the pages
    // walked through on the way are read for their links alone.
    private static Page photograph(WebSocket live, String tab, String landed,
                                   java.util.function.Consumer<String> note) {
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

    // ---- Looking something up in documentation ---------------------------------
    // A question, and the page of a product's documentation that answers it: the set's
    // own front page read for its links, the best one followed, and that page read for
    // ITS links, until the question stops matching anything deeper. Then - and only then
    // - the page is photographed and read.
    //
    // WHY A WALK AND NOT A SEARCH. Documentation has a search box, and it is JavaScript
    // asking a service that answers in JSON: nothing this agent can use, since it reads
    // pages from their pictures and runs no page's script. But documentation is also the
    // one kind of site built as a tree whose branches are LABELLED - "Text", "Grids and
    // guides", "Create baseline grids" - so the labels are the index, and following them
    // is what a person does with the same page.
    //
    // FIVE FETCHES, AND NOT ONE MORE. Every step is a page load in a real browser, and a
    // tree this deep is a tree whose answer is not down this branch anyway. The cap is
    // the loop's only exit that is not "nothing deeper matches": it ends the walk with
    // whatever it has rather than failing, because the page five steps in is still an
    // answer about the question and an operator waiting on a voice assistant would
    // rather have it than a refusal.
    private static final int DOCS_FETCHES_MAX = 5;

    // The documentation sets, by the page each one starts at. What they are CALLED is in
    // NAMES with every other name, so "indesign", "lightroom docs" and "bedrock user
    // guide" need saying in only one place; this table is what counts as documentation.
    private static final String[][] DOCS = {
        { "https://helpx.adobe.com/indesign/desktop.html", "Adobe InDesign (desktop)" },
        { "https://helpx.adobe.com/lightroom/mobile.html", "Adobe Lightroom (mobile)" },
        { "https://docs.aws.amazon.com/bedrock/latest/userguide/",
          "the Amazon Bedrock user guide" },
    };

    // Where a walk ended up, and how it got there. The tab is in it because the page is
    // still on screen in it: what reads the page comes next, and photographs this.
    private static final class Walk {
        final WebSocket live;
        final String tab, landed, set, wanted;
        final List<String> trail;
        final int fetched;

        Walk(WebSocket live, String tab, String landed, String set, String wanted,
             List<String> trail, int fetched) {
            this.live = live; this.tab = tab; this.landed = landed;
            this.set = set; this.wanted = wanted;
            this.trail = trail; this.fetched = fetched;
        }
    }

    // The walk, and then the page it stopped on, read.
    private static String docs(String asked) {
        Walk walk = walk(asked, note -> later(() -> say(note)));
        later(() -> say("Reading the page from its picture…"));
        Page page = photograph(walk.live, walk.tab, walk.landed,
                note -> later(() -> say(note)));
        String markdown = toMarkdown(page);
        return "Documentation: " + walk.set
                + "\nSite: " + page.site
                + (page.title.isEmpty() ? "" : "\nPage: " + page.title)
                + "\nAsked: " + walk.wanted
                + "\nWalked: " + (walk.trail.isEmpty()
                        ? "the front page of the set, which is where the question stopped "
                          + "matching anything deeper"
                        : String.join(" → ", walk.trail))
                + "   (" + walk.fetched + " of " + DOCS_FETCHES_MAX + " page fetches)"
                + "\nURL: " + page.url + "   (for your next request - never read an "
                + "address out loud)\n\n" + markdown
                + (page.cut ? "\n\n(The page went on past " + SLICES_MAX + " pieces, so "
                        + "this is the top of it.)" : "")
                + "\n\n(If this is the wrong page, the words of the question are what "
                + "chose it: ask again with the words the documentation would use, or "
                + "#links to see what the page it stopped on offers.)";
    }

    // The walk itself: no pictures, no model, and every step reported through note - so
    // --docs can run it on its own and show where it went.
    private static Walk walk(String asked, java.util.function.Consumer<String> note) {
        String question = asked.trim();
        // Which documentation, said anywhere in the question: "how do I set a baseline
        // grid in indesign" names it at the end, "bedrock: what is a guardrail" at the
        // front. The longest matching name wins, so "lightroom mobile" is not read as
        // "lightroom" with the word "mobile" left in the question.
        String root = null;
        String label = null;
        String named = "";
        for (String[] set : DOCS) {
            for (String[] names : NAMES) {
                if (!names[0].equals(set[0])) continue;
                for (int i = 1; i < names.length; i++) {
                    if (!containsWords(question, names[i])) continue;
                    if (names[i].length() <= named.length()) continue;
                    root = set[0];
                    label = set[1];
                    named = names[i];
                }
            }
        }
        if (root == null) {
            StringBuilder out = new StringBuilder("that did not say which documentation to "
                    + "look in. These are the ones this agent has:");
            for (String[] one : DOCS) out.append("\n- ").append(one[1]);
            throw new Stop(out.append("\nName one in the question, as \"#docs how do I "
                    + "set a baseline grid in indesign\".").toString());
        }
        // The product's name comes OUT of the question before anything is scored: it is
        // on every link in its own documentation, so leaving it in would score the nav
        // bar as highly as the page being looked for.
        String wanted = withoutWords(question, named);
        if (words(wanted).isEmpty()) {
            throw new Stop("that named " + label + " but not what to look up in it.");
        }

        final String set = label;          // settled, and said in the notes below
        WebSocket live = browserSocket(note);
        String tab = tab(live, site(root));
        String prefix = allowedPrefix(URI.create(root));
        List<String> trail = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String here = root;
        String landed;
        int fetched = 0;
        int followed = 0;      // the score of the link that brought us to this page
        while (true) {
            note.accept("Looking in " + set + ": page " + (fetched + 1) + " of at most "
                    + DOCS_FETCHES_MAX + "…");
            landed = fetchInto(live, tab, here, note);
            fetched++;
            seen.add(bare(landed));
            // Checked again after loading, as everywhere else: documentation redirects
            // constantly - a version, a language, a product that was renamed - and a
            // redirect out of this set is where the walk stops.
            checkedHost(landed, " after it loaded");
            if (!prefix.equals(allowedPrefix(URI.create(landed)))) {
                note.accept("That left " + prefix + ", so the walk stops here.");
                break;
            }
            if (fetched >= DOCS_FETCHES_MAX) break;

            Link best = null;
            int bestScore = 0;
            for (Link link : linksOn(live, tab)) {
                if (seen.contains(bare(link.href))) continue;        // been there
                // Inside THIS documentation and no other: a link to Photoshop's help, or
                // to Adobe's front page, is allowed by the list and is not an answer to
                // a question about InDesign.
                if (!prefix.equals(allowedPrefix(URI.create(link.href)))) continue;
                int score = score(stemmed(link.text), stemmed(wanted));
                if (score > bestScore || (score == bestScore && score > 0
                        && best != null && nearerTheTop(link, best))) {
                    best = link;
                    bestScore = score;
                }
            }
            // Nothing deeper is about the question: this page is the answer, or as close
            // as this documentation gets to one.
            if (best == null || bestScore < SCORE_FLOOR) break;
            // And nothing deeper is a BETTER match than the link that got us here.
            // STRICTLY better, which is the rule that keeps this a walk down a tree
            // rather than a tour of one floor of it: "Grids and guides" then "Create
            // baseline grids" goes deeper, while "Test your guardrail" then "Create your
            // guardrail" is a step sideways between pages that match the question exactly
            // as well. Without this the walk spent all five fetches on siblings and
            // stopped on the last one it happened to see, which is how a question about
            // what a guardrail IS ended up on the page about deleting one.
            //
            // It is also what makes one step enough on a site that renders its whole
            // navigation tree on every page, as the AWS user guide does: everything is
            // already visible from the front page, so there is nothing deeper to find.
            if (bestScore <= followed) break;
            followed = bestScore;
            trail.add(best.text);
            note.accept("Following \"" + best.text + "\" (" + bestScore + ").");
            here = best.href;
        }
        return new Walk(live, tab, landed, set, wanted, trail, fetched);
    }

    // Both sides of a documentation score, with plurals taken off.
    //
    // Documentation is written in the plural and asked about in the singular: the heading
    // is "Guardrails", "Denied topics", "Grids and guides", and the question is "a
    // guardrail", "block a topic", "the baseline grid". Unstemmed those meet as a near
    // miss, which is worth a quarter of an exact word - so the page whose title IS the
    // answer loses to any page that repeats a word of the question.
    //
    // Only in the walk. The scoring itself stays as it is, because #follow is calibrated
    // against it on news headlines, where "Tesla" and "Teslas" are different claims.
    private static String stemmed(String text) {
        StringBuilder out = new StringBuilder();
        for (String word : words(text)) {
            if (out.length() > 0) out.append(' ');
            out.append(singular(word));
        }
        return out.toString();
    }

    private static String singular(String word) {
        if (word.length() > 4 && word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";   // policies -> policy
        }
        if (word.length() > 4 && (word.endsWith("ses") || word.endsWith("xes")
                || word.endsWith("zes") || word.endsWith("ches") || word.endsWith("shes"))) {
            return word.substring(0, word.length() - 2);         // boxes -> box
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);         // grids -> grid
        }
        return word;
    }

    // Which of two equally-matching links to take: the one nearer the top of the
    // documentation, and then the one with less said on it.
    //
    // Nearer the top means fewer path segments and then a shorter address, because that
    // is how documentation is filed - an overview sits above the pages it introduces
    // ("/guardrails.html" over "/guardrails-permissions-kms.html"), and a question that
    // matches both of them equally means the overview. The words on the link break a
    // remaining tie, shorter first: a label that is nothing but the topic beats one that
    // mentions it in passing.
    private static boolean nearerTheTop(Link link, Link than) {
        int depth = segments(link.href);
        int theirs = segments(than.href);
        if (depth != theirs) return depth < theirs;
        if (link.href.length() != than.href.length()) {
            return link.href.length() < than.href.length();
        }
        return link.text.length() < than.text.length();
    }

    private static int segments(String url) {
        String path = URI.create(bare(url)).getPath();
        if (path == null || path.isEmpty()) return 0;
        int segments = 0;
        for (String part : path.split("/")) {
            if (!part.isEmpty()) segments++;
        }
        return segments;
    }

    // An address with its fragment cut off: two links to the same page, one of them at a
    // heading on it, are the same page for the purpose of not reading it twice.
    private static String bare(String url) {
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    // Are all the words of a name in this text, as whole words? Word by word rather than
    // as a substring, so "ai news" is not found inside "said newsagent".
    private static boolean containsWords(String text, String name) {
        Set<String> have = words(text);
        Set<String> wanted = words(name);
        if (wanted.isEmpty()) return false;
        return have.containsAll(wanted);
    }

    // What a preposition is doing at either end of a question once the product has been
    // taken out of it: "a baseline grid in indesign" loses "indesign" and would keep the
    // "in", which is a word the question does not end with in any language.
    private static final Set<String> HANGING = new LinkedHashSet<>(java.util.Arrays.asList(
            "in", "on", "for", "of", "about", "from", "with", "at", "into", "to", "the",
            "a", "an", "and", "or", "my", "its"));

    // The same text with a name's words taken out of it, which is how a question keeps
    // its question and loses the product it is about.
    private static String withoutWords(String text, String name) {
        Set<String> drop = words(name);
        List<String> kept = new ArrayList<>();
        for (String word : text.split("\\s+")) {
            String plain = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (plain.isEmpty() || drop.contains(plain)) continue;
            kept.add(word);
        }
        while (!kept.isEmpty() && hanging(kept.get(kept.size() - 1))) {
            kept.remove(kept.size() - 1);
        }
        while (!kept.isEmpty() && hanging(kept.get(0))) kept.remove(0);
        return String.join(" ", kept).replaceAll("^[\\s:,\\-]+|[\\s:,\\-]+$", "").trim();
    }

    private static boolean hanging(String word) {
        return HANGING.contains(word.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
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
    // Links off the list are dropped BEFORE anything is matched, so "follow the
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
        // Where we already are, so the links back to it can be dropped: "Skip to
        // content", "Expand all", the logo that points at the page it is on. Following
        // one would reload what is already in front of us, and on a page whose other
        // links all lead off the list they would be the only candidates left.
        String here = evaluate(live, tab, "location.href");
        String bare = here.contains("#") ? here.substring(0, here.indexOf('#')) : here;
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
            String plain = href.contains("#") ? href.substring(0, href.indexOf('#')) : href;
            if (plain.equals(bare)) continue;           // a link to this very page
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

    // The tab for one site: the one this run is using, the one the BROWSER already has, or
    // a new one - in that order, and a new one is the last resort. Everything the operator
    // does in it - a consent answered, a sign-in - stays done for every page on that site
    // after it.
    private static String tab(WebSocket live, String site) {
        synchronized (TABS) {
            String session = null;
            String[] have = TABS.get(site);
            if (have != null) {
                try {
                    // Still there? A tab the operator closed, or one whose session went
                    // with a socket, is one to find again - and the scan below usually
                    // finds it, still open and still on that site.
                    if ("1".equals(evaluate(live, have[1], "'1'"))) session = have[1];
                } catch (Stop gone) {
                    // It is not answering; the entry is stale either way.
                }
                if (session == null) TABS.remove(site);
            }

            // WHAT THE BROWSER HAS, read once: the tab already on this site, the empty
            // ones, and how many tabs there are in all.
            Set<String> ours = new LinkedHashSet<>();
            for (String[] held : TABS.values()) ours.add(held[0]);
            String onSite = null;
            List<String> blanks = new ArrayList<>();
            int pages = 0;
            for (String target : jsonObjects(call(live, null, "Target.getTargets", "{}"),
                    "targetInfos")) {
                // Pages only: a worker, an extension's background page and the devtools
                // window are all targets, and none of them is a tab to read a site in.
                if (!"page".equals(jsonString(target, "type"))) continue;
                String id = jsonString(target, "targetId");
                String url = jsonString(target, "url");
                if (id.isEmpty() || url.startsWith("devtools://")) continue;
                pages++;
                if (ours.contains(id)) continue;
                if (blankTab(url)) blanks.add(id);
                else if (onSite == null && site.equals(site(url))) onSite = id;
            }

            // THE TAB THE BROWSER ALREADY HAS, ADOPTED - the one on this site, and failing
            // that an empty one, and only failing both a new one.
            if (session == null && onSite != null) session = take(live, site, onSite);
            if (session == null && !blanks.isEmpty()) {
                session = take(live, site, blanks.remove(0));
            }
            if (session == null) {
                String made = call(live, null, "Target.createTarget",
                        "{\"url\":\"about:blank\"}");
                pages++;
                session = take(live, site, jsonString(made, "targetId"));
            }

            // AND THE EMPTY TABS NOBODY IS USING GO. The operator watches this window
            // hands-free, so a blank tab in it is a blank page they are looking at for no
            // reason - and the browser collects them: the command line that starts one
            // ends in about:blank, and a Chromium whose profile is already open answers
            // that by opening a blank tab in the window it already has.
            //
            // Closing an EMPTY tab takes nothing away, which is why these and nothing else
            // are closed here: there is no page on it, nothing signed in, nothing half
            // read. Never the last one, though - Chromium closes the window with its final
            // tab, and that would be this agent shutting its own browser.
            for (String id : blanks) {
                if (pages <= 1) break;
                call(live, null, "Target.closeTarget", "{\"targetId\":\"" + id + "\"}");
                pages--;
            }

            // AND TO THE FRONT, because a tab that is not the one on top cannot be
            // photographed: Chromium is not drawing it, so Page.captureScreenshot has
            // nothing to send and never answers at all. A tab made here is in front
            // already, which is why this was never needed while every page got a new one;
            // a tab reused - the one this run has for the site, or the one the browser
            // already had - is wherever the operator left it.
            //
            // It is also what the operator should see: the window shows the page being
            // read, which is how they know what was read and can deal with a consent wall
            // standing in front of it.
            String[] held = TABS.get(site);
            if (held != null) {
                call(live, null, "Target.activateTarget",
                        "{\"targetId\":\"" + held[0] + "\"}");
            }
            return session;
        }
    }

    // Why tab asks the browser what it has, every time:
    //
    // TABS is only this run's memory, and the browser outlives the run - its profile is
    // kept, the window survives an agent that was killed rather than closed, and the
    // operator opens tabs in it themselves. Remembering nothing about those means opening
    // a second duckduckgo.com tab for the first search after a restart, a third after the
    // next one, and a browser nobody closed ending up with a tab per request, which is a
    // browser that runs the machine out of memory. One call ends that.
    //
    // It is also how a lost session repairs itself: the tab is still there, so it is
    // attached to again rather than replaced. And it is where the empty tabs are found and
    // closed, which is the only kind of tab this agent ever closes.

    // One tab, in this agent's hands: attached to, told to report page and script events,
    // and sized to the window pages are read in.
    private static String take(WebSocket live, String site, String id) {
        if (id.isEmpty()) throw new Stop("the browser named no tab to use.");
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

    // A tab with nothing on it: the one the browser opens itself at startup, and whatever
    // each Chromium calls its new-tab page.
    private static boolean blankTab(String url) {
        String at = url.toLowerCase(Locale.ROOT);
        return at.isEmpty() || at.equals("about:blank") || at.startsWith("chrome://new")
                || at.startsWith("edge://new") || at.startsWith("brave://new")
                || at.startsWith("vivaldi://startpage");
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

    // WHERE THE BROWSER'S OWN PROFILE IS. A folder beside this agent's own files,
    // "jrock-browser-profile", which is the whole of what the browser this agent starts
    // knows: its cookies, its cache, its history, its local storage. Not a line of it is
    // shared with the operator's own browser - a different --user-data-dir is a different
    // browser as far as Chromium is concerned - so what is signed in here is what was
    // signed in HERE, and nothing this agent does can read a session of theirs.
    //
    // Kept rather than thrown away, which is the point of naming it: it is why "deal with
    // the consent wall once and ask again" survives a restart. It is also a cache, so it
    // grows; deleting the folder is how it is reset, and the next page starts from a
    // browser that has never been anywhere. Both of those are reasons the window says
    // where it is rather than leaving it to be found (see showProfile).
    private static Path profileFolder() {
        return profileDir != null ? profileDir
                : Paths.get("").toAbsolutePath().resolve("jrock-browser-profile");
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
        if (live != null && (running != null && running.isAlive()
                || adoptedBrowser && listening() != null)) {
            return live;
        }
        closeBrowser();

        // THE BROWSER THAT IS ALREADY THERE, ADOPTED RATHER THAN STARTED AGAIN - with its
        // tabs, which adopt then hands out per site.
        //
        // Starting a Chromium whose profile is already in use does not start a browser: it
        // asks the running one to open the page and exits. So the process this agent would
        // hold is dead a second later, every request after that would "start" the browser
        // again, and each of those adds a tab to a window that is never counted. Finding it
        // instead costs one request to the debugging port.
        //
        // ONLY IF IT IS OURS: see ownBrowser. Adopting somebody else's browser would be
        // adopting the operator's own, with every session they are signed in to in it, and
        // this agent's whole arrangement is that it never goes near those.
        String found = ownBrowser();
        if (found != null) {
            profileDir = profileFolder();
            later(JRockBrowser::showProfile);
            adoptedBrowser = true;
            note.accept("A browser with this agent's profile is already running on port "
                    + debugPort + " - using it, and the tabs it already has.");
            return connect(found, note);
        }

        Path program = program();
        if (program == null) {
            throw new Stop("no Chromium browser was found. Edge, Chrome, Brave or Vivaldi "
                    + "- one of them has to be installed, because the DevTools Protocol is "
                    + "what this agent reads pages with.");
        }
        Path keep = profileFolder();
        try {
            Files.createDirectories(keep);
            profileDir = keep;
        } catch (IOException ex) {
            throw new Stop("the browser's profile folder could not be made at " + keep
                    + ": " + ex.getMessage());
        }
        later(JRockBrowser::showProfile);   // it exists now, and has a size
        List<String> command = new ArrayList<>(List.of(program.toString(),
                "--remote-debugging-port=" + debugPort,
                "--user-data-dir=" + profileDir,
                "--no-first-run", "--no-default-browser-check", "--disable-sync",
                "--disable-extensions", "--disable-component-update",
                "--disable-background-networking", "--no-service-autorun",
                "--window-size=" + VIEW_WIDTH + "," + VIEW_HEIGHT));
        if (headless) command.add("--headless=new");
        command.add("about:blank");
        // Whether anything is answering there BEFORE this starts one, which decides who
        // owns what afterwards. A Chromium asked to open a profile that is already open
        // does not start a browser: the one holding that profile takes the request and
        // the process just started exits a moment later - too late to be noticed here,
        // because the port answers at once. And a browser this agent did not start is
        // never one for it to kill.
        boolean wasAlreadyThere = listening() != null;
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
        // STARTED, OR HANDED OVER. Either way the browser answering now is the one to
        // work with; what this decides is that a handed-over one is not "started" again
        // for every request after this, each time adding a tab to a window nobody is
        // counting.
        Process started = browser;
        if (wasAlreadyThere || started != null && !started.isAlive()) {
            browser = null;
            adoptedBrowser = true;
            note.accept("That profile was already open in a browser - using that one, and "
                    + "the tabs it already has.");
        }
        return connect(version, note);
    }

    // The debugging socket of the browser that answered, and the agent's own hold on it.
    private static WebSocket connect(String version,
                                     java.util.function.Consumer<String> note) {
        String url = jsonString(version, "webSocketDebuggerUrl");
        if (url.isEmpty()) throw new Stop("the browser gave no debugging socket.");
        try {
            socket = HTTP.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new Answers()).get(15, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new Stop("the browser's debugging socket would not open: " + ex);
        }
        rememberBrowser(version);
        note.accept("Browser ready: " + jsonString(version, "Browser") + ".");
        return socket;
    }

    // Is anything answering on the debugging port? One try and a short wait, which is what
    // makes this a different question from await's: that one waits for a browser being
    // started, this one asks about a browser that either is there already or is not.
    private static String listening() {
        try {
            HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + debugPort + "/json/version"))
                    .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (answer.statusCode() == 200) return answer.body();
        } catch (IOException ex) {
            // Nothing there, which is an answer.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    // A BROWSER THIS AGENT STARTED AND LEFT RUNNING, as its /json/version - or null, which
    // means there is nothing of ours on that port and one has to be started.
    //
    // Answered from a note this agent wrote itself when it last opened a browser: the port
    // and that browser's debugging socket, in the profile folder, which is the folder only
    // this agent's browsers use. The socket address carries the id of one running browser
    // and no other, so the question "is the thing answering on 9222 the browser I started
    // yesterday?" is answered by comparing two strings.
    //
    // IT HAS TO BE ASKED, because adopting the wrong browser would be adopting the
    // OPERATOR'S OWN - somebody with Chrome open on the same debugging port for their own
    // devtools has every session they are signed in to in it, and this agent's whole
    // arrangement is that it never goes near those. A note left behind by a browser that
    // has since been killed matches nothing: the next browser on that port has an id of
    // its own.
    //
    // (Chromium's own DevToolsActivePort file would say this, and on Edge there is no such
    // file when the port is given rather than chosen. Asking the browser for its command
    // line needs --enable-automation, which sets navigator.webdriver and gets pages
    // refused - a high price for a question this answers for nothing.)
    private static String ownBrowser() {
        List<String> lines;
        try {
            Path mark = profileFolder().resolve(SOCKET_MARK);
            if (!Files.isRegularFile(mark)) return null;
            lines = Files.readAllLines(mark, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        if (lines.size() < 2 || !lines.get(0).trim().equals(String.valueOf(debugPort))) {
            return null;
        }
        String version = listening();
        if (version == null) return null;
        String socketAddress = lines.get(1).trim();
        return !socketAddress.isEmpty()
                && socketAddress.equals(jsonString(version, "webSocketDebuggerUrl"))
                ? version : null;
    }

    // The note itself, written whenever a browser is in hand - so a run that is killed
    // rather than closed leaves behind the one thing the next run needs to find its
    // browser again instead of opening a second one.
    private static void rememberBrowser(String version) {
        String socketAddress = jsonString(version, "webSocketDebuggerUrl");
        if (socketAddress.isEmpty()) return;
        try {
            Files.write(profileFolder().resolve(SOCKET_MARK),
                    (debugPort + "\n" + socketAddress + "\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            // Then the next run starts a browser of its own, which is no worse than this
            // agent has ever been.
        }
    }

    private static void forgetBrowser() {
        try {
            Files.deleteIfExists(profileFolder().resolve(SOCKET_MARK));
        } catch (IOException | RuntimeException ex) {
            // The address in it names a browser that is gone, and nothing matches that.
        }
    }

    // The socket goes, and the browser with it IF THIS AGENT STARTED IT. One it found
    // already running is not its to end: the window belongs to whoever opened it, the
    // pages in it are theirs, and the next run adopts it again rather than opening a
    // second browser beside it. The tab map goes either way - a session belongs to the
    // socket it was made on, and adopt finds those tabs again.
    private static void closeBrowser() {
        WebSocket live = socket;
        socket = null;
        synchronized (TABS) {
            TABS.clear();
        }
        lastSite = null;
        boolean theirs = adoptedBrowser;
        adoptedBrowser = false;
        if (live != null) {
            try {
                live.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (RuntimeException ignored) {
                // Going anyway.
            }
        }
        Process running = browser;
        browser = null;
        if (running != null && !theirs) {
            // The note goes only if the browser goes. A process of ours that is ALREADY
            // dead is the launcher of a browser that took the request and lives on (see
            // browserSocket), and forgetting that one would be throwing away the address
            // the next run needs to find it.
            if (running.isAlive()) forgetBrowser();
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

    // The objects of one JSON array, each as its own text. The browser's list of tabs is
    // the only answer here that holds more than one thing worth reading, and a brace
    // counter is the whole of what reading it takes - with strings skipped over, because a
    // brace inside a page title is not a brace.
    private static List<String> jsonObjects(String json, String key) {
        List<String> out = new ArrayList<>();
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) return out;
        int from = json.indexOf('[', at);
        if (from < 0) return out;
        int depth = 0;
        int started = -1;
        boolean inString = false;
        for (int i = from + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth++ == 0) started = i;
            } else if (c == '}') {
                if (--depth == 0 && started >= 0) {
                    out.add(json.substring(started, i + 1));
                    started = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return out;
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

    // How long a request may be.
    //
    // A request here is an address, a search or a question about documentation, so
    // this is room to spare rather than a limit anybody will meet.
    private static final int REQUEST_MAX = 8_000;

    // ONE REQUEST, DECODED AS UTF-8. The agents speak UTF-8 to each other, and a request
    // carries words: a letter's name, a question, a whole document. Reading it a byte at
    // a time into chars would be reading it as Latin-1, which turns the two bytes of a
    // Cyrillic letter into two characters of their own - "Ð¡" for "С" - and that then
    // goes to a model, into a file name, or into a saved file as the mojibake it has
    // become.
    //
    // Longer than REQUEST_MAX is refused rather than cut: half a document, saved under a
    // name that says it is the whole one, is worse than an error.
    private static String readRequest(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1; ) {
            if (b == '\n') break;
            if (bytes.size() >= REQUEST_MAX) {
                throw new IOException("the request is longer than " + REQUEST_MAX
                        + " bytes");
            }
            bytes.write(b);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8)
                .replaceAll("\r$", "").trim();
    }

    private static void serve(Socket client) throws IOException {
        String request;
        try {
            request = readRequest(client.getInputStream());
        } catch (IOException tooLong) {
            String why = "Stopped: " + tooLong.getMessage() + ".";
            client.getOutputStream().write(why.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            later(() -> say(why));
            return;
        }
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
        // Counted, so the title is right whatever the list holds.
        window = new javax.swing.JFrame("JRock - Browser (" + ALLOWED.length
                + " hosts, read-only)");
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
        askField.setToolTipText("An address on one of the hosts on the list, or words to search for");
        javax.swing.JButton page = new javax.swing.JButton("Read page");
        javax.swing.JButton search = new javax.swing.JButton("Search");
        page.addActionListener(e -> ask("#page " + askField.getText().trim()));
        search.addActionListener(e -> ask("#search " + askField.getText().trim()));
        askField.addActionListener(e -> {
            String typed = askField.getText().trim();
            ask(typed.startsWith("http") ? "#page " + typed : "#search " + typed);
        });
        javax.swing.JButton look = new javax.swing.JButton("Look up\u2026");
        look.setToolTipText("Look the question up in documentation - name the product in "
                + "it, as \"how do I set a baseline grid in indesign\"");
        look.addActionListener(e -> ask("#docs " + askField.getText().trim()));
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
        buttons.add(look);
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

        // Always on screen, because "which browser is this and what does it know?" is a
        // fair question to have an answer to without reading the log - and because the
        // answer to "how do I make it forget everything?" is this path.
        profileLine = new javax.swing.JTextArea(1, 1);
        profileLine.setFont(small);
        profileLine.setForeground(MUTED);
        profileLine.setEditable(false);   // but selectable: the path can be copied out
        profileLine.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 2, 6, 2));
        profileLine.setOpaque(false);
        profileLine.setLineWrap(true);
        profileLine.setWrapStyleWord(true);

        // A WRAPPING TEXT AREA AND NOT A LABEL. A label wraps only if its HTML names a
        // width in CSS pixels, and Swing scales those by the display - "width:680px"
        // asks for 886 device pixels on a 130% screen - so a window with less than that
        // to give cuts the line off on the right. And an address or a path is one long
        // token with no space in it, which Swing's HTML cannot break anywhere at all. A
        // text area wraps to the width it HAS, and breaks inside a word when the word is
        // wider than that.
        status = new javax.swing.JTextArea(1, 1);
        status.setFont(small);
        status.setForeground(MUTED);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 2, 6, 2));
        status.setEditable(false);
        status.setOpaque(false);
        status.setFocusable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);

        listening = new javax.swing.JLabel(" ");
        listening.setFont(small);
        listening.setForeground(MUTED);
        listening.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 2, 0, 2));

        javax.swing.JPanel north = new javax.swing.JPanel();
        north.setLayout(new javax.swing.BoxLayout(north, javax.swing.BoxLayout.Y_AXIS));
        north.setOpaque(false);
        for (javax.swing.JComponent row : new javax.swing.JComponent[] {
                askRow, allowed }) {
            row.setAlignmentX(0f);
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                    row.getPreferredSize().height));
            north.add(row);
        }
        // The two wrapping rows are NOT capped at the height they have while the window
        // is built - a single space each - which would be one line for ever however much
        // arrives. They grow, and the list below gives up the room.
        for (javax.swing.JComponent row : new javax.swing.JComponent[] {
                profileLine, status }) {
            row.setAlignmentX(0f);
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
        // NOTHING THAT CAN BE CHANGED HAS THE FOCUS WHEN THIS OPENS. Swing gives it to
        // the first focusable component, which in these windows is a field, a checkbox or
        // a button - a setting one stray keypress away from being something else, with a
        // caret blinking in it as an invitation. The root pane takes it instead: it has no
        // key bindings of its own, so a keypress does nothing at all, and Tab still steps
        // into the window the usual way. Done in a later() so it happens AFTER Swing has
        // handed the focus out on activation.
        window.getRootPane().setFocusable(true);
        later(() -> window.getRootPane().requestFocusInWindow());
        showProfile();
        say("Ready. A page is read by photographing it in A4 pieces and asking this JRock "
                + "what it says. The browser's own window is left on the page: consent "
                + "walls and sign-ins are yours to answer there, once.");
    }

    // The profile line: where the browser's own cookies and cache live, and how much of
    // them there are. On the event thread.
    //
    // The size is measured on a thread of its own and filled in when it arrives: a
    // browser cache is tens of thousands of files and hundreds of megabytes - 284 MB
    // after a few days of this - and walking it is not something a window should wait
    // for. Until then the line says the path, which is the part that was asked for.
    private static void showProfile() {
        // --shot, --links and --docs start the same browser with no window of ours to
        // put this line in. They print what they find instead, so there is nothing to
        // fill in and nothing to complain about.
        if (profileLine == null) return;
        Path folder = profileFolder();
        boolean there = Files.isDirectory(folder);
        profileLine.setText("Browser profile (its own cookies, cache and sign-ins, kept "
                + "between runs; delete the folder to forget everything): " + folder
                + (there ? "" : "  - not made yet"));
        if (!there) return;
        daemon(() -> {
            long bytes = folderBytes(folder);
            later(() -> profileLine.setText("Browser profile (its own cookies, cache and "
                    + "sign-ins, kept between runs; delete the folder to forget "
                    + "everything): " + folder + "  - " + (bytes / (1024 * 1024)) + " MB"));
        }, "browser-profile-size").start();
    }

    // Every byte under a folder, deepest first, with anything unreadable skipped: a
    // browser writes and deletes files in its own cache while this walks it, and a
    // number that is a few files out is still the number that was wanted.
    private static long folderBytes(Path folder) {
        long[] total = { 0 };
        try (java.util.stream.Stream<Path> all = Files.walk(folder)) {
            all.forEach(one -> {
                try {
                    if (Files.isRegularFile(one)) total[0] += Files.size(one);
                } catch (IOException gone) {
                    // A cache file that went while this was looking at it.
                }
            });
        } catch (IOException ex) {
            return total[0];
        }
        return total[0];
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
        // Printed and logged whether or not there is a window to show it in: --shot,
        // --links and --docs run the same code with no window of ours.
        if (status == null) {
            System.out.println(text);
            return;
        }
        // No HTML escaping: this is text in a text area, so "<", ">" and "&" are the
        // characters they are, and an address with a query keeps its "&".
        status.setText(text);
        int wide = status.getWidth();
        if (wide > 0) status.setSize(wide, Short.MAX_VALUE);
        status.revalidate();
        System.out.println(text);
        JRock.automationNote("Browser agent: " + text);
    }

    // ---- Settings --------------------------------------------------------------
    // Three lines at most, beside this agent: which browser, which debugging port, and
    // whether its window shows. The hosts are NOT here - they are the one thing this
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
