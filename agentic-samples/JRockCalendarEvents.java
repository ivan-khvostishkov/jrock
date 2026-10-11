// A JRock automation: what is in your calendar, read out of Thunderbird's own offline
// copy - today, this week, any range - for other agents to ask about and for you to read.
//
// Run it the way JRock itself runs - no build step, no Maven, one file:
//
//     java -cp jrock.jar JRockCalendarEvents.java [--profile <thunderbird profile dir>]
//     java -cp jrock.jar JRockCalendarEvents.java --profile <dir> --print week
//
// COPY jrock.jar HERE FIRST, as with every automation in this directory - this one uses
// the jar for one thing only, placing its window beside JRock's. What it does NOT need is
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
//          #calendars                     the calendars, and how many events each holds
//
//      The push-to-talk agent asks when the model's answer is "I need your calendar for
//      today" or "... for this week", and puts what comes back into its next prompt.
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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JRockCalendarEvents {

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

    // Where this agent keeps the profile it was last pointed at, and the calendars left
    // out - beside this file, so the window remembers them and nothing of JRock's own
    // configuration is touched.
    private static final String SETTINGS = "jrock-calendar-events.txt";

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

        Occurrence(Instant start, Instant end, boolean allDay, String title,
                   String calendar, String location, String note) {
            this.start = start; this.end = end; this.allDay = allDay;
            this.title = title; this.calendar = calendar;
            this.location = location; this.note = note;
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

    private static volatile Path profile;          // the one Thunderbird profile to read
    private static volatile Path sqlite;           // the tool, once found
    private static final Set<String> excluded =    // calendar names left out, by name
            java.util.Collections.synchronizedSet(new LinkedHashSet<>());

    private static javax.swing.JFrame window;
    private static javax.swing.JTextField profileField;
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

        onEdt(JRockCalendarEvents::showWindow);
        String said = listen();
        later(() -> listening.setText(said));
        System.out.println("Calendar events agent is ready. " + said);
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
            default:
                throw new Stop("\"" + line + "\" is not something this agent answers. Ask "
                        + "#today, #week, #next [n], #range <from> <to> or #calendars.");
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
        List<Occurrence> found = occurrences(lo, hi, zone);

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
        return out.append('\n').append(footer()).toString();
    }

    private static String footer() {
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
            for (String[] row : query(db, "SELECT " + columns + " FROM cal_events"
                    + " WHERE recurrence_id IS NULL AND event_start < " + hiUs
                    + " AND event_end > " + loUs)) {
                if (cancelled(row[7])) continue;
                if (repeats(db, row[0], row[1])) continue;   // a master: expanded below
                Occurrence o = occurrence(row, names, start(row), end(row), "");
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
                        e.getKey(), lo, hi, names, found);
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
        String calendar = names.getOrDefault(row[0], "(unnamed)");
        if (excluded.contains(calendar)) return null;
        String title = row[2] == null || row[2].isBlank() ? "(no title)" : row[2].trim();
        boolean allDay = FLOATING.equalsIgnoreCase(row[5] == null ? "" : row[5].trim());
        return new Occurrence(from, to, allDay, title, calendar, "", note);
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
                               List<Occurrence> found) {
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
                add(found, occurrence(master, names, first, first.plus(length), why));
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
                    "repeats too often to work out - check it in Thunderbird"));
            return;
        }

        for (Instant when : candidates) {
            if (skip.contains(when)) continue;
            String[] instead = changed.get(masterKey + "@" + (when.toEpochMilli() * MICROS));
            if (instead != null) {
                if (cancelled(instead[7])) continue;           // this one was called off
                add(found, lo, hi, occurrence(instead, names, start(instead), end(instead),
                        "moved from " + ZonedDateTime.ofInstant(when, zone).format(DAY)));
                continue;
            }
            add(found, lo, hi, occurrence(master, names, when, when.plus(length),
                    rule.describe()));
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
        window = new javax.swing.JFrame("JRock - Calendar events (read-only)");
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

        // What to ask, which is also what an agent can ask.
        javax.swing.JPanel asks = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 6, 0));
        asks.setOpaque(false);
        for (String[] ask : new String[][] { { "Today", "#today" }, { "This week", "#week" },
                                             { "Next 5", "#next 5" },
                                             { "Calendars", "#calendars" } }) {
            javax.swing.JButton b = new javax.swing.JButton(ask[0]);
            String request = ask[1];
            b.addActionListener(e -> refresh(request));
            asks.add(b);
        }

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

        javax.swing.JPanel north = new javax.swing.JPanel(new java.awt.BorderLayout());
        north.setOpaque(false);
        north.add(profileRow, java.awt.BorderLayout.NORTH);
        north.add(asks, java.awt.BorderLayout.CENTER);
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
            showCalendars();
        }
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
            try {
                for (Path db : databases()) {
                    for (String[] row : query(db,
                            "SELECT DISTINCT cal_id FROM cal_events")) {
                        inUse.add(names.getOrDefault(row[0], "(unnamed: " + row[0] + ")"));
                    }
                }
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
                calendarBox.revalidate();
                calendarBox.repaint();
                if (note != null) say(note);
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

    // ---- Settings --------------------------------------------------------------
    // The profile and the calendars left out, in one small text file beside this agent:
    // two lines of its own, rather than anything of JRock's, which belongs to JRock.
    private static void readSettings() {
        Path file = ownDirectory().resolve(SETTINGS);
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.startsWith("profile=")) {
                    String at = text.substring("profile=".length()).trim();
                    if (!at.isEmpty()) profile = Paths.get(at);
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
        StringBuilder out = new StringBuilder("' JRock calendar events agent\n");
        if (profile != null) out.append("profile=").append(profile).append('\n');
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
                    JRockCalendarEvents.class.getProtectionDomain().getCodeSource();
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
