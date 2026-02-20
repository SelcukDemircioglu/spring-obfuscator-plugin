package com.obfuscator.processor;

import org.objectweb.asm.*;

import java.util.Random;

public class Level3ControlFlowObfuscator {

    private final Random random = new Random(System.currentTimeMillis());

    public ClassVisitor createVisitor(ClassVisitor cv) {
        return new ClassVisitor(Opcodes.ASM9, cv) {

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                // Only skip constructors and static initializers.
                // @Entity classes are FULL-protected and never reach here, so
                // it is safe to apply control-flow injection to get*/set*/is* methods too.
                if (name.equals("<init>") || name.equals("<clinit>")) {
                    return mv;
                }

                return new MethodVisitor(Opcodes.ASM9, mv) {

                    private int instructionCount = 0;

                    @Override
                    public void visitInsn(int opcode) {
                        if (++instructionCount % 5 == 0) {
                            injectOpaquePredicate();
                        }
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                               String descriptor, boolean isInterface) {
                        if (random.nextInt(3) == 0) {
                            injectDeadCode();
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }

                    private void injectOpaquePredicate() {
                        Label trueLabel = new Label();
                        Label endLabel = new Label();

                        mv.visitLdcInsn(random.nextInt(100));
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitInsn(Opcodes.IMUL);
                        mv.visitJumpInsn(Opcodes.IFGE, trueLabel);

                        mv.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitLdcInsn("Unreachable");
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                            "java/lang/RuntimeException", "<init>",
                            "(Ljava/lang/String;)V", false);
                        mv.visitInsn(Opcodes.ATHROW);

                        mv.visitLabel(trueLabel);
                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        mv.visitInsn(Opcodes.NOP);
                        mv.visitLabel(endLabel);
                    }

                    private void injectDeadCode() {
                        Label skipLabel = new Label();

                        mv.visitInsn(Opcodes.ICONST_0);
                        mv.visitJumpInsn(Opcodes.IFEQ, skipLabel);

                        mv.visitLdcInsn("Dead Code " + random.nextInt(1000));
                        mv.visitInsn(Opcodes.POP);

                        mv.visitIntInsn(Opcodes.BIPUSH, random.nextInt(100));
                        mv.visitIntInsn(Opcodes.BIPUSH, random.nextInt(100));
                        mv.visitInsn(Opcodes.IADD);
                        mv.visitInsn(Opcodes.POP);

                        mv.visitLabel(skipLabel);
                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                };
            }
        };
    }
}
