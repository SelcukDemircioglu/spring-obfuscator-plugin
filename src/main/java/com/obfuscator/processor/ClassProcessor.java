package com.obfuscator.processor;

import com.obfuscator.ObfuscationLevel;
import com.obfuscator.config.ObfuscationConfig;
import com.obfuscator.config.ProtectionLevel;
import com.obfuscator.config.SpringAnnotationDetector;
import com.obfuscator.util.BytecodeUtil;
import org.apache.maven.plugin.logging.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.nio.file.Path;

public class ClassProcessor {

    private final ObfuscationConfig config;
    private final Log log;
    private final Level1BasicObfuscator level1Obfuscator;
    private final Level2StringEncryptor level2Encryptor;
    private final Level3ControlFlowObfuscator level3FlowObfuscator;

    public ClassProcessor(ObfuscationConfig config, Log log) {
        this.config = config;
        this.log = log;
        this.level1Obfuscator = new Level1BasicObfuscator();
        this.level2Encryptor = new Level2StringEncryptor();
        this.level3FlowObfuscator = new Level3ControlFlowObfuscator();
    }

    /**
     * Processes a single class file through the configured obfuscation pipeline.
     * @return the ProtectionLevel that was applied (FULL classes are skipped/returned as FULL)
     */
    public ProtectionLevel processClass(Path classFile) throws IOException {
        ClassReader reader = BytecodeUtil.readClass(classFile);

        ProtectionLevel protection = ProtectionLevel.NONE;
        if (config.isPreserveSpringBeans()) {
            SpringAnnotationDetector detector = new SpringAnnotationDetector();
            reader.accept(detector, ClassReader.SKIP_CODE);
            protection = detector.getProtectionLevel();
        }

        if (protection == ProtectionLevel.FULL) {
            log.debug("Tam koruma (atlandı): " + classFile.getFileName());
            return ProtectionLevel.FULL;
        }

        if (protection == ProtectionLevel.PARTIAL) {
            log.info("Kısmi obfuscation (public API korundu): " + classFile.getFileName());
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // Avoid ClassLoader issues during frame computation
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Exception e) {
                    return "java/lang/Object";
                }
            }
        };
        ClassVisitor visitor = writer;

        ObfuscationLevel level = config.getLevel();

        if (level == ObfuscationLevel.LEVEL_4_ENCRYPTED
                || level == ObfuscationLevel.LEVEL_3_ADVANCED) {
            visitor = level3FlowObfuscator.createVisitor(visitor);
            visitor = level2Encryptor.createVisitor(visitor);
            visitor = level1Obfuscator.createVisitor(visitor);
        } else if (level == ObfuscationLevel.LEVEL_2_MEDIUM) {
            visitor = level2Encryptor.createVisitor(visitor);
            visitor = level1Obfuscator.createVisitor(visitor);
        } else if (level == ObfuscationLevel.LEVEL_1_BASIC) {
            visitor = level1Obfuscator.createVisitor(visitor);
        }

        // SKIP_DEBUG strips LocalVariableTable, LocalVariableTypeTable, LineNumberTable
        // and SourceFile attributes — prevents decompilers from reconstructing original names.
        //
        // Two-pass: pre-register ALL private method renames before processing
        // method bodies so visitMethodInsn remapping works regardless of declaration order.
        // (e.g. <init> that calls a private helper declared later in the class file)
        if (level == ObfuscationLevel.LEVEL_1_BASIC
                || level == ObfuscationLevel.LEVEL_2_MEDIUM
                || level == ObfuscationLevel.LEVEL_3_ADVANCED
                || level == ObfuscationLevel.LEVEL_4_ENCRYPTED) {
            level1Obfuscator.preRegisterMethods(reader);
        }

        reader.accept(visitor, ClassReader.EXPAND_FRAMES | ClassReader.SKIP_DEBUG);

        byte[] obfuscatedBytes = writer.toByteArray();
        BytecodeUtil.writeClass(classFile, obfuscatedBytes);

        log.info("Obfuscate edildi: " + classFile.getFileName() + " [" + level + "]");
        return protection;
    }
}
