package com.obfuscator.processor;

import org.objectweb.asm.*;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class Level2StringEncryptor {

    private static final String KEY = "ObfuscatorKey123";
    private static final String DECRYPT_METHOD = "decrypt$obf";

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
                    injectDecryptMethod(cv, className);
                    decryptNeeded = false;
                }
                super.visitEnd();
            }
        };
    }

    private static void injectDecryptMethod(ClassVisitor cv, String className) {
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            DECRYPT_METHOD,
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null
        );

        mv.visitCode();

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchStart = new Label();

        mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/Exception");

        mv.visitLabel(tryStart);

        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/util/Base64", "getDecoder",
            "()Ljava/util/Base64$Decoder;", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/util/Base64$Decoder", "decode",
            "(Ljava/lang/String;)[B", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        mv.visitTypeInsn(Opcodes.NEW, "javax/crypto/spec/SecretKeySpec");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(KEY);
        mv.visitLdcInsn("UTF-8");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/lang/String", "getBytes",
            "(Ljava/lang/String;)[B", false);
        mv.visitLdcInsn("AES");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "javax/crypto/spec/SecretKeySpec", "<init>",
            "([BLjava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);

        mv.visitLdcInsn("AES");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "javax/crypto/Cipher", "getInstance",
            "(Ljava/lang/String;)Ljavax/crypto/Cipher;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "javax/crypto/Cipher", "init",
            "(ILjava/security/Key;)V", false);

        mv.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "javax/crypto/Cipher", "doFinal",
            "([B)[B", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "java/lang/String", "<init>",
            "([B)V", false);

        mv.visitLabel(tryEnd);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(catchStart);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private String encrypt(String plainText) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(KEY.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(encrypted);
    }
}
