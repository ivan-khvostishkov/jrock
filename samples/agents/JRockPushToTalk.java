// A JRock agent (it loops, and is started with JRock): a push-to-talk voice agent.
//
// Run it the way JRock itself runs - no build step, no Maven, one file - with the jar on
// the class path:
//
//     java -cp jrock.jar JRockPushToTalk.java [--working-dir <dir>] [--prompts-dir <dir>]
//
// The jar is wherever the installer put it - one folder up from samples/flows/,
// two up from an agent's own folder - and -cp names it. Take the flags as
// JRock's own and expect a typed static call rather than reflection: all of
// that is samples/flows/JRockDocInventory.java's opening comment, and it is
// true of every agent and every flow. Read that file first.
//
// What this one does is a loop, not a pass:
//
//   1. JRock is started, and the prompt cleared.
//   2. A small window of its own opens beside JRock's, with one round button. Hold it -
//      or hold Ctrl while this window has the focus - and JRock records from the
//      microphone set in its Configure > Record from.
//   3. Let go, and JRock stops the recording and puts it in the prompt: as an @audio
//      token, or with "Transcribe recordings into the prompt" ticked, as the text
//      Windows heard. That is JRock's setting to decide, not this agent's.
//   4. As soon as the prompt has it, the agent presses Send - which empties the answer
//      area in its own window - waits for the reply, and shows it there. Only the last
//      answer: the conversation so far is in JRock's window already.
//   5. And JRock reads the whole answer aloud, on its Configure > Narrate on device,
//      while the button is already ready for the next turn. Pressing it stops the
//      voice, so the microphone does not hear the last answer as the next question.
//
// Hands-free, for a conversation with both hands off the keyboard and the mouse: the
// lever under the button (or Ctrl+Shift) hands the listening to JRock's own Mic always
// on. JRock mutes that for as long as an automation runs - nobody types into the prompt
// then, so nobody speaks into it either - and the lever lifts the mute
// (JRock.automationListen). From then on JRock hears when someone starts talking and
// puts what was said in the prompt once it is quiet again; the agent waits for that
// (JRock.automationAwaitHeard), sends it, shows and reads out the answer, and listens
// again - a spoken conversation, turn after turn. Mic always on has to be ticked in
// JRock for it; the agent says so if it is not. The lever off, the button, or Ctrl on
// its own ends it: the microphone is muted again, what was being said is not sent, and
// the answer being read out stops.
//
// While a request is on its way the microphone is muted. While the answer is read aloud
// JRock listens again, and speaking stops the reading and starts the next question - so
// on speakers, headphones keep JRock from hearing its own voice.
//
// UNPLUGGING THE MICROPHONE IS THE MUTE BUTTON, and a headset usually has no other one.
// Hands-free stays on and goes quiet: nothing heard, nothing sent, nothing read out, no
// error announced, the lever left where it was. Plug it back in and it listens again by
// itself, within a second or two. That is what makes it usable as a mute - keep the
// narrated answer out of the next recording, or say something to somebody in the room
// without saying it to a model - and it is why an absent microphone is asked about
// separately (JRock.automationMicReady) rather than being read as a turn that failed: a
// quiet room and an unplugged microphone look identical to the listening call itself,
// and they mean opposite things.
//
// The prompt is cleared before every recording, and History is as JRock has it: on, the
// model hears the conversation so far - as much as the History limit in Configure lets
// through - so a question it asked can be answered in the next recording; off, each
// turn stands alone. The whole conversation is in JRock's transcript either way, and
// closing this window hands JRock back to you with it.
//
// The recording goes first in the prompt - which is what Voxtral wants (see the
// README): it takes a recording as the question and answers it. After it comes the
// appendix, jrock-prompt-push-to-talk-appendix.txt beside this file, with #now in it
// replaced by the date, the time, the day of the week and the time zone in full: Voxtral
// does not work with JRock's own Clock, which goes as a system prompt, so what it needs
// for "what day is it" comes this way instead. The file is read again every turn, so an
// edit to it counts from the next question on.
//
// Alarms: an answer that says "Now is hh:mm:ss, I'm setting an alarm for hh:mm:ss" -
// which the appendix asks the model for - is taken at its word. The first time is checked
// against this machine's clock first, within ALARM_CLOCK_SLACK_S, so a model that has the
// time wrong sets nothing; then the second goes to the timer agent (JRockTimer.java) on
// 127.0.0.1:TIMER_PORT, which answers 1 when it has set it. Either way, the status line
// says what became of it.
//
// Location: an answer that is only "I need to know your location" - which the appendix
// asks the model for, in English - is neither shown nor read aloud. The geo location agent
// (JRockGeoLocation.java) on 127.0.0.1:LOCATION_PORT is sent "#here", and the line it sends
// back - "Munich, Bavaria, Lat: 48.13, Lon: 11.59" - goes to the model as it is, as the
// next prompt; its reply is the turn's answer. Once a turn. The model knows which
// question that answers only with History on. Without the agent, or when it does not
// know, the request is shown and read aloud after all, and the status line says why.
//
// Weather: an answer that is only "I need weather for this location: ..." is not shown or
// read aloud either. A location with no coordinates in it - "Munich" - goes to the
// geo location agent first, for them; one the model already has from "#here" is used as it
// is. The location goes to the weather agent (JRockWeather.java) on
// 127.0.0.1:WEATHER_PORT, which answers with a checked address on open-meteo.com; that
// is fetched, as Fetch URL does, into jrock-prompt-ptt-weather.txt beside this file, and
// the model's report from it is the turn's answer, read aloud. Once a turn, after the
// location if that came first.
//
// The microphone and the speaker are JRock's to choose, as they are for Ctrl+Space and
// Narrate in JRock's own window. Either one not set is a message here, not a dialog:
// press Ctrl+Space (or Narrate) in JRock once to list the devices into Configure, pick
// one, and carry on. A narration that cannot play is not a failed turn - the answer is
// on the screen all the same.
//
// A turn that brings no answer - a send that failed, a reply that never came, a reply
// that arrived empty - is said out loud as well as shown: "Something went wrong. Should I try again?",
// the same fixed phrase every time, because hands-free nobody is looking at the window
// and a silence where an answer should be says nothing about whether one is still
// coming. Trying again is the operator's: say it again, or press the button again.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class JRockPushToTalk {

    // Starting a JVM's worth of Swing and fetching the model list, on a cold machine.
    private static final long READY_TIMEOUT_MS = 120_000;

    // Letting go to the recording being in the prompt: the tail and a file write for an
    // @audio token, and for a transcription as long as Windows takes to hear it, which
    // for a minute of speech is several seconds.
    private static final long RECORDING_TIMEOUT_MS = 120_000;

    // A spoken question gets a spoken-length answer, but a reasoning model can think for
    // a while first. The failure this guards against is a reply that never comes.
    private static final long REPLY_TIMEOUT_MS = 10 * 60_000;

    // How many times one turn may fetch something before the model has to answer with
    // what it has. A list and then one thing off it is two; a site, a section of it and a
    // message beside it is three; five leaves room for a question nobody has asked yet
    // without letting one turn run on for minutes. Every fetch is a program and a model
    // round trip, so the ceiling is the operator's patience rather than the cost.
    private static final int FETCHES_MAX = 5;

    // What the status line says between turns, and while listening, lever off and on.
    // No middle dots as separators: next to "Ctrl" one reads as a full stop.
    private static final String READY =
            "Hold to talk, or hold Ctrl. Ctrl+Shift for hands-free.";
    private static final String LISTENING_HELD = "Listening\u2026  let go to send";
    private static final String LISTENING_HANDS_FREE =
            "Hands-free: just talk, it sends when you stop. Ctrl or the button ends it.";

    // WHERE THE DOCUMENTS THIS AGENT IS HANDED GO: one folder, so that one folder can
    // be synchronised. The letters and the messages the other agents pass over - a
    // scanned letter as text, one e-mail as Markdown - are written here and then included
    // in the prompt, so that the transcript holds a token and a file rather than ten
    // thousand characters of letter.
    //
    // NOT the saved answers any more: those belong to the paper docs agent, which has the
    // folder for documents and the flow that names them (see savedIn). What is left here
    // is working material - the thing a question was asked ABOUT - and it is still worth
    // keeping out of the working folder and out of JRock's own, which hold the API key,
    // the conversation and the log.
    private static final String FILES_DIR = "PTTFiles";
    private static final String MAIL_SUB = "mail";
    // The folder name is the old one on purpose, as the agent's settings file is:
    // a transcript written last week names files in PTTFiles/papers/, and a
    // rename would leave every one of those tokens pointing at nothing.
    private static final String PAPERS_SUB = "papers";

    // What goes after every recording, expected beside this file; and the placeholder in
    // it that becomes the time the question was asked.
    private static final String PROMPT_APPENDIX = "jrock-prompt-push-to-talk-appendix.txt";
    private static final String NOW_PLACEHOLDER = "#now";
    private static Path appendix;

    // Why a step failed, in one sentence (see check).
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        Stop(String why) { super(why); }
    }

    // Every automation API call blocks and must not be made on the event dispatch
    // thread, and they must not overlap: one thread, taking the button's presses and
    // releases in the order they came. A daemon, so closing JRock's window ends the
    // process as it always does.
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "jrock-ptt");
                t.setDaemon(true);
                return t;
            });

    // Where a turn is. Read and written on the EDT only, which is where presses and
    // releases arrive: a press is taken only when IDLE, a release only when RECORDING.
    private enum State { IDLE, RECORDING, ANSWERING }
    private static State state = State.IDLE;

    // Whether the recording this turn asked for actually started. Written and read on
    // the worker only, so the release's task can tell a turn that never began.
    private static boolean recordingStarted;

    // How long one wait for speech lasts before the conversation loop looks at the lever
    // again. Not a limit on anyone talking: a recording under way is waited out.
    private static final long LISTEN_POLL_MS = 250;

    // How long hands-free waits after a turn that failed before it listens again: long
    // enough that a failure which repeats at once - a microphone not back yet - does not
    // spin, short enough that the operator can simply say it again.
    private static final long RETRY_PAUSE_MS = 2000;

    // What a turn that brought no answer says out loud.
    //
    // Fixed, short, and nothing like an answer: hands-free, nobody is looking at the
    // window, and a silence where an answer should be is indistinguishable from a model
    // still thinking - so the one thing the operator must not have to guess is whether
    // anything is coming. It asks rather than tells, because trying again is theirs to
    // do and takes nothing but saying it again: hands-free listens by itself after
    // RETRY_PAUSE_MS, and a held turn is the button pressed once more.
    //
    // A SENTENCE, not a word. It was "Error." - a noun on its own, which is how a machine
    // labels a condition and not how anybody says it. Read out loud by a voice that has
    // been answering in sentences all day, a bare noun is the moment the illusion of
    // talking to somebody collapses. "Something went wrong" has a subject and a verb,
    // blames nobody, and covers every way a turn can fail - a send that did not go, a
    // reply that never came, a reply that arrived empty, a model that kept asking for
    // more instead of answering.
    private static final String FAILED_ALOUD = "Something went wrong. Should I try again?";

    // What this agent is called in JRock's log, in front of every line it writes there -
    // its own reports, and the sentences it says out loud (see sayOwnWords). One JRock
    // window can be driven by one agent, but a transcript is read long afterwards, and a
    // line with no name on it reads as something JRock said about itself.
    private static final String AGENT = "Push-to-talk";

    // How often hands-free looks for a microphone that is not there.
    //
    // UNPLUGGING THE MICROPHONE IS THE MUTE BUTTON, and a headset usually has no other
    // one. It is how you keep a narrated answer from being recorded back as the next
    // question, and how you say something to somebody in the room without saying it to a
    // model - so it is a gesture, not a fault. Hands-free stays on, says nothing, reads
    // nothing out, counts no failure and asks nothing: it waits, and listens again the
    // moment the microphone is back. One line in JRock's log each way is all it is worth,
    // and that is what JRock's own microphone watcher writes.
    private static final long MIC_WAIT_MS = 1000;
    private static final String WAITING_FOR_MIC = "No microphone - waiting for it…";

    // The sentence an answer sets an alarm with, the time the model thinks it is and the
    // time to ring. The apostrophe as typed or as typeset, and "I am" too, since a model
    // writes either.
    private static final java.util.regex.Pattern ALARM = java.util.regex.Pattern.compile(
            "Now is (\\d{1,2}:\\d{2}:\\d{2}),? I(?:['’]m| am) setting an alarm for "
            + "(\\d{1,2}:\\d{2}:\\d{2})", java.util.regex.Pattern.CASE_INSENSITIVE);

    // How far the model's "now" may be from this machine's and still be believed: the
    // time a reply takes, and then some.
    private static final long ALARM_CLOCK_SLACK_S = 120;

    // The sentence an answer puts something in the calendar with: a date, a time if the
    // operator gave one, and the name of the event. Asked for in the appendix, in this
    // exact shape and in English whatever the conversation is in, for the same reason the
    // alarm sentence is - a sentence read off the answer is one the operator HEARD, so
    // nothing is put in a calendar that was not also said out loud.
    //
    // "at HH:MM" is optional because "the 15th" is a thing people say and an all-day
    // event is what they mean by it.
    private static final java.util.regex.Pattern EVENT = java.util.regex.Pattern.compile(
            "I(?:['’]m| am) putting (?:it|this|that)? ?in the calendar(?: on|:)? "
            + "(\\d{4}-\\d{2}-\\d{2})(?: at (\\d{1,2}:\\d{2}))?,? (?:named|called) (.+)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    // The timer agent's port - JRockTimer.PORT, the first of JRock's agents' ports - and
    // how long it has to answer, on the same machine.
    private static final int TIMER_PORT = 47470;
    private static final int TIMER_TIMEOUT_MS = 3000;

    // The answer that asks where the operator is - the whole of it, as the appendix asks,
    // give or take quotes and a full stop.
    private static final java.util.regex.Pattern LOCATION_REQUEST = java.util.regex.Pattern.compile(
            "^\\W*I need to know your location\\W*$", java.util.regex.Pattern.CASE_INSENSITIVE);

    // The answer that asks for the weather, and the location it is for - the whole of
    // the answer, as the location request is.
    private static final java.util.regex.Pattern WEATHER_REQUEST = java.util.regex.Pattern.compile(
            "^\\W*I need weather for this location:\\s*(.*\\S)\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    // The answer that asks what is in the operator's calendar, and for when: "today",
    // "this week", or a span of two dates. The whole of the answer, as the others are.
    //
    // The span is handed to the calendar agent as it stands and parsed there, so the
    // words the model may use - today, this week - are understood in one place rather
    // than two, and anything else comes back as a reason rather than as a wrong day.
    private static final java.util.regex.Pattern CALENDAR_REQUEST = java.util.regex.Pattern.compile(
            "^\\W*I need your calendar for\\s*(.*\\S)\\W*$",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    // The sentence an answer proposes a new time with: the id of the meeting, which the
    // calendar briefing printed on its line, and the time to propose. Asked for in the
    // appendix in this exact shape, like the alarm and the new event are, and for the
    // same reason - it is read out before anything happens, so no meeting is touched
    // that the operator did not hear named.
    //
    // The id is six hex characters, or a whole UID for anybody who has one.
    private static final java.util.regex.Pattern MOVE = java.util.regex.Pattern.compile(
            "I(?:['\u2019]m| am) proposing to move (?:the )?(?:event|meeting) "
            + "([0-9a-fA-F]{6}|\\S+@\\S+) to (\\d{4}-\\d{2}-\\d{2})"
            + "(?: at (\\d{1,2}:\\d{2}))?",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    // The answer that asks about the operator's mail, and what about it: "today", "the
    // last 3 days", "unanswered", "senders", "mail with somebody". The whole of the
    // answer, as the others are, and the words after it are handed to the Thunderbird
    // agent to make sense of - one place, not two.
    private static final java.util.regex.Pattern MAIL_REQUEST = java.util.regex.Pattern.compile(
            "^\\W*I need your mail:\\s*(.*\\S)\\W*$",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    // The answer that asks for something off the web. The browser agent keeps the list
    // of hosts and refuses anything else - twice, before and after the page loads.
    //
    // TWO LAYERS, AND THEY CARRY DIFFERENT THINGS. Between this agent and the browser
    // agent: addresses, Markdown, and what to open said in words. Between the operator
    // and the model: the NAME of a site - "zdnet", "zdnet.com", "South China Morning
    // Post" - and what they want in their own words. An address is never read out: the
    // appendix says so, because a URL spoken aloud is a sentence nobody can check.
    private static final java.util.regex.Pattern WEB_REQUEST = java.util.regex.Pattern.compile(
            "^\\W*I need the web:\\s*(.*\\S)\\W*$",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    // The answer that asks about the PAPER post: what the scanner has put in its folder,
    // what arrived when, filing one, what a filed letter says, and printing one on paper.
    private static final java.util.regex.Pattern POST_REQUEST = java.util.regex.Pattern.compile(
            "^\\W*I need the post:\\s*(.*\\S)\\W*$",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    // The answer an agent gives when it is handing over a DOCUMENT rather than a
    // sentence: the marker, what kind of thing it is, a file name, and then the text. A
    // letter off the scanner and an e-mail out of the index arrive the same way, and both
    // go into the prompt as a file rather than as ten thousand characters of prompt.
    private static final String CONTENT = "CONTENT ";

    // The sentence that saves the answer before last as a document. The operator has
    // heard it, likes it, and wants it on disk - a drafted reply, a summary worth
    // keeping - so what is exported is the PREVIOUS answer and never this sentence.
    private static final java.util.regex.Pattern SAVE = java.util.regex.Pattern.compile(
            "I(?:['\u2019]m| am) saving (?:the|that|it|this) ?(?:last|previous)? ?"
            + "(?:answer|reply|draft|text)? ?to a (?:file|document)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    // The sentence that puts a drafted reply into Thunderbird's compose window - said
    // ONLY after the operator has heard the draft and agreed to it, which the appendix
    // is emphatic about. The id names the message being replied to; everything after the
    // colon is the draft itself, as many lines as it takes.
    private static final java.util.regex.Pattern DRAFT = java.util.regex.Pattern.compile(
            "I(?:['\u2019]m| am) preparing (?:the|a) (?:draft|reply)(?: reply)? to "
            + "(?:the message |message )?([0-9a-fA-F]{6}):\\s*(.+)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    // A NOTE TO SELF: "I'm making a note: <the note>". Everything after the colon is
    // the note itself, over as many lines as it takes, which is why this is DOTALL.
    private static final java.util.regex.Pattern NOTE = java.util.regex.Pattern.compile(
            "I(?:['\u2019]m| am) (?:making|taking|writing) (?:a|the) note(?: to self)?:"
            + "\\s*(.+)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    // The answer before this one, which is what the save sentence saves.
    private static volatile String lastAnswer;

    // Coordinates in a location line, as the geo location agent writes them - which the model
    // may already have from it, and then they are used as they are.
    private static final java.util.regex.Pattern COORDINATES = java.util.regex.Pattern.compile(
            "Lat(?:itude)?\\s*:\\s*[-+]?\\d", java.util.regex.Pattern.CASE_INSENSITIVE);

    // The prompt the weather is fetched into, beside this file: #location is where it is
    // for, #url where the page goes.
    private static final String WEATHER_PROMPT = "jrock-prompt-ptt-weather.txt";

    // The location and weather agents' ports - JRockGeoLocation.PORT and JRockWeather.PORT,
    // the second and third of JRock's agents' ports - and how long an agent has: at once
    // to take the connection, and for the answer as long as two questions to its own
    // model and a page fetched take.
    private static final int LOCATION_PORT = 47471;
    private static final int WEATHER_PORT = 47472;
    private static final int CALENDAR_PORT = 47473;
    private static final int BROWSER_PORT = 47474;
    private static final int PAPERS_PORT = 47475;
    private static final int AGENT_CONNECT_MS = 3000;
    private static final int AGENT_TIMEOUT_MS = 180_000;

    // Filing a letter is not a lookup: the paper docs agent drives the doc inventory flow,
    // and a scanned letter through Ghostscript, xpdf and three model passes takes
    // minutes. Only the request that files one waits this long - everything else the
    // paper docs agent answers, it answers at once.
    private static final int FILING_TIMEOUT_MS = 12 * 60_000;
    private static final String FILING_REQUEST = "#inventory";

    // Printing is a handover rather than a lookup too, but a quick one: the paper docs agent
    // hands the document to the program that owns it and the paper comes out afterwards.
    // It gets the ordinary limit, and a status line of its own because something is about
    // to happen in another room.
    private static final String PRINT_REQUEST = "#print";

    // The lever: on, JRock listens and the agent converses (see converse). Written on
    // the EDT, read by the worker's loop too. And, EDT only: whether that loop is
    // running, and whether Ctrl and Shift are down, so their auto-repeat is not taken
    // for another press.
    private static volatile boolean handsFree;
    private static boolean conversing;
    private static boolean ctrlHeld;
    private static boolean shiftHeld;

    // THE SMALLEST THIS WINDOW CAN BE, as a whole number of cells on JRock's 32x27
    // grid (JRock.CELL_W, JRock.CELL_H - one grid for every window in this project):
    // 14 x 23 cells = 448 x 621, room for the button, the lever and a spoken answer.
    //
    // A NUMBER IN THIS FILE, and not whatever the layout worked out. This used to be
    // "pack(); setMinimumSize(getSize())", which makes the floor a figure nobody wrote
    // and nobody can read off the source - one that moves with the platform's fonts, the
    // display scaling, and even the text that happened to be in a label while the window
    // was being built. pack() still asks for the size of what is in this window, and it
    // opens at whichever of the two is larger.
    private static final int WINDOW_MIN_CELLS_W = 14;
    private static final int WINDOW_MIN_CELLS_H = 23;
    private static final int WINDOW_MIN_W = WINDOW_MIN_CELLS_W * JRock.CELL_W;
    private static final int WINDOW_MIN_H = WINDOW_MIN_CELLS_H * JRock.CELL_H;

    private static javax.swing.JFrame window;
    private static TalkButton button;
    private static HandsFreeLever lever;
    private static javax.swing.JLabel status;
    private static javax.swing.JTextPane answerArea;

    public static void main(String[] args) {
        try {
            appendix = mustExist(ownDirectory().resolve(PROMPT_APPENDIX));
            System.out.println("Appendix: " + appendix);
            // JRock's own flags go straight through. A bare argument would be a prompt
            // file JRock loads - harmless, since the prompt is cleared a line later.
            JRock.main(args);
            check(JRock.automationAwaitReady(READY_TIMEOUT_MS));
            check(JRock.automationBegin("push-to-talk agent"));
            check(JRock.automationClearPrompt());
        } catch (Stop stop) {
            JRock.automationEnd("stopped - " + stop.getMessage());
            System.out.println("Stopped: " + stop.getMessage());
            onEdt(() -> javax.swing.JOptionPane.showMessageDialog(JRock.automationWindow(),
                    "The push-to-talk agent could not start:\n\n" + stop.getMessage(),
                    "Push-to-talk", javax.swing.JOptionPane.ERROR_MESSAGE));
            return;
        }
        onEdt(JRockPushToTalk::showWindow);
        System.out.println("Push-to-talk is ready: hold the button, or Ctrl, and speak "
                + "(Ctrl+Shift for hands-free).");
    }

    // ---- The turn ----------------------------------------------------------
    // Button (or Ctrl) down. On the EDT.
    private static void press() {
        if (state != State.IDLE) return;
        setState(State.RECORDING, LISTENING_HELD);
        WORKER.submit(() -> {
            recordingStarted = false;
            try {
                JRock.automationStopNarration();   // the voice, not the question
                check(JRock.automationClearPrompt());
                check(JRock.automationStartRecording());
                recordingStarted = true;
            } catch (Stop stop) {
                // Still held, or already let go of: either way this turn is over.
                report("not recording: " + stop.getMessage());
                later(() -> setState(State.IDLE, "Not recording: " + stop.getMessage()));
            }
        });
    }

    // The button or Ctrl let go of. Sends what was recorded. On the EDT.
    private static void release() {
        if (state != State.RECORDING || conversing) return;
        setState(State.ANSWERING, "Sending\u2026");
        WORKER.submit(() -> {
            // Queued behind the press's task, so this knows how that one went.
            if (!recordingStarted) return;   // the press's task has said why
            String outcome;
            try {
                check(JRock.automationStopRecording(RECORDING_TIMEOUT_MS));
                recordingStarted = false;
                outcome = answer(false);
            } catch (Stop stop) {
                report("that turn failed: " + stop.getMessage());
                outcome = join("That turn failed: " + stop.getMessage(), sayFailed());
            }
            String said = outcome;
            later(() -> {
                setState(State.IDLE, said == null ? READY : said);
                if (handsFree) converseNow();   // the lever went on during the answer
            });
        });
    }

    // Sends the prompt as it stands, shows the reply in place of the last one and reads
    // it aloud, while the button is free again - unless it is a hands-free turn and
    // hands-free has ended while the reply was on its way. On the worker. Returns null,
    // or what to say about a narration that could not play - the turn has succeeded all
    // the same.
    private static String answer(boolean handsFreeTurn) {
        String prompt = JRock.automationPromptText();
        if (prompt == null) throw new Stop("the prompt could not be read.");
        String after = read(appendix.toString());
        if (after == null) throw new Stop("there is no " + appendix + ".");
        after = after.replace(NOW_PLACEHOLDER, now()).replaceAll("\\s+$", "");
        check(JRock.automationSetPrompt(prompt.replaceAll("\\s+$", "") + "\n\n" + after + "\n"));
        String answer = sendAndRead("Waiting for the reply\u2026");
        // A request for the location or the weather is not shown or read aloud: the
        // operator did not ask for it, and what it asks for goes to the model instead.
        // Those two are once a turn - the location, then the weather for it, is a turn
        // that goes well - so a model that asks again is heard rather than asked forever.
        //
        // The calendar, the mail, the post and the web are not. One question is often
        // two or three requests deep, and the model only finds out what it needs by
        // reading what it has just been given: the mail of the last week, then one
        // message out of that list; a site's front page, then the section of it the
        // operator asked about. So the loop goes round and fetches again - see refuse for
        // the two things it will not do - and what reaches the operator is an answer
        // rather than the model's next instruction read out loud.
        String note = null;
        String stuck = null;   // a request that could not be served, and why (see refuse)
        boolean askedLocation = false, askedWeather = false;
        int fetched = 0;
        java.util.Set<String> served = new java.util.LinkedHashSet<>();
        while (note == null) {
            java.util.regex.Matcher weather = WEATHER_REQUEST.matcher(answer.trim());
            java.util.regex.Matcher calendar = CALENDAR_REQUEST.matcher(answer.trim());
            java.util.regex.Matcher mail = MAIL_REQUEST.matcher(answer.trim());
            java.util.regex.Matcher web = WEB_REQUEST.matcher(answer.trim());
            java.util.regex.Matcher post = POST_REQUEST.matcher(answer.trim());
            if (!askedLocation && LOCATION_REQUEST.matcher(answer.trim()).matches()) {
                askedLocation = true;
                later(() -> setState(State.ANSWERING, "Looking up the location\u2026"));
                String[] here = askAgent(LOCATION_PORT, "#here", "geo location", "JRockGeoLocation.java");
                if (here[0] == null) {
                    note = "Location not sent: " + here[1];
                    break;
                }
                check(JRock.automationClearPrompt());
                check(JRock.automationSetPrompt(here[0]));
                answer = sendAndRead("Sent the location, waiting for the reply\u2026");
            } else if (calendar.matches()) {
                // What is in the calendar is read off this machine by the calendar agent
                // (JRockThunderbird.java), out of Thunderbird's own offline copy -
                // nothing is fetched and nothing is asked of a server.
                String span = calendar.group(1).trim();
                stuck = refuse(served, fetched++, "calendar", span);
                if (stuck != null) break;
                later(() -> setState(State.ANSWERING, "Reading the calendar\u2026"));
                String[] events = askAgent(CALENDAR_PORT, calendarRequest(span), "calendar",
                        "JRockThunderbird.java");
                if (events[0] == null) {
                    note = "Calendar not sent: " + events[1];
                    break;
                }
                check(JRock.automationClearPrompt());
                check(JRock.automationSetPrompt(events[0]));
                answer = sendAndRead("Sent the calendar, waiting for the reply\u2026");
            } else if (mail.matches()) {
                // The mail is read off this machine by the Thunderbird agent, out of
                // Thunderbird's own search index. Nothing is fetched, no server is
                // asked, and no message is opened, moved or marked.
                //
                // Twice a turn is the normal way round here: a list first - "I need your
                // mail: 7 days" - and then one message off that list by the id on its
                // line, which is an id the model cannot know until it has the list.
                String about = mail.group(1).trim();
                stuck = refuse(served, fetched++, "mail", about);
                if (stuck != null) break;
                later(() -> setState(State.ANSWERING, "Reading the mail\u2026"));
                String[] mailbox = askAgent(CALENDAR_PORT, mailRequest(about), "Thunderbird",
                        "JRockThunderbird.java");
                if (mailbox[0] == null) {
                    note = "Mail not sent: " + mailbox[1];
                    break;
                }
                check(JRock.automationClearPrompt());
                if (mailbox[0].startsWith(CONTENT)) {
                    // One message, handed over as a document - the same way a scanned
                    // letter is.
                    String problem = includeDocument(mailbox[0]);
                    if (problem != null) {
                        note = "Message not included: " + problem;
                        break;
                    }
                } else {
                    check(JRock.automationSetPrompt(mailbox[0]));
                }
                answer = sendAndRead("Sent the mail, waiting for the reply\u2026");
            } else if (post.matches()) {
                // The paper post, off the paper docs agent - which has no model of its own:
                // it looks in the scanner's folder, and drives the doc inventory flow
                // when a letter has to be filed.
                String about = post.group(1).trim();
                stuck = refuse(served, fetched++, "post", about);
                if (stuck != null) break;
                later(() -> setState(State.ANSWERING, "Looking at the post\u2026"));
                String postAsked = postRequest(about);
                if (postAsked.startsWith(FILING_REQUEST)) {
                    later(() -> setState(State.ANSWERING,
                            "Filing the letter - this takes a few minutes…"));
                } else if (postAsked.startsWith(PRINT_REQUEST)) {
                    later(() -> setState(State.ANSWERING, "Printing it…"));
                }
                String[] letters = askAgent(PAPERS_PORT, postAsked, "paper docs",
                        "JRockPaperDocs.java",
                        postAsked.startsWith(FILING_REQUEST) ? FILING_TIMEOUT_MS
                                                             : AGENT_TIMEOUT_MS);
                if (letters[0] == null) {
                    note = "Post not read: " + letters[1];
                    break;
                }
                check(JRock.automationClearPrompt());
                if (letters[0].startsWith(CONTENT)) {
                    String problem = includeDocument(letters[0]);
                    if (problem != null) {
                        note = "Letter not included: " + problem;
                        break;
                    }
                    answer = sendAndRead("Sent the letter, waiting for the reply\u2026");
                } else {
                    check(JRock.automationSetPrompt(letters[0]));
                    answer = sendAndRead("Sent the post, waiting for the reply\u2026");
                }
            } else if (web.matches()) {
                // A page off the web, read by the browser agent: it photographs the page
                // and has its own JRock turn the picture into Markdown, so what arrives
                // here is a page as text. Slow - a browser, a render and a model - so the
                // status line says what is happening.
                //
                // More than once a turn is what the appendix already asks for: a site's
                // front page, then "follow the AI section on <that page's address>" - an
                // address the model can only have from the page it was just given.
                String about = web.group(1).trim();
                stuck = refuse(served, fetched++, "web", about);
                if (stuck != null) break;
                later(() -> setState(State.ANSWERING, "Reading the web\u2026"));
                String[] page = askAgent(BROWSER_PORT, webRequest(about), "browser",
                        "JRockBrowser.java");
                if (page[0] == null) {
                    note = "Web page not fetched: " + page[1];
                    break;
                }
                check(JRock.automationClearPrompt());
                check(JRock.automationSetPrompt(page[0]));
                answer = sendAndRead("Sent the page, waiting for the reply\u2026");
            } else if (!askedWeather && weather.matches()) {
                askedWeather = true;
                String[] report = weather(weather.group(1));
                if (report[0] == null) {
                    note = "Weather not fetched: " + report[1];
                    break;
                }
                answer = report[0];
            } else if (weather.matches() || LOCATION_REQUEST.matcher(answer.trim()).matches()) {
                // Asked again for one of the two things that are once a turn. Neither
                // the operator's location nor the weather there changes between two
                // sends, so a second fetch would hand back the first one's answer word
                // for word - the same dead end as asking for the same page twice, and it
                // ends the same way.
                stuck = "the model asked again for the location or the weather";
                break;
            } else {
                break;
            }
        }
        String reply = answer;
        later(() -> showAnswer(reply));
        String alarm = alarmIn(reply);
        String event = eventIn(reply);
        String moved = movedIn(reply);
        String drafted = draftIn(reply);
        String noted = noteIn(reply);
        String saved = savedIn(reply);
        // What the NEXT save would save: this answer, unless this answer was itself a
        // command about the last one.
        if (saved == null && drafted == null) lastAnswer = reply;
        if (handsFreeTurn && !handsFree) {
            return join(note, alarm, event, moved, drafted, noted, saved);
        }
        // An answer of nothing at all - a reply that arrived empty - is a failed turn to
        // anyone listening, however well the request itself went: reading it out loud
        // would be silence, which is the one thing a spoken answer cannot be.
        //
        // A reply that is STILL a request is the same kind of failure. "I need your mail:
        // message 0d613e" read out to somebody who asked what their post says is the
        // machine talking to itself, and they are still waiting for the answer. So it is
        // shown, because it says what the model wanted, and the fixed phrase is what they
        // hear - the one they already know means "say it again".
        if (reply.isBlank() || stuck != null) {
            if (stuck != null) report("that turn ended without an answer: " + stuck + ".");
            return join(note, alarm, event, moved, drafted, noted, saved,
                    reply.isBlank() ? "The reply was empty." : "No answer: " + stuck + ".",
                    sayFailed());
        }
        String silent = sayAnswer(reply);
        return join(note, alarm, event, moved, drafted, noted, saved,
                silent == null ? null : "Not read aloud: " + silent);
    }

    // What the model asked for, as the calendar agent's own request: "today" and "this
    // week" are its own commands, a pair of dates is a range, and anything else is passed
    // through for it to refuse with a reason rather than guessed at here.
    private static String calendarRequest(String span) {
        String asked = span.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9\\- ]", " ")
                .trim().replaceAll("\\s+", " ");
        java.util.regex.Matcher dates = java.util.regex.Pattern
                .compile("(\\d{4}-\\d{2}-\\d{2}).*?(\\d{4}-\\d{2}-\\d{2})").matcher(asked);
        if (dates.find()) return "#range " + dates.group(1) + " " + dates.group(2);
        // The maybes are kept out of every other answer, so asking for them is a
        // question of its own rather than a flag on another one.
        if (asked.contains("tentative") || asked.contains("optional")
                || asked.contains("maybe")) {
            return "#tentative";
        }
        if (asked.contains("week")) return "#week";
        if (asked.contains("next")) return "#next 5";
        return "#today";
    }

    // What the model asked about the mail, as the Thunderbird agent's own request. The
    // words a model actually uses are mapped here, and anything it says that is not one
    // of them is passed through for the agent to refuse with a reason - the same rule the
    // calendar requests follow, and for the same reason: a guess here would be a wrong
    // answer read aloud in a confident voice.
    // How many days a span said in words comes to: "a week", "3 weeks", "six months",
    // "half a year", "a year". Nobody dictates "one hundred and eighty days".
    private static long spanInDays(String asked) {
        java.util.regex.Matcher weeks = java.util.regex.Pattern
                .compile("(\\d{1,2}|a|one|two|three|four|five|six)\\s*week").matcher(asked);
        java.util.regex.Matcher months = java.util.regex.Pattern
                .compile("(\\d{1,2}|a|one|two|three|four|five|six|nine|twelve)\\s*month")
                .matcher(asked);
        if (asked.contains("half a year") || asked.contains("six months")) return 183;
        if (asked.contains("a year") || asked.contains("12 months")
                || asked.contains("this year") || asked.contains("last year")) {
            return 365;
        }
        if (months.find()) return Math.min(366, 31 * count(months.group(1)));
        if (weeks.find()) return Math.min(366, 7 * count(weeks.group(1)));
        if (asked.contains("fortnight")) return 14;
        return 0;
    }

    private static long count(String said) {
        switch (said) {
            case "a": case "one": return 1;
            case "two": return 2;
            case "three": return 3;
            case "four": return 4;
            case "five": return 5;
            case "six": return 6;
            case "nine": return 9;
            case "twelve": return 12;
            default:
                try {
                    return Math.max(1, Long.parseLong(said));
                } catch (NumberFormatException notANumber) {
                    return 1;
                }
        }
    }

    private static String mailRequest(String about) {
        String asked = about.toLowerCase(java.util.Locale.ROOT).trim();
        java.util.regex.Matcher with = java.util.regex.Pattern
                .compile("(?:with|from|to)\\s+(.+)").matcher(asked);
        java.util.regex.Matcher days = java.util.regex.Pattern
                .compile("(\\d{1,3})\\s*day").matcher(asked);
        if (asked.contains("unanswered") || asked.contains("no reply")
                || asked.contains("not answered")) {
            return "#unanswered" + (days.find() ? " " + days.group(1) : "");
        }
        if (asked.contains("sender") || asked.contains("who wrote")
                || asked.contains("from whom")) {
            return "#senders" + (days.find() ? " " + days.group(1) : "");
        }
        // One message, read: its own text rather than a line about it.
        java.util.regex.Matcher one = java.util.regex.Pattern
                .compile("(?:message|read|open|what did|contents of)\\s+(.+)").matcher(asked);
        if (one.matches()) return "#message " + one.group(1).trim();
        if (asked.startsWith("mailbox") || asked.contains("mailboxes")
                || asked.contains("accounts")) {
            return "#mailboxes";
        }
        if (with.matches()) return "#with " + with.group(1).trim();
        if (asked.contains("today")) return "#mail";
        if (days.find()) return "#mail " + days.group(1);
        // A SPAN SAID IN WEEKS, MONTHS OR YEARS. The agent counts in days and will go
        // back a year, so nothing here was ever the limit - but a model that says "six
        // months" to a mapping which only knew the word "day" fell through to today's
        // mail, and the operator asking about something from the spring got this
        // morning's post instead. The words it is likely to use, turned into days.
        long span = spanInDays(asked);
        if (span > 0) return "#mail " + span;
        return "#mail";
    }

    // What the model asked of the web, as the browser agent's own request. The leading
    // word decides, and everything after it is passed through AS WRITTEN - a description
    // of what to open, or an address the model got from an earlier answer, and this agent
    // does not try to understand either. The browser agent resolves a description against
    // the page's own links, which is the one place that can.
    private static String webRequest(String about) {
        String asked = about.trim();
        String lower = asked.toLowerCase(java.util.Locale.ROOT);
        for (String[] shape : new String[][] {
                { "follow", "#follow" }, { "open", "#follow" }, { "click", "#follow" },
                { "search", "#search" }, { "links", "#links" }, { "site", "#page" },
                { "page", "#page" }, { "hosts", "#hosts" },
                // Documentation, which is a walk through a product's own labels rather
                // than a page or a search: "docs how do I set a baseline grid in
                // indesign". The words a model reaches for when the question is about a
                // program it knows has a manual.
                { "docs", "#docs" }, { "documentation", "#docs" }, { "manual", "#docs" },
                { "help", "#docs" }, { "look up", "#docs" } }) {
            if (lower.equals(shape[0])) return shape[1];
            if (lower.startsWith(shape[0] + " ")) {
                return shape[1] + " " + asked.substring(shape[0].length()).trim();
            }
        }
        // Anything else is the name of a site, which is what an operator says.
        return "#page " + asked;
    }

    // What the model asked of the post, as the paper docs agent's own request. The leading
    // word decides and the rest is passed through as written - a letter's name as the
    // operator said it, which the paper docs agent matches against the file names itself.
    private static String postRequest(String about) {
        String asked = about.trim();
        String lower = asked.toLowerCase(java.util.Locale.ROOT);
        for (String[] shape : new String[][] {
                { "recent", "#recent" }, { "new", "#recent" }, { "arrived", "#recent" },
                { "scans", "#scans" }, { "all", "#scans" },
                { "filed", "#filed" },
                // "arrived" is already the scanner's folder, above, and the first match
                // wins: a second one here would be a line that never runs.
                { "incoming", "#incoming" }, { "documents", "#incoming" },
                { "file it", "#inventory" }, { "inventory", "#inventory" },
                // ON PAPER. "print it" is the newest scan, the way "file it" is, and
                // anything after "print" is the document's name - which the paper docs agent
                // matches, and whose best printable kind it picks.
                { "print it", "#print" }, { "print", "#print" },
                { "file", "#file" }, { "read", "#file" }, { "what", "#file" },
                { "folder", "#folder" } }) {
            if (lower.equals(shape[0])) return shape[1];
            if (lower.startsWith(shape[0] + " ")) {
                return shape[1] + " " + asked.substring(shape[0].length()).trim();
            }
        }
        // Anything else is a letter's name, which is what the operator says.
        return "#file " + asked;
    }

    // A DOCUMENT an agent handed over - a scanned letter, an e-mail - as a file in the
    // prompt rather than as a wall of prompt.
    //
    // Written into this agent's own files folder (see FILES_DIR) rather than into a temp
    // folder somebody would have to go looking for, and then INCLUDED exactly as a person
    // would include it with Ctrl+I. The transcript keeps a token and a file; the model
    // reads a document rather than a quotation of one. Being in that folder means a
    // letter read out in the kitchen is also on the operator's phone, which is the same
    // reason the saved answers are there.
    //
    // The first line is "CONTENT <kind> <name>", and the kind decides what the model is
    // asked to do with it: a letter is explained, an e-mail is answered about. Everything
    // after that line is the thing itself.
    private static String includeDocument(String answer) {
        String[] split = answer.split("\n", 2);
        String[] head = split[0].substring(CONTENT.length()).trim().split("\\s+", 2);
        String kind = head.length > 0 ? head[0].toLowerCase(java.util.Locale.ROOT) : "document";
        String name = head.length > 1 ? head[1].trim() : "document.txt";
        String body = split.length > 1 ? split[1] : "";
        boolean mail = kind.startsWith("message") || kind.startsWith("mail");
        try {
            java.nio.file.Path dir = filesDir(mail ? MAIL_SUB : PAPERS_SUB);
            String safe = name.replaceAll("[^A-Za-z0-9._-]", "-");
            if (!safe.contains(".")) safe += mail ? ".md" : ".txt";
            java.nio.file.Path file = dir.resolve(safe);
            java.nio.file.Files.write(file, body.getBytes(StandardCharsets.UTF_8));
            String problem = JRock.automationSetPrompt(mail
                    ? "The operator asked about this e-mail. It is one message, written "
                      + "out as Markdown: who sent it, when, what was attached, and what "
                      + "it says. Answer what they asked in a few spoken sentences, in "
                      + "their own language. If they asked whether it is worth replying "
                      + "to, say what you think and why. The line beginning "
                      + "\"Unsubscribe:\" answers whether this is something they can get "
                      + "off the list of - say whether there is a way out and nothing "
                      + "more, because \"none\" on that line means there is none. Do not "
                      + "read out addresses, file names or the message id.\n\n"
                    : "The operator asked about this letter. It is the text of a scanned "
                      + "letter, filed as \"" + name + "\". Say what it is about and what "
                      + "it asks of them, in a few spoken sentences, in the operator's own "
                      + "language. Name any date, amount or reference number that matters. "
                      + "Do not read out the file name.\n\n");
            if (problem != null) return problem;
            return JRock.automationInclude(file.toString(), "txt");
        } catch (IOException ex) {
            return "it could not be written beside the transcript: " + ex.getMessage();
        }
    }

    // The weather for a location the model named: its coordinates from the location
    // agent, if the model gave none; the address to fetch from the weather agent; and
    // the model's report of what is there, fetched into the weather prompt. Returns
    // { report, null }, or { null, why not }. On the worker.
    private static String[] weather(String location) {
        String where = location.trim().replaceAll("[.\"'\u201c\u201d]+$", "").trim();
        if (!COORDINATES.matcher(where).find()) {
            String named = where;
            later(() -> setState(State.ANSWERING, "Looking up where " + named + " is\u2026"));
            String[] found = askAgent(LOCATION_PORT, named, "geo location", "JRockGeoLocation.java");
            if (found[0] == null) return found;
            if (!COORDINATES.matcher(found[0]).find()) {
                return new String[] { null, "the geo location agent found no coordinates for " + named + "." };
            }
            where = found[0];
        }
        String at = where;
        later(() -> setState(State.ANSWERING, "Finding the weather for " + at + "\u2026"));
        String[] url = askAgent(WEATHER_PORT, at, "weather", "JRockWeather.java");
        if (url[0] == null) return url;
        String prompt = read(ownDirectory().resolve(WEATHER_PROMPT).toString());
        if (prompt == null) return new String[] { null, "there is no " + WEATHER_PROMPT + "." };
        prompt = prompt.replace("#location", at);
        int cut = prompt.indexOf("#url");
        String before = cut < 0 ? prompt.replaceAll("\\s+$", "") + "\n\n" : prompt.substring(0, cut);
        String after = cut < 0 ? "" : prompt.substring(cut + "#url".length());
        later(() -> setState(State.ANSWERING, "Fetching the weather\u2026"));
        check(JRock.automationClearPrompt());
        check(JRock.automationSetPrompt(before));
        String fetched = JRock.automationFetchUrl(url[0]);
        if (fetched != null) return new String[] { null, fetched };
        if (!after.isBlank()) {
            String sofar = JRock.automationPromptText();
            if (sofar == null) throw new Stop("the prompt could not be read.");
            check(JRock.automationSetPrompt(sofar + after));
        }
        return new String[] { sendAndRead("Fetched the weather, waiting for the report\u2026"), null };
    }

    // Sends the prompt as it stands and returns the reply, the answer area emptied and
    // the status line saying waiting while it comes. On the worker.
    private static String sendAndRead(String waiting) {
        later(() -> {
            answerArea.setText("");
            setState(State.ANSWERING, waiting);
        });
        String[] sent = JRock.automationSend(REPLY_TIMEOUT_MS);
        if (!"1".equals(sent[0])) throw new Stop(sent[3]);
        String answer = read(JRock.automationMessageFile("assistant", sent[2]));
        if (answer == null) throw new Stop("the reply was not written to JRock/messages/.");
        return answer;
    }

    // Whether a request the model has just made can be served, and why not when it
    // cannot - the sentence the operator gets in place of an answer. Serving one writes
    // it into served, so the next round knows it has been asked.
    //
    // Two things are refused. A turn that has fetched FETCHES_MAX times already has to
    // answer with what it has: somebody asked a question across a room, and at some
    // point the answer matters more than its completeness. And the same request twice is
    // a model going round in circles - the second fetch hands back exactly what the first
    // one did, so it cannot be the thing that unsticks it, and all another round would
    // spend is the waiting.
    private static String refuse(java.util.Set<String> served, int fetched, String kind,
            String about) {
        if (fetched >= FETCHES_MAX) {
            return "the model asked to fetch more than " + FETCHES_MAX + " times in one turn";
        }
        String asked = about.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        if (!served.add(kind + " " + asked)) {
            return "the model asked for the same thing twice (" + kind + ": " + asked + ")";
        }
        return null;
    }

    // SAYS SOMETHING OUT LOUD, THROUGH THE LOG. Returns null when it was said, or why
    // it could not be - which the status line reports and the turn survives.
    //
    // JRock's narration reads the log's SELECTION and takes no text, so anything said out
    // loud has to be in the log first. Which is the right way round: this agent speaks for
    // a model whose words nobody else hears, and a spoken answer with no record of it
    // would be the one kind of output this machine cannot account for afterwards. The
    // transcript holds everything that was ever said, in order, with the conversation
    // around it - and the window shows the very words while they are being spoken.
    //
    // THE ANSWER IS ALREADY THERE, and that is the difference between the two methods
    // below. It was written down a second time as a gray line and the gray copy was what
    // got selected, so every answer appeared twice in the window somebody was reading:
    // once as the model's answer, once as a gray repetition of it underneath. The answer
    // is selected where it stands now, and the gray lines are left for what is NOT in the
    // log - this agent's own sentences.
    //
    // Which also made narration work where it did not: in "Dialog only" the window shows
    // no gray lines at all, and what is not shown cannot be selected or read out.
    private static String sayAnswer(String reply) {
        return narrateFromLog(reply);
    }

    // This agent's OWN words, which are nowhere until it writes them: the fixed phrase a
    // failed turn ends with, and anything else it has to say for itself. Written with its
    // name in front (automationNote with an agent), and only the words after the name are
    // narrated - "Push-to-talk:" is for whoever reads the transcript.
    private static String sayOwnWords(String words) {
        String problem = JRock.automationNote(AGENT, words);
        if (problem != null) return problem;
        return narrateFromLog(words);
    }

    // The selection goes afterwards, because the log scrolls nowhere while one exists:
    // leaving it would stop the window following the conversation it is reading out.
    private static String narrateFromLog(String words) {
        String problem = JRock.automationSelectInLog(words);
        if (problem != null) return problem;
        String silent = JRock.automationNarrate();
        JRock.automationClearSelection();
        return silent;
    }

    // Says FAILED_ALOUD out loud, and returns what the status line should add about
    // saying it - null when it was said, the reason when it could not be (no speaker set
    // on this JRock, or the one set is not connected).
    //
    // Every turn that ends without an answer goes through here, whether the button was
    // held or hands-free is on: the reason on screen is for afterwards, and this is for
    // the moment it happens. A narration already playing is stopped first by
    // automationNarrate, so the phrase never queues behind half an old answer.
    private static String sayFailed() {
        String silent = sayOwnWords(FAILED_ALOUD);
        return silent == null ? null : "Not read aloud: " + silent;
    }

    // One line into JRock's own log, which is the only place an agent started by javaw
    // can be read: System.out goes nowhere there, and this window's status line holds
    // one sentence until the next turn replaces it. Every turn that fails says why here,
    // so the reason is still there to read afterwards.
    private static void report(String line) {
        System.out.println(line);
        JRock.automationNote(AGENT, line);
    }

    // The notes that are there, in one line; null when there are none.
    private static String join(String... notes) {
        StringBuilder out = new StringBuilder();
        for (String n : notes) {
            if (n == null) continue;
            if (out.length() > 0) out.append(' ');
            out.append(n);
        }
        return out.length() == 0 ? null : out.toString();
    }

    // Asks another agent - the geo location agent, the weather agent - on its port: the line
    // sent, and the line that comes back, read until the agent closes the connection.
    // Returns { line, null }, or { null, why not }; an empty line is the agent's "don't
    // know". what and file name the agent in that.
    private static String[] askAgent(int port, String line, String what, String file) {
        return askAgent(port, line, what, file, AGENT_TIMEOUT_MS);
    }

    // The same, with its own limit on how long the answer may take.
    //
    // Three minutes is generous for a LOOKUP - a calendar, a mailbox, a page off the web
    // - and nowhere near enough for the one request that does work rather than reading:
    // filing a letter drives the doc inventory flow, which is Ghostscript, xpdf and
    // three model passes on a scanned page, and a few minutes is a normal letter. The
    // only thing the short limit achieved there was telling the operator "the letters
    // agent did not answer" while the letter was being filed, and then showing them the
    // model's own request instead of an answer.
    private static String[] askAgent(int port, String line, String what, String file,
                                     int timeoutMs) {
        try (java.net.Socket agent = new java.net.Socket()) {
            agent.connect(new java.net.InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(), port), AGENT_CONNECT_MS);
            agent.setSoTimeout(timeoutMs);
            agent.getOutputStream().write((line.replaceAll("[\r\n]+", " ") + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            agent.getOutputStream().flush();
            String reply = new String(agent.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\r?\n$", "");
            return reply.isBlank()
                    ? new String[] { null, "the " + what + " agent has no answer for \"" + line
                            + "\" - its window says why." }
                    : new String[] { reply, null };
        } catch (java.net.ConnectException ex) {
            return new String[] { null, "the " + what + " agent is not running (" + file + ")." };
        } catch (IOException ex) {
            return new String[] { null, "the " + what + " agent did not answer (" + ex.getMessage() + ")." };
        }
    }

    // Sets the alarm an answer asks for, if it asks for one. Returns null when it does
    // not, and otherwise what became of the alarm.
    private static String alarmIn(String answer) {
        java.util.regex.Matcher m = ALARM.matcher(answer);
        if (!m.find()) return null;
        java.time.LocalTime said;
        try {
            said = java.time.LocalTime.parse(pad(m.group(1)));
            java.time.LocalTime.parse(pad(m.group(2)));
        } catch (java.time.format.DateTimeParseException ex) {
            return "Alarm not set: " + ex.getParsedString() + " is not a time.";
        }
        // Around midnight either one may be on the other side of it.
        long off = Math.abs(java.time.Duration.between(said, java.time.LocalTime.now()).getSeconds());
        off = Math.min(off, 24 * 3600 - off);
        if (off > ALARM_CLOCK_SLACK_S) {
            return "Alarm not set: the model said it is " + m.group(1) + ", which is "
                    + (off / 60) + " minutes off this computer's clock.";
        }
        try (java.net.Socket timer = new java.net.Socket()) {
            timer.connect(new java.net.InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(), TIMER_PORT), TIMER_TIMEOUT_MS);
            timer.setSoTimeout(TIMER_TIMEOUT_MS);
            timer.getOutputStream().write((pad(m.group(2)) + "\n").getBytes(StandardCharsets.US_ASCII));
            timer.getOutputStream().flush();
            int reply = timer.getInputStream().read();
            return reply == '1' ? "Alarm set for " + pad(m.group(2)) + "."
                    : "Alarm not set: the timer refused " + pad(m.group(2)) + ".";
        } catch (java.net.ConnectException ex) {
            return "Alarm not set: the timer agent is not running (JRockTimer.java).";
        } catch (IOException ex) {
            return "Alarm not set: the timer agent did not answer (" + ex.getMessage() + ").";
        }
    }

    // 7:30:00 as 07:30:00, which is what LocalTime.parse takes.
    private static String pad(String time) {
        return time.length() == 7 ? "0" + time : time;
    }

    // Proposes the new time an answer proposes, if it proposes one. Returns null when it
    // does not, and otherwise what became of it.
    //
    // Only an appointment that is entirely the operator's own can be moved: the
    // Thunderbird agent refuses one somebody else organised and one with any other
    // participant on it, naming them, and the mail it composes goes from the operator to
    // the operator. What goes over the wire from here is an id, a date and a time.
    private static String movedIn(String answer) {
        java.util.regex.Matcher m = MOVE.matcher(answer);
        if (!m.find()) return null;
        String request = "#move " + m.group(1) + " " + m.group(2)
                + (m.group(3) == null ? "" : " " + m.group(3));
        String[] proposed = askAgent(CALENDAR_PORT, request, "Thunderbird",
                "JRockThunderbird.java");
        if (proposed[0] == null) return "New time not proposed: " + proposed[1];
        report("calendar: " + proposed[0].replace("\n", " "));
        String first = proposed[0].split("\n", 2)[0].trim();
        return first.isEmpty() ? "New time proposed." : first;
    }

    // Puts a drafted reply into Thunderbird's compose window, if the answer says it is
    // preparing one. Returns null when it does not.
    //
    // The order matters and the appendix insists on it: the draft is READ ALOUD first, as
    // an ordinary answer, and this sentence comes only after the operator has agreed to
    // it. So nothing opens a window on the strength of a first attempt at wording.
    private static String draftIn(String answer) {
        java.util.regex.Matcher m = DRAFT.matcher(answer);
        if (!m.find()) return null;
        String id = m.group(1);
        String draft = m.group(2).trim();
        if (draft.isEmpty()) return "Draft not prepared: there was no text in it.";
        String[] prepared = askAgent(CALENDAR_PORT, "#reply " + id + " " + draft,
                "Thunderbird", "JRockThunderbird.java");
        if (prepared[0] == null) return "Draft not prepared: " + prepared[1];
        report("mail: " + prepared[0].replace("\n", " "));
        return prepared[0].split("\n", 2)[0].trim();
    }

    // Opens a note to self in Thunderbird, if the answer says it is making one. Returns
    // null when it does not, and otherwise what became of it.
    //
    // The Thunderbird agent does the work and the words go as they are: it is the one
    // that knows the operator's own address, and it takes the markdown off before the
    // note becomes a mail body. Nothing is sent here either - the compose window opens
    // and the last keystroke is the operator's, as with every other mail this machine
    // writes.
    private static String noteIn(String answer) {
        java.util.regex.Matcher m = NOTE.matcher(answer);
        if (!m.find()) return null;
        String words = m.group(1).trim();
        if (words.isEmpty()) return "Note not made: there was nothing in it.";
        String[] made = askAgent(CALENDAR_PORT, "#note " + words.replace("\n", " "),
                "Thunderbird", "JRockThunderbird.java");
        if (made[0] == null) return "Note not made: " + made[1];
        report("note: " + made[0].replace("\n", " "));
        return made[0].split("\n", 2)[0].trim();
    }

    // Saves the answer BEFORE this one as a DOCX in the working folder, if the answer says
    // it is saving one. Returns null when it does not.
    //
    // The previous answer and never this one: the operator liked something that was read
    // to them and asked for it to be kept, and the sentence asking for that is not the
    // thing they liked. JRock exports it the same way the window's own menu does - the
    // Markdown laid out as A4 with any picture it refers to placed in it - so a drafted
    // letter comes out as a letter rather than as a wall of text.
    private static String savedIn(String answer) {
        if (!SAVE.matcher(answer).find()) return null;
        String keep = lastAnswer;
        if (keep == null || keep.isBlank()) {
            return "Nothing saved: there is no earlier answer in this conversation.";
        }
        // THE PAPERS AGENT KEEPS IT, and this agent only hands it over. Documents are
        // what that agent is for: it has the folder the operator synchronises, it has the
        // flow that names things, and it already reads everything in there back out. This
        // one has a microphone and a model, which is a different job - and the markdown
        // travels between them as every other document here already does.
        String[] saved = askAgent(PAPERS_PORT, "#save " + keep, "paper docs",
                "JRockPaperDocs.java");
        if (saved[0] == null) return "Not saved: " + saved[1];
        report("paper docs: " + saved[0].replace("\n", " "));
        return saved[0].split("\n", 2)[0].trim();
    }

    // Puts in the calendar what an answer says it is putting there, if it says so.
    // Returns null when it does not, and otherwise what became of it.
    //
    // The calendar agent does the work, and what it does is write an .ics file and have
    // Thunderbird compose a mail from the configured address to that same address with
    // the invitation attached. Nothing here names an address and nothing here sends
    // anything: this hands over a date, a time and a name, and the last keystroke -
    // Ctrl+Enter in Thunderbird's own window - is the operator's.
    private static String eventIn(String answer) {
        java.util.regex.Matcher m = EVENT.matcher(answer);
        if (!m.find()) return null;
        String day = m.group(1);
        String at = m.group(2);
        // The name as the model wrote it, without the full stop that ends the sentence
        // and without quotes around it, which a model puts there about half the time.
        String name = m.group(3).trim().replaceAll("[.\\s]+$", "")
                .replaceAll("^[\"'“‘]|[\"'”’]$", "").trim();
        if (name.isEmpty()) return "Event not created: the answer named no event.";
        String request = "#new " + day + (at == null ? "" : " " + at) + " " + name;
        String[] made = askAgent(CALENDAR_PORT, request, "calendar", "JRockThunderbird.java");
        if (made[0] == null) return "Event not created: " + made[1];
        // The agent's first line is the one worth a status line; the rest tells the
        // operator what to press, and JRock's log has all of it.
        String first = made[0].split("\n", 2)[0].trim();
        report("calendar: " + made[0].replace("\n", " "));
        return first.isEmpty() ? "Event created." : first;
    }

    // The button or Ctrl let go of, or this window left: the end of a turn only when it
    // is being held - hands-free, nothing is.
    private static void releaseHeld() {
        if (!handsFree) release();
    }

    // The lever moved - clicked, or Ctrl+Shift, or Ctrl alone. On, the conversation
    // starts - at once, or after the answer on its way. Off, it ends: the microphone is
    // muted and the answer being read out stops, from a thread of its own, since the
    // worker is inside the loop and the EDT must not call JRock. The mute is what brings
    // the loop's wait back.
    private static void setHandsFree(boolean on) {
        if (on == handsFree) return;
        handsFree = on;
        lever.repaint();
        if (on) {
            if (state != State.ANSWERING) converseNow();
        } else if (conversing) {
            Thread mute = new Thread(() -> {
                JRock.automationListen(false);
                JRock.automationStopNarration();
            }, "jrock-ptt-mute");
            mute.setDaemon(true);
            mute.start();
        }
    }

    // Ctrl+Shift, in either order: the lever on.
    private static void lockOn() {
        setHandsFree(true);
    }

    // Starts the conversation loop, unless it is running already. On the EDT.
    private static void converseNow() {
        if (conversing) return;
        conversing = true;
        setState(State.RECORDING, LISTENING_HANDS_FREE);
        WORKER.submit(JRockPushToTalk::converse);
    }

    // Hands-free: JRock listens, the agent sends what it heard, and round again - until
    // the lever goes off. A turn that fails does not end it: the reason is shown, and
    // after a moment JRock listens again, so the operator says it again - a Bluetooth
    // headset that dropped out for a moment is usually back by then. On the worker.
    private static void converse() {
        String said = null;
        try {
            // Ctrl, then Shift: the Ctrl started a held turn, which the lever replaces.
            if (recordingStarted) {
                recordingStarted = false;
                JRock.automationStopRecording(RECORDING_TIMEOUT_MS);
            }
            String note = null;
            String waiting = null;   // why there is nothing to listen on, while there is
            while (handsFree) {
                try {
                    // No microphone is not a turn and not a failure: the operator has
                    // unplugged it to be quiet (see MIC_WAIT_MS). Nothing is said, out
                    // loud or on screen, beyond the status line and one log line - and
                    // the next look finds it again when it is plugged back in.
                    String noMic = JRock.automationMicReady();
                    if (noMic != null) {
                        if (!noMic.equals(waiting)) {
                            report("not listening: " + noMic);
                            waiting = noMic;
                            later(() -> setState(State.IDLE, WAITING_FOR_MIC));
                        }
                        JRock.automationListen(false);
                        Thread.sleep(MIC_WAIT_MS);
                        continue;
                    }
                    if (waiting != null) {
                        report("the microphone is back - listening again.");
                        waiting = null;
                        note = null;
                    }
                    check(JRock.automationClearPrompt());
                    check(JRock.automationListen(true));
                    String listening = note == null ? LISTENING_HANDS_FREE
                            : note + " " + LISTENING_HANDS_FREE;
                    later(() -> setState(State.RECORDING, listening));
                    String[] heard;
                    do {
                        heard = JRock.automationAwaitHeard(LISTEN_POLL_MS);
                        // A microphone goes while nobody is speaking, which is exactly
                        // when it is unplugged - and a quiet room looks the same to
                        // awaitHeard either way, so this is the one place that can tell
                        // the two apart. Asked on every poll: automationMicReady reports
                        // what JRock's own watcher last saw and goes nowhere near the
                        // devices, so asking costs nothing.
                    } while (handsFree && !"1".equals(heard[0]) && heard[1] == null
                             && JRock.automationMicReady() == null);
                    // Muted while the answer is on its way, so the question is not asked twice.
                    JRock.automationListen(false);
                    if (!handsFree) break;   // ended: what was being said is not sent
                    if ("1".equals(heard[0])) {
                        // What was said HAS reached the prompt, so it is sent - whether
                        // the microphone is still there or not. That is the usual way
                        // round, not the exception: say it, then unplug so the answer
                        // being read out is not recorded back as the next question. A
                        // recording that was asked for and then thrown away would be the
                        // worst of both - the operator waits for an answer to something
                        // that is sitting in the prompt unsent.
                        note = answer(true);
                    } else if (JRock.automationMicReady() != null) {
                        // Nothing was heard and there is nothing to listen on: the
                        // gesture, not a failure. The top of the loop does the waiting.
                        continue;
                    } else {
                        throw new Stop(heard[1]);
                    }
                } catch (Stop failed) {
                    JRock.automationListen(false);
                    // Everything that gets here is a real failure, and every one of them
                    // is said out loud and written down. The quiet cases - no microphone,
                    // and nothing heard because of it - are taken above and never throw,
                    // which is what keeps an operator who has unplugged on purpose from
                    // being told off for it. A send that failed WHILE they were unplugged
                    // still has to be heard: that is the whole reason for the phrase.
                    report("that turn failed: " + failed.getMessage());
                    note = join("That did not work: " + failed.getMessage() + " Say it again.",
                            sayFailed());
                    String shown = note;
                    later(() -> setState(State.IDLE, shown));
                    for (long w = 0; handsFree && w < RETRY_PAUSE_MS; w += LISTEN_POLL_MS) {
                        Thread.sleep(LISTEN_POLL_MS);
                    }
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            said = "Hands-free ended: the agent is shutting down.";
        } catch (Stop stop) {
            JRock.automationListen(false);
            said = "Hands-free ended: " + stop.getMessage();
        }
        String reason = said;
        later(() -> {
            conversing = false;
            if (reason != null) {
                handsFree = false;
                lever.repaint();
            }
            setState(State.IDLE, reason == null ? READY : reason);
            if (handsFree) converseNow();   // turned off and on again in the meantime
        });
    }

    private static void setState(State next, String message) {
        state = next;
        button.stateChanged();
        status.setText(wrap(message));
    }

    // The answer to the last question, and nothing else: the whole conversation is in
    // JRock's own window. From the top, where reading starts.
    private static void showAnswer(String answer) {
        javax.swing.text.StyledDocument doc = answerArea.getStyledDocument();
        answerArea.setText("");
        try {
            doc.insertString(0, answer.trim(), answerArea.getStyle(ANSWER));
        } catch (javax.swing.text.BadLocationException impossible) {
            // Offset 0 of an empty document.
        }
        answerArea.setCaretPosition(0);
    }

    // ---- The look ----------------------------------------------------------
    // One quiet palette: a pale background, white paper for the answer, slate for the
    // button at rest (and the lever on), red while it listens, amber while the answer
    // is on its way.
    private static final java.awt.Color BACKGROUND = new java.awt.Color(0xF4F5F7);
    private static final java.awt.Color PAPER = java.awt.Color.WHITE;
    private static final java.awt.Color HAIRLINE = new java.awt.Color(0xDCDFE4);
    private static final java.awt.Color INK = new java.awt.Color(0x1F2328);
    private static final java.awt.Color MUTED = new java.awt.Color(0x6B7280);
    private static final java.awt.Color IDLE = new java.awt.Color(0x37474F);
    private static final java.awt.Color IDLE_HOVER = new java.awt.Color(0x455A64);
    private static final java.awt.Color LISTENING = new java.awt.Color(0xE53935);
    private static final java.awt.Color ANSWERING = new java.awt.Color(0xF59E0B);
    private static final java.awt.Color LEVER_OFF = new java.awt.Color(0xC4C9D1);

    private static final String ANSWER = "answer";

    // The status line wraps rather than widening the window for a long reason.
    private static String wrap(String text) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='text-align:center;width:300px'>" + safe + "</div></html>";
    }

    // ---- The window --------------------------------------------------------
    private static void showWindow() {
        window = new javax.swing.JFrame("JRock - Push to talk");
        // Closing this window ends the agent, not JRock: the window is handed back with
        // everything that was said in its transcript.
        window.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                setHandsFree(false);
                WORKER.submit(() -> {
                    JRock.automationStopNarration();
                    JRock.automationEnd("push-to-talk agent closed");
                });
            }
        });
        // Leaving the window with Ctrl held is a release this window never sees. With the
        // lever on nothing is being held, so the conversation goes on wherever the
        // focus goes.
        window.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) {
                ctrlHeld = false;
                shiftHeld = false;
                releaseHeld();
            }
        });

        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);

        button = new TalkButton();
        button.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

        status = new javax.swing.JLabel(wrap(READY), javax.swing.SwingConstants.CENTER);
        status.setFont(base.deriveFont(java.awt.Font.PLAIN, 12.5f));
        status.setForeground(MUTED);
        status.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 0, 18, 0));

        lever = new HandsFreeLever(base.deriveFont(java.awt.Font.PLAIN, 12.5f));
        lever.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

        javax.swing.JPanel top = new javax.swing.JPanel();
        top.setOpaque(false);
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        top.add(button);
        top.add(javax.swing.Box.createVerticalStrut(8));
        top.add(lever);
        top.add(status);

        answerArea = new javax.swing.JTextPane();
        answerArea.setEditable(false);
        answerArea.setFocusable(false);   // Ctrl stays the window's, never a text field's
        answerArea.setBackground(PAPER);
        answerArea.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));
        javax.swing.text.Style answer = answerArea.addStyle(ANSWER, null);
        javax.swing.text.StyleConstants.setFontFamily(answer, base.getFamily());
        javax.swing.text.StyleConstants.setFontSize(answer, 13);
        javax.swing.text.StyleConstants.setForeground(answer, INK);
        javax.swing.text.StyleConstants.setLineSpacing(answer, 0.15f);

        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(answerArea,
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(new RoundedBorder(HAIRLINE, 10));
        scroll.getViewport().setBackground(PAPER);
        scroll.setBackground(PAPER);
        scroll.setPreferredSize(new java.awt.Dimension(380, 280));

        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout());
        content.setBackground(BACKGROUND);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(24, 20, 20, 20));
        content.add(top, java.awt.BorderLayout.NORTH);
        content.add(scroll, java.awt.BorderLayout.CENTER);
        window.setContentPane(content);

        // Ctrl, held, as the keyboard's button - in this window only, so Ctrl+C in
        // JRock's is still a copy. Shift with it, in either order, is the lever on; Ctrl
        // alone with the lever on turns it off, and nothing is sent. The auto-repeat
        // of a held key comes as more presses, and only the first one counts.
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(e -> {
                    int key = e.getKeyCode();
                    if ((key != java.awt.event.KeyEvent.VK_CONTROL
                            && key != java.awt.event.KeyEvent.VK_SHIFT)
                            || e.getComponent() == null) return false;
                    java.awt.Window from = (e.getComponent() instanceof java.awt.Window)
                            ? (java.awt.Window) e.getComponent()
                            : javax.swing.SwingUtilities.getWindowAncestor(e.getComponent());
                    if (from != window) return false;
                    if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                        if (key == java.awt.event.KeyEvent.VK_SHIFT) {
                            if (!shiftHeld && ctrlHeld) lockOn();   // Ctrl, then Shift
                            shiftHeld = true;
                        } else if (!ctrlHeld) {
                            ctrlHeld = true;
                            if (e.isShiftDown()) lockOn();     // Shift, then Ctrl
                            else if (handsFree) setHandsFree(false);
                            else press();
                        }
                    } else if (e.getID() == java.awt.event.KeyEvent.KEY_RELEASED) {
                        if (key == java.awt.event.KeyEvent.VK_SHIFT) {
                            shiftHeld = false;
                        } else {
                            ctrlHeld = false;
                            releaseHeld();
                        }
                    }
                    return false;
                });

        window.pack();
        window.setMinimumSize(new java.awt.Dimension(WINDOW_MIN_W, WINDOW_MIN_H));
        // Beside JRock's window rather than on top of it, so both can be watched.
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
        window.toFront();
        window.requestFocus();
    }

    // A one-pixel hairline with rounded corners, the paper inside it clipped to match.
    private static final class RoundedBorder extends javax.swing.border.AbstractBorder {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        private final java.awt.Color color;
        private final int radius;

        RoundedBorder(java.awt.Color color, int radius) {
            this.color = color;
            this.radius = radius;
        }

        @Override public void paintBorder(java.awt.Component c, java.awt.Graphics g,
                                          int x, int y, int w, int h) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            // The corners outside the curve are the window's background, not paper.
            java.awt.geom.Area corners = new java.awt.geom.Area(
                    new java.awt.Rectangle(x, y, w, h));
            corners.subtract(new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(
                    x, y, w - 1, h - 1, radius * 2, radius * 2)));
            g2.setColor(BACKGROUND);
            g2.fill(corners);
            g2.setColor(color);
            g2.draw(new java.awt.geom.RoundRectangle2D.Float(
                    x, y, w - 1, h - 1, radius * 2, radius * 2));
            g2.dispose();
        }

        @Override public java.awt.Insets getBorderInsets(java.awt.Component c) {
            int inset = radius / 2 + 1;
            return new java.awt.Insets(inset, inset, inset, inset);
        }
    }

    // The push-to-talk button: a flat disc with a microphone drawn on it. Slate at rest
    // (a shade lighter under the mouse), red while listening with a soft ring pulsing
    // out from it, amber while the answer is on its way. Held with the mouse; let go of
    // anywhere - dragging off it before letting go still sends, as a walkie-talkie's
    // button would. With the lever on, JRock listens by itself, and a click ends that.
    private static final class TalkButton extends javax.swing.JComponent {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        private static final int SIZE = 112;
        private static final int HALO = 22;

        private boolean hover;
        private float pulse;   // 0..1, the ring's way out while listening
        private final javax.swing.Timer ticker = new javax.swing.Timer(33, e -> {
            pulse = (pulse + 0.03f) % 1f;
            repaint();
        });

        TalkButton() {
            setPreferredSize(new java.awt.Dimension(SIZE + 2 * HALO, SIZE + 2 * HALO));
            setMaximumSize(getPreferredSize());
            setFocusable(false);   // Ctrl goes to the window, not a button
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || !inside(e)) return;
                    if (handsFree) setHandsFree(false);
                    else press();
                }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isLeftMouseButton(e)) releaseHeld();
                }
                @Override public void mouseExited(java.awt.event.MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
            addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                    boolean now = inside(e);
                    if (now != hover) { hover = now; repaint(); }
                }
            });
        }

        void stateChanged() {
            if (state == State.RECORDING) {
                pulse = 0f;
                ticker.start();
            } else {
                ticker.stop();
            }
            repaint();
        }

        private boolean inside(java.awt.event.MouseEvent e) {
            double dx = e.getX() - getWidth() / 2.0, dy = e.getY() - getHeight() / 2.0;
            return dx * dx + dy * dy <= (SIZE / 2.0) * (SIZE / 2.0);
        }

        @Override protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL,
                    java.awt.RenderingHints.VALUE_STROKE_PURE);
            double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
            java.awt.Color fill = state == State.RECORDING ? LISTENING
                    : state == State.ANSWERING ? ANSWERING
                    : hover ? IDLE_HOVER : IDLE;

            // The ring, only while listening: out from the disc, fading as it goes.
            if (state == State.RECORDING) {
                double r = SIZE / 2.0 + HALO * pulse;
                int alpha = (int) (110 * (1f - pulse));
                g2.setColor(new java.awt.Color(fill.getRed(), fill.getGreen(), fill.getBlue(),
                        alpha));
                g2.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            }

            // A faint shadow under the disc, then the disc.
            g2.setColor(new java.awt.Color(0, 0, 0, 28));
            g2.fill(new java.awt.geom.Ellipse2D.Double(cx - SIZE / 2.0, cy - SIZE / 2.0 + 3,
                    SIZE, SIZE));
            g2.setColor(fill);
            g2.fill(new java.awt.geom.Ellipse2D.Double(cx - SIZE / 2.0, cy - SIZE / 2.0,
                    SIZE, SIZE));

            // The microphone: a capsule, the cradle under it, and its stand.
            g2.setColor(java.awt.Color.WHITE);
            double w = SIZE * 0.16, h = SIZE * 0.30;
            g2.fill(new java.awt.geom.RoundRectangle2D.Double(cx - w / 2, cy - h * 0.72,
                    w, h, w, w));
            g2.setStroke(new java.awt.BasicStroke((float) (SIZE * 0.035),
                    java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            double arc = SIZE * 0.27;
            g2.draw(new java.awt.geom.Arc2D.Double(cx - arc / 2, cy - h * 0.72 + h - arc / 2 - 2,
                    arc, arc * 0.9, 180, 180, java.awt.geom.Arc2D.OPEN));
            double base = cy - h * 0.72 + h - arc / 2 - 2 + arc * 0.9;
            g2.draw(new java.awt.geom.Line2D.Double(cx, base, cx, base + SIZE * 0.08));
            g2.draw(new java.awt.geom.Line2D.Double(cx - SIZE * 0.08, base + SIZE * 0.08,
                    cx + SIZE * 0.08, base + SIZE * 0.08));
            g2.dispose();
        }
    }

    // The hands-free lever: a switch - a pill with a knob, grey and to the left when off,
    // slate and to the right when on - with its name beside it. Anywhere on it toggles
    // it. The lock is drawn on the knob when it is on, since that is what it does to the
    // button.
    private static final class HandsFreeLever extends javax.swing.JComponent {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        private static final int TRACK_W = 38, TRACK_H = 22, GAP = 9;
        private static final String LABEL = "Hands-free (Ctrl+Shift)";

        HandsFreeLever(java.awt.Font font) {
            setFont(font);
            java.awt.FontMetrics fm = getFontMetrics(font);
            java.awt.Dimension size = new java.awt.Dimension(
                    TRACK_W + GAP + fm.stringWidth(LABEL), Math.max(TRACK_H, fm.getHeight()) + 2);
            setPreferredSize(size);
            setMaximumSize(size);
            setFocusable(false);   // Ctrl goes to the window, not the lever
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            setToolTipText("On: just talk - JRock hears you, and the agent sends what you "
                    + "said once you stop. Needs Mic always on ticked in JRock.");
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isLeftMouseButton(e)) setHandsFree(!handsFree);
                }
            });
        }

        @Override protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double y = (getHeight() - TRACK_H) / 2.0;
            g2.setColor(handsFree ? IDLE : LEVER_OFF);
            g2.fill(new java.awt.geom.RoundRectangle2D.Double(0, y, TRACK_W, TRACK_H,
                    TRACK_H, TRACK_H));
            double knob = TRACK_H - 6;
            double kx = handsFree ? TRACK_W - 3 - knob : 3;
            g2.setColor(java.awt.Color.WHITE);
            g2.fill(new java.awt.geom.Ellipse2D.Double(kx, y + 3, knob, knob));
            if (handsFree) {
                // A padlock, small: the shackle, then the body.
                double cx = kx + knob / 2, cy = y + 3 + knob / 2;
                g2.setColor(IDLE);
                g2.setStroke(new java.awt.BasicStroke(1.4f));
                g2.draw(new java.awt.geom.Arc2D.Double(cx - 2.6, cy - 5, 5.2, 6, 0, 180,
                        java.awt.geom.Arc2D.OPEN));
                g2.fill(new java.awt.geom.RoundRectangle2D.Double(cx - 3.8, cy - 1.5, 7.6, 5.5,
                        1.5, 1.5));
            }
            g2.setFont(getFont());
            g2.setColor(handsFree ? INK : MUTED);
            java.awt.FontMetrics fm = g2.getFontMetrics();
            g2.drawString(LABEL, TRACK_W + GAP,
                    (float) ((getHeight() - fm.getHeight()) / 2.0 + fm.getAscent()));
            g2.dispose();
        }
    }

    // ---- Plumbing ----------------------------------------------------------
    // What #now becomes: now, in English and in full, e.g. "Friday, 25 September 2026,
    // 10:01:34 Europe/Berlin (Central European Summer Time, CEST, UTC+02:00)". English
    // because it is the model that reads it, whatever the language of the question.
    private static String now() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        java.util.Locale en = java.util.Locale.ENGLISH;
        java.time.ZoneId zone = now.getZone();
        boolean summer = zone.getRules().isDaylightSavings(now.toInstant());
        java.util.TimeZone tz = java.util.TimeZone.getTimeZone(zone);
        String offset = now.getOffset().getTotalSeconds() == 0 ? "+00:00" : now.getOffset().getId();
        return now.format(java.time.format.DateTimeFormatter.ofPattern(
                        "EEEE, d MMMM yyyy, HH:mm:ss", en))
                + " " + zone.getId()
                + " (" + tz.getDisplayName(summer, java.util.TimeZone.LONG, en)
                + ", " + tz.getDisplayName(summer, java.util.TimeZone.SHORT, en)
                + ", UTC" + offset + ")";
    }

    // Turns an automation API result into a stop, a null meaning there is nothing wrong.
    private static void check(String problem) {
        if (problem != null) throw new Stop(problem);
    }

    // A file the agent cannot run without.
    private static Path mustExist(Path file) {
        if (!Files.isRegularFile(file)) throw new Stop("there is no " + file + ".");
        return file;
    }

    // The directory this source file sits in, which is where the appendix is looked for.
    // jdk.launcher.sourcefile is set by the launcher in source-file mode (`java
    // Foo.java`), which is how this is meant to be run; the code source covers a compiled
    // class, and the working directory is the last resort.
    private static Path ownDirectory() {
        String source = System.getProperty("jdk.launcher.sourcefile");
        if (source != null && !source.isBlank()) {
            Path parent = Paths.get(source).toAbsolutePath().normalize().getParent();
            if (parent != null) return parent;
        }
        try {
            java.security.CodeSource code =
                    JRockPushToTalk.class.getProtectionDomain().getCodeSource();
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

    // The folder this agent's files go in, made the first time something is written
    // into it - under the working folder, so each push-to-talk has its own, and with a
    // subfolder for the kinds that arrive in numbers (see FILES_DIR).
    private static Path filesDir(String sub) throws IOException {
        Path dir = Paths.get("").toAbsolutePath().resolve(FILES_DIR);
        if (sub != null) dir = dir.resolve(sub);
        Files.createDirectories(dir);
        return dir;
    }

    // A message file's text, or null when there is none to read.
    private static String read(String file) {
        if (file == null) return null;
        try {
            return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new Stop("could not read " + file + ": " + ex.getMessage());
        }
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
