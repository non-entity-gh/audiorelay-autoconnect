package arautoconnect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.FieldVisitor;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;

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

    /** AudioRelay's JNI wrapper around the RtAudio library (Windows audio output); not obfuscated. */
    public static final String RTAUDIO_JNI = "com/azefsw/audioconnect/audio/RtAudioJni";

    /**
     * Works around AudioRelay's crash when the audio output device disappears mid-stream (Bluetooth
     * earbuds put in their case, a USB DAC unplugged, ...). AudioRelay notices the stopped stream and
     * throws StreamNotRunningException (handled), but its cleanup then calls destroy() on the half-torn-down
     * native stream, which faults inside native-rtaudio.dll and takes the whole JVM down ("Child process
     * exited with code 1").
     *
     * The device going away usually crashes AudioRelay twice over, and the patch covers both:
     *
     *  1. The running stream dies. destroy() on it faults. -> When an output stream was opened
     *     (callbackDataPointer != -1) and is no longer running, skip the two native teardown calls.
     *  2. AudioRelay immediately reopens the stream on the device ID that just vanished. RtAudio's
     *     RtApiWasapi::probeDeviceOpen marks the stream STOPPED as its first step but allocates the WASAPI
     *     handle much later, and its early failures ("device ID was not found", "Unable to retrieve device
     *     handle", ...) return in between. The native object is then left "not closed" with a NULL handle,
     *     and deleting it faults in closeStream() (native-rtaudio.dll+0xf5f1 in AudioRelay 0.27.5).
     *     -> When the native openOutputStream/openInputStream call threw on this instance, skip the
     *     native teardown too.
     *
     * Either way one small native object is leaked instead of crashing. Every other call to destroy()
     * is unchanged: device enumeration instances (nothing opened) and normal disconnects (stream still
     * running) tear down exactly as before.
     *
     * Implemented as private helper methods and two flag fields added to the class: the two native
     * invocations inside destroy() and the native open calls are redirected to the helpers.
     */
    public static byte[] injectDestroyGuard(byte[] bytes, String hookOwner) {
        ClassReader cr = new ClassReader(bytes);
        final String self = cr.getClassName();
        if (!self.equals(RTAUDIO_JNI)) throw new IllegalArgumentException("not RtAudioJni: " + self);

        // The private open natives (the public wrappers with the same names are left alone).
        final Map<String, String> opens = new LinkedHashMap<>();   // name -> desc
        cr.accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if ((access & Opcodes.ACC_PRIVATE) != 0 && desc.startsWith("(J")
                        && ("openOutputStream".equals(name) || "openInputStream".equals(name))) opens.put(name, desc);
                return null;
            }
        }, ClassReader.SKIP_CODE);

        final boolean[] seen = new boolean[4];   // destroy()V present, destroy(J) redirected, destroyOutputCallbackData(J) redirected, openOutputStream redirected
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!"destroy".equals(name) || !"()V".equals(desc)) {
                    // everywhere else: route calls to the private open natives through arOpen*
                    return new MethodVisitor(API, mv) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                            if (owner.equals(self) && mdesc.equals(opens.get(mname))) {
                                if ("openOutputStream".equals(mname)) seen[3] = true;
                                super.visitMethodInsn(Opcodes.INVOKESPECIAL, self, "ar" + Character.toUpperCase(mname.charAt(0))
                                        + mname.substring(1), mdesc, false);
                            } else {
                                super.visitMethodInsn(op, owner, mname, mdesc, itf);
                            }
                        }
                    };
                }
                seen[0] = true;
                return new MethodVisitor(API, mv) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                        if (owner.equals(self) && "(J)V".equals(mdesc) && "destroy".equals(mname)) {
                            seen[1] = true;
                            super.visitMethodInsn(Opcodes.INVOKESPECIAL, self, "arDestroy", "(J)V", false);
                        } else if (owner.equals(self) && "(J)V".equals(mdesc) && "destroyOutputCallbackData".equals(mname)) {
                            seen[2] = true;
                            super.visitMethodInsn(Opcodes.INVOKESPECIAL, self, "arDestroyCallbackData", "(J)V", false);
                        } else {
                            super.visitMethodInsn(op, owner, mname, mdesc, itf);
                        }
                    }
                };
            }

            @Override
            public void visitEnd() {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "arDeadStream", "Z", null, null).visitEnd();
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "arOpenFailed", "Z", null, null).visitEnd();

                // private <ret> arOpenOutputStream(<same args>) {        (likewise arOpenInputStream)
                //     arOpenFailed = true;                                // stays set if the native call throws
                //     <ret> r = openOutputStream(<args>);
                //     arOpenFailed = false;
                //     return r;
                // }
                for (Map.Entry<String, String> o : opens.entrySet()) {
                    String n = o.getKey(), d = o.getValue();
                    MethodVisitor m = cw.visitMethod(Opcodes.ACC_PRIVATE,
                            "ar" + Character.toUpperCase(n.charAt(0)) + n.substring(1), d, null, null);
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitInsn(Opcodes.ICONST_1);
                    m.visitFieldInsn(Opcodes.PUTFIELD, self, "arOpenFailed", "Z");
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    int slot = 1;
                    for (Type t : Type.getArgumentTypes(d)) { m.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot); slot += t.getSize(); }
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, self, n, d, false);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitInsn(Opcodes.ICONST_0);
                    m.visitFieldInsn(Opcodes.PUTFIELD, self, "arOpenFailed", "Z");
                    m.visitInsn(Type.getReturnType(d).getOpcode(Opcodes.IRETURN));
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }

                // private void arDestroy(long ptr) {
                //     arDeadStream = false;
                //     if (arOpenFailed) { arOpenFailed = false; arDeadStream = true; Agent.onFailedOpen(); return; }
                //     if (callbackDataPointer != -1 && !isStreamRunning(ptr)) { arDeadStream = true; Agent.onDeadStream(); return; }
                //     destroy(ptr);
                // }
                MethodVisitor m = cw.visitMethod(Opcodes.ACC_PRIVATE, "arDestroy", "(J)V", null, null);
                m.visitCode();
                Label doIt = new Label(), notFailed = new Label();
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitInsn(Opcodes.ICONST_0);
                m.visitFieldInsn(Opcodes.PUTFIELD, self, "arDeadStream", "Z");
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitFieldInsn(Opcodes.GETFIELD, self, "arOpenFailed", "Z");
                m.visitJumpInsn(Opcodes.IFEQ, notFailed);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitInsn(Opcodes.ICONST_0);
                m.visitFieldInsn(Opcodes.PUTFIELD, self, "arOpenFailed", "Z");
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitInsn(Opcodes.ICONST_1);
                m.visitFieldInsn(Opcodes.PUTFIELD, self, "arDeadStream", "Z");
                m.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "onFailedOpen", "()V", false);
                m.visitInsn(Opcodes.RETURN);
                m.visitLabel(notFailed);
                m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitFieldInsn(Opcodes.GETFIELD, self, "callbackDataPointer", "J");
                m.visitLdcInsn(Long.valueOf(-1L));
                m.visitInsn(Opcodes.LCMP);
                m.visitJumpInsn(Opcodes.IFEQ, doIt);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitVarInsn(Opcodes.LLOAD, 1);
                m.visitMethodInsn(Opcodes.INVOKESPECIAL, self, "isStreamRunning", "(J)Z", false);
                m.visitJumpInsn(Opcodes.IFNE, doIt);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitInsn(Opcodes.ICONST_1);
                m.visitFieldInsn(Opcodes.PUTFIELD, self, "arDeadStream", "Z");
                m.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "onDeadStream", "()V", false);
                m.visitInsn(Opcodes.RETURN);
                m.visitLabel(doIt);
                m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitVarInsn(Opcodes.LLOAD, 1);
                m.visitMethodInsn(Opcodes.INVOKESPECIAL, self, "destroy", "(J)V", false);
                m.visitInsn(Opcodes.RETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();

                // private void arDestroyCallbackData(long ptr) { if (!arDeadStream) destroyOutputCallbackData(ptr); }
                m = cw.visitMethod(Opcodes.ACC_PRIVATE, "arDestroyCallbackData", "(J)V", null, null);
                m.visitCode();
                Label skip = new Label();
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitFieldInsn(Opcodes.GETFIELD, self, "arDeadStream", "Z");
                m.visitJumpInsn(Opcodes.IFNE, skip);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitVarInsn(Opcodes.LLOAD, 1);
                m.visitMethodInsn(Opcodes.INVOKESPECIAL, self, "destroyOutputCallbackData", "(J)V", false);
                m.visitLabel(skip);
                m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                m.visitInsn(Opcodes.RETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();

                super.visitEnd();
            }
        }, 0);
        if (!seen[0] || !seen[1] || !seen[2] || !seen[3]) {
            throw new IllegalStateException("RtAudioJni doesn't look as expected (found destroy()="
                    + seen[0] + ", destroy(J)=" + seen[1] + ", destroyOutputCallbackData(J)=" + seen[2]
                    + ", openOutputStream call=" + seen[3] + ")");
        }
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
