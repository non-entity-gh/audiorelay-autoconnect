package arautoconnect;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AudioRelay Auto-Connect: a Java agent that makes the AudioRelay desktop Player connect to a fixed
 * server automatically, exactly as if you had typed the address under "Connect by address" and
 * clicked Connect.
 *
 * Agent options (comma separated), e.g. -javaagent:audiorelay-autoconnect.jar=host=192.168.1.50
 *   host=ADDRESS          IPv4/IPv6 address or hostname of the AudioRelay server (required)
 *   persistent=true|false reconnect after the connection drops (default true)
 *   interval=SECONDS      first retry delay; doubles on each failure up to 60 s (default 10)
 *   delay=SECONDS         wait after launch before the first attempt (default 3)
 *
 * When it reconnects, and when it deliberately doesn't:
 *   - Connection error / dropped connection (server rebooted, slept, network blip): reconnects.
 *   - You clicked Disconnect: pauses until you connect again yourself.
 *   - The server ended the session on purpose (its user disconnected you, or a premium limit such as
 *     multi-device or session duration): pauses. This add-on never works around AudioRelay's limits.
 *
 * Safety properties:
 *   - Does nothing unless the JVM is running AudioRelay (audiorelay.jar on the class path).
 *   - Every failure is caught and logged; AudioRelay keeps working normally without auto-connect.
 *   - No network access of its own: it only asks AudioRelay to connect to the address you configured.
 */
public final class Agent {
    public static final String VERSION = "1.0.0";
    private static final String SELF = "arautoconnect/Agent";
    /** IPv4, IPv6 (optionally [bracketed] or with %zone) or hostname; must not start with '-'. */
    static final Pattern HOST_OK = Pattern.compile("[A-Za-z0-9\\[][A-Za-z0-9._:%\\[\\]-]{0,252}");
    private static final Pattern ENDPOINT = Pattern.compile("initialAddress=([^,)\\s]+)");
    private static final long MAX_BACKOFF_MS = 60_000;
    private static final long MAX_LOG_BYTES = 512 * 1024;

    static String host;
    static boolean persistent = true;
    static long intervalMs = 10_000;
    static long delayMs = 3_000;

    private static volatile String holderClass;      // internal names found by Scanner
    private static volatile String playerFieldName;
    private static volatile String eventClass;
    private static volatile String stateBaseClass;
    private static final Set<String> machineClasses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean started;
    private static PrintWriter log;

    // Written by onState() on AudioRelay's thread, read by our thread.
    private static final Object EVENTS = new Object();
    private static final List<String[]> pending = new ArrayList<>();   // {kind, detail, toString}

