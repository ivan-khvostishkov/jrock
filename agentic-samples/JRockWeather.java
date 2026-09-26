// A JRock automation: where to get the weather for a location - the address on
// open-meteo.com that has it, checked, for other agents to fetch.
//
// Run it the way JRock itself runs - no build step, no Maven, one file - with the jar on
// the class path:
//
//     java -cp jrock.jar JRockWeather.java [--working-dir <dir>]
//
// COPY jrock.jar HERE FIRST, take the flags as JRock's own, and expect a typed static
// call rather than reflection: all of that is JRockDocInventory.java's opening comment,
// and it is true of every automation in this directory. Read that file first.
//
//   1. JRock is started and taken as an automation: its prompt read-only and Mic always
//      on muted. This JRock is the agent's own - it asks the model for the address - so
//      run it in a folder of its own, with History off there. It needs a Bedrock API key.
//   2. A small window opens beside JRock's: what it has been asked, and what it answered.
//      A location typed into it and Get address asks the same way another agent would.
//   3. Other agents ask over TCP, on the loopback interface only: connect to
//      127.0.0.1:PORT, send a location and a newline, and read until the connection
//      closes. What comes back is one line: the address, or an empty line when there is
//      none - and this window's status line says why.
//
// The location is a line as the location agent (JRockLocation.java) sends it, and it has
// to have coordinates in it - the weather is for a point, not a name:
//
//     Munich, Bavaria, Lat: 48.13, Lon: 11.59
//
// The model is asked for the address (jrock-prompt-weather-url.txt, read again every
// time, with #lat, #lon and #place in it filled in). Building it - which service, which
// query string - is the model's to know. The address out of its reply must parse as a
// Java URL, be https, and point to open-meteo.com or one of its subdomains - on the
// default port, with no user name in it. Anything else is not answered with. Nothing is
// fetched here: that is the asking agent's to do.
//
// The address for a location is the same whatever the time - it is the weather at it
// that changes - so answers are kept for the run, and asking again is instant.
//
// The push-to-talk agent asks when the model's answer is "I need weather for this
// location: ...", and fetches the address into jrock-prompt-ptt-weather.txt for its own
// model to read out.

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JRockWeather {

    // JRock's agents' ports (see JRockTimer.java), and the weather agent's, the third of
    // them. Other agents connect to PORT.
    public static final int AGENT_PORTS_FIRST = 47470;
    public static final int PORT = AGENT_PORTS_FIRST + 2;

    // Starting a JVM's worth of Swing, on a cold machine.
    private static final long READY_TIMEOUT_MS = 120_000;

    // How long a client has to send its line, and the longest line taken.
    private static final int CLIENT_TIMEOUT_MS = 5000;
    private static final int LINE_MAX = 512;

    // How long the model may take, and an answer to a client.
    private static final long REPLY_TIMEOUT_MS = 75_000;
    private static final long ANSWER_TIMEOUT_MS = 90_000;

    // The prompt, expected beside this file.
    private static final String WEATHER_URL = "jrock-prompt-weather-url.txt";
    private static Path prompts;

    // The one site addresses are answered from.
    private static final String SITE = "open-meteo.com";

    // A location line as JRockLocation.java has it: the place, then the coordinates,
    // either way round, the labels short or long.
    private static final String NUMBER = "[-+]?\\d+(?:[.,]\\d+)?";
    private static final String LABEL = "(Lat(?:itude)?|Lon(?:gitude)?|Lng)";
    private static final Pattern LINE = Pattern.compile(
            "^(?:(.*?)\\s*,\\s*)?" + LABEL + "\\s*:\\s*(" + NUMBER + ")\\s*,\\s*"
            + LABEL + "\\s*:\\s*(" + NUMBER + ")\\s*$", Pattern.CASE_INSENSITIVE);

    // An address in the model's reply: the first thing that looks like one.
    private static final Pattern ADDRESS = Pattern.compile("https?://[^\\s<>\"'`()\\[\\]{}]+",
            Pattern.CASE_INSENSITIVE);

    // Why a step failed, in one sentence.
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        Stop(String why) { super(why); }
    }

    // Everything that asks JRock goes through this one thread: automation calls must not
    // overlap, and two clients asking at once are answered one after the other.
    private static final java.util.concurrent.ExecutorService RESOLVER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> daemon(r, "jrock-weather-resolver"));

    // Addresses found, for the run, by the coordinates they are for.
    private static final java.util.Map<String, String> cache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static javax.swing.JFrame window;
    private static javax.swing.JTextField locationField;
    private static javax.swing.JButton askButton;
    private static javax.swing.DefaultListModel<String> served;   // EDT only
    private static javax.swing.JLabel status;
    private static javax.swing.JLabel listening;
    private static volatile ServerSocket server;

    public static void main(String[] args) {
        String problem;
        try {
            prompts = ownDirectory();
            if (!Files.isRegularFile(prompts.resolve(WEATHER_URL))) {
                throw new Stop("there is no " + prompts.resolve(WEATHER_URL) + ".");
            }
            JRock.main(args);
            problem = JRock.automationAwaitReady(READY_TIMEOUT_MS);
            if (problem == null) problem = JRock.automationBegin("weather agent");
        } catch (Stop stop) {
            problem = stop.getMessage();
        }
        if (problem != null) {
            String why = problem;
            System.out.println("Stopped: " + why);
            onEdt(() -> javax.swing.JOptionPane.showMessageDialog(JRock.automationWindow(),
                    "The weather agent could not start:\n\n" + why,
                    "Weather", javax.swing.JOptionPane.ERROR_MESSAGE));
            return;
        }
        onEdt(JRockWeather::showWindow);
        String said = listen();
        later(() -> listening.setText(said));
        System.out.println("Weather is ready. " + said);
    }

    // ---- Answering -------------------------------------------------------------
    // The address for one location line, or "" - and the status line says why. On the
    // resolver.
    private static String answer(String request) {
        String line = request == null ? "" : request.trim();
        try {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) throw new Stop("\"" + line + "\" has no coordinates in it.");
            boolean firstIsLat = m.group(2).toLowerCase().startsWith("lat");
            if (firstIsLat == m.group(4).toLowerCase().startsWith("lat")) {
                throw new Stop("\"" + line + "\" has two latitudes or two longitudes.");
            }
            BigDecimal lat = number(firstIsLat ? m.group(3) : m.group(5));
            BigDecimal lon = number(firstIsLat ? m.group(5) : m.group(3));
            if (lat == null || lon == null || lat.abs().compareTo(BigDecimal.valueOf(90)) > 0
                    || lon.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
                throw new Stop("\"" + line + "\" is not a place on the Earth.");
            }
            String place = m.group(1) == null ? "" : m.group(1).trim();
            String key = lat.toPlainString() + "," + lon.toPlainString();
            String url = cache.get(key);
            if (url != null) {
                served(line, url, "from what was found before");
                return url;
            }
            later(() -> say("Asking the model for the address for " + line + "\u2026"));
            String reply = ask(prompt(WEATHER_URL)
                    .replace("#lat", lat.toPlainString())
                    .replace("#lon", lon.toPlainString())
                    .replace("#place", place.isEmpty() ? "(not named)" : place));
            url = address(reply);
            cache.put(key, url);
            served(line, url, null);
            return url;
        } catch (Stop stop) {
            later(() -> {
                say("No address for \"" + line + "\": " + stop.getMessage());
                served.add(0, line + "   ->   (none)");
            });
            return "";
        }
    }

    private static void served(String line, String url, String how) {
        later(() -> {
            served.add(0, line + "   ->   " + url);
            say("Answered " + line + (how == null ? "." : ", " + how + "."));
        });
    }

    private static BigDecimal number(String text) {
        try {
            return new BigDecimal(text.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // The address in the model's reply, if it is one this agent answers with (see
    // invalidAddress).
    private static String address(String reply) {
        Matcher m = ADDRESS.matcher(reply);
        if (!m.find()) throw new Stop("the model's reply has no address in it: \"" + reply + "\".");
        String url = m.group().replaceAll("[.,;:!?]+$", "");
        String why = invalidAddress(url);
        if (why != null) throw new Stop(url + " " + why);
        return url;
    }

    // Null when url is one to answer with: a Java URL, https, on open-meteo.com or one of
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

    // Asks this agent's JRock one question and returns the reply. On the resolver.
    private static String ask(String prompt) {
        check(JRock.automationClearPrompt());
        check(JRock.automationSetPrompt(prompt));
        String[] sent = JRock.automationSend(REPLY_TIMEOUT_MS);
        if (!"1".equals(sent[0])) throw new Stop("the model did not answer: " + sent[3]);
        String reply = read(JRock.automationMessageFile("assistant", sent[2]));
        if (reply == null) throw new Stop("the reply was not written to JRock/messages/.");
        return reply.trim();
    }

    private static String prompt(String name) {
        String text = read(prompts.resolve(name).toString());
        if (text == null) throw new Stop("there is no " + prompts.resolve(name) + ".");
        return text;
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
                        daemon(() -> serve(client), "jrock-weather-client").start();
                    } catch (IOException closed) {
                        // Closed with the window, or failed: either way, no more clients.
                        return;
                    }
                }
            }, "jrock-weather-server").start();
            return "Other agents ask on 127.0.0.1:" + PORT + ".";
        } catch (IOException ex) {
            return "Not listening on port " + PORT + " (" + ex.getMessage() + ") - is "
                    + "another weather agent running?";
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
                    later(() -> say("No address for \"" + line + "\" in time - answered with an empty line."));
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
    private static final java.awt.Color MUTED = new java.awt.Color(0x6B7280);

    private static void showWindow() {
        window = new javax.swing.JFrame("JRock - Weather");
        // Closing this window ends the agent, not JRock: no more clients.
        window.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                ServerSocket s = server;
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) { }
                }
                RESOLVER.submit(() -> JRock.automationEnd("weather agent closed"));
            }
        });

        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);
        java.awt.Font small = base.deriveFont(java.awt.Font.PLAIN, 12f);

        locationField = new javax.swing.JTextField("Munich, Bavaria, Lat: 48.13, Lon: 11.59", 28);
        locationField.setFont(base.deriveFont(java.awt.Font.PLAIN, 14f));
        locationField.setToolTipText("A location as the location agent gives it, with its coordinates");
        askButton = new javax.swing.JButton("Get address");
        Runnable askIt = () -> {
            String line = locationField.getText();
            askButton.setEnabled(false);
            RESOLVER.submit(() -> {
                try {
                    answer(line);
                } finally {
                    later(() -> askButton.setEnabled(true));
                }
            });
        };
        askButton.addActionListener(e -> askIt.run());
        locationField.addActionListener(e -> askIt.run());

        javax.swing.JPanel top = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(locationField, java.awt.BorderLayout.CENTER);
        top.add(askButton, java.awt.BorderLayout.EAST);

        status = new javax.swing.JLabel(" ");
        status.setFont(small);
        status.setForeground(MUTED);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 2, 10, 2));

        served = new javax.swing.DefaultListModel<>();
        javax.swing.JList<String> list = new javax.swing.JList<>(served);
        list.setFont(small);
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(list);
        scroll.setPreferredSize(new java.awt.Dimension(460, 180));

        listening = new javax.swing.JLabel(" ");
        listening.setFont(small);
        listening.setForeground(MUTED);
        listening.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 2, 0, 2));

        javax.swing.JPanel north = new javax.swing.JPanel(new java.awt.BorderLayout());
        north.setOpaque(false);
        north.add(top, java.awt.BorderLayout.NORTH);
        north.add(status, java.awt.BorderLayout.CENTER);

        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout());
        content.setBackground(BACKGROUND);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 12, 18));
        content.add(north, java.awt.BorderLayout.NORTH);
        content.add(scroll, java.awt.BorderLayout.CENTER);
        content.add(listening, java.awt.BorderLayout.SOUTH);
        window.setContentPane(content);

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
    }

    // The status line, wrapped rather than widening the window for a long message.
    private static void say(String text) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        status.setText("<html><div style='width:440px'>" + safe + "</div></html>");
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

    // The directory this source file sits in, which is where the prompt is looked for
    // (see JRockPushToTalk.ownDirectory).
    private static Path ownDirectory() {
        String source = System.getProperty("jdk.launcher.sourcefile");
        if (source != null && !source.isBlank()) {
            Path parent = Paths.get(source).toAbsolutePath().normalize().getParent();
            if (parent != null) return parent;
        }
        try {
            java.security.CodeSource code =
                    JRockWeather.class.getProtectionDomain().getCodeSource();
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
