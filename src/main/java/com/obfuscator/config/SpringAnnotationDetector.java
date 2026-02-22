package com.obfuscator.config;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

/**
 * Scans class-level annotations and assigns a ProtectionLevel:
 *
 *  FULL    — framework depends on exact names (JPA entities, Spring configuration).
 *            Class is skipped entirely by the obfuscator.
 *
 *  PARTIAL — Spring wires the bean but only private internals need hiding.
 *            Public method names must stay intact (Spring Data query derivation,
 *            controller mapping, JSON serialisation via getters).
 *            Private fields, private helper methods, local variables and string
 *            literals are obfuscated.
 *
 *  NONE    — No Spring constraint detected; full obfuscation pipeline applies.
 */
public class SpringAnnotationDetector extends ClassVisitor {

    // Classes whose public API must NOT be touched — skip entirely.
    private static final Set<String> FULL_PROTECTION = new HashSet<>();

    // Classes whose private internals can be obfuscated safely.
    private static final Set<String> PARTIAL_PROTECTION = new HashSet<>();

    static {
        // ── FULL protection ────────────────────────────────────────────────────
        // Spring Boot entry point — MANIFEST Start-Class + @ComponentScan base.
        FULL_PROTECTION.add("Lorg/springframework/boot/autoconfigure/SpringBootApplication;");
        // JPA entities: private field names = DB column names (without @Column).
        // Content obfuscation would rename fields → Hibernate can't find columns.
        // These classes ARE moved by flatten (with @Table injection); content stays intact.
        FULL_PROTECTION.add("Ljakarta/persistence/Entity;");
        FULL_PROTECTION.add("Ljakarta/persistence/Table;");
        FULL_PROTECTION.add("Ljakarta/persistence/MappedSuperclass;");
        FULL_PROTECTION.add("Ljavax/persistence/Entity;");
        FULL_PROTECTION.add("Ljavax/persistence/MappedSuperclass;");
        // @Configuration: CGLIB subclass proxying depends on field/method bytecode
        // layout (e.g. @Autowired fields, @Bean method names referenced by CGLIB).
        // Renaming private fields breaks field-injection and lambdas capturing them.
        // Class is still MOVED by flatten (FIXED_ANNOTATIONS removed @Configuration);
        // only *content* obfuscation is skipped.
        FULL_PROTECTION.add("Lorg/springframework/context/annotation/Configuration;");
        FULL_PROTECTION.add("Lorg/springframework/context/annotation/Bean;");

        // ── PARTIAL protection ─────────────────────────────────────────────────
        // Spring wires these beans by type; public method names must survive.
        // Private helpers, local variables and string literals are fair game.
        PARTIAL_PROTECTION.add("Lorg/springframework/stereotype/Service;");
        PARTIAL_PROTECTION.add("Lorg/springframework/stereotype/Repository;");
        PARTIAL_PROTECTION.add("Lorg/springframework/stereotype/Controller;");
        PARTIAL_PROTECTION.add("Lorg/springframework/web/bind/annotation/RestController;");
        PARTIAL_PROTECTION.add("Lorg/springframework/stereotype/Component;");
    }

    private ProtectionLevel protectionLevel = ProtectionLevel.NONE;

    public SpringAnnotationDetector() {
        super(Opcodes.ASM9);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        if (FULL_PROTECTION.contains(descriptor)) {
            // FULL always wins regardless of any other annotation present.
            protectionLevel = ProtectionLevel.FULL;
        } else if (PARTIAL_PROTECTION.contains(descriptor)
                && protectionLevel != ProtectionLevel.FULL) {
            protectionLevel = ProtectionLevel.PARTIAL;
        }
        return super.visitAnnotation(descriptor, visible);
    }

    public ProtectionLevel getProtectionLevel() {
        return protectionLevel;
    }

    /** Backward-compatible helper used by existing call sites. */
    public boolean hasSpringAnnotation() {
        return protectionLevel != ProtectionLevel.NONE;
    }
}
