import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the device-disconnect crash fix (Scanner.injectDestroyGuard) without any native code.
 *
 * It compiles a stand-in for AudioRelay's com.azefsw.audioconnect.audio.RtAudioJni that has the same
 * field and method names, but whose "native" methods just record what was called (destroy(long) blows
 * up when the stream is dead, like the real one does). The stand-in is patched exactly like the real
 * class and the three situations destroy() can run into are checked.
 *
 * With a real audiorelay.jar as the first argument it also patches the real class and makes the JVM
 * verify the result (natives are only bound when called, so loading it is safe).
 *
 * Run: see test/README.md. Needs --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED.
 */
public class DestroyGuardTest {
    static final String NAME = "com.azefsw.audioconnect.audio.RtAudioJni";

    static final String FAKE = String.join("\n",
        "package com.azefsw.audioconnect.audio;",
        "public final class RtAudioJni {",
        "  public static java.util.List<String> calls = new java.util.ArrayList<>();",
        "  public static boolean streamRunning = true;",
        "  public static boolean openFails = false;",
        "  public static boolean halfOpen = false;",
        "  private volatile long rtAudioPointer = -1L;",
        "  private volatile long callbackDataPointer = -1L;",
        "  public void init() { rtAudioPointer = 100L; }",
        "  public void open() { callbackDataPointer = openOutputStream(rtAudioPointer, 7); }",
        // like RtApiWasapi::probeDeviceOpen failing early: the native object is left half-initialized
        "  private long openOutputStream(long p, int x) { calls.add(\"openOutputStream(\" + p + \")\");",
        "    if (openFails) { halfOpen = true; throw new IllegalStateException(\"RtAudioException: device ID was not found\"); }",
        "    return 200L; }",
        "  private boolean isStreamRunning(long p) { calls.add(\"isStreamRunning(\" + p + \")\"); return streamRunning; }",
        "  private void destroy(long p) { calls.add(\"destroy(\" + p + \")\");",
        "    if ((!streamRunning && callbackDataPointer != -1L) || halfOpen) throw new Error(\"EXCEPTION_ACCESS_VIOLATION in native-rtaudio.dll\"); }",
        "  private void destroyOutputCallbackData(long p) { calls.add(\"destroyOutputCallbackData(\" + p + \")\"); }",
        "  public void destroy() {",
        "    if (rtAudioPointer != -1L) destroy(rtAudioPointer);",
        "    if (callbackDataPointer != -1L) destroyOutputCallbackData(callbackDataPointer);",
        "    rtAudioPointer = -1L; callbackDataPointer = -1L;",
        "  }",
        "  public long ptr() { return rtAudioPointer; }",
        "  public long cb() { return callbackDataPointer; }",
        "}");