    public static void premain(String args, Instrumentation inst) {
        String appJar = findAudioRelayJar();
        if (appJar == null) return;   // not AudioRelay: stay completely inert

        openLog();
        try {
            log("=== AudioRelay Auto-Connect " + VERSION + " | AudioRelay " + System.getProperty("jpackage.app-version")
                    + " | Java " + System.getProperty("java.version") + " | " + System.getProperty("os.name")
                    + " | pid " + ProcessHandle.current().pid());
            parseArgs(args);
            if (host == null) {
                log("No valid host given (expected e.g. host=192.168.1.50). Auto-connect disabled.");
                return;
            }
            log("Target " + host + ", persistent=" + persistent + ", interval=" + intervalMs / 1000 + "s");

            // Let our Scanner class use the JDK's internal ASM (java.base doesn't export it by default).
            Module base = Object.class.getModule();
            inst.redefineModule(base, Set.of(), Map.of("jdk.internal.org.objectweb.asm", Set.of(Agent.class.getModule())),
                    Map.of(), Set.of(), Map.of());

            // Scanner is loaded via reflection only after the export, so a problem there can't block startup.
            Class<?> scanner = Class.forName("arautoconnect.Scanner");
            long t0 = System.nanoTime();
            String[] found = (String[]) scanner.getMethod("scan", String.class).invoke(null, appJar);
            holderClass = found[0];
            playerFieldName = found[1];
            eventClass = found[2];
            stateBaseClass = found[3];
            log("Located app internals in " + (System.nanoTime() - t0) / 1_000_000 + " ms: service=" + holderClass
                    + " player=" + playerFieldName + " event=" + eventClass + " state=" + stateBaseClass);

            final Method injectCtor = scanner.getMethod("injectConstructorHook", byte[].class, String.class);
            final Method injectState = scanner.getMethod("injectStateHook", byte[].class, String.class, String.class);
            final Method isStateWriter = scanner.getMethod("isStateWriter", byte[].class, String.class);
            inst.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(Module module, ClassLoader loader, String className,
                                        Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
                    // only AudioRelay's own (default-package) classes
                    if (redefined != null || className == null || className.indexOf('/') >= 0) return null;
                    try {
                        if (className.equals(holderClass)) {
                            byte[] out = (byte[]) injectCtor.invoke(null, bytes, SELF);
                            log("Hook installed in " + className + " (startup service).");
                            return out;
                        }
                        if ((Boolean) isStateWriter.invoke(null, bytes, stateBaseClass)) {
                            byte[] out = (byte[]) injectState.invoke(null, bytes, SELF, stateBaseClass);
                            machineClasses.add(className);
                            log("Hook installed in " + className + " (player state).");
                            return out;
                        }
                    } catch (Throwable t) {
                        log("Could not install hook in " + className + ": " + t);
                    }
                    return null;
                }
            });
        } catch (Throwable t) {
            log("Auto-connect disabled: " + describe(t));
        }
    }

    /** Called from the hooked startup-service constructor. Must never throw into AudioRelay. */
    public static void onBusHolder(Object holder) {
        try {
            if (started) return;
            started = true;
            Thread t = new Thread(() -> run(holder), "audiorelay-autoconnect");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            log("Could not start: " + t);
        }
    }

    /** Called by AudioRelay's state machine on every Player state change. Must be fast and never throw. */
    public static void onState(Object state) {
        try {
            if (state == null) return;
            String s = state.toString();
            String kind = stateKind(s);
            String detail = kind.equals("Stopping") ? stopReason(state) : kind.equals("Running") ? endpoint(s) : null;
            synchronized (EVENTS) {
                if (pending.size() < 1000) pending.add(new String[] { kind, detail, s });
                EVENTS.notifyAll();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void run(Object holder) {
        try {
            ClassLoader cl = holder.getClass().getClassLoader();
            Object[] parts = null;
            // Fields may be filled in after the constructor finishes, so retry for a while.
            for (int i = 0; parts == null; i++) {
                try {
                    parts = locate(holder, cl);
                } catch (Exception e) {
                    if (i >= 59) throw e;
                    Thread.sleep(1000);
                }
            }
            Object subject = parts[0];
            Method onNext = (Method) parts[1];
            Constructor<?> eventCtor = (Constructor<?>) parts[2];
            Object machine = parts[3];
            Field stateField = (Field) parts[4];
            log("Ready.");

            Thread.sleep(delayMs);

            boolean armed = true;          // false after a manual or server-side disconnect
            boolean everConnected = false;
            boolean attemptPending = false;
            String target = host;          // reconnect to the server we were last connected to
            long backoff = intervalMs;
            long nextAttempt = 0;
            String lastKind = stateKind(String.valueOf(stateField.get(machine)));
            log("Player: " + lastKind);

            while (true) {
                List<String[]> events;
                synchronized (EVENTS) {
                    if (pending.isEmpty()) EVENTS.wait(250);
                    events = new ArrayList<>(pending);
                    pending.clear();
                }
                for (String[] ev : events) {
                    String kind = ev[0];
                    if (kind.equals(lastKind) && !kind.equals("Stopping")) continue;
                    lastKind = kind;
                    log("Player: " + (kind.equals("Idle") ? "Idle" : ev[2]));
                    if (kind.equals("Running")) {
                        everConnected = true;
                        armed = true;      // any successful connection (even a manual one) re-arms
                        attemptPending = false;
                        backoff = intervalMs;
                        if (ev[1] != null && HOST_OK.matcher(ev[1]).matches()) target = ev[1];
                    } else if (kind.equals("Stopping")) {
                        String reason = ev[1];
                        if ("user_requested".equals(reason)) {
                            armed = false;
                            log("You disconnected, so auto-connect is paused until you connect again.");
                        } else if (reason != null && !reason.equals("error")) {
                            armed = false;
                            log("The server ended the session (" + reason + "), so auto-connect is paused"
                                    + " until you connect again.");
                        } else if (!persistent && everConnected) {
                            armed = false;
                            log("Connection lost; persistent=false, so not reconnecting.");
                        }
                    }
                }

                // Read the field directly too, in case a transition happened without the hook.
                String kind = stateKind(String.valueOf(stateField.get(machine)));
                long now = System.currentTimeMillis();
                boolean wanted = armed && (persistent || !everConnected);
                if (kind.equals("Idle") && lastKind.equals("Idle") && wanted && now >= nextAttempt) {
                    if (attemptPending) backoff = Math.min(backoff * 2, Math.max(MAX_BACKOFF_MS, intervalMs));
                    log("Connecting to " + target + " (next retry in " + backoff / 1000 + " s if it fails)");
                    onNext.invoke(subject, eventCtor.newInstance(target));
                    attemptPending = true;
                    nextAttempt = now + backoff;
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            log("Auto-connect stopped: " + describe(t));
        }
    }

    /** Returns {subject, onNext, eventCtor, machine, stateField}. */
    private static Object[] locate(Object holder, ClassLoader cl) throws Exception {
        // the startup service has exactly two reference fields: the player manager and the event bus
        Object player = null, bus = null;
        for (Field f : instanceFields(holder.getClass())) {
            f.setAccessible(true);
            if (f.getName().equals(playerFieldName)) player = f.get(holder);
            else if (bus == null) bus = f.get(holder);
            else throw new IllegalStateException("unexpected extra field in startup service");
        }
        if (player == null || bus == null) throw new IllegalStateException("startup service fields not set");

        Object[] subj = findSubject(bus);

        Class<?> stateBase = Class.forName(stateBaseClass.replace('/', '.'), false, cl);
        for (Field f : instanceFields(player.getClass())) {
            f.setAccessible(true);
            Object v = f.get(player);
            if (v == null || !machineClasses.contains(v.getClass().getName().replace('.', '/'))) continue;
            for (Field g : instanceFields(v.getClass())) {
                if (g.getType() == stateBase) {
                    g.setAccessible(true);
                    Constructor<?> ctor = Class.forName(eventClass.replace('/', '.'), false, cl).getConstructor(String.class);
                    return new Object[] { subj[0], subj[1], ctor, v, g };
                }
            }
        }
        throw new IllegalStateException("player state not found");
    }

    /** The bus wraps an Rx subject; find it and its single public void (Object) method (onNext). */
    private static Object[] findSubject(Object bus) throws ReflectiveOperationException {
        for (Field f : instanceFields(bus.getClass())) {
            f.setAccessible(true);
            Object v = f.get(bus);
            if (v == null) continue;
            List<Method> cands = new ArrayList<>();
            for (Method m : v.getClass().getMethods()) {
                if (m.getDeclaringClass() == Object.class || Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() == void.class && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Object.class) {
                    if (cands.stream().noneMatch(c -> c.getName().equals(m.getName()))) cands.add(m);
                }
            }
            if (cands.size() == 1) {
                Method m = cands.get(0);
                m.setAccessible(true);
                return new Object[] { v, m };
            }
        }
        throw new IllegalStateException("event bus not recognised");
    }

    private static List<Field> instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()) out.add(f);
            }
        }
        return out;
    }

    static String stateKind(String s) {
        if (s == null) return "Idle";
        if (s.startsWith("Running(")) return "Running";
        if (s.startsWith("Connecting(")) return "Connecting";
        if (s.startsWith("RetryConnecting(")) return "RetryConnecting";
        if (s.startsWith("Stopping(")) return "Stopping";
        return "Idle";   // the idle state is a singleton without a custom toString()
    }

    static String endpoint(String runningToString) {
        Matcher m = ENDPOINT.matcher(runningToString);
        return m.find() ? m.group(1) : null;
    }

    /** Stopping(reason=<obj>, ...): the reason object holds a code such as "user_requested" or "error". */
    private static String stopReason(Object stopping) {
        try {
            for (Field f : instanceFields(stopping.getClass())) {
                f.setAccessible(true);
                Object reason = f.get(stopping);
                if (reason == null) continue;
                for (Field g : instanceFields(reason.getClass())) {
                    if (g.getType() == String.class) { g.setAccessible(true); return (String) g.get(reason); }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String findAudioRelayJar() {
        String cp = System.getProperty("java.class.path", "");
        for (String p : cp.split(Pattern.quote(File.pathSeparator))) {
            File f = new File(p);
            if (f.getName().equalsIgnoreCase("audiorelay.jar") && f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    static void parseArgs(String args) {
        if (args == null) return;
        for (String part : args.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            String k = eq < 0 ? "host" : p.substring(0, eq).trim().toLowerCase();
            String v = eq < 0 ? p : p.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "host":
                        if (HOST_OK.matcher(v).matches()) host = v;
                        else log("Ignoring invalid host value.");
                        break;
                    case "persistent": persistent = !v.equalsIgnoreCase("false"); break;
                    case "interval": intervalMs = Math.min(3600, Math.max(2, Long.parseLong(v))) * 1000; break;
                    case "delay": delayMs = Math.min(600, Math.max(0, Long.parseLong(v))) * 1000; break;
                    default: log("Ignoring unknown option '" + k + "'.");
                }
            } catch (NumberFormatException e) {
                log("Ignoring invalid number for '" + k + "'.");
            }
        }
    }

    /** Windows: %TEMP%; macOS: ~/Library/Logs; Linux: $XDG_STATE_HOME or ~/.local/state (Flatpak: the app's own dir). */
    static File logDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) return new File(System.getProperty("java.io.tmpdir"));
        if (os.contains("mac")) return new File(home, "Library/Logs");
        String xdg = System.getenv("XDG_STATE_HOME");
        if (xdg != null && !xdg.isEmpty()) return new File(xdg);
        String flatpak = System.getenv("FLATPAK_ID");
        if (flatpak != null && flatpak.matches("[A-Za-z0-9._-]+")) return new File(home, ".var/app/" + flatpak + "/.local/state");
        return new File(home, ".local/state");
    }

    private static void openLog() {
        try {
            File dir = logDir();
            if (!dir.isDirectory() && !dir.mkdirs()) return;   // no file log rather than a shared temp dir
            Path p = new File(dir, "audiorelay-autoconnect.log").toPath();
            boolean big = Files.exists(p, LinkOption.NOFOLLOW_LINKS) && Files.size(p) > MAX_LOG_BYTES;
            OutputStream os = Files.newOutputStream(p, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, big ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND);
            log = new PrintWriter(new java.io.OutputStreamWriter(os, StandardCharsets.UTF_8), true);
        } catch (Throwable ignored) {
        }
    }

    private static String describe(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c != c.getCause()) c = c.getCause();
        return c + " (AudioRelay may have changed internally; please report it with this log)";
    }

    static synchronized void log(String msg) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " [autoconnect] " + msg;
        System.out.println(line);
        if (log != null) log.println(line);
    }
}
