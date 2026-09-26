// A JRock automation: where the operator is, for other agents to ask - the place, its
// coordinates, or both, typed in or found out.
//
// Run it the way JRock itself runs - no build step, no Maven, one file - with the jar on
// the class path:
//
//     java -cp jrock.jar JRockLocation.java [--working-dir <dir>]
//
// COPY jrock.jar HERE FIRST, take the flags as JRock's own, and expect a typed static
// call rather than reflection: all of that is JRockDocInventory.java's opening comment,
// and it is true of every automation in this directory. Read that file first.
//
//   1. JRock is started and taken as an automation: its prompt read-only and Mic always
//      on muted. This JRock is the agent's own - it asks the model what the agent cannot
//      work out by itself - so run it in a folder of its own, with History off there.
//   2. A small window opens beside JRock's. Nothing in it is kept across runs:
//        - Place: as you would say it, "Munich, Bavaria" - typed at every start, or, with
//          Determine automatically, found from the coordinates.
//        - Coordinates: typed, from GPS, or found from the place. GPS is Windows' own
//          location service - GPS, Wi-Fi, whatever it has - asked through PowerShell, as
//          JRock asks Windows for speech; Settings > Privacy > Location decides whether it
//          may. They are kept with every digit the service gave.
//        - Round to: how many digits after the point other agents get. The fields keep
//          them all.
//        - Refresh GPS every: with GPS, ask it again every 5 or 30 minutes, and keep the
//          answer ready (see below).
//      In GPS mode it locates once at every start, so the coordinates are this run's.
//   3. Other agents ask over TCP, on the loopback interface only: connect to
//      127.0.0.1:PORT, send a line, and read until the connection closes. What comes back
//      is one line in the same form as the one sent.
//
// The line: the place, the coordinates, or the place and then the coordinates, with
// commas between and the latitude first -
//
//     Munich, Bavaria
//     Munich, Bavaria, Lat: 48.13, Lon: 11.59
//     Lat: 48.13, Lon: 11.59
//
// Sent a place, it answers with the place and its coordinates; sent coordinates, with the
// place there and the coordinates; sent both, with both. "#here" instead stands for where
// the operator is: what this window says, with whatever it is set to find out found out.
// A half it could not find is left out - an empty line is "don't know" - and this window's
// status line says why. The push-to-talk agent sends "#here" when the model's answer is
// "I need to know your location", and gives the model the line that comes back.
//
// Finding out goes through openstreetmap.org, with this agent's JRock doing the work:
//   1. The model is asked for the address on openstreetmap.org that answers the question
//      (jrock-prompt-place-to-gps-url.txt, jrock-prompt-gps-to-place-url.txt). Building
//      it - which service, which query string - is the model's to know.
//   2. The address out of its reply must parse as a Java URL, be https, and point to
//      openstreetmap.org or one of its subdomains - on the default port, with no user
//      name in it. Anything else is not fetched.
//   3. JRock fetches it, as Fetch URL does by hand, into a second prompt
//      (jrock-prompt-place-to-gps.txt, jrock-prompt-gps-to-place-name.txt) where #url is,
//      and the model reads the answer out of it.
// The four prompt files are read again every time, so an edit counts from the next one.
//
// Answers for places and coordinates that were sent are kept for the run, so asking
// again is instant. "#here" is kept only while Refresh GPS is on, and made again at every
// refresh - that is what lets it answer at once; otherwise every "#here" is found afresh.
//
// A Bedrock API key is needed only for finding things out: typed in, or from GPS, a
// location is served without one.

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JRockLocation {

    // JRock's agents' ports (see JRockTimer.java), and the location agent's, the second
    // of them. Other agents connect to PORT.
    public static final int AGENT_PORTS_FIRST = 47470;
    public static final int PORT = AGENT_PORTS_FIRST + 1;

    // Starting a JVM's worth of Swing, on a cold machine.
    private static final long READY_TIMEOUT_MS = 120_000;

    // How long a client has to send its line, and the longest line taken.
    private static final int CLIENT_TIMEOUT_MS = 5000;
    private static final int LINE_MAX = 512;

    // How long one question to the model may take, and one answer to a client - which is
    // two questions and a fetch. The push-to-talk agent waits a little longer than that.
    private static final long REPLY_TIMEOUT_MS = 75_000;
    private static final long ANSWER_TIMEOUT_MS = 170_000;

    // How long Windows has to find a position - a cold GPS takes a while, Wi-Fi seconds.
    private static final int LOCATE_TIMEOUT_S = 15;

    // The prompts, expected beside this file; and their placeholders.
    private static final String PLACE_TO_GPS_URL = "jrock-prompt-place-to-gps-url.txt";
    private static final String PLACE_TO_GPS = "jrock-prompt-place-to-gps.txt";
    private static final String GPS_TO_PLACE_URL = "jrock-prompt-gps-to-place-url.txt";
    private static final String GPS_TO_PLACE_NAME = "jrock-prompt-gps-to-place-name.txt";
    private static Path prompts;

    // The request that stands for where the operator is.
    public static final String HERE = "#here";

    // A number, with a point or a comma in it.
    private static final String NUMBER = "[-+]?\\d+(?:[.,]\\d+)?";
    private static final String LABEL = "(Lat(?:itude)?|Lon(?:gitude)?|Lng)";

    // The coordinates at the end of a line - "Lat: 48.13, Lon: 11.59", either way round,
    // the labels short or long - and whatever comes before them, which is the place. A
    // place may have commas of its own ("Munich, Bavaria"), so it is everything up to the
    // comma before the first label.
    private static final Pattern LINE = Pattern.compile(
            "^(?:(.*?)\\s*,\\s*)?" + LABEL + "\\s*:\\s*(" + NUMBER + ")\\s*,\\s*"
            + LABEL + "\\s*:\\s*(" + NUMBER + ")\\s*$", Pattern.CASE_INSENSITIVE);

    // An address in the model's reply: the first thing that looks like one.
    private static final Pattern ADDRESS = Pattern.compile("https?://[^\\s<>\"'`()\\[\\]{}]+",
            Pattern.CASE_INSENSITIVE);

    // The one site addresses are fetched from.
    private static final String SITE = "openstreetmap.org";

    // The model's "there is nothing there".
    private static final Pattern NOT_FOUND = Pattern.compile("\\W*NOT FOUND\\W*", Pattern.CASE_INSENSITIVE);

    // The longest place name taken from the model - a sentence is not a place.
    private static final int PLACE_MAX = 200;

    // Where the coordinates come from.
    private static final String[] SOURCES = {
            "Enter manually", "From GPS", "From the place (openstreetmap.org)" };
    private static final int MANUAL = 0, GPS = 1, FROM_PLACE = 2;

    // Round to: null for every digit, else that many after the point.
    private static final String[] ROUNDINGS = {
            "Don't", "2 digits", "3 digits", "4 digits", "5 digits", "6 digits" };
    private static final Integer[] DIGITS = { null, 2, 3, 4, 5, 6 };

    // Refresh GPS every: 0 for never, else minutes.
    private static final String[] REFRESHES = { "Don't", "5 min", "30 min" };
    private static final int[] REFRESH_MINUTES = { 0, 5, 30 };

    // Asks Windows' location service, through .NET's GeoCoordinateWatcher, run as
    // powershell -Command - no JNI, as JRock does it for speech. Prints one line: FIX|
    // latitude|longitude|accuracy in metres, "R" so that every digit the service gave
    // survives and InvariantCulture so the point is a point; or NOFIX|why.
    private static final String LOCATE_SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "try {",
            "  Add-Type -AssemblyName System.Device",
            "  $w = New-Object System.Device.Location.GeoCoordinateWatcher("
                    + "[System.Device.Location.GeoPositionAccuracy]::High)",
            "  $null = $w.TryStart($false, [TimeSpan]::FromSeconds(" + LOCATE_TIMEOUT_S + "))",
            "  $deadline = (Get-Date).AddSeconds(" + LOCATE_TIMEOUT_S + ")",
            "  while ($w.Status -ne 'Ready' -and $w.Status -ne 'Disabled' "
                    + "-and $w.Permission -ne 'Denied' -and (Get-Date) -lt $deadline) {",
            "    Start-Sleep -Milliseconds 200",
            "  }",
            "  $c = $w.Position.Location",
            "  if ($w.Permission -eq 'Denied') {",
            "    'NOFIX|Windows does not let apps have the location (Settings > Privacy > Location).'",
            "  } elseif ($w.Status -eq 'Disabled') {",
            "    'NOFIX|the location service is off (Settings > Privacy > Location).'",
            "  } elseif ($w.Status -ne 'Ready' -or $c.IsUnknown) {",
            "    'NOFIX|no position within " + LOCATE_TIMEOUT_S + " seconds (' + $w.Status + ').'",
            "  } else {",
            "    'FIX|' + [string]::Format([Globalization.CultureInfo]::InvariantCulture,",
            "        '{0:R}|{1:R}|{2:R}', $c.Latitude, $c.Longitude, $c.HorizontalAccuracy)",
            "  }",
            "  $w.Stop()",
            "} catch {",
            "  'NOFIX|' + $_.Exception.Message",
            "}",
            "[Console]::Out.Flush()");

    // Why a step failed, in one sentence.
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        Stop(String why) { super(why); }
    }

    // A location: any of the three may be missing, but the coordinates come in pairs.
    private static final class Where {
        final String place;
        final BigDecimal lat, lon;
        Where(String place, BigDecimal lat, BigDecimal lon) {
            this.place = place == null || place.isBlank() ? null : place.trim().replaceAll("\\s+", " ");
            boolean both = lat != null && lon != null;
            this.lat = both ? lat : null;
            this.lon = both ? lon : null;
        }
        boolean hasCoordinates() { return lat != null; }
        boolean isEmpty() { return place == null && lat == null; }
    }

    // What the window says, taken on the EDT for the resolver to work from.
    private static final class Settings {
        boolean placeAuto;
        String place;
        int source;
        String lat, lon;
        Integer digits;
        int refreshMinutes;
    }

    // Everything that asks JRock or Windows goes through this one thread: automation
    // calls must not overlap, and two clients asking at once are answered one after the
    // other. A daemon, so closing JRock's window ends the process as it always does.
    private static final java.util.concurrent.ExecutorService RESOLVER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> daemon(r, "jrock-location-resolver"));

    // Answers found for lines that were sent, for the run; and "#here", while Refresh GPS
    // is on. The key is the line as sent, with every digit.
    private static final java.util.Map<String, Where> cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Where hereCache;

    // Why this JRock cannot ask a model, or null when it can.
    private static volatile String noModel;

    private static javax.swing.JFrame window;
    private static javax.swing.JTextField placeField;
    private static javax.swing.JCheckBox placeAuto;
    private static javax.swing.JComboBox<String> source;
    private static javax.swing.JTextField latField;
    private static javax.swing.JTextField lonField;
    private static javax.swing.JComboBox<String> roundTo;
    private static javax.swing.JComboBox<String> refresh;
    private static javax.swing.JButton locateButton;
    private static javax.swing.JLabel preview;
    private static javax.swing.JLabel status;
    private static javax.swing.JLabel listening;
    private static javax.swing.Timer refreshTimer;
    private static volatile ServerSocket server;
    // EDT only: true while the agent itself writes into the fields, which is not an edit.
    private static boolean filling;

    public static void main(String[] args) {
        String problem;
        try {
            prompts = ownDirectory();
            for (String name : new String[] { PLACE_TO_GPS_URL, PLACE_TO_GPS, GPS_TO_PLACE_URL, GPS_TO_PLACE_NAME }) {
                if (!Files.isRegularFile(prompts.resolve(name))) {
                    throw new Stop("there is no " + prompts.resolve(name) + ".");
                }
            }
            JRock.main(args);
            // Ready, or ready but without a key - which only finding things out needs.
            // Only no window at all is a reason not to start.
            noModel = JRock.automationAwaitReady(READY_TIMEOUT_MS);
            problem = JRock.automationWindow() == null ? noModel
                    : JRock.automationBegin("location agent");
        } catch (Stop stop) {
            problem = stop.getMessage();
        }
        if (problem != null) {
            String why = problem;
            System.out.println("Stopped: " + why);
            onEdt(() -> javax.swing.JOptionPane.showMessageDialog(JRock.automationWindow(),
                    "The location agent could not start:\n\n" + why,
                    "Location", javax.swing.JOptionPane.ERROR_MESSAGE));
            return;
        }
        onEdt(JRockLocation::showWindow);
        String said = listen();
        later(() -> listening.setText(said));
        System.out.println("Location is ready. " + said);
        later(JRockLocation::locateNow);
    }

    // ---- The line --------------------------------------------------------------
    // A line as it is sent, or null when it is not one - a line that is only a place is
    // always one.
    static Where parse(String line) {
        String text = line == null ? "" : line.trim();
        Matcher m = LINE.matcher(text);
        if (!m.matches()) return text.isEmpty() ? new Where(null, null, null) : new Where(text, null, null);
        boolean firstIsLat = m.group(2).toLowerCase().startsWith("lat");
        boolean secondIsLat = m.group(4).toLowerCase().startsWith("lat");
        if (firstIsLat == secondIsLat) return null;
        BigDecimal a = number(m.group(3));
        BigDecimal b = number(m.group(5));
        BigDecimal lat = firstIsLat ? a : b;
        BigDecimal lon = firstIsLat ? b : a;
        if (onEarth(lat, lon) != null) return null;
        return new Where(m.group(1), lat, lon);
    }

    // A location as a line, with digits after the point (null for every one there is).
    static String format(Where w, Integer digits) {
        String coordinates = w.hasCoordinates()
                ? "Lat: " + rounded(w.lat, digits) + ", Lon: " + rounded(w.lon, digits) : null;
        if (w.place == null) return coordinates == null ? "" : coordinates;
        return coordinates == null ? w.place : w.place + ", " + coordinates;
    }

    // A coordinate as typed - a comma for the point taken too - or null when it is not one.
    private static BigDecimal number(String text) {
        if (text == null) return null;
        try {
            return new BigDecimal(text.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // Null when the pair is a place on the Earth, else why not.
    private static String onEarth(BigDecimal lat, BigDecimal lon) {
        if (lat == null || lon == null) return "not a number.";
        if (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0) return "a latitude is between -90 and 90.";
        if (lon.abs().compareTo(BigDecimal.valueOf(180)) > 0) return "a longitude is between -180 and 180.";
        return null;
    }

    // As many digits after the point as it has, or at most that many, with no zeros
    // padding it out: 11.50 is 11.5.
    private static String rounded(BigDecimal value, Integer digits) {
        if (digits == null) return value.toPlainString();
        BigDecimal r = value.setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros();
        return (r.scale() < 0 ? r.setScale(0) : r).toPlainString();
    }

    // ---- Answering -------------------------------------------------------------
    // The answer to one request line. On the resolver.
    private static String answer(String request) {
        Settings s = settings();
        if (request.trim().equalsIgnoreCase(HERE)) return format(here(s, true), s.digits);
        Where asked = parse(request);
        if (asked == null) {
            later(() -> say("Not a location: \"" + request + "\" - answered with an empty line."));
            return "";
        }
        String key = format(asked, null);
        Where known = cache.get(key);
        if (known == null) {
            known = complete(asked, "\"" + key + "\"");
            if (known.place != null && known.hasCoordinates()) cache.put(key, known);
        } else {
            later(() -> say("Answered \"" + key + "\" from what was found before."));
        }
        return format(known, s.digits);
    }

    // Where the operator is, as the window is set: GPS asked, if that is where the
    // coordinates come from, and whatever half is to be found, found. The fields are
    // filled with the result. asked is whether a client is waiting, which is when a
    // "#here" kept by Refresh GPS is good enough. On the resolver.
    private static Where here(Settings s, boolean asked) {
        Where kept = hereCache;
        if (asked && kept != null && s.refreshMinutes > 0 && s.source == GPS) {
            later(() -> say("Answered #here from the last refresh."));
            return kept;
        }
        BigDecimal lat = null, lon = null;
        if (s.source == MANUAL) {
            lat = number(s.lat);
            lon = number(s.lon);
            if ((!s.lat.isEmpty() || !s.lon.isEmpty()) && onEarth(lat, lon) != null) {
                String why = onEarth(lat, lon);
                later(() -> say("The coordinates are left out: " + why));
                lat = lon = null;
            }
        } else if (s.source == GPS) {
            String[] fix = askWindows();
            if (fix[0] != null) {
                lat = number(fix[0]);
                lon = number(fix[1]);
                fill(null, fix[0], fix[1]);
                later(() -> say("Located by Windows, " + accuracy(fix[2]) + "."));
            } else {
                // The last fix, if there was one, rather than nothing.
                lat = number(s.lat);
                lon = number(s.lon);
                String why = fix[1];
                later(() -> say("Not located: " + why));
            }
        }
        // Only what is set to be found is found: a place left empty by hand stays empty.
        Where w = new Where(s.placeAuto ? null : s.place, lat, lon);
        if (s.placeAuto || s.source == FROM_PLACE) w = complete(w, "#here");
        fill(s.placeAuto ? w.place : null,
                s.source == FROM_PLACE && w.hasCoordinates() ? w.lat.toPlainString() : null,
                s.source == FROM_PLACE && w.hasCoordinates() ? w.lon.toPlainString() : null);
        hereCache = s.refreshMinutes > 0 && s.source == GPS ? w : null;
        return w;
    }

    // A location with its missing half found through openstreetmap.org, as far as it can
    // be: what could not be found is left out, and the status line says why. On the
    // resolver.
    private static Where complete(Where w, String what) {
        if (w.isEmpty() || (w.place != null && w.hasCoordinates())) return w;
        try {
            if (noModel != null) throw new Stop("this JRock cannot ask a model: " + noModel);
            if (w.place == null) {
                String place = placeAt(w.lat, w.lon);
                later(() -> say("Found the place for " + what + ": " + place + "."));
                return new Where(place, w.lat, w.lon);
            }
            BigDecimal[] at = coordinatesOf(w.place);
            later(() -> say("Found the coordinates for " + what + "."));
            return new Where(w.place, at[0], at[1]);
        } catch (Stop stop) {
            later(() -> say("Not found for " + what + ": " + stop.getMessage()));
            return w;
        }
    }

    // The place at those coordinates. On the resolver.
    private static String placeAt(BigDecimal lat, BigDecimal lon) {
        String url = ask(prompt(GPS_TO_PLACE_URL).replace("#lat", lat.toPlainString())
                .replace("#lon", lon.toPlainString()), null, "the address for the place");
        String reply = ask(prompt(GPS_TO_PLACE_NAME), address(url), "the place");
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String line : reply.split("\r?\n")) if (!line.isBlank()) lines.add(line.trim());
        if (lines.size() != 1) throw new Stop("the model's reply was not one line: \"" + reply + "\".");
        String place = lines.get(0).replaceAll("^[\"'\u201C\u201D\u00AB\u00BB*_]+|[\"'\u201C\u201D\u00AB\u00BB*_.]+$", "").trim();
        if (NOT_FOUND.matcher(place).matches()) throw new Stop(SITE + " knows no place there.");
        if (place.isEmpty() || place.length() > PLACE_MAX) {
            throw new Stop("the model's reply is not a place: \"" + reply + "\".");
        }
        return place;
    }

    // The coordinates of that place, latitude first. On the resolver.
    private static BigDecimal[] coordinatesOf(String place) {
        String url = ask(prompt(PLACE_TO_GPS_URL).replace("#place", place), null,
                "the address for the coordinates");
        String reply = ask(prompt(PLACE_TO_GPS), address(url), "the coordinates");
        if (NOT_FOUND.matcher(reply).matches()) throw new Stop(SITE + " knows no place called " + place + ".");
        Where w = null;
        for (String line : reply.split("\r?\n")) {
            Where found = parse(line.replaceAll("[*_`]", ""));
            if (found != null && found.hasCoordinates()) {
                w = found;
                break;
            }
        }
        if (w == null) throw new Stop("the model's reply has no coordinates in it: \"" + reply + "\".");
        return new BigDecimal[] { w.lat, w.lon };
    }

    // The address in the model's reply, if it is one this agent fetches (see
    // invalidAddress).
    private static String address(String reply) {
        Matcher m = ADDRESS.matcher(reply);
        if (!m.find()) throw new Stop("the model's reply has no address in it: \"" + reply + "\".");
        String url = m.group().replaceAll("[.,;:!?]+$", "");
        String why = invalidAddress(url);
        if (why != null) throw new Stop("not fetched: " + url + " " + why);
        return url;
    }

    // Null when url is one to fetch: a Java URL, https, on openstreetmap.org or one of
    // its subdomains, on the default port and with no user name - else why not.
    static String invalidAddress(String url) {
        java.net.URI uri;
        try {
            uri = new java.net.URI(url);
            uri.toURL();
        } catch (java.net.URISyntaxException | java.net.MalformedURLException | IllegalArgumentException ex) {
            return "is not a URL (" + ex.getMessage() + ").";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) return "is not https.";
        if (uri.getRawUserInfo() != null) return "has a user name in it.";
        if (uri.getPort() != -1 && uri.getPort() != 443) return "is not on the https port.";
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!host.equals(SITE) && !host.endsWith("." + SITE)) return "is not on " + SITE + ".";
        return null;
    }

    // Asks this agent's JRock one question and returns the reply: the prompt, and the
    // page at fetch where the prompt says #url. On the resolver.
    private static String ask(String prompt, String fetch, String what) {
        later(() -> say("Asking the model for " + what + "\u2026"));
        check(JRock.automationClearPrompt());
        if (fetch == null) {
            check(JRock.automationSetPrompt(prompt));
        } else {
            int at = prompt.indexOf("#url");
            String before = at < 0 ? prompt.replaceAll("\\s+$", "") + "\n\n" : prompt.substring(0, at);
            String after = at < 0 ? "" : prompt.substring(at + "#url".length());
            check(JRock.automationSetPrompt(before));
            String problem = JRock.automationFetchUrl(fetch);
            if (problem != null) throw new Stop("could not fetch " + fetch + ": " + problem);
            if (!after.isBlank()) {
                String sofar = JRock.automationPromptText();
                if (sofar == null) throw new Stop("the prompt could not be read.");
                check(JRock.automationSetPrompt(sofar + after));
            }
        }
        String[] sent = JRock.automationSend(REPLY_TIMEOUT_MS);
        if (!"1".equals(sent[0])) throw new Stop("the model did not answer: " + sent[3]);
        String reply = read(JRock.automationMessageFile("assistant", sent[2]));
        if (reply == null) throw new Stop("the reply was not written to JRock/messages/.");
        return reply.trim();
    }

    // A prompt file's text, read again every time.
    private static String prompt(String name) {
        String text = read(prompts.resolve(name).toString());
        if (text == null) throw new Stop("there is no " + prompts.resolve(name) + ".");
        return text;
    }

    // ---- GPS -------------------------------------------------------------------
    // { latitude, longitude, accuracy } as Windows gave them, or { null, why }. Any
    // thread but the EDT.
    private static String[] askWindows() {
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile",
                    "-NonInteractive", "-Command", LOCATE_SCRIPT);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            int code = p.waitFor();
            for (String line : out.split("\r?\n")) {
                line = line.trim();
                if (line.startsWith("FIX|")) {
                    String[] v = line.split("\\|");
                    if (v.length == 4 && onEarth(number(v[1]), number(v[2])) == null) {
                        return new String[] { v[1], v[2], v[3] };
                    }
                } else if (line.startsWith("NOFIX|")) {
                    return new String[] { null, line.substring(6) };
                }
            }
            return new String[] { null, "PowerShell gave no position (exit " + code + ")." };
        } catch (IOException ex) {
            return new String[] { null, "PowerShell could not be started (" + ex.getMessage() + ")." };
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new String[] { null, "interrupted." };
        }
    }

    private static String accuracy(String metres) {
        try {
            return "to within " + Math.round(Double.parseDouble(metres)) + " m";
        } catch (NumberFormatException ex) {
            return "with no accuracy given";
        }
    }

    // ---- The server ------------------------------------------------------------
    // Opens PORT on the loopback interface and takes connections on a thread of its own.
    // Returns what the window should say about it.
    private static String listen() {
        try {
            ServerSocket socket = new ServerSocket(PORT, 16, InetAddress.getLoopbackAddress());
            server = socket;
            daemon(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket client = socket.accept();
                        daemon(() -> serve(client), "jrock-location-client").start();
                    } catch (IOException closed) {
                        // Closed with the window, or failed: either way, no more clients.
                        return;
                    }
                }
            }, "jrock-location-server").start();
            return "Other agents ask on 127.0.0.1:" + PORT + ".";
        } catch (IOException ex) {
            return "Not listening on port " + PORT + " (" + ex.getMessage() + ") - is "
                    + "another location agent running?";
        }
    }

    // One client: a line in, a line out, closed.
    private static void serve(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(CLIENT_TIMEOUT_MS);
            String line = readLine(c.getInputStream());
            String reply = "";
            if (line != null) {
                java.util.concurrent.Future<String> found = RESOLVER.submit(() -> answer(line));
                try {
                    reply = found.get(ANSWER_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ex) {
                    later(() -> say("No answer for \"" + line + "\" in time - answered with an empty line."));
                } catch (java.util.concurrent.ExecutionException ex) {
                    String why = String.valueOf(ex.getCause());
                    later(() -> say("Could not answer \"" + line + "\": " + why));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            OutputStream out = c.getOutputStream();
            out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ex) {
            // A client that went away, or never wrote: nobody to tell.
        }
    }

    // The text up to a newline (a \r before it dropped), or null for none within
    // LINE_MAX bytes.
    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1; ) {
            if (b == '\n') return bytes.toString(StandardCharsets.UTF_8.name()).replaceAll("\r$", "");
            if (bytes.size() >= LINE_MAX) return null;
            bytes.write(b);
        }
        return null;
    }

    // ---- The window ------------------------------------------------------------
    private static final java.awt.Color BACKGROUND = new java.awt.Color(0xF4F5F7);
    private static final java.awt.Color INK = new java.awt.Color(0x1F2328);
    private static final java.awt.Color MUTED = new java.awt.Color(0x6B7280);

    private static void showWindow() {
        window = new javax.swing.JFrame("JRock - Location");
        // Closing this window ends the agent, not JRock: no more clients.
        window.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                refreshTimer.stop();
                ServerSocket s = server;
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) { }
                }
                RESOLVER.submit(() -> JRock.automationEnd("location agent closed"));
            }
        });

        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);
        java.awt.Font small = base.deriveFont(java.awt.Font.PLAIN, 12f);
        java.awt.Font digits = new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 14);

        placeField = new javax.swing.JTextField(22);
        placeField.setFont(base.deriveFont(java.awt.Font.BOLD, 18f));
        placeField.setToolTipText("The place, as you would say it - e.g. Munich, Bavaria");
        placeAuto = new javax.swing.JCheckBox("Determine automatically");
        placeAuto.setOpaque(false);
        placeAuto.setToolTipText("Finds the place from the coordinates, on " + SITE);

        source = new javax.swing.JComboBox<>(SOURCES);
        source.setSelectedIndex(GPS);
        latField = new javax.swing.JTextField(16);
        lonField = new javax.swing.JTextField(16);
        latField.setFont(digits);
        lonField.setFont(digits);
        latField.setToolTipText("Degrees north, negative for south - optional");
        lonField.setToolTipText("Degrees east, negative for west - optional");
        locateButton = new javax.swing.JButton("Locate now");
        locateButton.setToolTipText("Finds out now whatever is set to be found automatically");
        locateButton.addActionListener(e -> locateNow());

        roundTo = new javax.swing.JComboBox<>(ROUNDINGS);
        roundTo.setToolTipText("Digits after the point other agents get - the fields keep them all");
        refresh = new javax.swing.JComboBox<>(REFRESHES);
        refresh.setToolTipText("Asks GPS again this often, and keeps #here ready for other agents");

        placeAuto.addActionListener(e -> {
            if (placeAuto.isSelected() && source.getSelectedIndex() == FROM_PLACE) {
                source.setSelectedIndex(GPS);
                say("Coordinates now from GPS: the place cannot be found from coordinates "
                        + "that are found from the place.");
            }
            changed();
        });
        source.addActionListener(e -> {
            if (source.getSelectedIndex() == FROM_PLACE && placeAuto.isSelected()) {
                placeAuto.setSelected(false);
                say("The place is now typed: coordinates found from the place need a place "
                        + "to find them from.");
            }
            changed();
            if (source.getSelectedIndex() == GPS) locateNow();
        });
        roundTo.addActionListener(e -> showPreview());
        refresh.addActionListener(e -> {
            changed();
            if (REFRESH_MINUTES[refresh.getSelectedIndex()] > 0) locateNow();
        });
        javax.swing.event.DocumentListener edited = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { edited(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { edited(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { edited(); }
        };
        placeField.getDocument().addDocumentListener(edited);
        latField.getDocument().addDocumentListener(edited);
        lonField.getDocument().addDocumentListener(edited);

        javax.swing.JPanel form = new javax.swing.JPanel(new java.awt.GridBagLayout());
        form.setOpaque(false);
        java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
        g.insets = new java.awt.Insets(3, 0, 3, 8);
        g.anchor = java.awt.GridBagConstraints.WEST;
        g.fill = java.awt.GridBagConstraints.HORIZONTAL;
        int row = 0;
        row = formRow(form, g, row, "Place", placeField);
        row = formRow(form, g, row, "", placeAuto);
        row = formRow(form, g, row, "Coordinates", source);
        row = formRow(form, g, row, "Latitude", latField);
        row = formRow(form, g, row, "Longitude", lonField);
        row = formRow(form, g, row, "Round to", roundTo);
        row = formRow(form, g, row, "Refresh GPS every", refresh);
        for (java.awt.Component c : form.getComponents()) {
            if (c instanceof javax.swing.JLabel) c.setFont(small);
        }
        javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, 0, 4));
        buttons.setOpaque(false);
        buttons.add(locateButton);

        javax.swing.JLabel previewTitle = new javax.swing.JLabel("Other agents get, for #here:");
        previewTitle.setFont(small);
        previewTitle.setForeground(MUTED);
        previewTitle.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 0, 2, 0));
        preview = new javax.swing.JLabel(" ");
        preview.setFont(base.deriveFont(java.awt.Font.PLAIN, 14f));
        status = new javax.swing.JLabel(" ");
        status.setFont(small);
        status.setForeground(MUTED);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 0, 0, 0));
        listening = new javax.swing.JLabel(" ");
        listening.setFont(small);
        listening.setForeground(MUTED);
        listening.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 0, 0, 0));

        javax.swing.JPanel south = new javax.swing.JPanel();
        south.setLayout(new javax.swing.BoxLayout(south, javax.swing.BoxLayout.Y_AXIS));
        south.setOpaque(false);
        for (javax.swing.JComponent c : new javax.swing.JComponent[] {
                buttons, previewTitle, preview, status, listening }) {
            c.setAlignmentX(0f);
            south.add(c);
        }

        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout());
        content.setBackground(BACKGROUND);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 14, 18));
        content.add(form, java.awt.BorderLayout.CENTER);
        content.add(south, java.awt.BorderLayout.SOUTH);
        window.setContentPane(content);

        // Every refresh, from GPS: #here found again and kept, so a client gets it at once.
        refreshTimer = new javax.swing.Timer(Integer.MAX_VALUE, e -> locateNow());
        refreshTimer.setRepeats(true);

        changed();
        say(" ");
        window.pack();
        window.setMinimumSize(window.getSize());
        // Beside JRock's window rather than on top of it, so both can be watched.
        java.awt.Window jrock = JRock.automationWindow();
        if (jrock != null) {
            java.awt.Rectangle at = jrock.getBounds();
            window.setLocation(Math.max(0, at.x - window.getWidth()), at.y);
        } else {
            window.setLocationRelativeTo(null);
        }
        window.setVisible(true);
        placeField.requestFocusInWindow();
    }

    private static int formRow(javax.swing.JPanel form, java.awt.GridBagConstraints g, int row,
                               String label, javax.swing.JComponent field) {
        g.gridy = row;
        g.gridx = 0;
        g.weightx = 0;
        javax.swing.JLabel l = new javax.swing.JLabel(label);
        l.setForeground(INK);
        form.add(l, g);
        g.gridx = 1;
        g.weightx = 1;
        form.add(field, g);
        return row + 1;
    }

    // Finds out now whatever is set to be found - GPS, the place, the coordinates - and
    // fills it in. On the EDT; the finding is on the resolver.
    private static void locateNow() {
        Settings s = settings();
        if (!s.placeAuto && s.source == MANUAL) {
            say("Nothing to find out: the place and the coordinates are both typed.");
            return;
        }
        locateButton.setEnabled(false);
        if (s.source == GPS) say("Asking Windows where this computer is\u2026");
        RESOLVER.submit(() -> {
            try {
                here(s, false);
            } catch (RuntimeException ex) {
                later(() -> say("Could not find out: " + ex.getMessage()));
            } finally {
                later(() -> locateButton.setEnabled(true));
            }
        });
    }

    // A setting changed: the fields enabled as it says, the kept #here dropped, the
    // refresh timer set again. On the EDT.
    private static void changed() {
        boolean gps = source.getSelectedIndex() == GPS;
        placeField.setEnabled(!placeAuto.isSelected());
        latField.setEnabled(source.getSelectedIndex() == MANUAL);
        lonField.setEnabled(source.getSelectedIndex() == MANUAL);
        refresh.setEnabled(gps);
        hereCache = null;
        int minutes = REFRESH_MINUTES[refresh.getSelectedIndex()];
        refreshTimer.stop();
        if (gps && minutes > 0) {
            refreshTimer.setDelay(minutes * 60_000);
            refreshTimer.setInitialDelay(minutes * 60_000);
            refreshTimer.start();
        }
        showPreview();
    }

    // A field typed into - not filled by the agent. On the EDT.
    private static void edited() {
        if (!filling) hereCache = null;
        showPreview();
    }

    // Fills in what was found: any that is null is left as it is. Any thread.
    private static void fill(String place, String lat, String lon) {
        later(() -> {
            filling = true;
            try {
                if (place != null) placeField.setText(place);
                if (lat != null) latField.setText(lat);
                if (lon != null) lonField.setText(lon);
                placeField.setCaretPosition(0);
            } finally {
                filling = false;
            }
        });
    }

    // What #here gives as the fields stand. On the EDT.
    private static void showPreview() {
        Settings s = settings();
        BigDecimal lat = number(s.lat), lon = number(s.lon);
        if (onEarth(lat, lon) != null) lat = lon = null;
        String line = format(new Where(s.place, lat, lon), s.digits);
        preview.setText(line.isEmpty() ? "(nothing yet - an empty line)" : line);
        preview.setForeground(line.isEmpty() ? MUTED : INK);
    }

    // What the window says. Any thread: off the EDT it is taken on the EDT.
    private static Settings settings() {
        Settings s = new Settings();
        Runnable take = () -> {
            s.placeAuto = placeAuto.isSelected();
            s.place = placeField.getText().trim();
            s.source = source.getSelectedIndex();
            s.lat = latField.getText().trim();
            s.lon = lonField.getText().trim();
            s.digits = DIGITS[Math.max(0, roundTo.getSelectedIndex())];
            s.refreshMinutes = REFRESH_MINUTES[Math.max(0, refresh.getSelectedIndex())];
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) take.run(); else onEdt(take);
        return s;
    }

    // The status line, wrapped rather than widening the window for a long message.
    private static void say(String text) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        status.setText("<html><div style='width:320px'>" + safe + "</div></html>");
    }

    // ---- Plumbing --------------------------------------------------------------
    // Turns an automation API result into a stop, a null meaning there is nothing wrong.
    private static void check(String problem) {
        if (problem != null) throw new Stop(problem);
    }

    // A file's text, or null when there is none to read.
    private static String read(String file) {
        if (file == null) return null;
        try {
            return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new Stop("could not read " + file + ": " + ex.getMessage());
        }
    }

    // The directory this source file sits in, which is where the prompts are looked for
    // (see JRockPushToTalk.ownDirectory).
    private static Path ownDirectory() {
        String source = System.getProperty("jdk.launcher.sourcefile");
        if (source != null && !source.isBlank()) {
            Path parent = Paths.get(source).toAbsolutePath().normalize().getParent();
            if (parent != null) return parent;
        }
        try {
            java.security.CodeSource code =
                    JRockLocation.class.getProtectionDomain().getCodeSource();
            if (code != null && code.getLocation() != null) {
                Path at = Paths.get(code.getLocation().toURI()).toAbsolutePath().normalize();
                Path parent = Files.isDirectory(at) ? at : at.getParent();
                if (parent != null) return parent;
            }
        } catch (RuntimeException | java.net.URISyntaxException ignore) {
            // Fall through to the working directory.
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static Thread daemon(Runnable body, String name) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    private static void later(Runnable body) {
        javax.swing.SwingUtilities.invokeLater(body);
    }

    // Runs body on the event dispatch thread and waits for it.
    private static void onEdt(Runnable body) {
        try {
            javax.swing.SwingUtilities.invokeAndWait(body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw new RuntimeException(ex.getCause());
        }
    }
}
