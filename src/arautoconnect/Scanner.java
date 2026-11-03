package arautoconnect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.FieldVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/**
 * Everything that touches bytecode lives here, using the copy of ASM that ships inside the JDK.
 *
 * AudioRelay is obfuscated and every build (and every OS) uses different class names, so nothing is
 * hard-coded. Instead we locate the pieces by text that the app itself contains:
 *
 *   - the "connect to address" event: the only class whose toString() contains "Connection(address="
 *   - the startup service that turns that event into a connection: a lambda class that contains the log
 *     key "called_start_when_not_idle" and casts to the event class. It reads two fields from its owner
 *     class ("holder"): the event bus and the player connection manager.
 *   - the player state type: superclass of the class whose toString() contains "Running(endpoint="
 *
 *   - the state machine: classes that assign a field of that state type outside constructors
 *     (detected as classes load, see isStateWriter)
 *
 * Result format (all names are internal JVM names):
 *   holder, holderPlayerField, eventClass, stateBaseClass
 */
public final class Scanner {
    private static final int API = Opcodes.ASM8;

    public static String[] scan(String jarPath) throws IOException {
        byte[] kEvent = "Connection(address=".getBytes(StandardCharsets.UTF_8);
        byte[] kStart = "called_start_when_not_idle".getBytes(StandardCharsets.UTF_8);
        byte[] kRunning = "Running(endpoint=".getBytes(StandardCharsets.UTF_8);

        List<byte[]> eventCandidates = new ArrayList<>();
        List<byte[]> startCandidates = new ArrayList<>();
        List<byte[]> runningCandidates = new ArrayList<>();

        try (ZipFile zip = new ZipFile(jarPath)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                // obfuscated app classes live in the default package
                if (e.isDirectory() || !n.endsWith(".class") || n.indexOf('/') >= 0) continue;
                byte[] b = read(zip.getInputStream(e));
                if (contains(b, kEvent)) eventCandidates.add(b);
                if (contains(b, kStart)) startCandidates.add(b);
                if (contains(b, kRunning)) runningCandidates.add(b);
            }
        }

        // 1. event class: has a (String) constructor and one String field
        String event = null;
        for (byte[] b : eventCandidates) {
            ClassInfo ci = info(b);
            if (ci.ctorDescs.contains("(Ljava/lang/String;)V")) {
                if (event != null) throw new IllegalStateException("ambiguous event class");
                event = ci.name;
            }
        }
        if (event == null) throw new IllegalStateException("event class not found");

        // 2. lambda that casts to the event and reads holder.<playerField>
        String holder = null, playerField = null;
        for (byte[] b : startCandidates) {
            String[] r = findHolder(b, event);
            if (r != null) {
                if (holder != null && !holder.equals(r[0])) throw new IllegalStateException("ambiguous holder");
                holder = r[0];
                playerField = r[1];
            }
        }
        if (holder == null) throw new IllegalStateException("startup service not found");

        // 3. player state base type
        String stateBase = null;
        for (byte[] b : runningCandidates) {
            ClassInfo ci = info(b);
            if (ci.superName != null && !ci.superName.startsWith("java/")) { stateBase = ci.superName; break; }
        }
        if (stateBase == null) throw new IllegalStateException("player state type not found");

        return new String[] { holder, playerField, event, stateBase };
    }

    /**
     * True if this class assigns a field of the player-state type outside constructors, i.e. it is the
     * state machine. Called for each class as it loads (cheap byte search first).
     */
    public static boolean isStateWriter(byte[] bytes, String stateBase) {
        String stateDesc = "L" + stateBase + ";";
        if (!contains(bytes, stateDesc.getBytes(StandardCharsets.UTF_8))) return false;
        return stateWriter(bytes, stateDesc) != null;
    }

    private static String stateWriter(byte[] bytes, String stateDesc) {
        ClassReader cr = new ClassReader(bytes);
        final String self = cr.getClassName();
        final boolean[] found = { false };
        cr.accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                if ("<init>".equals(n) || "<clinit>".equals(n)) return null;
                return new MethodVisitor(API) {
                    @Override
                    public void visitFieldInsn(int op, String owner, String name, String desc) {
                        if (op == Opcodes.PUTFIELD && owner.equals(self) && desc.equals(stateDesc)) found[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0] ? self : null;
    }

    /**
     * After every "this.<state> = value" (outside constructors) adds "<hookOwner>.onState(this.<state>)".
     * Only reads the field back and passes it on; doesn't change the app's behavior.
     */
    public static byte[] injectStateHook(byte[] bytes, String hookOwner, String stateBase) {
        final String stateDesc = "L" + stateBase + ";";
        ClassReader cr = new ClassReader(bytes);
        final String self = cr.getClassName();
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if ("<init>".equals(name) || "<clinit>".equals(name) || (access & Opcodes.ACC_STATIC) != 0) return mv;
                return new MethodVisitor(API, mv) {
                    @Override
                    public void visitFieldInsn(int op, String owner, String fname, String fdesc) {
                        super.visitFieldInsn(op, owner, fname, fdesc);
                        if (op == Opcodes.PUTFIELD && owner.equals(self) && fdesc.equals(stateDesc)) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, self, fname, fdesc);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "onState", "(Ljava/lang/Object;)V", false);
                        }
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    /** Looks for: CHECKCAST event ... GETFIELD <self>.x : L<holder>; ... GETFIELD <holder>.<p> */
    private static String[] findHolder(byte[] bytes, String event) {
        final String[] out = new String[2];
        final boolean[] cast = { false };
        ClassReader cr = new ClassReader(bytes);
        final String self = cr.getClassName();
        cr.accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(API) {
                    String holderType;
                    boolean castHere;

                    @Override
                    public void visitTypeInsn(int op, String type) {
                        if (op == Opcodes.CHECKCAST && type.equals(event)) castHere = true;
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String name, String desc) {
                        if (op != Opcodes.GETFIELD) return;
                        if (owner.equals(self) && desc.startsWith("L") && desc.indexOf('/') < 0) {
                            holderType = desc.substring(1, desc.length() - 1);
                        } else if (castHere && holderType != null && owner.equals(holderType) && out[0] == null) {
                            out[0] = holderType;
                            out[1] = name;
                            cast[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return cast[0] ? out : null;
    }

    /** Adds "<hookOwner>.onBusHolder(this)" before every RETURN in the target's constructors. */
    public static byte[] injectConstructorHook(byte[] bytes, String hookOwner) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!"<init>".equals(name)) return mv;
                return new MethodVisitor(API, mv) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "onBusHolder",
                                    "(Ljava/lang/Object;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    private static final class ClassInfo {
        String name, superName;
        final List<String> ctorDescs = new ArrayList<>();
    }

    private static ClassInfo info(byte[] b) {
        ClassInfo ci = new ClassInfo();
        new ClassReader(b).accept(new ClassVisitor(API) {
            @Override
            public void visit(int v, int a, String name, String sig, String sup, String[] itf) {
                ci.name = name;
                ci.superName = sup;
            }

            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                if ("<init>".equals(n)) ci.ctorDescs.add(d);
                return null;
            }

            @Override
            public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return ci;
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0, max = hay.length - needle.length; i <= max; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    private static byte[] read(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }
}
