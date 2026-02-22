package com.obfuscator.processor;

import org.objectweb.asm.*;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

public class Level2StringEncryptor {

    /** Varsayılan anahtar — dışarıdan verilmezse kullanılır. Tam 16 karakter. */
    private static final String DEFAULT_KEY = "ObfuscatorKey123";
    private static final String DECRYPT_METHOD = "decrypt$obf";

    private final String KEY;

    public Level2StringEncryptor() {
        this.KEY = DEFAULT_KEY;
    }

    public Level2StringEncryptor(String key) {
        if (key == null || key.isBlank()) {
            this.KEY = DEFAULT_KEY;
        } else if (key.length() != 16) {
            throw new IllegalArgumentException(
                "[Level2] stringEncryptionKey tam 16 karakter olmalıdır, verilen: " + key.length());
        } else {
            this.KEY = key;
        }
    }

    public ClassVisitor createVisitor(ClassVisitor cv) {
        return new ClassVisitor(Opcodes.ASM9, cv) {

            private String className;
            private boolean decryptNeeded = false;

            @Override
            public void visit(int version, int access, String name, String signature,
                            String superName, String[] interfaces) {
                this.className = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                // Skip the decrypt method itself
                if (name.equals(DECRYPT_METHOD)) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            String original = (String) value;
                            if (original.length() > 3) {
                                try {
                                    String encrypted = encrypt(original);
                                    decryptNeeded = true;
                                    super.visitLdcInsn(encrypted);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        className,
                                        DECRYPT_METHOD,
                                        "(Ljava/lang/String;)Ljava/lang/String;",
                                        false);
                                    return;
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        super.visitLdcInsn(value);
                    }
                };
            }

            @Override
            public void visitEnd() {
                // Inject decrypt method exactly once at the end
                if (decryptNeeded) {
                    injectDecryptMethod(cv, className, KEY);
                    decryptNeeded = false;
                }
                super.visitEnd();
            }
        };
    }

    /**
     * Inject edilen decrypt$obf metodunda KEY string sabiti olarak görünmez.
     * Build zamanında rastgele bir XOR mask ile maskelenmiş byte dizisi olarak gömülür;
     * runtime'da XOR ile geri açılarak SecretKeySpec oluşturulur.
     *
     * Decompiler çıktısı:
     *   byte[] k = new byte[16];
     *   k[0] = (byte)(0x4A ^ 0x17);  // okunabilir değil
     *   k[1] = (byte)(0x3F ^ 0x17);  // ...
     *   SecretKeySpec spec = new SecretKeySpec(k, "AES");
     */
    private static void injectDecryptMethod(ClassVisitor cv, String className, String KEY) {
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            DECRYPT_METHOD,
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null
        );

        mv.visitCode();

        Label tryStart  = new Label();
        Label tryEnd    = new Label();
        Label catchStart = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/Exception");
        mv.visitLabel(tryStart);

        // ── local 1: byte[] decoded = Base64.decode(arg0) ────────────────
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        // ── local 2: byte[] k = new byte[keyLen] ─────────────────────────
        // KEY her sınıfa inject edilirken rastgele XOR mask ile maskelenir.
        // Bytecode'da plain-text string görünmez; sadece masked byte literalleri + mask int'i var.
        byte[] keyBytes = KEY.getBytes(StandardCharsets.UTF_8);
        int xorMask = new Random().nextInt(256); // build-time rastgele, 0-255

        pushInt(mv, keyBytes.length);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        mv.visitVarInsn(Opcodes.ASTORE, 2);

        for (int i = 0; i < keyBytes.length; i++) {
            mv.visitVarInsn(Opcodes.ALOAD, 2);        // array ref
            pushInt(mv, i);                            // index
            pushInt(mv, (keyBytes[i] & 0xFF) ^ xorMask); // masked byte value
            pushInt(mv, xorMask);                      // XOR mask
            mv.visitInsn(Opcodes.IXOR);                // original byte value
            mv.visitInsn(Opcodes.I2B);                 // cast to byte
            mv.visitInsn(Opcodes.BASTORE);             // k[i] = byte
        }

        // ── local 3: SecretKeySpec spec = new SecretKeySpec(k, "AES") ────
        mv.visitTypeInsn(Opcodes.NEW, "javax/crypto/spec/SecretKeySpec");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitLdcInsn("AES");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "javax/crypto/spec/SecretKeySpec", "<init>", "([BLjava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        // ── local 4: Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding") ──
        mv.visitLdcInsn("AES/ECB/PKCS5Padding");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "javax/crypto/Cipher", "getInstance",
            "(Ljava/lang/String;)Ljavax/crypto/Cipher;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        // cipher.init(DECRYPT_MODE, spec)
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitInsn(Opcodes.ICONST_2); // Cipher.DECRYPT_MODE = 2
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "javax/crypto/Cipher", "init", "(ILjava/security/Key;)V", false);

        // return new String(cipher.doFinal(decoded))
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "javax/crypto/Cipher", "doFinal", "([B)[B", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "java/lang/String", "<init>", "([B)V", false);

        mv.visitLabel(tryEnd);
        mv.visitInsn(Opcodes.ARETURN);

        // catch(Exception e) { return input; }
        mv.visitLabel(catchStart);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** int sabitini en küçük opcode ile emit eder. */
    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value); // ICONST_M1 … ICONST_5
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private String encrypt(String plainText) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(KEY.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(encrypted);
    }
}
