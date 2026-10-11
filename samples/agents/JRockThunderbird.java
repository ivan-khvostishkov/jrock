// A JRock agent (it loops, and is started with JRock): what Thunderbird already knows,
// read out of its own files - your calendar and your mail - for other agents to ask
// about and for you to read. And one thing it puts back: a calendar event, by posting an
// invitation to you from yourself.
//
// THE CALENDAR half reads the cached calendars; THE MAIL half reads Thunderbird's global
// search index, the one Search Messages is built on. Both read-only, both with the same
// sqlite3, and neither of them ever the authority on anything: Thunderbird is. What is
// here is what Thunderbird has written down, which is why every answer says how fresh
// that is.
//
// Run it the way JRock itself runs - no build step, no Maven, one file:
//
//     java -cp jrock.jar JRockThunderbird.java [--profile <thunderbird profile dir>]
//     java -cp jrock.jar JRockThunderbird.java --profile <dir> --print week
//
// THE JAR IS NAMED ON -cp, as with every agent and every flow - this one uses it for one
// thing only, placing its window beside JRock's. What it does NOT need is
// anything else of JRock's: no model, no Bedrock key, and no JRock taken as an automation,
// because it answers out of files rather than by asking anybody. It can sit beside a JRock
// you are already using, or run on its own.
//
// --print writes one briefing to standard output and exits: no window, no socket. It is
// the way to see exactly what an agent would be told.
//
// EVENTS, and that is the name: Thunderbird keeps tasks (VTODO) in a table of their own,
// and a calendar served over CalDAV by Google has none - the cached table is empty. If
// your "tasks for today" are the things in your calendar, this is them; if you ever keep
// real tasks in Thunderbird, they are a second query away and not here yet.
//
//   1. Thunderbird caches every server calendar in <profile>/calendar-data/cache.sqlite,
//      and a local calendar of its own in local.sqlite. Both are read, read-only, never
//      written, never moved. The calendars' names come from <profile>/prefs.js, where
//      Thunderbird keeps them as calendar.registry.<uuid>.name.
//   2. A small window opens: the profile to read, which calendars to include, and the
//      briefing itself - Today, This week, or Next, the same text an agent gets.
//   3. Other agents ask over TCP, on the loopback interface only: connect to
//      127.0.0.1:PORT, send one line, and read until the connection closes.
//
//          #today                         what is on today
//          #week                          today and the six days after it
//          #next [n]                      the next n events from now (default 5)
//          #range 2026-10-12 2026-10-19   any span of days
//          #tentative [days]              the maybes (tentative, unanswered, optional)
//          #tentative 2026-12-01 2026-12-31  the same over a span
//          #calendars                     the calendars, and how many events each holds
//          #new 2026-10-15 14:00 Dentist  a new event, by post (see below)
//          #new 2026-12-24 Christmas Eve  the same, for a whole day
//          #move a3f91c 2026-10-20 14:30  move an appointment of your own - and only
//                                         your own - by the id its line carries
//          #mail [days]                   what arrived today, or over that many days
//          #unanswered [days]             received, with no reply sent (default 14)
//          #with <name or address>        the last each way, and how much there has been
//          #senders [days]                who wrote, and how often (default 5)
//          #mailboxes                     the accounts indexed, and how fresh the index is
//
//      The push-to-talk agent asks when the model's answer is "I need your calendar for
//      today" or "... for this week", and puts what comes back into its next prompt. It
//      sends #new when the answer is "I'm putting it in the calendar on ...", which is a
//      sentence the operator has HEARD before anything happens.
//   4. #new is the one thing here that is not read-only, and it still does not write to
//      a calendar. It writes a temporary .ics file and has Thunderbird compose a mail
//      with it attached, FROM the address chosen in the window TO that same address.
//      Thunderbird is the only thing in this chain that touches a calendar, through its
//      own account and its own server, when you accept the invitation that arrives.
//
//      Two reasons it is done by post. A cache is a cache: a row written into
//      cache.sqlite would be an event no server has heard of, and the next sync would
//      wipe it. And an agent takes its instructions from a model, which takes them from
//      whatever was said out loud in a noisy room - so the address is picked from a
//      dropdown of the addresses this profile sends from, the same one is used for both
//      ends, and no address said out loud can reach this code. The worst a mistake can
//      do is send you a mail you did not want.
//
//      THE LAST KEYSTROKE IS YOURS: Thunderbird's command line can compose a message
//      but has no switch that sends one, so the window comes up filled in and Ctrl+Enter
//      is the operator's. Which is where the one irreversible step belongs anyway.
//
// Reading SQLite needs sqlite3, the command-line tool from sqlite.org - the one outside
// thing this agent wants, as Ghostscript and xpdf are for PDFs. It is looked for on
// PATH, then beside this file, then in the working directory, and when it is nowhere the
// window says exactly that. Every query runs with -readonly, and nothing in here writes
// SQL from anything a client sent: a range is parsed into two dates and rendered back as
// numbers, so there is nothing to inject into.
//
// What it understands of repeats: FREQ=DAILY, WEEKLY, MONTHLY and YEARLY, with INTERVAL,
// COUNT, UNTIL, BYDAY (weekly), BYMONTH and BYMONTHDAY, plus EXDATE and RDATE, and the
// modified instances Thunderbird stores as rows of their own. Anything else is still
// listed - with a note saying the rule was not fully understood - because a repeat
// quietly dropped is an appointment missed.
//
// Nothing here is a promise about Thunderbird: the schema is its own internal one
// (version 23 as this was written) and a new Thunderbird may change it. The version is
// read and reported rather than assumed.

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JRockThunderbird {

    // JRock's agents' ports (see JRockTimer.java), and the calendar agent's, the fourth
    // of them: timer 47470, location 47471, weather 47472, calendar 47473.
    public static final int AGENT_PORTS_FIRST = 47470;
    public static final int PORT = AGENT_PORTS_FIRST + 3;

    // How long a client has to send its line, and the longest line taken.
    private static final int CLIENT_TIMEOUT_MS = 5000;
    private static final int LINE_MAX = 512;

    // How long sqlite3 may take over one query. Generous: a cold 7 MB file on a network
    // drive is slower than the same file read twice on an SSD.
    private static final int QUERY_TIMEOUT_MS = 20_000;

    // How long this agent's own JRock gets to come up.
    private static final long READY_TIMEOUT_MS = 120_000;

    // Where this agent keeps the profile it was last pointed at, and the calendars left
    // out - beside this file, so the window remembers them and nothing of JRock's own
    // configuration is touched.
    private static final String SETTINGS = "jrock-thunderbird.txt";

    // What this agent was called when it only read calendars. Its settings file is read
    // when the current one is not there, so a profile and an address chosen before the
    // rename are still chosen after it - and the next save writes the new name.
    private static final String SETTINGS_WAS = "jrock-calendar-events.txt";

    // Thunderbird's two calendar databases inside a profile: the cache of every calendar
    // on a server, and the local calendar it keeps itself. Both are read; either may be
    // missing or empty.
    private static final String[] DATABASES = { "calendar-data/cache.sqlite",
                                                "calendar-data/local.sqlite" };

    // The schema this was written against (cal_calendar_schema_version). A different one
    // is reported, not refused: the tables this reads have been stable for years, and a
    // bare refusal would be worse than a warning on a version that still works.
    private static final int SCHEMA_KNOWN = 23;

    // Thunderbird stores times as PRTime: microseconds since the epoch, UTC.
    private static final long MICROS = 1000L;

    // How long an event lasts when nobody says: an hour, which is what a calendar
    // application assumes too. Said out loud, "half past two" is a time and not a span,
    // and an event with no end at all is one a calendar draws as a line.
    private static final long EVENT_MINUTES = 60;

    // A time with no zone of its own - an all-day event, a birthday - is stored as the
    // wall clock it shows, counted as if it were UTC. Read in any other zone it slides
    // by hours and lands on the wrong day, which is the one thing a date must not do.
    private static final String FLOATING = "floating";

    // What the briefing's columns are written with.
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter LONG_DAY =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    // sqlite3's output: rows and columns separated by characters no calendar entry holds,
    // because a title can hold anything else - a comma, a tab, a newline of its own.
    private static final String COLUMN = "\u001f";
    private static final String ROW = "\u001e";

    // Why a step failed, in one sentence.
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        Stop(String why) { super(why); }
    }

    // One occurrence, ready to print: when it is, what it is called, which calendar it
    // came from, and what else is worth a line.
    private static final class Occurrence implements Comparable<Occurrence> {
        final Instant start, end;
        final boolean allDay;
        final String title, calendar, location, note;
        // The short id of the event this is an occurrence of: what a briefing prints on
        // every line, and what naming one in a request means (see handle).
        final String id;
        // Why this one is a maybe rather than a plan - "tentative", "optional", "you
        // said maybe" - or "" when it is simply happening. Kept as the words rather than
        // a flag, because a list of maybes is worth reading with the reason beside each.
        final String maybe;

        Occurrence(Instant start, Instant end, boolean allDay, String title,
                   String calendar, String location, String note, String maybe,
                   String id) {
            this.start = start; this.end = end; this.allDay = allDay;
            this.title = title; this.calendar = calendar;
            this.location = location; this.note = note;
            this.maybe = maybe == null ? "" : maybe;
            this.id = id == null ? "" : id;
        }

        @Override public int compareTo(Occurrence other) {
            int by = start.compareTo(other.start);
            return by != 0 ? by : title.compareToIgnoreCase(other.title);
        }
    }

    // Everything that reads the database goes through this one thread: two clients asking
    // at once are answered one after the other, and the window's buttons queue behind
    // them rather than starting a second sqlite3.
    private static final java.util.concurrent.ExecutorService READER =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> daemon(r, "jrock-calendar-reader"));

    // Why this agent's own JRock cannot ask a model, or null when it can. Only reading
    // ONE MESSAGE needs it - the calendar and the mail listings are files and nothing
    // else - so a missing key is a reason to refuse that and nothing more.
    private static volatile String noModel = "its JRock has not been started.";

    private static volatile Path profile;          // the one Thunderbird profile to read
    private static volatile Path sqlite;           // the tool, once found
    // The address a new event is mailed FROM and TO. One address, both ways, chosen in
    // the window from the ones this profile is set up to send from - which is what makes
    // #new incapable of mailing anybody else, whatever it is asked for. Nothing in a
    // request line can name an address; this field is the only one there is.
    private static volatile String eventEmail = "";
    private static final Set<String> excluded =    // calendar names left out, by name
            java.util.Collections.synchronizedSet(new LinkedHashSet<>());

    private static javax.swing.JFrame window;
    private static javax.swing.JTextField profileField;
    private static javax.swing.JComboBox<String> emailBox;
    private static javax.swing.JTextArea briefing;
    private static javax.swing.JPanel calendarBox;
    private static javax.swing.JLabel status;
    private static javax.swing.JLabel listening;
    private static volatile ServerSocket server;

    public static void main(String[] args) {
        String printing = null;
        Path given = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--profile") && i + 1 < args.length) {
                given = Paths.get(args[++i]);
            } else if (args[i].equals("--print") && i + 1 < args.length) {
                printing = args[++i];
            }
        }
        readSettings();
        if (given != null) profile = given;

        // --print: the briefing on standard output and nothing else. No window, no
        // socket, no JRock - which makes it the way to see what an agent would be told.
        if (printing != null) {
            try {
                System.out.println(answer(printing.startsWith("#") ? printing : "#" + printing));
            } catch (Stop stop) {
                System.out.println("Stopped: " + stop.getMessage());
            }
            return;
        }

        // A JROCK OF ITS OWN, because a message is never handed over raw: its text and
        // its attachments go through a model first (see refine). Started before the
        // window so the window can sit beside it, and started even when there is no key -
        // everything except reading one message works without one.
        JRock.main(args);
        noModel = JRock.automationAwaitReady(READY_TIMEOUT_MS);
        if (JRock.automationWindow() == null) {
            System.out.println("Stopped: " + noModel);
            return;
        }
        String taken = JRock.automationBegin("Thunderbird agent");
        if (taken != null) noModel = taken;

        onEdt(JRockThunderbird::showWindow);
        String said = listen();
        later(() -> listening.setText(said));
        System.out.println("Thunderbird agent is ready. " + said);
        later(() -> refresh("#week"));
    }

    // ---- Answering -------------------------------------------------------------
    // One request line, as a client sends it or a button asks it, into the text that goes
    // back. On the reader thread.
    private static String answer(String request) {
        String line = request == null ? "" : request.trim();
        if (line.isEmpty()) line = "#today";
        if (!line.startsWith("#")) line = "#" + line;
        String[] parts = line.split("\\s+");
        String what = parts[0].toLowerCase(Locale.ROOT);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);

        switch (what) {
            case "#today":
                return briefing(today, today, zone, "Today");
            case "#week":
                return briefing(today, today.plusDays(6), zone, "This week");
            case "#next": {
                int n = 5;
                if (parts.length > 1) {
                    try {
                        n = Math.max(1, Math.min(50, Integer.parseInt(parts[1])));
                    } catch (NumberFormatException ex) {
                        throw new Stop("\"" + parts[1] + "\" is not a number of events.");
                    }
                }
                return next(n, zone);
            }
            case "#range": {
                if (parts.length < 3) {
                    throw new Stop("#range wants two dates, as #range 2026-10-12 2026-10-19.");
                }
                LocalDate from = date(parts[1]), to = date(parts[2]);
                if (to.isBefore(from)) throw new Stop("that range ends before it starts.");
                if (from.plusYears(2).isBefore(to)) {
                    throw new Stop("that range is longer than two years.");
                }
                // No range in the heading: briefing() writes the dates itself, and
                // saying them twice reads like a stutter.
                return briefing(from, to, zone, "In the calendar");
            }
            case "#calendars":
                return calendars();
            case "#tentative":
            case "#optional": {
                // A number of days ahead, or a span of two dates like #range takes -
                // because "what have I not settled?" is as fair a question about last
                // April as about next week.
                if (parts.length > 2 && parts[1].length() == 10) {
                    LocalDate from = date(parts[1]), until = date(parts[2]);
                    if (until.isBefore(from)) throw new Stop("that range ends before it starts.");
                    return tentative(from, until, zone);
                }
                return tentative(today, today.plusDays(daysIn(parts, 1, TENTATIVE_DAYS)),
                        zone);
            }
            case "#new":
                return newEvent(line.substring(what.length()).trim());
            case "#move":
            case "#propose":
                return moveEvent(line.substring(what.length()).trim(), zone);
            case "#mail":
                return mail(daysIn(parts, 1, 0), zone);
            case "#message":
            case "#read":
                return message(line.substring(what.length()).trim(), zone);
            case "#reply":
                return reply(line.substring(what.length()).trim(), zone);
            case "#unanswered":
                return unanswered(daysIn(parts, 1, UNANSWERED_DAYS), zone);
            case "#with":
                return withPerson(line.substring(what.length()).trim(), zone);
            case "#senders":
                return senders(daysIn(parts, 1, SENDERS_DAYS), zone);
            case "#mailboxes":
                return mailboxes(zone);
            default:
                throw new Stop("\"" + line + "\" is not something this agent answers. Ask "
                        + "#today, #week, #next [n], #range <from> <to>, #calendars, "
                        + "#tentative [days] or "
                        + "#new <date> [time] <name> or #move <id> <date> [time] about "
                        + "the calendar, or #mail [days], "
                        + "#unanswered [days], #with <somebody>, #senders [days], "
                        + "#message <id or words>, #reply <id> <text> or #mailboxes "
                        + "about the mail.");
        }
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text.trim(), ISO);
        } catch (DateTimeParseException ex) {
            throw new Stop("\"" + text + "\" is not a date as 2026-10-12.");
        }
    }

    // The briefing for a span of whole local days, both ends included.
    private static String briefing(LocalDate from, LocalDate to, ZoneId zone, String heading) {
        Instant lo = from.atStartOfDay(zone).toInstant();
        Instant hi = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<Occurrence> all = occurrences(lo, hi, zone);
        // The maybes are left out, and counted. An event somebody has not settled, or
        // that this operator was invited to optionally, is not what "what is on today"
        // means - and reading it out as if it were would have them turn up to it.
        List<Occurrence> found = new ArrayList<>();
        int maybes = 0;
        for (Occurrence o : all) {
            if (o.maybe.isEmpty()) found.add(o);
            else maybes++;
        }

        StringBuilder out = new StringBuilder();
        String when = from.equals(to) ? from.format(LONG_DAY)
                : from.format(DAY) + " to " + to.format(DAY) + " " + to.getYear();
        out.append(heading).append(", ").append(when).append(" (").append(zone).append("):");
        if (found.isEmpty()) {
            out.append("\nNothing in the calendar.");
        } else {
            LocalDate day = null;
            for (Occurrence o : found) {
                LocalDate on = dayOf(o, zone);
                if (!on.equals(day)) {
                    day = on;
                    out.append('\n').append(on.format(DAY)).append(':');
                }
                out.append('\n').append(line(o, zone));
            }
        }
        if (maybes > 0) {
            out.append("\n").append(maybes).append(maybes == 1
                    ? " event is not listed above because it is a maybe"
                    : " events are not listed above because they are maybes")
                    .append(" - tentative, unanswered, or one you were invited to "
                            + "optionally. Ask for the tentative ones to see ")
                    .append(maybes == 1 ? "it." : "them.");
        }
        return out.append('\n').append(footer()).toString();
    }

    // The maybes themselves, for when they are what was asked for: marked tentative,
    // answered "maybe", not answered at all, or invited to optionally - with the reason
    // beside each. Nothing is hidden anywhere; it is only kept out of the answer to a
    // question it does not answer.
    private static String tentative(LocalDate from, LocalDate to, ZoneId zone) {
        Instant lo = from.atStartOfDay(zone).toInstant();
        Instant hi = to.plusDays(1).atStartOfDay(zone).toInstant();
        StringBuilder out = new StringBuilder("Tentative, unanswered and optional events, "
                + from.format(DAY) + " to " + to.format(DAY) + " " + to.getYear()
                + " (" + zone + "):");
        int shown = 0;
        LocalDate day = null;
        for (Occurrence o : occurrences(lo, hi, zone)) {
            if (o.maybe.isEmpty()) continue;
            shown++;
            LocalDate on = dayOf(o, zone);
            if (!on.equals(day)) {
                day = on;
                out.append('\n').append(on.format(DAY)).append(':');
            }
            out.append('\n').append(line(o, zone));
        }
        if (shown == 0) out.append("\nNothing tentative, unanswered or optional in that span.");
        return out.append('\n').append(footer()).toString();
    }

    // The next n events from now, however far ahead they are - for "what is next?", which
    // a week's briefing answers with silence when the week is empty.
    private static String next(int n, ZoneId zone) {
        Instant now = Instant.now();
        // A year ahead is as far as this looks: an event further off is not "next".
        List<Occurrence> found = occurrences(now, now.plus(Duration.ofDays(366)), zone);
        StringBuilder out = new StringBuilder("The next ").append(n)
                .append(n == 1 ? " event" : " events").append(" from now (")
                .append(zone).append("):");
        if (found.isEmpty()) {
            out.append("\nNothing in the calendar for the next year.");
        } else {
            for (int i = 0; i < Math.min(n, found.size()); i++) {
                Occurrence o = found.get(i);
                out.append('\n').append(dayOf(o, zone).format(DAY)).append("  ")
                        .append(line(o, zone));
            }
        }
        return out.append('\n').append(footer()).toString();
    }

    // The calendars, their names as Thunderbird knows them, and how many events each
    // holds - so a question about WHICH calendar something is in has an answer, and so
    // the model knows what it is being told about.
    private static String calendars() {
        Map<String, String> names = calendarNames();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Path db : databases()) {
            for (String[] row : query(db, "SELECT cal_id, count(*) FROM cal_events GROUP BY cal_id")) {
                counts.merge(row[0], Integer.parseInt(row[1]), Integer::sum);
            }
        }
        StringBuilder out = new StringBuilder("Calendars Thunderbird has cached:");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String name = names.getOrDefault(e.getKey(), "(unnamed: " + e.getKey() + ")");
            out.append("\n- ").append(name).append(": ").append(e.getValue())
                    .append(e.getValue() == 1 ? " event" : " events")
                    .append(excluded.contains(name) ? " (left out of briefings)" : "");
        }
        if (counts.isEmpty()) out.append("\n- none; nothing is cached in this profile.");
        Map<String, String> invisible = uncachedCalendars();
        if (!invisible.isEmpty()) {
            out.append("\nIn Thunderbird, but NOT cached - no events of theirs are on this "
                    + "disk, so none can be read here:");
            for (String name : invisible.values()) {
                out.append("\n- ").append(name).append(": Offline Support is off in its "
                        + "properties");
            }
        }
        return out.append('\n').append(readFrom()).toString();
    }

    private static String footer() {
        return readFrom() + uncachedNote();
    }

    private static String readFrom() {
        return "Read from Thunderbird's own offline copy in " + profile + ", read-only.";
    }

    // One event as a line: when, what, where, and which calendar it is in.
    private static String line(Occurrence o, ZoneId zone) {
        ZoneId show = o.allDay ? ZoneOffset.UTC : zone;
        String time = o.allDay ? "all day"
                : ZonedDateTime.ofInstant(o.start, show).format(CLOCK) + "-"
                        + ZonedDateTime.ofInstant(o.end, show).format(CLOCK);
        StringBuilder out = new StringBuilder("  ").append(pad(time, 13)).append(o.title);
        if (!o.location.isEmpty()) out.append(", at ").append(o.location);
        out.append("  [").append(o.calendar).append(']');
        // The id last, after everything a person reads: it is for naming this event in
        // the next request, and nobody needs it until they do.
        if (!o.id.isEmpty()) out.append("  id ").append(o.id);
        // Said on the line itself, because a list of maybes is read one line at a time
        // and "tentative" is the whole of what matters about one of them.
        if (!o.maybe.isEmpty()) out.append("  (").append(o.maybe).append(')');
        if (!o.note.isEmpty()) out.append("  (").append(o.note).append(')');
        return out.toString();
    }

    private static LocalDate dayOf(Occurrence o, ZoneId zone) {
        return ZonedDateTime.ofInstant(o.start, o.allDay ? ZoneOffset.UTC : zone).toLocalDate();
    }

    private static String pad(String text, int width) {
        StringBuilder out = new StringBuilder(text);
        while (out.length() < width) out.append(' ');
        return out.toString();
    }

    // ---- Reading the calendars -------------------------------------------------
    // Everything in [lo, hi): the single events that overlap it, and every occurrence of
    // a repeating one that lands in it. On the reader thread.
    private static List<Occurrence> occurrences(Instant lo, Instant hi, ZoneId zone) {
        Map<String, String> names = calendarNames();
        List<Occurrence> found = new ArrayList<>();
        for (Path db : databases()) {
            long loUs = lo.toEpochMilli() * MICROS;
            long hiUs = hi.toEpochMilli() * MICROS;

            // Everything this agent reads about an event, in one row per event. Spelled
            // out rather than SELECT *, so a column added by a later Thunderbird cannot
            // shift the ones below.
            String columns = "cal_id, id, title, event_start, event_end, event_start_tz, "
                    + "recurrence_id, ical_status";

            // 1. The plain ones: they overlap the window, and they are not an instance of
            //    a repeat (those come with their master, below).
            Map<String, String> maybes = maybesFor(db);
            for (String[] row : query(db, "SELECT " + columns + " FROM cal_events"
                    + " WHERE recurrence_id IS NULL AND event_start < " + hiUs
                    + " AND event_end > " + loUs)) {
                if (cancelled(row[7])) continue;
                if (repeats(db, row[0], row[1])) continue;   // a master: expanded below
                Occurrence o = occurrence(row, names, start(row), end(row), "", maybes);
                if (o != null) found.add(o);
            }

            // 2. The repeating ones. There are few of them - 55 in the profile this was
            //    written against - so every master is read and expanded, rather than
            //    asking the database for a window it cannot reason about.
            Map<String, String[]> masters = new LinkedHashMap<>();
            for (String[] row : query(db, "SELECT " + columns + " FROM cal_events e"
                    + " WHERE recurrence_id IS NULL AND EXISTS (SELECT 1 FROM cal_recurrence r"
                    + " WHERE r.item_id = e.id AND r.cal_id = e.cal_id)")) {
                masters.put(key(row[0], row[1]), row);
            }
            if (masters.isEmpty()) continue;

            Map<String, List<String>> rules = new LinkedHashMap<>();
            for (String[] row : query(db, "SELECT cal_id, item_id, icalString FROM cal_recurrence")) {
                rules.computeIfAbsent(key(row[0], row[1]), k -> new ArrayList<>()).add(row[2]);
            }
            // A changed occurrence is a row of its own, tied to the master by the start it
            // replaces. Collected here so an expansion can put it in place of the one it
            // overrides - or leave a gap, when it is a cancellation.
            Map<String, String[]> changed = new LinkedHashMap<>();
            for (String[] row : query(db, "SELECT " + columns + " FROM cal_events"
                    + " WHERE recurrence_id IS NOT NULL")) {
                changed.put(key(row[0], row[1]) + "@" + row[6], row);
            }

            for (Map.Entry<String, String[]> e : masters.entrySet()) {
                String[] master = e.getValue();
                if (cancelled(master[7])) continue;
                expand(master, rules.getOrDefault(e.getKey(), List.of()), changed,
                        e.getKey(), lo, hi, names, found, maybes);
            }
        }
        java.util.Collections.sort(found);
        return found;
    }

    // Whether this event has a repeat rule of its own.
    private static boolean repeats(Path db, String calId, String id) {
        return !query(db, "SELECT 1 FROM cal_recurrence WHERE cal_id = " + text(calId)
                + " AND item_id = " + text(id) + " LIMIT 1").isEmpty();
    }

    // WHY AN EVENT IS A MAYBE, in a few words - and "" when it is not one.
    //
    // Four things mean "not necessarily happening", and a briefing keeps none of them:
    //
    //   - STATUS:TENTATIVE on the event itself: whoever made it has not settled it. 244
    //     of 1,569 events in the profile this was written against.
    //   - my own ATTENDEE line saying PARTSTAT=TENTATIVE: I answered "maybe".
    //   - PARTSTAT=NEEDS-ACTION: nobody has answered it yet, so it is not arranged with
    //     me however arranged it looks in somebody else's calendar.
    //   - ROLE=OPT-PARTICIPANT: I was invited optionally.
    //
    // All four are left out of "what is on today" and listed by #tentative with the
    // reason on each line, so an unanswered invitation is found by asking for the maybes
    // rather than by being read out as a plan.
    private static String maybeWhy(String icalStatus, Map<String, String> maybes, String key) {
        if ("TENTATIVE".equalsIgnoreCase(icalStatus == null ? "" : icalStatus.trim())) {
            return "tentative";
        }
        return maybes.getOrDefault(key, "");
    }

    // Every event one of this profile's own addresses is a maybe on, and which kind -
    // read in one query per database rather than one per event: the attendee rows that
    // say any of the three things are read whole, and the ones naming an address this
    // Thunderbird sends from are kept.
    private static Map<String, String> maybesFor(Path db) {
        Set<String> mine = new LinkedHashSet<>();
        try {
            for (String[] one : identities()) mine.add(one[1].toLowerCase(Locale.ROOT));
        } catch (Stop ignored) {
            // No prefs.js to read: then nothing can be known to be mine.
        }
        Map<String, String> maybes = new LinkedHashMap<>();
        if (mine.isEmpty()) return maybes;
        for (String[] row : query(db, "SELECT cal_id, item_id, icalString FROM cal_attendees"
                + " WHERE icalString LIKE '%PARTSTAT=TENTATIVE%'"
                + " OR icalString LIKE '%PARTSTAT=NEEDS-ACTION%'"
                + " OR icalString LIKE '%ROLE=OPT-PARTICIPANT%'")) {
            if (row.length < 3) continue;
            String line = row[2] == null ? "" : row[2].toLowerCase(Locale.ROOT);
            boolean ours = false;
            for (String address : mine) {
                if (line.contains(address)) {
                    ours = true;
                    break;
                }
            }
            if (!ours) continue;
            // What I said about it first, what nobody has said second, how I was asked
            // last: "maybe" is a decision, NEEDS-ACTION is the absence of one, and being
            // an optional guest is the weakest of the three.
            String why = line.contains("partstat=tentative") ? "you said maybe"
                    : line.contains("partstat=needs-action") ? "you have not answered it"
                    : "optional for you";
            maybes.put(key(row[0], row[1]), why);
        }
        return maybes;
    }

    private static boolean cancelled(String icalStatus) {
        return "CANCELLED".equalsIgnoreCase(icalStatus == null ? "" : icalStatus.trim());
    }

    private static String key(String calId, String id) {
        return calId + "\u0000" + id;
    }

    private static Instant start(String[] row) {
        return Instant.ofEpochMilli(Long.parseLong(row[3]) / MICROS);
    }

    private static Instant end(String[] row) {
        return Instant.ofEpochMilli(Long.parseLong(row[4]) / MICROS);
    }

    // One row as an occurrence, at the times given - which are the row's own for a single
    // event and the expansion's for a repeat.
    private static Occurrence occurrence(String[] row, Map<String, String> names,
                                         Instant from, Instant to, String note) {
        return occurrence(row, names, from, to, note, java.util.Collections.emptyMap());
    }

    private static Occurrence occurrence(String[] row, Map<String, String> names,
                                         Instant from, Instant to, String note,
                                         Map<String, String> maybesFor) {
        String calendar = names.getOrDefault(row[0], "(unnamed)");
        if (excluded.contains(calendar)) return null;
        String title = row[2] == null || row[2].isBlank() ? "(no title)" : row[2].trim();
        boolean allDay = FLOATING.equalsIgnoreCase(row[5] == null ? "" : row[5].trim());
        return new Occurrence(from, to, allDay, title, calendar, "", note,
                maybeWhy(row[7], maybesFor, key(row[0], row[1])),
                handle(row[0], row[1]));
    }

    // ---- Repeats ---------------------------------------------------------------
    // Every occurrence of one repeating event that lands in [lo, hi), with the changed
    // instances put in place of the ones they replace.
    //
    // Walked from the master's own start rather than jumped to: a rule can carry COUNT,
    // which only counting from the beginning can honour, and the walk is cheap - a step
    // per period, capped, over a window nobody asks more than two years of.
    private static void expand(String[] master, List<String> icalStrings,
                               Map<String, String[]> changed, String masterKey,
                               Instant lo, Instant hi, Map<String, String> names,
                               List<Occurrence> found, Map<String, String> maybesFor) {
        Instant first = start(master);
        Duration length = Duration.between(first, end(master));
        boolean floating = FLOATING.equalsIgnoreCase(master[5] == null ? "" : master[5].trim());
        ZoneId zone = floating ? ZoneOffset.UTC : ZoneId.systemDefault();

        Rule rule = null;
        Set<Instant> skip = new LinkedHashSet<>();
        Set<Instant> extra = new LinkedHashSet<>();
        StringBuilder unknown = new StringBuilder();
        for (String ical : icalStrings) {
            for (String raw : ical.replace("\r", "").split("\n")) {
                String piece = raw.trim();
                if (piece.isEmpty()) continue;
                String name = piece.split("[:;]", 2)[0].toUpperCase(Locale.ROOT);
                if (name.equals("RRULE") && rule == null) {
                    rule = Rule.of(piece.substring(piece.indexOf(':') + 1), unknown);
                } else if (name.equals("EXDATE")) {
                    skip.addAll(dates(piece, zone));
                } else if (name.equals("RDATE")) {
                    extra.addAll(dates(piece, zone));
                } else if (!name.equals("RRULE")) {
                    note(unknown, name);
                }
            }
        }

        // No rule this agent can walk: say so on the event itself rather than drop it.
        // An appointment listed with a warning can be checked in Thunderbird; one that
        // was never listed cannot.
        if (rule == null) {
            String why = unknown.length() == 0 ? "repeats, rule not understood"
                    : "repeats, " + unknown + " not understood";
            if (first.isBefore(hi) && first.plus(length).isAfter(lo)) {
                add(found, occurrence(master, names, first, first.plus(length), why,
                        maybesFor));
            }
            return;
        }

        List<Instant> candidates = new ArrayList<>(extra);
        ZonedDateTime at = ZonedDateTime.ofInstant(first, zone);
        boolean walked = false;
        int steps = 0;
        for (int taken = 0; steps < Rule.STEPS_MAX; steps++) {
            Instant when = at.toInstant();
            if (rule.until != null && when.isAfter(rule.until)) { walked = true; break; }
            if (rule.count > 0 && taken >= rule.count) { walked = true; break; }
            if (!when.isBefore(hi)) { walked = true; break; }  // past the window: done
            if (rule.matches(at)) {
                taken++;
                candidates.add(when);
            }
            at = rule.step(at);
        }
        // The walk gave up before it reached the window - a daily repeat running since
        // the nineties, say. The event is listed once with that said, for the same reason
        // an unparsed rule is: an appointment nobody was told about is the worst outcome.
        if (!walked) {
            add(found, occurrence(master, names, first, first.plus(length),
                    "repeats too often to work out - check it in Thunderbird",
                    maybesFor));
            return;
        }

        for (Instant when : candidates) {
            if (skip.contains(when)) continue;
            String[] instead = changed.get(masterKey + "@" + (when.toEpochMilli() * MICROS));
            if (instead != null) {
                if (cancelled(instead[7])) continue;           // this one was called off
                add(found, lo, hi, occurrence(instead, names, start(instead), end(instead),
                        "moved from " + ZonedDateTime.ofInstant(when, zone).format(DAY),
                        maybesFor));
                continue;
            }
            add(found, lo, hi, occurrence(master, names, when, when.plus(length),
                    rule.describe(), maybesFor));
        }
    }

    private static void add(List<Occurrence> found, Instant lo, Instant hi, Occurrence o) {
        if (o == null) return;
        if (o.start.isBefore(hi) && o.end.isAfter(lo)) found.add(o);
    }

    private static void add(List<Occurrence> found, Occurrence o) {
        if (o != null) found.add(o);
    }

    private static void note(StringBuilder unknown, String what) {
        if (unknown.indexOf(what) >= 0) return;
        if (unknown.length() > 0) unknown.append(", ");
        unknown.append(what);
    }

    // The dates in an EXDATE or RDATE line, each as the instant it names.
    private static List<Instant> dates(String piece, ZoneId zone) {
        List<Instant> out = new ArrayList<>();
        int colon = piece.indexOf(':');
        if (colon < 0) return out;
        for (String value : piece.substring(colon + 1).split(",")) {
            Instant when = instant(value.trim(), zone);
            if (when != null) out.add(when);
        }
        return out;
    }

    // An iCalendar date or date-time: 20261012, 20261012T140000, or the same with a Z.
    private static Instant instant(String value, ZoneId zone) {
        String text = value.trim();
        try {
            if (text.endsWith("Z")) {
                return LocalDateTime.parse(text.substring(0, text.length() - 1),
                        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC);
            }
            if (text.contains("T")) {
                return LocalDateTime.parse(text,
                        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(zone).toInstant();
            }
            return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyyMMdd"))
                    .atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    // One RRULE, as much of it as this agent walks.
    private static final class Rule {
        // A walk has to stop somewhere: a daily rule with no end, asked for a two-year
        // window, is a few hundred steps, and this is far past anything legitimate.
        static final int STEPS_MAX = 20_000;

        String freq = "";
        int interval = 1;
        int count;                       // 0: no count
        Instant until;                   // null: no end
        Set<DayOfWeek> byDay = new LinkedHashSet<>();
        Set<Integer> byMonth = new LinkedHashSet<>();
        Set<Integer> byMonthDay = new LinkedHashSet<>();

        static Rule of(String parts, StringBuilder unknown) {
            Rule r = new Rule();
            for (String part : parts.split(";")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                String key = kv[0].trim().toUpperCase(Locale.ROOT);
                String value = kv[1].trim();
                switch (key) {
                    case "FREQ": r.freq = value.toUpperCase(Locale.ROOT); break;
                    case "INTERVAL": r.interval = Math.max(1, number(value, 1)); break;
                    case "COUNT": r.count = Math.max(0, number(value, 0)); break;
                    case "UNTIL": r.until = instant(value, ZoneOffset.UTC); break;
                    case "BYDAY":
                        for (String day : value.split(",")) {
                            DayOfWeek d = weekday(day);
                            if (d != null) r.byDay.add(d);
                            else note(unknown, "BYDAY=" + day);
                        }
                        break;
                    case "BYMONTH":
                        for (String m : value.split(",")) r.byMonth.add(number(m, 0));
                        break;
                    case "BYMONTHDAY":
                        for (String d : value.split(",")) r.byMonthDay.add(number(d, 0));
                        break;
                    case "WKST":
                        break;    // only changes which week a BYDAY interval starts in
                    default:
                        note(unknown, key);
                }
            }
            if (!r.freq.equals("DAILY") && !r.freq.equals("WEEKLY")
                    && !r.freq.equals("MONTHLY") && !r.freq.equals("YEARLY")) {
                note(unknown, "FREQ=" + r.freq);
                return null;
            }
            return r;
        }

        // The next candidate after this one. A weekly rule with named days steps a day at
        // a time and lets matches() pick, which is what makes "every Tuesday and
        // Thursday" come out right without a calendar of its own.
        ZonedDateTime step(ZonedDateTime at) {
            switch (freq) {
                case "DAILY":   return at.plusDays(interval);
                case "WEEKLY":  return byDay.isEmpty() ? at.plusWeeks(interval) : at.plusDays(1);
                case "MONTHLY": return byMonthDay.isEmpty() ? at.plusMonths(interval)
                                                            : at.plusDays(1);
                default:        return byMonth.isEmpty() && byMonthDay.isEmpty()
                                        ? at.plusYears(interval) : at.plusDays(1);
            }
        }

        boolean matches(ZonedDateTime at) {
            if (!byDay.isEmpty() && !byDay.contains(at.getDayOfWeek())) return false;
            if (!byMonth.isEmpty() && !byMonth.contains(at.getMonthValue())) return false;
            if (!byMonthDay.isEmpty() && !byMonthDay.contains(at.getDayOfMonth())) return false;
            return true;
        }

        String describe() {
            if (interval == 1) return "repeats " + freq.toLowerCase(Locale.ROOT);
            String unit = freq.equals("DAILY") ? "day" : freq.equals("WEEKLY") ? "week"
                    : freq.equals("MONTHLY") ? "month" : "year";
            return "repeats every " + interval + " " + unit + "s";
        }

        static int number(String text, int fallback) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }

        static DayOfWeek weekday(String code) {
            String day = code.trim().toUpperCase(Locale.ROOT);
            // A BYDAY can carry an ordinal - "2TU", the second Tuesday - which this does
            // not walk; the day is taken and the ordinal reported as not understood.
            day = day.replaceAll("^[-+]?\\d+", "");
            switch (day) {
                case "MO": return DayOfWeek.MONDAY;
                case "TU": return DayOfWeek.TUESDAY;
                case "WE": return DayOfWeek.WEDNESDAY;
                case "TH": return DayOfWeek.THURSDAY;
                case "FR": return DayOfWeek.FRIDAY;
                case "SA": return DayOfWeek.SATURDAY;
                case "SU": return DayOfWeek.SUNDAY;
                default:   return null;
            }
        }
    }

    // ---- The database ----------------------------------------------------------
    // The profile's calendar databases that are actually there.
    private static List<Path> databases() {
        Path at = profile;
        if (at == null) throw new Stop("no Thunderbird profile is set.");
        if (!Files.isDirectory(at)) throw new Stop(at + " is not a folder.");
        List<Path> found = new ArrayList<>();
        for (String name : DATABASES) {
            Path db = at.resolve(name.replace('/', java.io.File.separatorChar));
            if (Files.isRegularFile(db)) found.add(db);
        }
        if (found.isEmpty()) {
            throw new Stop(at + " holds no calendar-data/cache.sqlite - is it a "
                    + "Thunderbird profile, and has Thunderbird ever had a calendar in it?");
        }
        return found;
    }

    // ---- A new event, by post ---------------------------------------------------
    // READING is this agent's whole job everywhere else, and this is the one thing it
    // writes - and it does not write it to the calendar. It writes an .ics file and hands
    // it to Thunderbird as an attachment to a mail FROM the configured address TO the
    // same configured address. Thunderbird is the only thing here that touches a
    // calendar, through its own account, its own server and its own rules.
    //
    // Why by post rather than into the database: cache.sqlite is a CACHE of a calendar
    // that lives on a server. Writing a row into it would put an event on this screen
    // that no server has ever heard of, and the next sync would wipe it. An invitation
    // mailed to yourself goes the way every other invitation goes - Thunderbird reads it,
    // you accept it, and the server has it.
    //
    // Why from and to the same address, read from a dropdown and never from the request:
    // an agent takes its instructions from a model, which takes them from whatever was
    // said out loud in a noisy room. No address said out loud can reach this code, and
    // the worst a mistake can do is send you a mail you did not want.
    //
    // THE LAST KEYSTROKE IS YOURS. Thunderbird's command line can open a composed
    // message but has no switch that sends one - there is no -send, and there never has
    // been. So the compose window comes up with everything in it, and Ctrl+Enter is the
    // operator's. Which is the right place for the one irreversible step anyway.
    private static String newEvent(String spec) {
        if (spec.isEmpty()) {
            throw new Stop("#new wants a date, an optional time and a name, as "
                    + "#new 2026-10-15 14:00 Dentist.");
        }
        String[] bits = spec.split("\\s+", 2);
        LocalDate day = date(bits[0]);
        String rest = bits.length > 1 ? bits[1].trim() : "";
        // A time if there is one, and an all-day event if there is not: "the 15th" is a
        // thing people say, and an all-day event is what they mean by it.
        LocalTime at = null;
        Matcher clock = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::\\d{2})?(?=\\s|$)").matcher(rest);
        if (clock.find()) {
            int hour = Integer.parseInt(clock.group(1));
            int minute = Integer.parseInt(clock.group(2));
            if (hour > 23 || minute > 59) throw new Stop("\"" + clock.group() + "\" is not a time.");
            at = LocalTime.of(hour, minute);
            rest = rest.substring(clock.end()).trim();
        }
        if (rest.isEmpty()) throw new Stop("#new wants a name for the event.");
        String summary = rest.length() > 200 ? rest.substring(0, 200).trim() : rest;

        String email = eventEmail.trim();
        if (email.isEmpty()) {
            throw new Stop("no address is set for new events. Pick one in the agent's "
                    + "window - the list is what this Thunderbird profile sends from.");
        }
        Path thunderbird = thunderbird();
        if (thunderbird == null) {
            throw new Stop("Thunderbird was not found. It is what sends the invitation, so "
                    + "there is nothing to send it with.");
        }

        Path file;
        try {
            file = Files.createTempFile("jrock-event-", ".ics");
            Files.write(file, ics(day, at, summary, email).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new Stop("the .ics file could not be written: " + ex.getMessage());
        }

        // One argument, Thunderbird's own compose syntax, with every value percent-encoded
        // because the fields are separated by commas and a name with a comma in it would
        // otherwise become two fields. preselectid names the identity, so the message
        // leaves as the account that owns the address rather than as the default one.
        String identity = identityOf(email);
        String when = at == null ? day.format(LONG_DAY)
                : day.format(LONG_DAY) + " at " + at.format(CLOCK);
        String compose = "from=" + encode(email)
                + ",to=" + encode(email)
                + (identity == null ? "" : ",preselectid=" + identity)
                + ",subject=" + encode(summary)
                + ",body=" + encode("Calendar event: " + summary + "\r\n" + when
                        + "\r\n\r\nSent by the JRock Thunderbird agent, to itself. The "
                        + "attached invitation is what puts it in the calendar.")
                + ",attachment=" + file.toUri();
        try {
            new ProcessBuilder(thunderbird.toString(), "-compose", compose)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException ex) {
            throw new Stop("Thunderbird would not start: " + ex.getMessage());
        }
        return "Event prepared: \"" + summary + "\", " + when + "."
                + "\nThunderbird is composing it from " + email + " to " + email
                + ", with the invitation attached (" + file + ")."
                + "\nPress Ctrl+Enter in that window to send it, then accept the invitation "
                + "when it arrives - that is what puts it in the calendar.";
    }

    // The event as an iCalendar invitation. UTC throughout for a timed event, so no
    // VTIMEZONE has to be written and nothing can be read an hour out; a plain date for
    // an all-day one, which is the one case that must not be converted at all.
    private static String ics(LocalDate day, LocalTime at, String summary, String email) {
        DateTimeFormatter utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
        DateTimeFormatter date = DateTimeFormatter.ofPattern("yyyyMMdd");
        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(utc);
        String start;
        String end;
        if (at == null) {
            start = "DTSTART;VALUE=DATE:" + day.format(date);
            end = "DTEND;VALUE=DATE:" + day.plusDays(1).format(date);
        } else {
            ZonedDateTime from = day.atTime(at).atZone(ZoneId.systemDefault());
            start = "DTSTART:" + from.withZoneSameInstant(ZoneOffset.UTC).format(utc);
            end = "DTEND:" + from.plusMinutes(EVENT_MINUTES)
                    .withZoneSameInstant(ZoneOffset.UTC).format(utc);
        }
        return String.join("\r\n",
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//NoSocial.Net//JRock Calendar Events//EN",
                "METHOD:REQUEST",
                "BEGIN:VEVENT",
                "UID:" + UUID.randomUUID() + "@jrock",
                "DTSTAMP:" + stamp,
                start,
                end,
                "SUMMARY:" + icsText(summary),
                "ORGANIZER:mailto:" + email,
                "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:" + email,
                "SEQUENCE:0",
                "STATUS:CONFIRMED",
                "END:VEVENT",
                "END:VCALENDAR",
                "");
    }

    // A text value in an iCalendar file: backslash, semicolon, comma and newline are the
    // four characters that mean something there (RFC 5545).
    private static String icsText(String text) {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    private static String encode(String value) {
        try {
            // A space as %20 rather than +: this is a URL field, not a form post.
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return value;
        }
    }

    // The addresses this profile sends from: mail.identity.idN.useremail in prefs.js,
    // with the name beside it. Read every time, like the calendar names are - an address
    // added in Thunderbird should appear in the dropdown without restarting anything.
    // Each entry is {identity key, address, name}.
    private static List<String[]> identities() {
        Map<String, String[]> found = new LinkedHashMap<>();
        Path prefs = profile == null ? null : profile.resolve("prefs.js");
        if (prefs == null || !Files.isRegularFile(prefs)) return new ArrayList<>();
        Pattern pref = Pattern.compile(
                "user_pref\\(\"mail\\.identity\\.(id\\d+)\\.(useremail|fullName)\",\\s*\"(.*)\"\\);");
        try {
            for (String line : Files.readAllLines(prefs, StandardCharsets.UTF_8)) {
                Matcher m = pref.matcher(line.trim());
                if (!m.matches()) continue;
                String[] one = found.computeIfAbsent(m.group(1),
                        key -> new String[] { key, "", "" });
                one[m.group(2).equals("useremail") ? 1 : 2] = unescape(m.group(3));
            }
        } catch (IOException ex) {
            throw new Stop("could not read " + prefs + ": " + ex.getMessage());
        }
        List<String[]> addresses = new ArrayList<>();
        for (String[] one : found.values()) {
            if (!one[1].isEmpty()) addresses.add(one);
        }
        return addresses;
    }

    // Which identity owns an address, for preselectid - or null, which leaves Thunderbird
    // to pick, as it would for a mail composed by hand.
    private static String identityOf(String email) {
        try {
            for (String[] one : identities()) {
                if (one[1].equalsIgnoreCase(email)) return one[0];
            }
        } catch (Stop ignored) {
            // No prefs.js to read: Thunderbird's own default identity will do.
        }
        return null;
    }

    // Thunderbird itself: on PATH, where Windows records its mail client, or where each
    // platform installs it. Looked for when it is needed rather than kept, so installing
    // Thunderbird after this agent started is enough.
    private static Path thunderbird() {
        String[] names = isWindows() ? new String[] { "thunderbird.exe" }
                                     : new String[] { "thunderbird" };
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
        if (isWindows()) {
            // Where Windows records the mail client, which is where it actually is.
            for (String line : run(QUERY_TIMEOUT_MS, "reg.exe", "query",
                    "HKLM\\SOFTWARE\\Clients\\Mail\\Mozilla Thunderbird\\shell\\open\\command",
                    "/ve")) {
                Matcher m = Pattern.compile("\"([^\"]+thunderbird\\.exe)\"",
                        Pattern.CASE_INSENSITIVE).matcher(line);
                if (m.find()) {
                    Path at = Paths.get(m.group(1));
                    if (Files.isRegularFile(at)) return at;
                }
            }
        }
        for (String at : new String[] {
                "C:\\Program Files\\Mozilla Thunderbird\\thunderbird.exe",
                "C:\\Program Files (x86)\\Mozilla Thunderbird\\thunderbird.exe",
                "/Applications/Thunderbird.app/Contents/MacOS/thunderbird",
                "/usr/bin/thunderbird", "/usr/local/bin/thunderbird",
                "/snap/bin/thunderbird" }) {
            Path candidate = Paths.get(at);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    // THE CALENDARS NOTHING HERE CAN SEE: in the registry, but with Offline Support
    // turned off (calendar.registry.<uuid>.cache.enabled = false) on a calendar that
    // lives on a server. Thunderbird keeps those nowhere on this disk - it asks the
    // server every time it needs them - so no file-based reader can see a single event
    // in them, however often it looks.
    //
    // Which is worth saying out loud rather than leaving as a silence. An assistant that
    // answers "nothing on Thursday" when Thursday is full, because the calendar it is in
    // is not cached, is worse than one that says it cannot see that calendar: the first
    // is believed. So every briefing ends with a line naming them when there are any,
    // and #calendars lists them with the rest.
    //
    // A "storage" calendar is Thunderbird's own local one and lives in local.sqlite,
    // which is read: cache.enabled means nothing there, and it is not reported.
    private static Map<String, String> uncachedCalendars() {
        Map<String, String> names = new LinkedHashMap<>();   // uuid -> name
        Map<String, Boolean> cached = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        Path prefs = profile == null ? null : profile.resolve("prefs.js");
        if (prefs == null || !Files.isRegularFile(prefs)) return new LinkedHashMap<>();
        Pattern pref = Pattern.compile("user_pref\\(\"calendar\\.registry\\."
                + "([0-9a-fA-F-]+)\\.(name|cache\\.enabled|type)\",\\s*(.*)\\);");
        try {
            for (String line : Files.readAllLines(prefs, StandardCharsets.UTF_8)) {
                Matcher m = pref.matcher(line.trim());
                if (!m.matches()) continue;
                String uuid = m.group(1);
                String value = m.group(3).trim();
                switch (m.group(2)) {
                    case "name":
                        names.put(uuid, unescape(strip(value)));
                        break;
                    case "cache.enabled":
                        cached.put(uuid, Boolean.parseBoolean(value));
                        break;
                    default:
                        types.put(uuid, strip(value));
                }
            }
        } catch (IOException ex) {
            throw new Stop("could not read " + prefs + ": " + ex.getMessage());
        }
        Map<String, String> invisible = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> one : cached.entrySet()) {
            if (one.getValue()) continue;
            if ("storage".equals(types.get(one.getKey()))) continue;   // local.sqlite
            invisible.put(one.getKey(), names.getOrDefault(one.getKey(),
                    "(unnamed: " + one.getKey() + ")"));
        }
        return invisible;
    }

    // A prefs.js value with its quotes taken off, when it has any.
    private static String strip(String value) {
        return value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    // The one line a briefing ends with when a calendar cannot be read at all, and
    // nothing when they all can.
    private static String uncachedNote() {
        Map<String, String> invisible;
        try {
            invisible = uncachedCalendars();
        } catch (Stop stop) {
            return "";
        }
        if (invisible.isEmpty()) return "";
        return "\nNOT INCLUDED, and not readable here at all: "
                + String.join(", ", invisible.values())
                + ". Thunderbird keeps " + (invisible.size() == 1 ? "it" : "them")
                + " on the server only (Offline Support is off in the calendar's "
                + "properties), so nothing on this disk holds "
                + (invisible.size() == 1 ? "its" : "their") + " events. Anything in "
                + (invisible.size() == 1 ? "it" : "them") + " is missing from this "
                + "briefing - say so if it matters.";
    }

    // The calendars' names, as Thunderbird keeps them in prefs.js. Read every time: a
    // calendar renamed in Thunderbird should be renamed here without a restart.
    private static Map<String, String> calendarNames() {
        Map<String, String> names = new LinkedHashMap<>();
        Path prefs = profile == null ? null : profile.resolve("prefs.js");
        if (prefs == null || !Files.isRegularFile(prefs)) return names;
        Pattern name = Pattern.compile(
                "user_pref\\(\"calendar\\.registry\\.([0-9a-fA-F-]+)\\.name\",\\s*\"(.*)\"\\);");
        try {
            for (String line : Files.readAllLines(prefs, StandardCharsets.UTF_8)) {
                Matcher m = name.matcher(line.trim());
                if (m.matches()) names.put(m.group(1), unescape(m.group(2)));
            }
        } catch (IOException ex) {
            throw new Stop("could not read " + prefs + ": " + ex.getMessage());
        }
        return names;
    }

    // prefs.js is JavaScript: a name with a quote or a non-ASCII letter in it is escaped.
    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 'u' && i + 4 < value.length()) {
                try {
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                } catch (NumberFormatException ex) {
                    out.append(next);
                }
            } else {
                out.append(next);
            }
        }
        return out.toString();
    }

    // A short-lived program whose output is wanted, for the one lookup that is not a
    // query: where Windows says Thunderbird is. Nothing on an empty answer, which is
    // what "not there" looks like to the caller.
    private static List<String> run(int timeoutMs, String... command) {
        List<String> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) out.add(line);
            }
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
        } catch (IOException ex) {
            // Not there, which an empty answer says.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    // One query, read-only, through sqlite3. Rows and columns come back separated by
    // characters a calendar entry cannot hold, so a title with a newline in it does not
    // become two rows.
    private static List<String[]> query(Path db, String sql) {
        Path tool = sqlite3();
        List<String> cmd = List.of(tool.toString(), "-readonly", "-batch", "-noheader",
                "-nullvalue", "", "-separator", COLUMN, "-newline", ROW, db.toString(), sql);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(QUERY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new Stop("sqlite3 did not answer within "
                        + QUERY_TIMEOUT_MS / 1000 + " s.");
            }
            if (p.exitValue() != 0) {
                throw new Stop("sqlite3 said: " + out.replace(ROW, " ").trim());
            }
            List<String[]> rows = new ArrayList<>();
            for (String row : out.split(ROW)) {
                if (row.isEmpty()) continue;
                rows.add(row.split(COLUMN, -1));
            }
            return rows;
        } catch (IOException ex) {
            throw new Stop("could not run " + tool + ": " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Stop("interrupted while reading the calendar.");
        }
    }

    // A string for SQL, quoted the one way SQL quotes one. Only ever used on ids read
    // out of the database itself - nothing a client sent reaches a query.
    private static String text(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // sqlite3: on PATH, then beside this file, then in the working directory - the three
    // places it is reasonable to have put it. Found once and remembered.
    private static Path sqlite3() {
        Path found = sqlite;
        if (found != null) return found;
        String exe = isWindows() ? "sqlite3.exe" : "sqlite3";
        List<Path> tried = new ArrayList<>();
        for (String dir : System.getenv().getOrDefault("PATH", "")
                .split(Pattern.quote(java.io.File.pathSeparator))) {
            if (dir.isBlank()) continue;
            tried.add(Paths.get(dir).resolve(exe));
        }
        tried.add(ownDirectory().resolve(exe));
        tried.add(Paths.get("").toAbsolutePath().resolve(exe));
        for (Path at : tried) {
            if (Files.isRegularFile(at)) {
                sqlite = at;
                return at;
            }
        }
        throw new Stop("sqlite3 was not found - on PATH, beside this agent, or in the "
                + "working directory. Download the command-line tools from "
                + "https://sqlite.org/download.html and put sqlite3" + (isWindows() ? ".exe" : "")
                + " in one of those places.");
    }

    // What the window says about the schema it just read, or null when it is the one this
    // was written against.
    private static String schemaNote() {
        for (Path db : databases()) {
            List<String[]> rows = query(db, "SELECT version FROM cal_calendar_schema_version");
            if (rows.isEmpty()) continue;
            int version = Rule.number(rows.get(0)[0], -1);
            if (version != SCHEMA_KNOWN) {
                return "Thunderbird's calendar schema here is version " + version
                        + ", and this agent was written against " + SCHEMA_KNOWN
                        + " - what it reads may be incomplete.";
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ---- The server ------------------------------------------------------------
    private static String listen() {
        try {
            ServerSocket socket = new ServerSocket(PORT, 16, InetAddress.getLoopbackAddress());
            server = socket;
            daemon(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket client = socket.accept();
                        daemon(() -> serve(client), "jrock-calendar-client").start();
                    } catch (IOException closed) {
                        return;     // closed with the window, or failed: no more clients
                    }
                }
            }, "jrock-calendar-server").start();
            return "Other agents ask on 127.0.0.1:" + PORT + ".";
        } catch (IOException ex) {
            return "Not listening on port " + PORT + " (" + ex.getMessage() + ") - is "
                    + "another calendar agent running? The window still works.";
        }
    }

    // One client: a line in, the briefing out, closed. The reply is many lines, so the
    // close is what ends it - which is how the location agent's clients read it too.
    private static void serve(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(CLIENT_TIMEOUT_MS);
            String line = readLine(c.getInputStream());
            String reply;
            try {
                reply = READER.submit(() -> answer(line)).get();
            } catch (java.util.concurrent.ExecutionException ex) {
                Throwable why = ex.getCause() == null ? ex : ex.getCause();
                reply = "No calendar: " + why.getMessage();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                reply = "No calendar: interrupted.";
            }
            String said = reply;
            later(() -> {
                say("Answered " + (line == null ? "a client" : line) + ".");
                briefing.setText(said);
                briefing.setCaretPosition(0);
            });
            OutputStream out = c.getOutputStream();
            out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ex) {
            // A client that went away, or never wrote: nobody to tell.
        }
    }

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
    private static final java.awt.Color MUTED = new java.awt.Color(0x6B7280);

    private static void showWindow() {
        window = new javax.swing.JFrame("JRock - Thunderbird: calendar and mail");
        window.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                ServerSocket s = server;
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) { }
                }
            }
        });

        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);
        java.awt.Font small = base.deriveFont(java.awt.Font.PLAIN, 12f);
        java.awt.Font fixed = new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13);

        // The profile. One specific folder, typed or browsed to - never a pattern: which
        // profile is read is not something to be guessed at when there are several.
        profileField = new javax.swing.JTextField(profile == null ? "" : profile.toString(), 40);
        profileField.setFont(small);
        profileField.setToolTipText("The Thunderbird profile folder to read, "
                + "e.g. K:\\Thunderbird\\Profiles\\xxxxxxxx.default-release");
        javax.swing.JButton browse = new javax.swing.JButton("Browse\u2026");
        browse.addActionListener(e -> {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setDialogTitle("The Thunderbird profile to read");
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (profile != null && Files.isDirectory(profile)) {
                chooser.setCurrentDirectory(profile.toFile());
            }
            if (chooser.showOpenDialog(window) == javax.swing.JFileChooser.APPROVE_OPTION) {
                profileField.setText(chooser.getSelectedFile().getAbsolutePath());
                useProfile();
            }
        });
        javax.swing.JButton use = new javax.swing.JButton("Use");
        use.addActionListener(e -> useProfile());
        profileField.addActionListener(e -> useProfile());

        javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(browse);
        buttons.add(use);

        javax.swing.JPanel profileRow = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        profileRow.setOpaque(false);
        profileRow.add(new javax.swing.JLabel("Thunderbird profile:"), java.awt.BorderLayout.WEST);
        profileRow.add(profileField, java.awt.BorderLayout.CENTER);
        profileRow.add(buttons, java.awt.BorderLayout.EAST);

        // The address a new event is mailed from and to - both, the same one. The list is
        // what this profile is set up to send from, so there is nothing to type and no
        // address to get wrong; an address that is not in it cannot be chosen at all.
        emailBox = new javax.swing.JComboBox<>();
        emailBox.setFont(small);
        emailBox.setToolTipText("New events are mailed from this address to the same "
                + "address, with the invitation attached - so nothing can reach anybody else");
        emailBox.addActionListener(e -> {
            Object picked = emailBox.getSelectedItem();
            String chosen = picked == null ? "" : addressIn(picked.toString());
            if (chosen.equals(eventEmail)) return;
            eventEmail = chosen;
            writeSettings();
            say(eventEmail.isEmpty() ? "No address set for new events."
                    : "New events will be mailed from " + eventEmail + " to itself.");
        });
        javax.swing.JPanel emailRow = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        emailRow.setOpaque(false);
        emailRow.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 0, 0, 0));
        emailRow.add(new javax.swing.JLabel("New events from/to:"), java.awt.BorderLayout.WEST);
        emailRow.add(emailBox, java.awt.BorderLayout.CENTER);

        // What to ask, which is also what an agent can ask.
        javax.swing.JPanel asks = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 6, 0));
        asks.setOpaque(false);
        asks.add(label("Calendar:"));
        for (String[] ask : new String[][] { { "Today", "#today" }, { "This week", "#week" },
                                             { "Next 5", "#next 5" },
                                             { "Calendars", "#calendars" } }) {
            javax.swing.JButton b = new javax.swing.JButton(ask[0]);
            String request = ask[1];
            b.addActionListener(e -> refresh(request));
            asks.add(b);
        }

        // The mail questions, on a row of their own: the same buttons an agent's request
        // lines are, so what the operator can see is what a model can ask for.
        javax.swing.JPanel mailAsks = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 6, 0));
        mailAsks.setOpaque(false);
        mailAsks.add(label("Mail:"));
        for (String[] ask : new String[][] { { "Arrived today", "#mail" },
                                             { "Last 3 days", "#mail 3" },
                                             { "Unanswered", "#unanswered" },
                                             { "Senders (5 days)", "#senders 5" },
                                             { "Mailboxes", "#mailboxes" } }) {
            javax.swing.JButton b = new javax.swing.JButton(ask[0]);
            String request = ask[1];
            b.addActionListener(e -> refresh(request));
            mailAsks.add(b);
        }
        // And the one question that needs a word typed into it.
        javax.swing.JTextField withField = new javax.swing.JTextField(14);
        withField.setToolTipText("A name or an address: the last mail each way, and how "
                + "much there has been");
        javax.swing.JButton withButton = new javax.swing.JButton("Mail with\u2026");
        java.awt.event.ActionListener askWith = e -> {
            String asked = withField.getText().trim();
            if (asked.isEmpty()) {
                say("Type a name or an address to look for first.");
                return;
            }
            refresh("#with " + asked);
        };
        withButton.addActionListener(askWith);
        withField.addActionListener(askWith);
        mailAsks.add(withButton);
        mailAsks.add(withField);

        calendarBox = new javax.swing.JPanel();
        calendarBox.setLayout(new javax.swing.BoxLayout(calendarBox, javax.swing.BoxLayout.Y_AXIS));
        calendarBox.setOpaque(false);
        javax.swing.JScrollPane calendars = new javax.swing.JScrollPane(calendarBox);
        calendars.setBorder(javax.swing.BorderFactory.createTitledBorder("Include"));
        calendars.setPreferredSize(new java.awt.Dimension(240, 220));

        briefing = new javax.swing.JTextArea(14, 60);
        briefing.setFont(fixed);
        briefing.setEditable(false);          // read-only, like everything else here
        briefing.setLineWrap(false);
        javax.swing.JScrollPane text = new javax.swing.JScrollPane(briefing);
        text.setBorder(javax.swing.BorderFactory.createTitledBorder("What an agent is told"));

        status = new javax.swing.JLabel(" ");
        status.setFont(small);
        status.setForeground(MUTED);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 2, 8, 2));

        listening = new javax.swing.JLabel(" ");
        listening.setFont(small);
        listening.setForeground(MUTED);
        listening.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 2, 0, 2));

        javax.swing.JPanel rows = new javax.swing.JPanel();
        rows.setLayout(new javax.swing.BoxLayout(rows, javax.swing.BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        for (javax.swing.JComponent row : new javax.swing.JComponent[] {
                profileRow, emailRow, asks, mailAsks }) {
            row.setAlignmentX(0f);
            rows.add(row);
        }

        javax.swing.JPanel north = new javax.swing.JPanel(new java.awt.BorderLayout());
        north.setOpaque(false);
        north.add(rows, java.awt.BorderLayout.NORTH);
        north.add(status, java.awt.BorderLayout.SOUTH);

        javax.swing.JPanel middle = new javax.swing.JPanel(new java.awt.BorderLayout(10, 0));
        middle.setOpaque(false);
        middle.add(text, java.awt.BorderLayout.CENTER);
        middle.add(calendars, java.awt.BorderLayout.EAST);

        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout());
        content.setBackground(BACKGROUND);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 12, 16));
        content.add(north, java.awt.BorderLayout.NORTH);
        content.add(middle, java.awt.BorderLayout.CENTER);
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
        if (profile == null) {
            say("Set the Thunderbird profile folder above, then press Use.");
        } else {
            showEmails();
            showCalendars();
        }
    }

    // The dropdown of addresses, filled from whichever profile is in use. The one
    // remembered in the settings file is selected when it is still there, and said so
    // when it is not: a profile that no longer sends from an address cannot mail from it,
    // and finding that out when an event is asked for would be finding out too late.
    private static void showEmails() {
        List<String[]> addresses;
        try {
            addresses = identities();
        } catch (Stop stop) {
            addresses = new ArrayList<>();
        }
        emailBox.removeAllItems();
        emailBox.addItem("");
        String keep = eventEmail;
        String select = null;
        for (String[] one : addresses) {
            String shown = one[2].isEmpty() ? one[1] : one[2] + " <" + one[1] + ">";
            emailBox.addItem(shown);
            if (one[1].equalsIgnoreCase(keep)) select = shown;
        }
        // Set directly, so the listener does not read this as the operator choosing.
        eventEmail = select == null ? "" : keep;
        emailBox.setSelectedItem(select == null ? "" : select);
        if (addresses.isEmpty()) {
            say("No addresses in this profile's prefs.js, so no event can be mailed yet.");
        } else if (select == null && !keep.isEmpty()) {
            say("This profile does not send from " + keep + " any more - pick an address "
                    + "for new events.");
        }
    }

    // The caption at the head of a row of buttons.
    private static javax.swing.JLabel label(String text) {
        javax.swing.JLabel caption = new javax.swing.JLabel(text);
        caption.setForeground(MUTED);
        return caption;
    }

    // The address out of what the dropdown shows, which is "Name <address>".
    private static String addressIn(String shown) {
        int open = shown.lastIndexOf('<');
        int close = shown.lastIndexOf('>');
        String address = open >= 0 && close > open ? shown.substring(open + 1, close) : shown;
        return address.trim();
    }

    // The profile as typed, taken and remembered.
    private static void useProfile() {
        String typed = profileField.getText().trim();
        if (typed.isEmpty()) {
            say("No profile set.");
            return;
        }
        profile = Paths.get(typed).toAbsolutePath().normalize();
        profileField.setText(profile.toString());
        writeSettings();
        showEmails();
        showCalendars();
        refresh("#week");
    }

    // One checkbox per calendar, so a noisy one - a holiday feed of several hundred
    // entries - can be left out of a briefing without unsubscribing from it anywhere.
    private static void showCalendars() {
        READER.submit(() -> {
            Map<String, String> names;
            String note;
            try {
                names = calendarNames();
                note = schemaNote();
            } catch (Stop stop) {
                later(() -> say(stop.getMessage()));
                return;
            }
            Set<String> inUse = new LinkedHashSet<>();
            Map<String, String> invisible;
            try {
                for (Path db : databases()) {
                    for (String[] row : query(db,
                            "SELECT DISTINCT cal_id FROM cal_events")) {
                        inUse.add(names.getOrDefault(row[0], "(unnamed: " + row[0] + ")"));
                    }
                }
                invisible = uncachedCalendars();
            } catch (Stop stop) {
                later(() -> say(stop.getMessage()));
                return;
            }
            later(() -> {
                calendarBox.removeAll();
                for (String name : inUse) {
                    javax.swing.JCheckBox box = new javax.swing.JCheckBox(name,
                            !excluded.contains(name));
                    box.setOpaque(false);
                    box.addActionListener(e -> {
                        if (box.isSelected()) excluded.remove(name);
                        else excluded.add(name);
                        writeSettings();
                        refresh(lastAsked);
                    });
                    calendarBox.add(box);
                }
                // The ones there is nothing to include: in Thunderbird, not on this disk.
                // Shown rather than left out, because a calendar missing from this list
                // is the one thing that would send somebody looking for a refresh button
                // that would not have helped.
                for (String name : invisible.values()) {
                    javax.swing.JCheckBox box = new javax.swing.JCheckBox(name, false);
                    box.setOpaque(false);
                    box.setEnabled(false);
                    box.setToolTipText("Offline Support is off for this calendar in "
                            + "Thunderbird, so none of its events are on this disk and "
                            + "nothing here can read them");
                    calendarBox.add(box);
                }
                calendarBox.revalidate();
                calendarBox.repaint();
                if (note != null) say(note);
                if (!invisible.isEmpty()) {
                    say("Not readable here: " + String.join(", ", invisible.values())
                            + " - Offline Support is off for "
                            + (invisible.size() == 1 ? "it" : "them")
                            + " in Thunderbird, so none of "
                            + (invisible.size() == 1 ? "its" : "their")
                            + " events are on this disk. Turn it on in the calendar's "
                            + "properties and let Thunderbird sync.");
                }
            });
        });
    }

    private static volatile String lastAsked = "#week";

    // Asks the same question the socket would, and shows the answer.
    private static void refresh(String request) {
        lastAsked = request;
        READER.submit(() -> {
            String text;
            try {
                text = answer(request);
            } catch (Stop stop) {
                text = "No calendar: " + stop.getMessage();
            }
            String shown = text;
            later(() -> {
                briefing.setText(shown);
                briefing.setCaretPosition(0);
                say("Showing " + request + ". Nothing here ever writes to Thunderbird.");
            });
        });
    }

    private static void say(String text) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        status.setText("<html><div style='width:700px'>" + safe + "</div></html>");
    }

    // ---- Moving an appointment of the operator's own ----------------------------
    // ONLY AN EVENT THAT IS ENTIRELY THEIRS: organised by them, or by nobody, and with
    // no participant but them. Which means this cannot address a mail to anybody else -
    // it goes from the operator to the operator, exactly as #new does, and the whole of
    // this agent keeps the property that no address said out loud can reach it.
    //
    // Everything else is refused with the reason. A meeting somebody else arranged is
    // theirs to move, and asking them to is a conversation rather than a command; a
    // meeting with other people in it cannot be moved without telling them, which is
    // not something to do from across a room by voice. In this calendar that leaves 311
    // of the 312 upcoming events movable and one refused, which is about the right shape
    // for a rule nobody has to think about.
    //
    // The mechanism follows from who is asking: the ORGANIZER of an event is the one
    // allowed to change it, so this writes METHOD:REQUEST with the same UID and the
    // SEQUENCE one higher than the event carries - an update, in the calendar standard's
    // own terms (RFC 5546), which is what Thunderbird reads as "this event has been
    // moved" rather than as a new one. METHOD:COUNTER, which an attendee would send to
    // an organizer, has no place here: nobody is being asked for anything.
    //
    // Nothing is sent, as ever: Thunderbird's window opens with everything in it and
    // Ctrl+Enter is the operator's. Until then the calendar is untouched.
    private static String moveEvent(String spec, ZoneId zone) {
        String[] parts = spec.trim().split("\\s+");
        if (parts.length < 2) {
            throw new Stop("#move wants an event and a new time, as "
                    + "#move a3f91c 2026-10-20 14:30.");
        }
        LocalDate day = date(parts[1]);
        LocalTime at = null;
        if (parts.length > 2) {
            Matcher clock = Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(parts[2]);
            if (!clock.matches()) throw new Stop("\"" + parts[2] + "\" is not a time.");
            int hour = Integer.parseInt(clock.group(1));
            int minute = Integer.parseInt(clock.group(2));
            if (hour > 23 || minute > 59) throw new Stop("\"" + parts[2] + "\" is not a time.");
            at = LocalTime.of(hour, minute);
        }

        // The event, by the short handle a briefing prints or by its whole UID.
        String asked = parts[0].trim();
        Path where = null;
        String[] event = null;
        for (Path db : databases()) {
            for (String[] row : query(db, "SELECT cal_id, id, title, event_start, event_end,"
                    + " event_start_tz, recurrence_id, ical_status FROM cal_events"
                    + " WHERE recurrence_id IS NULL")) {
                if (row.length < 8) continue;
                if (!asked.equalsIgnoreCase(handle(row[0], row[1]))
                        && !asked.equalsIgnoreCase(row[1])) {
                    continue;
                }
                where = db;
                event = row;
                break;
            }
            if (event != null) break;
        }
        if (event == null) {
            throw new Stop("no event has the id \"" + asked + "\". Ask for the calendar "
                    + "again - every line carries its id - and use the one on the line.");
        }

        Set<String> mine = new LinkedHashSet<>();
        try {
            for (String[] one : identities()) mine.add(one[1].toLowerCase(Locale.ROOT));
        } catch (Stop ignored) {
            // No prefs.js: then nothing is known to be the operator's own.
        }
        // Whose event it is. No organizer at all is an appointment somebody typed into
        // their own calendar, which is the commonest kind there is and as much theirs as
        // one that names them.
        String[] organizer = organizerOf(where, event[0], event[1]);
        if (organizer != null && !mine.contains(organizer[0].toLowerCase(Locale.ROOT))) {
            throw new Stop("\"" + event[2] + "\" is organised by "
                    + (organizer[1].isEmpty() ? organizer[0] : organizer[1])
                    + ", not by you, so it is not yours to move. Ask them to.");
        }
        // Who else is on it. One other participant is one person who would turn up at
        // the old time, and nothing here writes to them.
        List<String> others = otherAttendees(where, event[0], event[1], mine);
        if (!others.isEmpty()) {
            throw new Stop("\"" + event[2] + "\" has " + others.size()
                    + (others.size() == 1 ? " other participant (" : " other participants (")
                    + String.join(", ", others.subList(0, Math.min(3, others.size())))
                    + (others.size() > 3 ? ", ..." : "")
                    + "), so moving it is something they have to be told about. Do that one "
                    + "in Thunderbird.");
        }
        String from = attendeeAddress(where, event[0], event[1], mine);
        if (from == null && organizer != null) from = organizer[0];
        if (from == null) from = eventEmail.trim();
        if (from == null || from.isEmpty()) {
            throw new Stop("no address of yours is on \"" + event[2] + "\" and none is set "
                    + "in this window, so there is nothing to send the update from.");
        }

        // The new time, the old length. A meeting moved is the same meeting: nothing here
        // shortens or stretches it, which is not what was asked for.
        Instant was = start(event);
        Duration length = Duration.between(was, end(event));
        boolean allDay = FLOATING.equalsIgnoreCase(event[5] == null ? "" : event[5].trim());
        if (at == null && !allDay) {
            at = ZonedDateTime.ofInstant(was, zone).toLocalTime();
        }
        ZonedDateTime now = allDay && at == null ? day.atStartOfDay(ZoneOffset.UTC)
                : day.atTime(at).atZone(zone);

        Path file;
        try {
            file = Files.createTempFile("jrock-move-", ".ics");
            Files.write(file, moveIcs(event, from, now, length, allDay,
                    sequenceOf(where, event[0], event[1]) + 1)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new Stop("the .ics file could not be written: " + ex.getMessage());
        }

        Path thunderbird = thunderbird();
        if (thunderbird == null) {
            throw new Stop("Thunderbird was not found, and it is what sends the update.");
        }
        String when = allDay && at == null ? day.format(LONG_DAY)
                : day.format(LONG_DAY) + " at " + now.format(CLOCK);
        String wasWhen = ZonedDateTime.ofInstant(was, allDay ? ZoneOffset.UTC : zone)
                .format(LONG_DAY) + (allDay ? ""
                : " at " + ZonedDateTime.ofInstant(was, zone).format(CLOCK));
        String identity = identityOf(from);
        String compose = "from=" + encode(from)
                + ",to=" + encode(from)
                + (identity == null ? "" : ",preselectid=" + identity)
                + ",subject=" + encode("Moved: " + event[2])
                + ",body=" + encode("An appointment of yours, at a new time.\r\n\r\n"
                        + event[2] + "\r\nWas: " + wasWhen + "\r\nNow: " + when
                        + "\r\n\r\nAccept the update when it arrives and the calendar "
                        + "follows. Sent by the JRock Thunderbird agent, to itself.")
                + ",attachment=" + file.toUri();
        try {
            new ProcessBuilder(thunderbird.toString(), "-compose", compose)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException ex) {
            throw new Stop("Thunderbird would not start: " + ex.getMessage());
        }
        return "New time for \"" + event[2] + "\": " + wasWhen + " becomes " + when
                + ".\nThunderbird is composing it from " + from + " to " + from
                + ", as an update to that appointment (" + file + ")."
                + "\nPress Ctrl+Enter in that window to send it, then accept the update "
                + "when it arrives - that is what moves it in the calendar.";
    }

    // The update itself: the same UID, so the calendar knows which appointment is meant,
    // and a SEQUENCE one higher than it carries, which is how a calendar tells an update
    // from the invitation it replaces. The operator is the organizer and the only
    // attendee, because that is the only kind of event this is allowed on.
    private static String moveIcs(String[] event, String from, ZonedDateTime when,
                                  Duration length, boolean allDay, int sequence) {
        DateTimeFormatter utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
        DateTimeFormatter date = DateTimeFormatter.ofPattern("yyyyMMdd");
        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(utc);
        String start;
        String end;
        if (allDay) {
            start = "DTSTART;VALUE=DATE:" + when.toLocalDate().format(date);
            end = "DTEND;VALUE=DATE:" + when.toLocalDate()
                    .plusDays(Math.max(1, length.toDays())).format(date);
        } else {
            start = "DTSTART:" + when.withZoneSameInstant(ZoneOffset.UTC).format(utc);
            end = "DTEND:" + when.plus(length).withZoneSameInstant(ZoneOffset.UTC).format(utc);
        }
        return String.join("\r\n",
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//NoSocial.Net//JRock Thunderbird Agent//EN",
                "METHOD:REQUEST",
                "BEGIN:VEVENT",
                "UID:" + event[1],
                "DTSTAMP:" + stamp,
                start,
                end,
                "SUMMARY:" + icsText(event[2] == null ? "(no title)" : event[2]),
                "ORGANIZER:mailto:" + from,
                "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:" + from,
                "SEQUENCE:" + sequence,
                "STATUS:CONFIRMED",
                "END:VEVENT",
                "END:VCALENDAR",
                "");
    }

    // The ORGANIZER of an event, as {address, name} - or null when it has none, which is
    // what an event somebody made for themselves looks like.
    //
    // Two shapes, because two kinds of server write it two ways: the value may be a
    // mailto: URI, or a principal URL with the address in an EMAIL parameter beside it,
    // which is what iCloud does.
    private static String[] organizerOf(Path db, String calId, String id) {
        for (String[] row : query(db, "SELECT icalString FROM cal_attendees"
                + " WHERE cal_id = " + text(calId) + " AND item_id = " + text(id)
                + " AND icalString LIKE 'ORGANIZER%'")) {
            String line = row.length == 0 ? "" : row[0];
            String address = icalAddress(line);
            if (address.isEmpty()) continue;
            Matcher cn = Pattern.compile("(?i)CN=([^;:]*)").matcher(line);
            return new String[] { address, cn.find() ? cn.group(1).trim() : "" };
        }
        return null;
    }

    // Everybody on this event who is not the operator, by the address on their line -
    // the people who would turn up at the old time, and the reason an event with any of
    // them on it is not one to move by voice.
    private static List<String> otherAttendees(Path db, String calId, String id,
                                               Set<String> mine) {
        List<String> others = new ArrayList<>();
        for (String[] row : query(db, "SELECT icalString FROM cal_attendees"
                + " WHERE cal_id = " + text(calId) + " AND item_id = " + text(id)
                + " AND icalString LIKE 'ATTENDEE%'")) {
            String line = row.length == 0 ? "" : row[0];
            String address = icalAddress(line);
            if (address.isEmpty() || mine.contains(address.toLowerCase(Locale.ROOT))) continue;
            Matcher cn = Pattern.compile("(?i)CN=([^;:]*)").matcher(line);
            String name = cn.find() ? cn.group(1).trim() : "";
            others.add(name.isEmpty() ? address : name + " <" + address + ">");
        }
        return others;
    }

    // Which of the operator's own addresses is an attendee of this event - the one the
    // invitation came to, and so the one the update comes from.
    private static String attendeeAddress(Path db, String calId, String id, Set<String> mine) {
        if (mine.isEmpty()) return null;
        for (String[] row : query(db, "SELECT icalString FROM cal_attendees"
                + " WHERE cal_id = " + text(calId) + " AND item_id = " + text(id)
                + " AND icalString LIKE 'ATTENDEE%'")) {
            String line = (row.length == 0 ? "" : row[0]).toLowerCase(Locale.ROOT);
            for (String address : mine) {
                if (line.contains(address)) return address;
            }
        }
        return null;
    }

    // The address out of one ORGANIZER or ATTENDEE line: the mailto: value, or the EMAIL
    // parameter when the value is a principal URL.
    private static String icalAddress(String icalLine) {
        Matcher mailto = Pattern.compile("(?i)mailto:([^\\s;:,]+)").matcher(icalLine);
        if (mailto.find()) return mailto.group(1).trim();
        Matcher email = Pattern.compile("(?i)EMAIL=([^;:]+)").matcher(icalLine);
        return email.find() ? email.group(1).trim() : "";
    }

    // The SEQUENCE this event carries. An update has to carry a higher one for a calendar
    // to read it as newer than what it already has, rather than as a stale copy of it.
    // Absent means 0, which is what absent means in a .ics.
    private static int sequenceOf(Path db, String calId, String id) {
        for (String[] row : query(db, "SELECT value FROM cal_properties"
                + " WHERE cal_id = " + text(calId) + " AND item_id = " + text(id)
                + " AND key = 'SEQUENCE' LIMIT 1")) {
            try {
                return Integer.parseInt(row[0].trim());
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
                return 0;
            }
        }
        return 0;
    }

    // THE SHORT ID EVERY EVENT LINE CARRIES, and what #move takes.
    //
    // Six hex characters of a hash of the calendar and the event's UID - not the UID
    // itself, which is the thing it stands for. A real one reads
    // "20311231_q4m7x2vk9hs3nd8pt5wzr1bjyc@google.com": forty-five characters of
    // somebody else's bookkeeping, on every line of a briefing that has to be read out
    // loud, and repeated back through a model without a character going astray. Six are
    // enough to tell 1,569 events apart, short enough to say, and derived rather than
    // stored, so the same event has the same id in every answer and after a restart.
    //
    // The whole UID still works wherever the short one does, for anybody who has it.
    private static String handle(String calId, String id) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest((calId + "\u0000" + id).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) hex.append(String.format("%02x", sum[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            return "------";
        }
    }

    // ---- Mail ------------------------------------------------------------------
    // Thunderbird's own global index, global-messages-db.sqlite - what Search Messages
    // and the quick filter are built on. Read with the same sqlite3, the same -readonly,
    // and the same never-write rule as the calendars.
    //
    // Three things make this answerable at all without reading a single mailbox file:
    //
    //   - messages holds one row per indexed message with its date (PRTime, as the
    //     calendar's are), its folder and a JSON blob of Gloda's own attributes;
    //   - that blob is keyed by attribute id, and the ones worth having are "43" the
    //     sender, "59" read, "60" replied to, "54" from me - so "unanswered" is a fact
    //     in the index rather than a guess from the text;
    //   - messagesText_content holds the subject, the author and the recipients as the
    //     text they were written as, which is what a person wants read back to them.
    //
    // The index is Thunderbird's and only Thunderbird keeps it up to date. A message that
    // arrived while Thunderbird was closed is not in here until it has run again, so
    // #mailboxes says how fresh the newest indexed message is and every answer can be
    // read in that light.
    private static final String MAIL_DB = "global-messages-db.sqlite";

    // JRock's own folder, where this agent writes what it hands to a model.
    private static final String PRODUCT = "JRock";

    // Gloda attribute ids inside messages.jsonAttributes.
    private static final String FROM = "43";          // the sender's identity id
    private static final String FROM_ME = "54";       // set when I sent it
    private static final String READ = "59";
    private static final String REPLIED = "60";
    private static final int INVOLVES = 52;           // in messageAttributes, and indexed

    // How far ahead the tentative ones are listed when the question does not say.
    private static final int TENTATIVE_DAYS = 30;

    // How far back a question looks when it does not say.
    private static final int UNANSWERED_DAYS = 14;
    private static final int SENDERS_DAYS = 5;
    private static final int MAIL_LIMIT = 40;

    // The folders whose name says they are not mail that arrived: the bins, the drafts,
    // and Gmail's virtual ones - All Mail holds a copy of every message there is, so
    // counting it would count everything twice.
    private static final Set<String> SKIP_FOLDERS = new LinkedHashSet<>(java.util.Arrays.asList(
            "trash", "bin", "spam", "junk", "drafts", "templates", "unsent messages",
            "outbox", "all mail", "important", "starred", "[gmail]", "archive"));
    private static final Set<String> SENT_FOLDERS = new LinkedHashSet<>(java.util.Arrays.asList(
            "sent", "sent mail", "sent items", "gesendet", "gesendete elemente"));

    // One indexed folder: which account it belongs to, and which of the three kinds of
    // folder it is. The kind comes from the last part of its URI, because that is what
    // the server called it and Thunderbird's display name can be anything.
    private static final class MailFolder {
        final int id;
        final String account;
        final String name;
        final boolean received;
        final boolean sent;

        MailFolder(int id, String account, String name, boolean received, boolean sent) {
            this.id = id;
            this.account = account;
            this.name = name;
            this.received = received;
            this.sent = sent;
        }
    }

    // Where the mail index is, or a Stop saying it is not there. Separate from the
    // calendar databases on purpose: a profile can have one and not the other, and a
    // calendar question should not fail because mail was never indexed.
    private static Path mailDatabase() {
        if (profile == null) throw new Stop("no Thunderbird profile is set.");
        Path db = profile.resolve(MAIL_DB);
        if (!Files.isRegularFile(db)) {
            throw new Stop("there is no " + MAIL_DB + " in " + profile + ". Thunderbird "
                    + "builds it when Search Messages is used or indexing is on, and "
                    + "until it exists there is nothing here to read.");
        }
        return db;
    }

    // Every folder the index knows, classified. Read on each request: a folder added in
    // Thunderbird should be in the next answer.
    private static List<MailFolder> mailFolders() {
        List<MailFolder> folders = new ArrayList<>();
        for (String[] row : query(mailDatabase(),
                "SELECT id, name, folderURI FROM folderLocations")) {
            if (row.length < 3) continue;
            int id;
            try {
                id = Integer.parseInt(row[0].trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            String uri = row[2];
            String last = uri.contains("/") ? uri.substring(uri.lastIndexOf('/') + 1) : uri;
            String tail = decode(last).toLowerCase(Locale.ROOT);
            boolean sent = SENT_FOLDERS.contains(tail);
            boolean skip = SKIP_FOLDERS.contains(tail);
            folders.add(new MailFolder(id, accountOf(uri), decode(last),
                    !sent && !skip, sent));
        }
        return folders;
    }

    // The account a folder belongs to: the user part of an IMAP or POP URI, which is the
    // address you would call that mailbox by, and "Local Folders" for the ones that are
    // only on this machine.
    private static String accountOf(String uri) {
        Matcher m = Pattern.compile("^[a-z]+://([^@/]*)@([^/]*)").matcher(uri);
        if (!m.find()) return "(unknown)";
        String user = decode(m.group(1));
        String host = decode(m.group(2));
        if (user.equalsIgnoreCase("nobody")) return host.replace("%20", " ");
        return user.contains("@") ? user : user + " at " + host;
    }

    private static String decode(String text) {
        try {
            return java.net.URLDecoder.decode(text, "UTF-8");
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException ex) {
            return text;
        }
    }

    // The folder ids of one kind, as an SQL list of numbers - which is the only thing
    // that ever goes into a query here, parsed from the index's own ids.
    private static String idsOf(List<MailFolder> folders, boolean received) {
        StringBuilder out = new StringBuilder();
        for (MailFolder f : folders) {
            if (received ? !f.received : !f.sent) continue;
            if (out.length() > 0) out.append(',');
            out.append(f.id);
        }
        return out.length() == 0 ? "-1" : out.toString();
    }

    // One folder's own URI, by its id - read again because MailFolder keeps the name
    // and the account rather than the URI it was made from.
    private static String folderUri(String folderId, List<MailFolder> folders) {
        for (String[] row : query(mailDatabase(),
                "SELECT folderURI FROM folderLocations WHERE id = " + number(folderId))) {
            if (row.length > 0 && !row[0].isBlank()) return row[0];
        }
        return null;
    }

    private static Map<Integer, MailFolder> byId(List<MailFolder> folders) {
        Map<Integer, MailFolder> map = new LinkedHashMap<>();
        for (MailFolder f : folders) map.put(f.id, f);
        return map;
    }

    // PRTime to this machine's clock, for the one-line timestamps in an answer.
    private static ZonedDateTime when(String micros, ZoneId zone) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(micros.trim()) / MICROS).atZone(zone);
        } catch (NumberFormatException ex) {
            return Instant.EPOCH.atZone(zone);
        }
    }

    // Gloda writes the author and recipients into its full-text table as the header said
    // them, and appends the display name it did not have as the word "undefined". Taken
    // off here rather than shown to anybody.
    private static String who(String text) {
        String name = text == null ? "" : text.trim();
        if (name.endsWith(" undefined")) name = name.substring(0, name.length() - 10).trim();
        return name.isEmpty() ? "(no sender)" : name;
    }

    private static String subjectOf(String text) {
        String subject = text == null ? "" : text.trim();
        return subject.isEmpty() ? "(no subject)" : subject;
    }

    // What arrived, newest first, over a span of days ending now. days = 0 is today.
    private static String mail(int days, ZoneId zone) {
        List<MailFolder> folders = mailFolders();
        LocalDate from = LocalDate.now(zone).minusDays(Math.max(0, days));
        long since = from.atStartOfDay(zone).toInstant().toEpochMilli() * MICROS;
        List<String[]> rows = query(mailDatabase(),
                "SELECT m.date, m.folderID, t.c3author, t.c1subject,"
                + " json_extract(m.jsonAttributes,'$.\"" + READ + "\"'), m.headerMessageID"
                + " FROM messages m LEFT JOIN messagesText_content t ON t.docid = m.id"
                + " WHERE m.deleted = 0 AND m.folderID IN (" + idsOf(folders, true) + ")"
                + " AND m.date >= " + since
                + " ORDER BY m.date DESC LIMIT " + MAIL_LIMIT);
        String heading = days == 0 ? "Mail that arrived today, "
                + LocalDate.now(zone).format(LONG_DAY)
                : "Mail that arrived since " + from.format(LONG_DAY);
        if (rows.isEmpty()) {
            return heading + ":\nNothing arrived.\n" + mailFooter();
        }
        Map<Integer, MailFolder> where = byId(folders);
        StringBuilder out = new StringBuilder(heading + " (" + rows.size()
                + (rows.size() == MAIL_LIMIT ? " shown, newest first" : ", newest first")
                + "):");
        LocalDate day = null;
        for (String[] row : rows) {
            ZonedDateTime at = when(row[0], zone);
            if (!at.toLocalDate().equals(day)) {
                day = at.toLocalDate();
                out.append("\n").append(day.format(DAY)).append(':');
            }
            MailFolder folder = where.get(number(row[1]));
            out.append("\n  ").append(at.format(CLOCK)).append("  ").append(who(row[2]))
                    .append("\n      ").append(subjectOf(row[3]))
                    .append(folder == null ? "" : "  [" + folder.account + " / "
                            + folder.name + "]")
                    .append("0".equals(row[4].trim()) ? "  (unread)" : "")
                    .append(row.length > 5 ? "  id " + messageId(row[5]) : "");
        }
        return out.append('\n').append(mailFooter()).toString();
    }

    // What arrived and has had no reply: Gloda's own "replied to" flag, which Thunderbird
    // sets when a reply is sent, so this is a fact out of the index and not a guess.
    // Things I sent myself are left out, and so is anything in a bin or a drafts folder.
    private static String unanswered(int days, ZoneId zone) {
        List<MailFolder> folders = mailFolders();
        LocalDate from = LocalDate.now(zone).minusDays(Math.max(1, days));
        long since = from.atStartOfDay(zone).toInstant().toEpochMilli() * MICROS;
        List<String[]> rows = query(mailDatabase(),
                "SELECT m.date, m.folderID, t.c3author, t.c1subject,"
                + " json_extract(m.jsonAttributes,'$.\"" + READ + "\"'), m.headerMessageID"
                + " FROM messages m LEFT JOIN messagesText_content t ON t.docid = m.id"
                + " WHERE m.deleted = 0 AND m.folderID IN (" + idsOf(folders, true) + ")"
                + " AND m.date >= " + since
                + " AND IFNULL(json_extract(m.jsonAttributes,'$.\"" + REPLIED + "\"'),0) = 0"
                + " AND json_extract(m.jsonAttributes,'$.\"" + FROM_ME + "\"') IS NULL"
                + " ORDER BY m.date DESC LIMIT " + MAIL_LIMIT);
        String heading = "Mail with no reply sent, from the last " + Math.max(1, days)
                + " day(s)";
        if (rows.isEmpty()) {
            return heading + ":\nNothing is waiting for a reply.\n" + mailFooter();
        }
        Map<Integer, MailFolder> where = byId(folders);
        StringBuilder out = new StringBuilder(heading + " (" + rows.size()
                + (rows.size() == MAIL_LIMIT ? " shown" : "") + ", newest first):");
        for (String[] row : rows) {
            ZonedDateTime at = when(row[0], zone);
            MailFolder folder = where.get(number(row[1]));
            out.append("\n  ").append(at.format(DAY)).append(' ').append(at.format(CLOCK))
                    .append("  ").append(who(row[2]))
                    .append("\n      ").append(subjectOf(row[3]))
                    .append(folder == null ? "" : "  [" + folder.account + " / "
                            + folder.name + "]")
                    .append("0".equals(row[4].trim()) ? "  (unread)" : "")
                    .append(row.length > 5 ? "  id " + messageId(row[5]) : "");
        }
        out.append("\nReplied to or not is Thunderbird's own flag: a reply sent from "
                + "somewhere else - a phone, a web mailbox - is not in this index, so it "
                + "can show as waiting when it is not.");
        return out.append('\n').append(mailFooter()).toString();
    }

    // Everything between you and somebody: the last they wrote, the last you wrote back,
    // and how much there has been. Matched on the address and on the name in the address
    // book, because "abc" is as likely to be a person as a mailbox.
    private static String withPerson(String text, ZoneId zone) {
        String asked = text.trim();
        if (asked.length() < 2) {
            throw new Stop("#with wants somebody to look for, as #with acme.");
        }
        String like = "'%" + asked.replace("'", "''").toLowerCase(Locale.ROOT) + "%'";
        List<String[]> who = query(mailDatabase(),
                "SELECT i.id, i.value, IFNULL(c.name,'') FROM identities i"
                + " LEFT JOIN contacts c ON c.id = i.contactID"
                + " WHERE lower(i.value) LIKE " + like
                + " OR lower(IFNULL(c.name,'')) LIKE " + like + " LIMIT 40");
        if (who.isEmpty()) {
            return "Nobody in the mail index matches \"" + asked + "\".\n" + mailFooter();
        }
        StringBuilder ids = new StringBuilder();
        Set<String> addresses = new LinkedHashSet<>();
        for (String[] one : who) {
            if (ids.length() > 0) ids.append(',');
            ids.append(number(one[0]));
            addresses.add(one[2].isEmpty() ? one[1] : one[2] + " <" + one[1] + ">");
        }
        List<MailFolder> folders = mailFolders();
        Map<Integer, MailFolder> where = byId(folders);
        // involves is a multi-valued attribute with a row per identity and an index on
        // it, which is what makes this a lookup rather than a scan of 200,000 messages.
        List<String[]> rows = query(mailDatabase(),
                "SELECT m.date, m.folderID, t.c1subject,"
                + " json_extract(m.jsonAttributes,'$.\"" + FROM_ME + "\"')"
                + " FROM messageAttributes a JOIN messages m ON m.id = a.messageID"
                + " LEFT JOIN messagesText_content t ON t.docid = m.id"
                + " WHERE a.attributeID = " + INVOLVES + " AND a.value IN (" + ids + ")"
                + " AND m.deleted = 0 ORDER BY m.date DESC LIMIT 200");
        String[] lastIn = null;
        String[] lastOut = null;
        int in = 0;
        int out2 = 0;
        for (String[] row : rows) {
            MailFolder folder = where.get(number(row[1]));
            boolean mine = row[3] != null && !row[3].trim().isEmpty()
                    || (folder != null && folder.sent);
            if (mine) {
                out2++;
                if (lastOut == null) lastOut = row;
            } else if (folder == null || folder.received) {
                in++;
                if (lastIn == null) lastIn = row;
            }
        }
        // "immobilienscout24" matches twenty-one addresses at that domain, and a line
        // naming all of them is a line nobody can read and nothing can say out loud. A
        // few are named and the rest counted.
        List<String> named = new ArrayList<>(addresses);
        String heading = named.size() <= 3 ? String.join(", ", named)
                : named.size() + " addresses matching \"" + asked + "\", among them "
                        + String.join(", ", named.subList(0, 3));
        StringBuilder out = new StringBuilder("Mail with " + heading + ":");
        out.append("\n  Last received: ").append(lastIn == null ? "never"
                : when(lastIn[0], zone).format(LONG_DAY) + " at "
                        + when(lastIn[0], zone).format(CLOCK) + " - "
                        + subjectOf(lastIn[2]));
        out.append("\n  Last sent:     ").append(lastOut == null ? "never"
                : when(lastOut[0], zone).format(LONG_DAY) + " at "
                        + when(lastOut[0], zone).format(CLOCK) + " - "
                        + subjectOf(lastOut[2]));
        out.append("\n  In the index:  ").append(in).append(" received, ").append(out2)
                .append(" sent")
                .append(rows.size() >= 200 ? " of the 200 most recent" : "");
        return out.append('\n').append(mailFooter()).toString();
    }

    // Who wrote, over a span of days: one line each, most recent first, with how many.
    private static String senders(int days, ZoneId zone) {
        List<MailFolder> folders = mailFolders();
        LocalDate from = LocalDate.now(zone).minusDays(Math.max(1, days));
        long since = from.atStartOfDay(zone).toInstant().toEpochMilli() * MICROS;
        List<String[]> rows = query(mailDatabase(),
                "SELECT json_extract(m.jsonAttributes,'$.\"" + FROM + "\"') AS who,"
                + " count(*), max(m.date)"
                + " FROM messages m"
                + " WHERE m.deleted = 0 AND m.folderID IN (" + idsOf(folders, true) + ")"
                + " AND m.date >= " + since + " AND who IS NOT NULL"
                + " GROUP BY who ORDER BY max(m.date) DESC LIMIT " + MAIL_LIMIT);
        String heading = "Who wrote in the last " + Math.max(1, days) + " day(s), since "
                + from.format(LONG_DAY);
        if (rows.isEmpty()) return heading + ":\nNobody.\n" + mailFooter();
        StringBuilder ids = new StringBuilder();
        for (String[] row : rows) {
            if (ids.length() > 0) ids.append(',');
            ids.append(number(row[0]));
        }
        Map<Integer, String> names = new LinkedHashMap<>();
        for (String[] one : query(mailDatabase(),
                "SELECT i.id, i.value, IFNULL(c.name,'') FROM identities i"
                + " LEFT JOIN contacts c ON c.id = i.contactID"
                + " WHERE i.id IN (" + ids + ")")) {
            names.put(number(one[0]), one[2].isEmpty() ? one[1]
                    : one[2] + " <" + one[1] + ">");
        }
        StringBuilder out = new StringBuilder(heading + " (" + rows.size()
                + (rows.size() == MAIL_LIMIT ? " shown" : "") + "):");
        for (String[] row : rows) {
            ZonedDateTime at = when(row[2], zone);
            int count = number(row[1]);
            out.append("\n  ").append(names.getOrDefault(number(row[0]), "(unknown)"))
                    .append(" - ").append(count)
                    .append(count == 1 ? " message, last " : " messages, last ")
                    .append(at.format(DAY)).append(' ').append(at.format(CLOCK));
        }
        return out.append('\n').append(mailFooter()).toString();
    }

    // The mailboxes themselves: which accounts are indexed, how much of each, and how
    // fresh the index is - which is the one thing every mail answer depends on.
    private static String mailboxes(ZoneId zone) {
        List<MailFolder> folders = mailFolders();
        Map<Integer, MailFolder> where = byId(folders);
        Map<String, int[]> perAccount = new LinkedHashMap<>();   // {messages, folders}
        for (MailFolder f : folders) {
            perAccount.computeIfAbsent(f.account, a -> new int[2])[1]++;
        }
        for (String[] row : query(mailDatabase(),
                "SELECT folderID, count(*) FROM messages WHERE deleted = 0"
                + " GROUP BY folderID")) {
            MailFolder f = where.get(number(row[0]));
            if (f == null) continue;
            perAccount.computeIfAbsent(f.account, a -> new int[2])[0] += number(row[1]);
        }
        StringBuilder out = new StringBuilder("Mailboxes in Thunderbird's index:");
        for (Map.Entry<String, int[]> one : perAccount.entrySet()) {
            out.append("\n- ").append(one.getKey()).append(": ")
                    .append(one.getValue()[0]).append(" indexed message(s) in ")
                    .append(one.getValue()[1]).append(" folder(s)");
        }
        return out.append('\n').append(mailFooter()).toString();
    }

    // Every mail answer ends with this, because every mail answer depends on it: the
    // index is Thunderbird's, and it is only as new as the last time Thunderbird ran.
    private static String mailFooter() {
        ZoneId zone = ZoneId.systemDefault();
        String newest = "";
        try {
            List<String[]> rows = query(mailDatabase(),
                    "SELECT max(date) FROM messages WHERE deleted = 0");
            if (!rows.isEmpty() && rows.get(0).length > 0 && !rows.get(0)[0].isBlank()) {
                ZonedDateTime at = when(rows.get(0)[0], zone);
                newest = " The newest message in it is from " + at.format(LONG_DAY)
                        + " at " + at.format(CLOCK) + ".";
            }
        } catch (Stop ignored) {
            // The footer is not the place to fail.
        }
        return "Read from Thunderbird's own search index in " + profile + ", read-only."
                + newest + " Nothing that arrived after that is in here yet - the index is "
                + "Thunderbird's, and it fills while Thunderbird runs.";
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    // How many days a mail question was asked about, when it says.
    private static int daysIn(String[] parts, int index, int whenMissing) {
        if (parts.length <= index) return whenMissing;
        try {
            return Math.max(0, Math.min(366, Integer.parseInt(parts[index])));
        } catch (NumberFormatException ex) {
            throw new Stop("\"" + parts[index] + "\" is not a number of days.");
        }
    }

    // ---- One message, read ------------------------------------------------------
    // What a letter in the mail actually says, out of the index rather than out of the
    // mailbox: Gloda keeps the body as TEXT in messagesText_content, which is what it
    // searches, and that is exactly the shape wanted here. No MIME is parsed, no HTML is
    // rendered, no raw message leaves this agent - a model asked what somebody wrote gets
    // what they wrote, laid out as Markdown, and nothing else.
    //
    // ATTACHMENTS ARE NAMED, NOT READ. The index knows their names, types and sizes
    // (attribute 51), and those go in the answer. What is inside one is a different job
    // with different tools - the doc inventory flow, Ghostscript, xpdf - and guessing at
    // an invoice's contents from its file name is exactly the kind of help nobody wants.
    private static final int MESSAGE_MAX = 40_000;

    // The short id every mail line carries, and what #message and #reply take: six hex
    // characters of the message's own headerMessageID. Not the Message-ID itself, which
    // reads <CAHk-=wi8rPqY4nF2xvQ...@mail.gmail.com> and is unsayable, unrepeatable and
    // forty characters of somebody else's bookkeeping. Derived, so the same message has
    // the same id in every answer and after a restart.
    private static String messageId(String headerMessageId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest((headerMessageId == null ? "" : headerMessageId)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) hex.append(String.format("%02x", sum[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            return "------";
        }
    }

    // One message by its short id, or by words in its sender or subject.
    private static String message(String asked, ZoneId zone) {
        if (asked.isBlank()) {
            throw new Stop("#message wants an id from a mail answer, or words from the "
                    + "sender or the subject.");
        }
        List<MailFolder> folders = mailFolders();
        String like = "'%" + asked.trim().replace("'", "''").toLowerCase(Locale.ROOT) + "%'";
        // By id, which is a hash and so cannot be matched in SQL: the candidates come
        // back by date and the id is worked out here. By words, the index does the work.
        boolean byId = asked.trim().matches("[0-9a-fA-F]{6}");
        List<String[]> rows = query(mailDatabase(),
                "SELECT m.id, m.date, m.folderID, m.headerMessageID, t.c3author,"
                + " t.c1subject, t.c4recipients, t.c0body,"
                + " json_extract(m.jsonAttributes,'$.\"" + FROM + "\"'),"
                + " json_extract(m.jsonAttributes,'$.\"51\"'),"
                + " json_extract(m.jsonAttributes,'$.\"" + READ + "\"')"
                + " FROM messages m LEFT JOIN messagesText_content t ON t.docid = m.id"
                + " WHERE m.deleted = 0"
                + (byId ? "" : " AND (lower(t.c3author) LIKE " + like
                        + " OR lower(t.c1subject) LIKE " + like + ")")
                + " ORDER BY m.date DESC LIMIT " + (byId ? 4000 : 40));
        String[] found = null;
        List<String[]> matches = new ArrayList<>();
        for (String[] row : rows) {
            if (byId) {
                if (asked.trim().equalsIgnoreCase(messageId(row[3]))) {
                    found = row;
                    break;
                }
            } else {
                matches.add(row);
            }
        }
        if (found == null && matches.isEmpty()) {
            throw new Stop("no message matches \"" + asked + "\"" + (byId
                    ? " - an id is only good for as long as that message is in the index."
                    : ". Ask for the mail of a day or a sender first, and use the id on "
                      + "the line.") + "");
        }
        StringBuilder others = new StringBuilder();
        if (found == null) {
            found = matches.get(0);          // the most recent of them
            if (matches.size() > 1) {
                others.append("\n\n*").append(matches.size()).append(" messages matched \"")
                        .append(asked).append("\" and this is the most recent. The others, "
                                + "by id:*");
                for (int i = 1; i < Math.min(matches.size(), 10); i++) {
                    String[] one = matches.get(i);
                    ZonedDateTime at = when(one[1], zone);
                    others.append("\n- *id ").append(messageId(one[3])).append("  ")
                            .append(at.format(DAY)).append(' ').append(at.format(CLOCK))
                            .append("  ").append(who(one[4])).append(" - ")
                            .append(subjectOf(one[5])).append('*');
                }
            }
        }
        // The message as the index has it, and then - if anything came with it - the
        // attachments themselves off the disk.
        String raw = oneMessage(found, folders, zone) + others;
        List<Attached> attachments = new ArrayList<>();
        List<String> named = attachments(found[9]);
        if (!named.isEmpty()) {
            MailFolder folder = byId(folders).get(number(found[2]));
            String uri = folder == null ? null : folderUri(found[2], folders);
            Path file = uri == null ? null : messageFile(uri, found[3]);
            if (file == null) {
                raw += "\n\n*The message file itself could not be found, so what is in "
                        + "the attachment could not be read - only its name is known.*";
            } else {
                Path into = Paths.get("").toAbsolutePath().resolve(PRODUCT)
                        .resolve("attachments");
                attachments.addAll(attachmentsIn(file, into));
                later(() -> say(attachments.size() + " attachment(s) taken out of the "
                        + "message."));
            }
        }
        // NOTHING RAW LEAVES: the model's version of all of it is the answer.
        String refined = refine(raw, attachments);
        return "CONTENT message message-" + messageId(found[3]) + ".md\n" + refined;
    }

    // One message as Markdown: who, when, to whom, what it is about, what came with it,
    // and then the text as it was written.
    private static String oneMessage(String[] row, List<MailFolder> folders, ZoneId zone) {
        Map<Integer, MailFolder> where = byId(folders);
        MailFolder folder = where.get(number(row[2]));
        ZonedDateTime at = when(row[1], zone);
        StringBuilder out = new StringBuilder("# ").append(subjectOf(row[5])).append("\n\n");
        out.append("- **From:** ").append(who(row[4])).append('\n');
        out.append("- **To:** ").append(who(row[6])).append('\n');
        out.append("- **When:** ").append(at.format(LONG_DAY)).append(" at ")
                .append(at.format(CLOCK)).append('\n');
        if (folder != null) {
            out.append("- **Where:** ").append(folder.account).append(" / ")
                    .append(folder.name).append('\n');
        }
        out.append("- **id:** ").append(messageId(row[3]))
                .append("  (for a reply to this message)\n");
        List<String> attached = attachments(row[9]);
        if (!attached.isEmpty()) {
            out.append("- **Attached:** ").append(String.join(", ", attached))
                    .append("\n- *What is inside an attachment is not read here: this "
                            + "agent names them. A scan or a PDF goes through the doc "
                            + "inventory flow to be read.*\n");
        }
        String body = row[7] == null ? "" : row[7];
        boolean cut = body.length() > MESSAGE_MAX;
        if (cut) body = body.substring(0, MESSAGE_MAX);
        out.append("\n").append(tidy(body));
        if (cut) {
            out.append("\n\n*(Cut off after ").append(MESSAGE_MAX)
                    .append(" characters.)*");
        }
        return out.append("\n\n").append(mailFooter()).toString();
    }

    // The attachments as the index lists them: [[name, type, size, ...], ...] out of the
    // JSON, which is a list of lists and so read by hand rather than by json_extract.
    private static List<String> attachments(String json) {
        List<String> named = new ArrayList<>();
        if (json == null || json.isBlank()) return named;
        Matcher m = Pattern.compile("\\[\"([^\"]+)\",\"([^\"]*)\",(\\d+)").matcher(json);
        while (m.find()) {
            long size = Long.parseLong(m.group(3));
            named.add(m.group(1) + " (" + m.group(2)
                    + (size > 0 ? ", " + (size < 1024 ? size + " bytes"
                            : String.format(Locale.ENGLISH, "%.0f kB", size / 1024.0)) : "")
                    + ")");
        }
        return named;
    }

    // The body as the index kept it, made readable: the runs of blank lines a mail client
    // leaves behind collapsed, the quoted-reply tail cut where it starts, and the tab
    // columns some senders lay out in turned into spaces.
    private static String tidy(String body) {
        String text = body.replace("\r", "").replace('\t', ' ');
        // The thread below a reply is already in the conversation; the first line of it
        // is where this message stops saying anything new.
        Matcher quoted = Pattern.compile(
                "(?m)^(On .{0,80}wrote:|-{2,}\\s*Original Message\\s*-{2,}|"
                + "From: .{0,120}Sent: )").matcher(text);
        if (quoted.find() && quoted.start() > 200) {
            text = text.substring(0, quoted.start()).trim()
                    + "\n\n*(The quoted thread below this is not included.)*";
        }
        return text.replaceAll("\n{3,}", "\n\n").replaceAll("[ ]{3,}", "  ").trim();
    }

    // ---- A reply, prepared but not sent -----------------------------------------
    // The draft the operator has already heard and agreed to, put in front of them in
    // Thunderbird's own compose window - addressed, titled, quoted and ready, with the
    // send key theirs as it is everywhere else here.
    //
    // THE ADDRESS IS READ OFF THE MESSAGE, never from anything said out loud: the sender
    // of the message being replied to, and the identity it was addressed to as the sender
    // of the reply. So this can write to the people who have written to the operator, and
    // to nobody else - the same rule the calendar's #move follows.
    //
    // Not threaded, and that is a real limitation: Thunderbird's command line has no
    // In-Reply-To, so this is a new message with "Re: " on it rather than a reply inside
    // the thread. It goes to the right person with the right subject, and the thread it
    // belongs to is the one thing it cannot say.
    private static String reply(String asked, ZoneId zone) {
        String[] split = asked.split("\\s", 2);
        if (split.length < 2 || split[1].isBlank()) {
            throw new Stop("#reply wants the id of the message and then the text of the "
                    + "reply, as #reply a3f91c Thank you, Tuesday suits me.");
        }
        String id = split[0].trim();
        String draft = split[1].trim();
        List<MailFolder> folders = mailFolders();
        List<String[]> rows = query(mailDatabase(),
                "SELECT m.id, m.date, m.folderID, m.headerMessageID, t.c3author,"
                + " t.c1subject, t.c4recipients FROM messages m"
                + " LEFT JOIN messagesText_content t ON t.docid = m.id"
                + " WHERE m.deleted = 0 ORDER BY m.date DESC LIMIT 4000");
        String[] found = null;
        for (String[] row : rows) {
            if (id.equalsIgnoreCase(messageId(row[3]))) {
                found = row;
                break;
            }
        }
        if (found == null) {
            throw new Stop("no message has the id \"" + id + "\". Ask for the message "
                    + "again and use the id on its line.");
        }
        String to = address(found[4]);
        if (to.isEmpty()) {
            throw new Stop("\"" + subjectOf(found[5]) + "\" has no address to reply to.");
        }
        Set<String> mine = new LinkedHashSet<>();
        try {
            for (String[] one : identities()) mine.add(one[1].toLowerCase(Locale.ROOT));
        } catch (Stop ignored) {
            // Then the account Thunderbird would pick by itself is the one used.
        }
        if (mine.contains(to.toLowerCase(Locale.ROOT))) {
            throw new Stop("that message is from one of your own addresses, so there is "
                    + "nobody else to reply to.");
        }
        // The identity it was addressed to, so the reply leaves as the right account.
        String from = "";
        for (String candidate : mine) {
            if ((found[6] == null ? "" : found[6].toLowerCase(Locale.ROOT))
                    .contains(candidate)) {
                from = candidate;
                break;
            }
        }
        if (from.isEmpty()) from = eventEmail.trim();

        Path thunderbird = thunderbird();
        if (thunderbird == null) {
            throw new Stop("Thunderbird was not found, and it is what the draft goes into.");
        }
        String subject = subjectOf(found[5]);
        if (!subject.toLowerCase(Locale.ROOT).startsWith("re:")) subject = "Re: " + subject;
        ZonedDateTime at = when(found[1], zone);
        String body = draft + "\r\n\r\n"
                + "-- \r\nOn " + at.format(LONG_DAY) + " at " + at.format(CLOCK) + ", "
                + who(found[4]) + " wrote:\r\n> (the message this replies to)\r\n";
        String identity = from.isEmpty() ? null : identityOf(from);
        String compose = (from.isEmpty() ? "" : "from=" + encode(from) + ",")
                + "to=" + encode(to)
                + (identity == null ? "" : ",preselectid=" + identity)
                + ",subject=" + encode(subject)
                + ",body=" + encode(body);
        try {
            new ProcessBuilder(thunderbird.toString(), "-compose", compose)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException ex) {
            throw new Stop("Thunderbird would not start: " + ex.getMessage());
        }
        return "Draft prepared: \"" + subject + "\"\nTo: " + to
                + (from.isEmpty() ? "" : "\nFrom: " + from)
                + "\nThunderbird's compose window is open with it. Read it there, change "
                + "anything, and press Ctrl+Enter to send - nothing is sent until you do."
                + "\nIt is a new message with \"Re:\" on it rather than a reply inside the "
                + "thread: Thunderbird's command line cannot say In-Reply-To.";
    }

    // The address out of an author or recipient line as the index keeps it: "Name
    // <someone@example.com>", or a bare address, with the "undefined" the index appends
    // already taken off by who().
    private static String address(String line) {
        String text = who(line);
        Matcher angled = Pattern.compile("<([^<>@\\s]+@[^<>@\\s]+)>").matcher(text);
        if (angled.find()) return angled.group(1).trim();
        Matcher bare = Pattern.compile("([^<>@\\s,;]+@[^<>@\\s,;]+)").matcher(text);
        return bare.find() ? bare.group(1).trim() : "";
    }

    // ---- The message on disk, and what came with it -----------------------------
    // THE INDEX KNOWS WHAT AN ATTACHMENT IS CALLED; ONLY THE MESSAGE ITSELF HAS IT. So to
    // read an invoice that arrived as a PDF, the message file has to be found and its
    // MIME walked - which is the one piece of real mail plumbing in this agent, and it is
    // here rather than anywhere else because nothing else needs it.
    //
    // Finding the file is the awkward part. Thunderbird stores each message as a file in
    // a maildir, and the file's name is a maildir unique name - a timestamp, a process
    // id and a counter - with no relation to anything the index holds. There is a map
    // from one to the other, in the folder's .msf, and it is in Mork, which is a format
    // nobody should have to read twice.
    //
    // So the folder is scanned NEWEST FIRST and each file's Message-ID header is compared
    // with the one the index gave. A message from this morning is found in the first
    // handful of files; an old one in a folder of thousands may not be found before the
    // cap, and then the answer says so rather than pretending the attachment is not
    // there. Which is the right trade for an assistant that is asked about this morning's
    // post, and an honest limit the moment it is not.
    private static final int MESSAGE_SCAN_MAX = 3_000;
    private static final long MESSAGE_SCAN_MS = 25_000;
    private static final int HEADER_READ = 8_192;
    private static final long ATTACHMENT_MAX = 25L * 1024 * 1024;

    // Where one folder's messages are, worked out of the folder's own URI and the server
    // prefs: hostname and user name identify the account, directory-rel says where it
    // keeps its mail, and a nested folder is <parent>.sbd/<child> as every Thunderbird
    // store has been since Netscape.
    private static Path folderStore(String folderUri) {
        Matcher m = Pattern.compile("^([a-z]+)://([^@/]*)@([^/]+)(?:/(.*))?$")
                .matcher(folderUri);
        if (!m.matches()) return null;
        String user = decode(m.group(2));
        String host = decode(m.group(3));
        String path = m.group(4) == null ? "" : m.group(4);

        Path base = null;
        Map<String, String> prefs = serverPrefs();
        for (int i = 1; i <= 64; i++) {
            String id = "server" + i;
            String prefHost = prefs.get(id + ".hostname");
            if (prefHost == null) continue;
            String prefUser = prefs.get(id + ".userName");
            boolean sameUser = user.equalsIgnoreCase(prefUser == null ? "" : prefUser)
                    || user.equalsIgnoreCase("nobody");
            if (!prefHost.equalsIgnoreCase(host) || !sameUser) continue;
            String dir = prefs.get(id + ".directory-rel");
            if (dir == null) dir = prefs.get(id + ".directory");
            if (dir == null) continue;
            base = dir.startsWith("[ProfD]") ? profile.resolve(dir.substring(7)
                    .replace('/', java.io.File.separatorChar)) : Paths.get(dir);
            break;
        }
        if (base == null || !Files.isDirectory(base)) return null;

        Path at = base;
        String[] parts = path.isEmpty() ? new String[0] : path.split("/");
        for (int i = 0; i < parts.length; i++) {
            String name = decode(parts[i]);
            at = i == parts.length - 1 ? at.resolve(name)
                    : at.resolve(name + ".sbd");
        }
        // maildir keeps the messages in cur/; an mbox store is one file with this name,
        // which this agent does not read - said where it matters rather than here.
        Path cur = at.resolve("cur");
        return Files.isDirectory(cur) ? cur : null;
    }

    // mail.server.serverN.* out of prefs.js, read once per request like everything else
    // here: an account added in Thunderbird should need no restart.
    private static Map<String, String> serverPrefs() {
        Map<String, String> prefs = new LinkedHashMap<>();
        Path file = profile == null ? null : profile.resolve("prefs.js");
        if (file == null || !Files.isRegularFile(file)) return prefs;
        Pattern pref = Pattern.compile(
                "user_pref\\(\"mail\\.server\\.(server\\d+\\.[A-Za-z-]+)\",\\s*\"(.*)\"\\);");
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Matcher m = pref.matcher(line.trim());
                if (m.matches()) prefs.put(m.group(1), unescape(m.group(2)));
            }
        } catch (IOException ex) {
            // No prefs: then no store can be found, and the caller says so.
        }
        return prefs;
    }

    // The file one message is in, by its Message-ID. Newest first, headers only, capped.
    private static Path messageFile(String folderUri, String messageId) {
        if (messageId == null || messageId.isBlank()) return null;
        Path cur = folderStore(folderUri);
        if (cur == null) return null;
        String wanted = messageId.replaceAll("^<|>$", "").trim();
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> all = Files.list(cur)) {
            all.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException ex) {
            return null;
        }
        files.sort((a, b) -> Long.compare(modified(b), modified(a)));
        long deadline = System.currentTimeMillis() + MESSAGE_SCAN_MS;
        int looked = 0;
        for (Path file : files) {
            if (looked++ >= MESSAGE_SCAN_MAX || System.currentTimeMillis() > deadline) break;
            String id = messageIdIn(file);
            if (id != null && id.equalsIgnoreCase(wanted)) return file;
        }
        return null;
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ex) {
            return 0;
        }
    }

    // The Message-ID out of a file's headers, reading only as far as it has to.
    private static String messageIdIn(Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[HEADER_READ];
            int read = in.readNBytes(buffer, 0, buffer.length);
            String head = new String(buffer, 0, Math.max(0, read), StandardCharsets.ISO_8859_1);
            int blank = head.indexOf("\r\n\r\n");
            if (blank < 0) blank = head.indexOf("\n\n");
            if (blank > 0) head = head.substring(0, blank);
            Matcher m = Pattern.compile("(?im)^message-id:\\s*<?([^>\\s]+)>?\\s*$")
                    .matcher(head);
            return m.find() ? m.group(1).trim() : null;
        } catch (IOException ex) {
            return null;
        }
    }

    // ---- MIME, as far as the attachments ----------------------------------------
    // One attachment, saved beside this agent's own files.
    private static final class Attached {
        final String name;
        final String type;
        final Path file;

        Attached(String name, String type, Path file) {
            this.name = name; this.type = type; this.file = file;
        }
    }

    // Every attachment in one message file, written out as files. Nothing else of the
    // message is taken: the TEXT comes from the index, which already has it, so this walk
    // only has to find the parts that carry a file name - and never has to decide what a
    // message says.
    private static List<Attached> attachmentsIn(Path eml, Path into) {
        List<Attached> saved = new ArrayList<>();
        byte[] whole;
        try {
            if (Files.size(eml) > ATTACHMENT_MAX * 4) return saved;   // a mailbox, not a mail
            whole = Files.readAllBytes(eml);
        } catch (IOException ex) {
            return saved;
        }
        // ISO-8859-1 keeps every byte as itself, which is what matters: the base64 of an
        // attachment has to survive being read as text and turned back into bytes.
        String text = new String(whole, StandardCharsets.ISO_8859_1);
        walkParts(text, into, saved, 0);
        return saved;
    }

    private static void walkParts(String part, Path into, List<Attached> saved, int depth) {
        if (depth > 8 || saved.size() >= 10) return;
        int blank = part.indexOf("\r\n\r\n");
        int gap = 4;
        if (blank < 0) {
            blank = part.indexOf("\n\n");
            gap = 2;
        }
        if (blank < 0) return;
        String headers = unfold(part.substring(0, blank));
        String body = part.substring(blank + gap);

        String contentType = header(headers, "content-type");
        String boundary = parameter(contentType, "boundary");
        if (boundary != null && !boundary.isEmpty()) {
            // A multipart: every piece between the boundaries, each walked on its own.
            String[] pieces = body.split(Pattern.quote("--" + boundary));
            for (int i = 1; i < pieces.length; i++) {
                String piece = pieces[i];
                if (piece.startsWith("--")) break;          // the closing boundary
                walkParts(piece.replaceFirst("^\r?\n", ""), into, saved, depth + 1);
            }
            return;
        }

        String disposition = header(headers, "content-disposition");
        String name = parameter(disposition, "filename");
        if (name == null) name = parameter(contentType, "name");
        if (name == null || name.isBlank()) return;         // not an attachment
        String encoding = header(headers, "content-transfer-encoding")
                .toLowerCase(Locale.ROOT).trim();
        byte[] bytes;
        if (encoding.contains("base64")) {
            try {
                bytes = Base64.getMimeDecoder().decode(
                        body.replaceAll("[^A-Za-z0-9+/=]", ""));
            } catch (IllegalArgumentException ex) {
                return;
            }
        } else if (encoding.contains("quoted-printable")) {
            bytes = unQuoted(body).getBytes(StandardCharsets.ISO_8859_1);
        } else {
            bytes = body.getBytes(StandardCharsets.ISO_8859_1);
        }
        if (bytes.length == 0 || bytes.length > ATTACHMENT_MAX) return;
        try {
            Files.createDirectories(into);
            String safe = decodeHeader(name).replaceAll("[^A-Za-z0-9._ -]", "-").trim();
            if (safe.isEmpty()) safe = "attachment-" + (saved.size() + 1);
            Path file = into.resolve(safe);
            Files.write(file, bytes);
            saved.add(new Attached(decodeHeader(name), contentType.split(";")[0].trim(),
                    file));
        } catch (IOException ex) {
            // One attachment that would not be written is not a reason to drop the rest.
        }
    }

    // Header folding undone: a long Content-Type runs over several lines, and every one
    // after the first starts with a space.
    private static String unfold(String headers) {
        return headers.replace("\r\n", "\n").replaceAll("\n[ \t]+", " ");
    }

    private static String header(String headers, String name) {
        Matcher m = Pattern.compile("(?im)^" + Pattern.quote(name) + ":\\s*(.*)$")
                .matcher(headers);
        return m.find() ? m.group(1).trim() : "";
    }

    // One parameter of a header, quoted or not - and the RFC 2231 "name*0", "name*1"
    // continuations some senders split a long file name over.
    private static String parameter(String header, String name) {
        Matcher quoted = Pattern.compile("(?i)" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"")
                .matcher(header);
        if (quoted.find()) return quoted.group(1);
        Matcher bare = Pattern.compile("(?i)" + Pattern.quote(name) + "\\s*=\\s*([^;\\s]+)")
                .matcher(header);
        if (bare.find()) return bare.group(1);
        Matcher split = Pattern.compile("(?i)" + Pattern.quote(name)
                + "\\*\\d+\\*?\\s*=\\s*\"?([^\";]*)\"?").matcher(header);
        StringBuilder joined = new StringBuilder();
        while (split.find()) joined.append(split.group(1));
        return joined.length() == 0 ? null : joined.toString();
    }

    private static String unQuoted(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' && i + 2 < text.length()) {
                String hex = text.substring(i + 1, i + 3);
                if (hex.equals("\r\n") || hex.startsWith("\n")) {
                    i += hex.startsWith("\n") ? 1 : 2;
                    continue;
                }
                try {
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ex) {
                    // Not an escape after all.
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    // A file name as a header may carry it: =?UTF-8?B?...?= or =?UTF-8?Q?...?=.
    private static String decodeHeader(String text) {
        Matcher m = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").matcher(text);
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (m.find()) {
            out.append(text, at, m.start());
            String charset = m.group(1);
            String body = m.group(3);
            try {
                byte[] bytes = m.group(2).equalsIgnoreCase("b")
                        ? Base64.getMimeDecoder().decode(body)
                        : unQuoted(body.replace('_', ' ')).getBytes(StandardCharsets.ISO_8859_1);
                out.append(new String(bytes, java.nio.charset.Charset.forName(charset)));
            } catch (RuntimeException ex) {
                out.append(body);
            }
            at = m.end();
        }
        out.append(text.substring(at));
        return out.toString();
    }

    // ---- Through this agent's own JRock -----------------------------------------
    // NOTHING RAW GOES BACK. Whatever is asked about one message - its text out of the
    // index, and whatever came attached to it - is put in front of this agent's own JRock
    // and the MODEL's version of it is what leaves here.
    //
    // Which is worth the wait for three reasons. A mail body as it arrives is a mess of
    // soft line breaks, tracking footers, unsubscribe boilerplate and =?UTF-8?B? wreckage;
    // a scanned invoice is a picture and says nothing at all until something reads it; and
    // what the operator asked for is the letter, not the plumbing it arrived in. One pass
    // through a model turns all three into a page of Markdown that can be read aloud.
    //
    // The prompt is a file beside this agent, like every other prompt here, so it can be
    // changed without changing any of this.
    private static final String MAIL_PROMPT = "jrock-prompt-mail-to-markdown.txt";
    private static final long MAIL_SEND_TIMEOUT_MS = 10 * 60_000;

    // How many of a message's attachments are sent to the model, smallest first.
    private static final int ATTACHMENTS_SENT = 3;

    private static String refine(String markdown, List<Attached> attachments) {
        String why = noModel;
        if (why != null) {
            throw new Stop("this agent's own JRock cannot ask a model (" + why + "), and a "
                    + "message is only given out after a model has read it.");
        }
        Path prompt = ownDirectory().resolve(MAIL_PROMPT);
        if (!Files.isRegularFile(prompt)) {
            throw new Stop("there is no " + prompt + ", and that prompt is what turns a "
                    + "message into something readable.");
        }
        synchronized (MODEL_LOCK) {
            check(JRock.automationClearPrompt());
            check(JRock.automationLoadPrompt(prompt.toString()));
            String text = JRock.automationPromptText();
            if (text == null) throw new Stop("the prompt could not be read.");
            StringBuilder without = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                String bare = line.trim();
                if (bare.equals("@txt") || bare.equals("@img")) continue;
                without.append(line).append('\n');
            }
            check(JRock.automationSetPrompt(without.toString().replaceAll("\\s+$", "")
                    + "\n\n"));
            // The message itself as a file, not as prompt text: the same reason a letter
            // goes in as a file - the transcript keeps a token and the document beside it.
            Path mailDir = Paths.get("").toAbsolutePath().resolve(PRODUCT).resolve("mail");
            try {
                Files.createDirectories(mailDir);
                Path body = mailDir.resolve("message.md");
                Files.write(body, markdown.getBytes(StandardCharsets.UTF_8));
                check(JRock.automationInclude(body.toString(), "txt"));
            } catch (IOException ex) {
                throw new Stop("the message could not be written for including: "
                        + ex.getMessage());
            }
            // THREE AT THE MOST, and the biggest left out first. A DHL order
            // confirmation carries the invoice (75 kB) and the terms and conditions
            // (355 kB): one of those is what the operator asked about and the other is
            // twenty pages of boilerplate that would be rasterised, sent and paid for.
            // The ones left out are named in the answer rather than dropped silently.
            List<Attached> going = new ArrayList<>(attachments);
            going.sort(java.util.Comparator.comparingLong(one -> {
                try {
                    return Files.size(one.file);
                } catch (IOException ex) {
                    return Long.MAX_VALUE;
                }
            }));
            List<String> leftOut = new ArrayList<>();
            for (int i = 0; i < going.size(); i++) {
                Attached one = going.get(i);
                if (i >= ATTACHMENTS_SENT) {
                    leftOut.add(one.name);
                    continue;
                }
                // "auto" so each kind goes in the way this folder's settings say: a PDF as
                // page images or as text, a photograph as itself.
                String problem = JRock.automationInclude(one.file.toString(), "auto");
                if (problem != null) {
                    later(() -> say("Could not include " + one.name + ": " + problem));
                }
            }
            if (!leftOut.isEmpty()) {
                check(JRock.automationSetPrompt(JRock.automationPromptText()
                        + "\n\nNot included, being the largest of several attachments: "
                        + String.join(", ", leftOut) + ". Say at the end that they were "
                        + "not read.\n"));
            }
            String[] sent = JRock.automationSend(MAIL_SEND_TIMEOUT_MS);
            if (!"1".equals(sent[0])) {
                throw new Stop("the message was not read: "
                        + (sent[3] == null ? "no reason given" : sent[3]));
            }
            String file = JRock.automationMessageFile("assistant", sent[2]);
            if (file == null) throw new Stop("the reply was not written to JRock/messages/.");
            try {
                String refined = new String(Files.readAllBytes(Paths.get(file)),
                        StandardCharsets.UTF_8).trim();
                if (refined.isEmpty()) throw new Stop("the reply was empty.");
                return refined;
            } catch (IOException ex) {
                throw new Stop("the reply could not be read: " + ex.getMessage());
            }
        }
    }

    private static final Object MODEL_LOCK = new Object();

    private static void check(String problem) {
        if (problem != null) throw new Stop(problem);
    }

    // ---- Settings --------------------------------------------------------------
    // The profile and the calendars left out, in one small text file beside this agent:
    // two lines of its own, rather than anything of JRock's, which belongs to JRock.
    private static void readSettings() {
        Path file = ownDirectory().resolve(SETTINGS);
        if (!Files.isRegularFile(file)) {
            Path was = ownDirectory().resolve(SETTINGS_WAS);
            if (!Files.isRegularFile(was)) return;
            file = was;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.startsWith("profile=")) {
                    String at = text.substring("profile=".length()).trim();
                    if (!at.isEmpty()) profile = Paths.get(at);
                } else if (text.startsWith("email=")) {
                    eventEmail = text.substring("email=".length()).trim();
                } else if (text.startsWith("exclude=")) {
                    for (String name : text.substring("exclude=".length()).split("\\|")) {
                        if (!name.trim().isEmpty()) excluded.add(name.trim());
                    }
                }
            }
        } catch (IOException ex) {
            System.out.println("Could not read " + file + ": " + ex.getMessage());
        }
    }

    private static void writeSettings() {
        Path file = ownDirectory().resolve(SETTINGS);
        StringBuilder out = new StringBuilder("' JRock Thunderbird agent\n");
        if (profile != null) out.append("profile=").append(profile).append('\n');
        if (!eventEmail.isEmpty()) out.append("email=").append(eventEmail).append('\n');
        if (!excluded.isEmpty()) {
            out.append("exclude=").append(String.join("|", excluded)).append('\n');
        }
        try {
            Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            later(() -> say("Could not remember the profile in " + file + ": " + ex.getMessage()));
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
                    JRockThunderbird.class.getProtectionDomain().getCodeSource();
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
