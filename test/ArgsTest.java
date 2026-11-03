import java.lang.reflect.Field;

/** Tests option parsing/validation. Uses reflection on the package-private parser. */
public class ArgsTest {
    static int fails = 0;
    static Object get(String name) throws Exception {
        Field f = Class.forName("arautoconnect.Agent").getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }
    static void reset() throws Exception {
        for (String[] kv : new String[][]{{"host", null}}) { Field f = Class.forName("arautoconnect.Agent").getDeclaredField(kv[0]); f.setAccessible(true); f.set(null, kv[1]); }
    }
    static void check(String args, String expectHost) throws Exception {
        reset();
        java.lang.reflect.Method m = Class.forName("arautoconnect.Agent").getDeclaredMethod("parseArgs", String.class);
        m.setAccessible(true); m.invoke(null, args);
        Object h = get("host");
        boolean ok = (expectHost == null) ? h == null : expectHost.equals(h);
        System.out.println((ok ? "ok   " : "FAIL ") + "'" + args + "' -> " + h);
        if (!ok) fails++;
    }
    static void endpointCheck(String in, String want) throws Exception {
        java.lang.reflect.Method m = Class.forName("arautoconnect.Agent").getDeclaredMethod("endpoint", String.class);
        m.setAccessible(true); Object got = m.invoke(null, in);
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "ok   " : "FAIL ") + "endpoint(" + in + ") -> " + got);
        if (!ok) fails++;
    }
    public static void main(String[] a) throws Exception {
        check("host=192.168.1.50", "192.168.1.50");
        check("192.168.1.50", "192.168.1.50");
        check("host=my-pc.local,persistent=false,interval=5", "my-pc.local");
        check("host=fe80::1%eth0", "fe80::1%eth0");
        check("host=[::1]", "[::1]");
        check("host=1.2.3.4 -Dx=y", null);
        check("host=$(id)", null);
        check("host=", null);
        check("interval=abc,host=10.0.0.1", "10.0.0.1");
        check("host=--no-persistent", null);
        check("host=-Dfoo", null);
        check("host=" + "a".repeat(254), null);
        endpointCheck("Running(endpoint=ServerEndpoint(initialAddress=192.168.1.9))", "192.168.1.9");
        endpointCheck("Running(endpoint=null)", null);
        if (fails > 0) { System.out.println(fails + " failures"); System.exit(1); }
        System.out.println("all passed");
    }
}
