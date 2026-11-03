import java.lang.reflect.*;
import java.util.*;
import java.util.zip.*;

/**
 * End-to-end check against a real audiorelay.jar (not included in this repo). Builds the app's real startup
 * service, event bus and Player state machine, attaches the agent, then drives the state machine through its
 * own setter (so the agent's hooks fire exactly as in the app) and counts the connect events the agent posts.
 * Usage: java -cp test:/path/audiorelay.jar -javaagent:dist/audiorelay-autoconnect.jar=host=192.168.1.50,interval=2,delay=1 E2E /path/audiorelay.jar
 */
public class E2E {
    static sun.misc.Unsafe U;
    static ClassLoader L = E2E.class.getClassLoader();
    static Class<?> c(String n) throws Exception { return Class.forName(n.replace('/', '.'), false, L); }
    static Object machine; static Method setter; static List<Object> got = new java.util.concurrent.CopyOnWriteArrayList<>();
    static Map<String, Object> reasons = new HashMap<>();
    static Class<?> runningC, stoppingC; static Object IDLE;

    static void state(Object s) throws Exception { setter.invoke(machine, s); }
    static Object stopping(String reason) throws Exception {
        Object st = U.allocateInstance(stoppingC);
        for (Field f : stoppingC.getDeclaredFields()) if (!f.getType().isPrimitive() && !Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); f.set(st, reasons.get(reason)); }
        return st;
    }
    static Object running(String addr) throws Exception {
        Object r = U.allocateInstance(runningC);
        for (Field f : runningC.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()) {
            Object ep = f.getType().getDeclaredConstructor(String.class).newInstance(addr); f.setAccessible(true); f.set(r, ep); }
        return r;
    }
    /** A drop/disconnect sequence the way AudioRelay does it: Running -> Stopping -> Idle with no pause. */
    static void cycle(String reason) throws Exception { state(running("10.9.9.9")); Thread.sleep(300); state(stopping(reason)); state(IDLE); }

    public static void main(String[] a) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true); U = (sun.misc.Unsafe) uf.get(null);
        String jar = a[0];
        Class<?> agent = Class.forName("arautoconnect.Agent");
        // wait until the agent's premain finished scanning
        Field hf = agent.getDeclaredField("holderClass"); hf.setAccessible(true);
        Field pf0 = agent.getDeclaredField("playerFieldName"); pf0.setAccessible(true);
        Field sb = agent.getDeclaredField("stateBaseClass"); sb.setAccessible(true);
        Field mc = agent.getDeclaredField("machineClasses"); mc.setAccessible(true);
        Class<?> H = c((String) hf.get(null)), W = c((String) sb.get(null));
        @SuppressWarnings("unchecked") Set<String> machines = (Set<String>) mc.get(null);

        Field pf = H.getDeclaredField((String) pf0.get(null)); Field bf = null;
        for (Field f : H.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers()) && !f.equals(pf)) bf = f;
        Object bus = bf.getType().getDeclaredConstructor().newInstance();

        Class<?> impl = null, idleC = null;
        try (ZipFile z = new ZipFile(jar)) {
            for (ZipEntry e : Collections.list(z.entries())) {
                String n = e.getName(); if (n.contains("/") || !n.endsWith(".class")) continue;
                String s = new String(z.getInputStream(e).readAllBytes(), "ISO-8859-1");
                Class<?> k; try { k = c(n.substring(0, n.length() - 6)); } catch (Throwable t) { continue; }
                try {
                    if (pf.getType().isAssignableFrom(k) && !k.isInterface()) impl = k;
                    if (k.getSuperclass() == W) for (Field f : k.getDeclaredFields()) if (Modifier.isStatic(f.getModifiers()) && f.getType() == k) idleC = k;
                } catch (Throwable t) { }
                if (s.contains("Running(endpoint=")) runningC = k;
                if (s.contains("Stopping(reason=")) stoppingC = k;
                for (String r : new String[] { "error", "user_requested", "user_requested_from_server", "premium_needed_for_multi", "unknown" }) {
                    if (!s.contains(r)) continue;
                    try { Object o = k.getDeclaredConstructor().newInstance();
                        for (Class<?> kk = k; kk != Object.class; kk = kk.getSuperclass()) for (Field f : kk.getDeclaredFields())
                            if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); if (r.equals(f.get(o))) reasons.put(r, o); }
                    } catch (Throwable t) { }
                }
            }
        }
        for (Field f : idleC.getDeclaredFields()) if (f.getType() == idleC) { f.setAccessible(true); IDLE = f.get(null); }
        // player impl with a real state machine inside
        Object player = U.allocateInstance(impl);
        for (Field f : impl.getDeclaredFields()) if (machines.contains(f.getType().getName())) {
            machine = f.getType().getDeclaredConstructor().newInstance(); f.setAccessible(true); f.set(player, machine); }
        for (Method m : machine.getClass().getDeclaredMethods())
            if (m.getReturnType() == void.class && m.getParameterCount() == 1 && m.getParameterTypes()[0] == W) setter = m;
        setter.setAccessible(true);
        System.out.println("machine=" + machine.getClass().getName() + " setter=" + setter.getName() + " reasons=" + reasons.keySet());

        // count connect events posted on the bus
        Object subject = null;
        for (Class<?> k = bus.getClass(); k != Object.class; k = k.getSuperclass()) for (Field f : k.getDeclaredFields()) { f.setAccessible(true); if (f.get(bus) != null) subject = f.get(bus); }
        Method sub = null;
        for (Method m : subject.getClass().getMethods()) if (m.getParameterCount() == 1 && m.getReturnType() == void.class && m.getParameterTypes()[0].isInterface() && m.getParameterTypes()[0].getMethods().length == 4) sub = m;
        Field ef = agent.getDeclaredField("eventClass"); ef.setAccessible(true);
        Class<?> ev = c((String) ef.get(null));
        sub.invoke(subject, Proxy.newProxyInstance(L, new Class[] { sub.getParameterTypes()[0] }, (pr, m, ar) -> {
            if (ar != null && ar.length == 1 && ev.isInstance(ar[0])) { System.out.println("  >> EVENT " + ar[0]); got.add(ar[0]); }
            return null; }));

        state(IDLE);
        Constructor<?> hc = H.getDeclaredConstructors()[0]; Object[] args = new Object[2];
        Class<?>[] pt = hc.getParameterTypes(); for (int i = 0; i < 2; i++) args[i] = pt[i].isInstance(bus) ? bus : player;
        hc.newInstance(args);

        int fails = 0;
        Thread.sleep(2500);
        fails += expect("connects at launch", got.size() >= 1);
        int n = got.size(); cycle("error"); Thread.sleep(2500);
        fails += expect("reconnects after a drop (error), to last server", got.size() > n && got.get(got.size() - 1).toString().contains("10.9.9.9"));
        n = got.size(); cycle("user_requested"); Thread.sleep(3000);
        fails += expect("manual Disconnect pauses (fast Stopping->Idle)", got.size() == n);
        state(running("10.9.9.9")); Thread.sleep(300); state(IDLE);  // user reconnects manually -> re-armed
        n = got.size(); cycle("premium_needed_for_multi"); Thread.sleep(3000);
        fails += expect("server premium limit pauses", got.size() == n);
        n = got.size(); cycle("user_requested_from_server"); Thread.sleep(3000);
        fails += expect("server-side disconnect pauses", got.size() == n);
        n = got.size(); cycle("unknown"); Thread.sleep(3000);
        fails += expect("unknown server reason (e.g. time limit) pauses", got.size() == n);
        n = got.size(); cycle("error"); Thread.sleep(2500);
        fails += expect("re-armed by a manual connect, reconnects after error", got.size() > n);
        System.out.println(fails == 0 ? "RESULT PASS" : "RESULT FAIL (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }

    static int expect(String what, boolean ok) { System.out.println((ok ? "  ok   " : "  FAIL ") + what); return ok ? 0 : 1; }
}