    static int failures;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("destroyguard");
        Path src = dir.resolve("com/azefsw/audioconnect/audio/RtAudioJni.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, FAKE);
        javax.tools.JavaCompiler jc = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (jc.run(null, null, null, "-d", dir.toString(), src.toString()) != 0) throw new IllegalStateException("javac failed");
        byte[] original = Files.readAllBytes(dir.resolve("com/azefsw/audioconnect/audio/RtAudioJni.class"));

        Method inject = Class.forName("arautoconnect.Scanner").getMethod("injectDestroyGuard", byte[].class, String.class);
        byte[] patched = (byte[]) inject.invoke(null, original, "arautoconnect/Agent");

        // 1. device enumeration only: no stream was opened -> native teardown runs as before
        Object j = fresh(patched);
        call(j, "init");
        call(j, "destroy");
        expect(j, "enumeration-only instance", "[destroy(100)]", -1L, -1L);

        // 2. normal disconnect: stream still running -> native teardown runs as before
        j = fresh(patched);
        call(j, "init"); call(j, "open");
        call(j, "destroy");
        expect(j, "running stream", "[openOutputStream(100), isStreamRunning(100), destroy(100), destroyOutputCallbackData(200)]", -1L, -1L);

        // 3. the crash case: device disappeared, stream dead -> both native calls skipped, no crash
        j = fresh(patched);
        call(j, "init"); call(j, "open");
        j.getClass().getField("streamRunning").set(null, false);
        call(j, "destroy");
        expect(j, "dead stream (device removed)", "[openOutputStream(100), isStreamRunning(100)]", -1L, -1L);

        // 3b. and the unpatched class really does blow up in that case (so the test tests something)
        j = fresh(original);
        call(j, "init"); call(j, "open");
        j.getClass().getField("streamRunning").set(null, false);
        try {
            call(j, "destroy");
            fail("unpatched dead-stream destroy() did not fail");
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (!(e.getCause() instanceof Error)) fail("unexpected: " + e.getCause());
            else System.out.println("ok: unpatched class fails as expected: " + e.getCause().getMessage());
        }

        // 5. the second crash: AudioRelay reopens on the device that just vanished, the native open fails
        //    and leaves a half-initialized stream (callbackDataPointer stays -1) -> teardown skipped, no crash
        j = fresh(patched);
        j.getClass().getField("openFails").set(null, true);
        call(j, "init");
        try { call(j, "open"); fail("open did not fail"); } catch (java.lang.reflect.InvocationTargetException e) { /* expected */ }
        call(j, "destroy");
        expect(j, "failed open (device vanished during reopen)", "[openOutputStream(100)]", -1L, -1L);

        // 5b. the unpatched class crashes there
        j = fresh(original);
        j.getClass().getField("openFails").set(null, true);
        call(j, "init");
        try { call(j, "open"); } catch (java.lang.reflect.InvocationTargetException e) { /* expected */ }
        try {
            call(j, "destroy");
            fail("unpatched failed-open destroy() did not fail");
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.out.println("ok: unpatched class fails after a failed open: " + e.getCause().getMessage());
        }

        // 6. the same instance used again after a skipped teardown tears down normally (flags are reset)
        j = fresh(patched);
        j.getClass().getField("openFails").set(null, true);
        call(j, "init");
        try { call(j, "open"); } catch (java.lang.reflect.InvocationTargetException e) { /* expected */ }
        call(j, "destroy");
        j.getClass().getField("openFails").set(null, false);
        j.getClass().getField("halfOpen").set(null, false);
        j.getClass().getField("calls").set(null, new ArrayList<String>());
        call(j, "init"); call(j, "open");
        call(j, "destroy");
        expect(j, "reused after failed open", "[openOutputStream(100), isStreamRunning(100), destroy(100), destroyOutputCallbackData(200)]", -1L, -1L);

        // 7. a dead stream followed by a normal session on the same instance also tears down fully
        j = fresh(patched);
        call(j, "init"); call(j, "open");
        j.getClass().getField("streamRunning").set(null, false);
        call(j, "destroy");
        j.getClass().getField("streamRunning").set(null, true);
        j.getClass().getField("calls").set(null, new ArrayList<String>());
        call(j, "init"); call(j, "open");
        call(j, "destroy");
        expect(j, "reused after dead stream", "[openOutputStream(100), isStreamRunning(100), destroy(100), destroyOutputCallbackData(200)]", -1L, -1L);

        // 4. optional: patch the real class from an AudioRelay jar and let the JVM verify it
        if (args.length > 0) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(args[0])) {
                java.util.zip.ZipEntry e = zf.getEntry(NAME.replace('.', '/') + ".class");
                if (e == null) throw new IllegalStateException("RtAudioJni not in " + args[0]);
                byte[] real = zf.getInputStream(e).readAllBytes();
                byte[] realPatched = (byte[]) inject.invoke(null, real, "arautoconnect/Agent");
                // the jar supplies the types the real class refers to; initializing the class runs the verifier
                // (RtAudioJni has no static initializer, and natives are bound only when first called)
                ClassLoader jarLoader = new java.net.URLClassLoader(new java.net.URL[] { Path.of(args[0]).toUri().toURL() },
                        DestroyGuardTest.class.getClassLoader());
                Class<?> c = Class.forName(NAME, true, new Loader(realPatched, NAME, jarLoader));
                int priv = 0;
                for (Method m : c.getDeclaredMethods()) if (m.getName().startsWith("ar")) priv++;
                if (priv != 4) fail("expected 4 helper methods in real class (arDestroy, arDestroyCallbackData,"
                        + " arOpenOutputStream, arOpenInputStream), found " + priv);
                c.getDeclaredField("arDeadStream");
                c.getDeclaredField("arOpenFailed");
                System.out.println("ok: real RtAudioJni from " + args[0] + " patched and verified (" + realPatched.length + " bytes)");
            }
        }

        if (failures > 0) { System.out.println(failures + " FAILED"); System.exit(1); }
        System.out.println("DestroyGuardTest: all passed");
    }

    static Object fresh(byte[] bytes) throws Exception {
        Class<?> c = new Loader(bytes, NAME).loadClass(NAME);
        c.getField("calls").set(null, new ArrayList<String>());
        c.getField("streamRunning").set(null, true);
        c.getField("openFails").set(null, false);
        c.getField("halfOpen").set(null, false);
        return c.getConstructor().newInstance();
    }

    static void call(Object o, String m) throws Exception {
        o.getClass().getMethod(m).invoke(o);
    }

    @SuppressWarnings("unchecked")
    static void expect(Object j, String what, String calls, long ptr, long cb) throws Exception {
        List<String> got = (List<String>) j.getClass().getField("calls").get(null);
        long p = (Long) j.getClass().getMethod("ptr").invoke(j);
        long c = (Long) j.getClass().getMethod("cb").invoke(j);
        if (!got.toString().equals(calls) || p != ptr || c != cb) {
            fail(what + ": calls=" + got + " ptr=" + p + " cb=" + c + " (expected " + calls + ", " + ptr + ", " + cb + ")");
        } else {
            System.out.println("ok: " + what + ": " + got);
        }
    }

    static void fail(String s) { failures++; System.out.println("FAIL: " + s); }

    /** Loads one class from bytes; verification is on (-Xverify default verifies non-bootstrap classes). */
    static final class Loader extends ClassLoader {
        final byte[] bytes; final String name;
        Loader(byte[] bytes, String name) { this(bytes, name, DestroyGuardTest.class.getClassLoader()); }
        Loader(byte[] bytes, String name, ClassLoader parent) { super(parent); this.bytes = bytes; this.name = name; }
        @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
            if (n.equals(name)) return defineClass(n, bytes, 0, bytes.length);
            throw new ClassNotFoundException(n);
        }
        @Override protected Class<?> loadClass(String n, boolean r) throws ClassNotFoundException {
            if (n.equals(name)) { Class<?> c = findLoadedClass(n); return c != null ? c : findClass(n); }
            return super.loadClass(n, r);
        }
    }
}
