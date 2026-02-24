package com.obfuscator.processor;

import com.obfuscator.util.NameGenerator;
import org.objectweb.asm.*;

public class Level1BasicObfuscator {

    private final NameGenerator nameGenerator = new NameGenerator();

    /**
     * Global pre-scan pass: walks only field declarations and registers the
     * obfuscated name for every private non-synthetic field.
     *
     * Must be called for ALL classes before any class is processed. This prevents
     * NoSuchFieldError when an inner class (e.g. mg$DevicePollTask) is processed
     * before its outer class (mg) — the inner class emits GETFIELD mg.fieldName
     * and needs the rename mapping to already exist in the NameGenerator.
     *
     * Outer class files sort alphabetically AFTER inner class files because
     * '.' (44) > '$' (36), so without this pre-pass the outer class field rename
     * would not yet be registered when the inner class is transformed.
     */
    public void preRegisterFields(ClassReader reader) {
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            private String className;
            // If the class itself carries a Jackson annotation (e.g. @JsonInclude)
            // we must NOT obfuscate any of its fields, because Jackson will access
            // them by the field name (directly or via merged property introspection)
            // and the obfuscated name would leak into the JSON response.
            private boolean classHasJacksonAnnotation = false;

            @Override
            public void visit(int version, int access, String name, String signature,
                             String superName, String[] interfaces) {
                this.className = name;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                // Class-level Jackson annotation → protect ALL fields of this class
                if (descriptor.startsWith("Lcom/fasterxml/jackson/")) {
                    classHasJacksonAnnotation = true;
                }
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                          String signature, Object value) {
                boolean isPrivate   = (access & Opcodes.ACC_PRIVATE)   != 0;
                boolean isSynthetic = (access & Opcodes.ACC_SYNTHETIC)  != 0;
                // Skip outer-class capture fields ("this$0", "this$1", ...)
                if (!isPrivate || isSynthetic || name.startsWith("this$")) return null;
                // If the class is Jackson-annotated, never obfuscate its fields
                if (classHasJacksonAnnotation) return null;
                final String fieldKey = className + "." + name;
                // Return a real FieldVisitor so we can inspect field-level annotations
                // before deciding whether to register an obfuscated name
                return new FieldVisitor(Opcodes.ASM9) {
                    boolean fieldHasJacksonAnnotation = false;
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (desc.startsWith("Lcom/fasterxml/jackson/")) {
                            fieldHasJacksonAnnotation = true;
                        }
                        return null;
                    }
                    @Override
                    public void visitEnd() {
                        // Only register rename if field has no Jackson annotation
                        if (!fieldHasJacksonAnnotation) {
                            nameGenerator.generateObfuscatedName(fieldKey, "f");
                        }
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /**
     * Pre-scan pass: walks only method declarations (SKIP_CODE) and registers
     * the obfuscated name for every private non-special method so that call-site
     * remapping in visitMethodInsn works correctly regardless of declaration order.
     */
    public void preRegisterMethods(ClassReader reader) {
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            private String className;

            @Override
            public void visit(int version, int access, String name, String signature,
                             String superName, String[] interfaces) {
                this.className = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                boolean isPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
                boolean isSpecial = name.equals("<init>") || name.equals("<clinit>")
                        || name.startsWith("$")
                        || name.startsWith("lambda$")
                        || name.startsWith("access$");
                if (isPrivate && !isSpecial) {
                    nameGenerator.generateObfuscatedName(
                        className + "." + name + descriptor, "m");
                }
                return null; // skip body
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    public ClassVisitor createVisitor(ClassVisitor cv) {
        return new ClassVisitor(Opcodes.ASM9, cv) {

            private String className;

            @Override
            public void visit(int version, int access, String name, String signature,
                            String superName, String[] interfaces) {
                this.className = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                          String signature, Object value) {
                if ((access & Opcodes.ACC_PRIVATE) != 0 && !name.startsWith("this$")) {
                    // Use only pre-registered mappings (from preRegisterFields).
                    // Fields skipped during pre-registration (Jackson-annotated fields
                    // or fields in Jackson-annotated classes) return null here, so
                    // their original names are preserved — preventing obfuscated names
                    // from leaking into JSON serialization / deserialization.
                    String obfuscatedName = nameGenerator.getMappedName(className + "." + name);
                    if (obfuscatedName != null) {
                        return super.visitField(access, obfuscatedName, descriptor, signature, value);
                    }
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                String methodName = name;

                boolean isPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
                boolean isSpecial = name.equals("<init>") || name.equals("<clinit>")
                        || name.startsWith("$")        // enum: $values(), and similar JVM synthetics
                        || name.startsWith("lambda$")  // lambda bodies generated by javac
                        || name.startsWith("access$"); // inner-class access bridge methods

                if (isPrivate && !isSpecial) {
                    methodName = nameGenerator.generateObfuscatedName(
                        className + "." + name + descriptor, "m");
                }

                MethodVisitor mv = super.visitMethod(access, methodName, descriptor,
                                                     signature, exceptions);

                final String finalMethodName = methodName;
                return new MethodVisitor(Opcodes.ASM9, mv) {

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        // Remap GETFIELD/PUTFIELD references.
                        //
                        // Case 1: field declared in this class → key = className + "." + name
                        // Case 2: field declared in a private inner class (e.g. private record)
                        //         accessed from the outer class. Java compiler generates direct
                        //         GETFIELD (not accessor call) for same-compilation-unit access.
                        //         owner = "OuterClass$InnerRecord", key = owner + "." + name.
                        //
                        // getMappedName() returns null when no mapping exists, so this is safe
                        // for all external class references — only registered renames get applied.
                        String lookupKey = owner.equals(className)
                                ? className + "." + name   // fast path: same class
                                : owner + "." + name;      // inner/other class field
                        String mapped = nameGenerator.getMappedName(lookupKey);
                        if (mapped != null) {
                            name = mapped;
                        }
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                               String descriptor, boolean isInterface) {
                        // Remap call sites for private methods we renamed in this class
                        if (owner.equals(className)) {
                            String mapped = nameGenerator.getMappedName(
                                className + "." + name + descriptor);
                            if (mapped != null) {
                                name = mapped;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        // Remap method references (e.g. this::connectionLoop) that were
                        // renamed by Level1. The bootstrap args contain Handle objects
                        // pointing to the actual target method — update those too.
                        for (int i = 0; i < bootstrapMethodArguments.length; i++) {
                            if (bootstrapMethodArguments[i] instanceof Handle h) {
                                if (h.getOwner().equals(className)) {
                                    String mapped = nameGenerator.getMappedName(
                                        className + "." + h.getName() + h.getDesc());
                                    if (mapped != null) {
                                        bootstrapMethodArguments[i] = new Handle(
                                            h.getTag(), h.getOwner(), mapped,
                                            h.getDesc(), h.isInterface());
                                    }
                                }
                            }
                        }
                        super.visitInvokeDynamicInsn(name, descriptor,
                            bootstrapMethodHandle, bootstrapMethodArguments);
                    }

                    @Override
                    public void visitLocalVariable(String varName, String descriptor,
                                                   String signature, Label start, Label end, int index) {
                        if (!varName.equals("this")) {
                            varName = nameGenerator.generateObfuscatedName(
                                className + "." + finalMethodName + "." + varName, "v");
                        }
                        super.visitLocalVariable(varName, descriptor, signature, start, end, index);
                    }
                };
            }
        };
    }
}
