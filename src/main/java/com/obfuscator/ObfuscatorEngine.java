package com.obfuscator;

import com.obfuscator.config.ObfuscationConfig;
import com.obfuscator.config.ProtectionLevel;
import com.obfuscator.processor.ClassEncryptionProcessor;
import com.obfuscator.processor.ClassProcessor;
import com.obfuscator.processor.PackageFlattenProcessor;
import org.apache.maven.plugin.logging.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Framework-agnostic obfuscation engine.
 *
 * <p>Contains all bytecode processing logic. Used by both the Maven Mojo
 * ({@code ObfuscatorMojo}) and the Gradle Task ({@code ObfuscatorGradleTask}).
 *
 * <p><b>LEVEL_4 limitation:</b> LEVEL_4_ENCRYPTED relies on a custom
 * JVM ClassLoader ({@code EncryptedClassLoader}) and is only supported
 * on the JVM (Spring Boot). Android (ART runtime) cannot use it.
 */
public class ObfuscatorEngine {

    // ── Logger interface ──────────────────────────────────────────────────────

    public interface PluginLogger {
        void info(String msg);
        void warn(String msg);
        void error(String msg, Throwable t);
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final PluginLogger log;

    /**
     * Adapts {@link PluginLogger} to the Maven {@link Log} interface so existing
     * processor classes ({@code ClassProcessor}, {@code PackageFlattenProcessor},
     * {@code ClassEncryptionProcessor}) can receive a unified logger type.
     */
    private final Log mavenLog;

    private final ObfuscationConfig config;

    /**
     * Binary name of the @SpringBootApplication main class (LEVEL_4 only).
     * e.g. {@code "com.example.MyApplication"}
     */
    private final String mainClass;

    /**
     * Optional 64-char hex AES-256 key (LEVEL_4 only).
     * {@code null} → random key generated per build.
     */
    private final String encryptionKey;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ObfuscatorEngine(PluginLogger log,
                            ObfuscationConfig config,
                            String mainClass,
                            String encryptionKey) {
        this.log            = log;
        this.config         = config;
        this.mainClass      = mainClass;
        this.encryptionKey  = encryptionKey;
        // Wrap PluginLogger in a Maven Log adapter for processor APIs
        this.mavenLog       = new MavenLogAdapter(log);
    }

    // ── MavenLogAdapter ───────────────────────────────────────────────────────

    /**
     * Bridges {@link PluginLogger} to Maven's {@link Log} interface.
     * Processors (ClassProcessor, PackageFlattenProcessor, ClassEncryptionProcessor)
     * require a Maven Log; this adapter lets them work with any PluginLogger.
     */
    private static final class MavenLogAdapter implements Log {
        private final PluginLogger delegate;
        MavenLogAdapter(PluginLogger delegate) { this.delegate = delegate; }

        @Override public boolean isDebugEnabled() { return false; }
        @Override public boolean isInfoEnabled()  { return true;  }
        @Override public boolean isWarnEnabled()  { return true;  }
        @Override public boolean isErrorEnabled() { return true;  }

        @Override public void debug(CharSequence msg)                    { /* no-op */ }
        @Override public void debug(CharSequence msg, Throwable t)       { /* no-op */ }
        @Override public void debug(Throwable t)                         { /* no-op */ }

        @Override public void info(CharSequence msg)                     { delegate.info(msg.toString()); }
        @Override public void info(CharSequence msg, Throwable t)        { delegate.info(msg.toString()); }
        @Override public void info(Throwable t)                          { delegate.info(t.getMessage()); }

        @Override public void warn(CharSequence msg)                     { delegate.warn(msg.toString()); }
        @Override public void warn(CharSequence msg, Throwable t)        { delegate.warn(msg.toString()); }
        @Override public void warn(Throwable t)                          { delegate.warn(t.getMessage()); }

        @Override public void error(CharSequence msg)                    { delegate.error(msg.toString(), null); }
        @Override public void error(CharSequence msg, Throwable t)       { delegate.error(msg.toString(), t); }
        @Override public void error(Throwable t)                         { delegate.error(t.getMessage(), t); }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs the full obfuscation pipeline on the given compiled classes directory.
     *
     * @param classesDir the directory containing .class files (Maven outputDirectory
     *                   or Gradle {@code destinationDirectory})
     * @throws Exception on any obfuscation/IO error
     */
    public void execute(File classesDir) throws Exception {

        ObfuscationLevel level = config.getLevel();

        log.info("╔════════════════════════════════════════════╗");
        log.info("║   Spring Boot Obfuscator Plugin - Engine  ║");
        log.info("╚════════════════════════════════════════════╝");
        log.info("Obfuscation seviyesi: " + level);
        log.info("Spring Bean korumasi: " +
                (config.isPreserveSpringBeans() ? "Aktif" : "Devre disi"));

        if (level == ObfuscationLevel.LEVEL_4_ENCRYPTED) {
            if (!config.isFlattenPackages()) {
                log.info("[LEVEL_4] flattenPackages=true zorla aktif edildi.");
                config.setFlattenPackages(true);
            }
            if (!config.isFlattenObfuscateNames()) {
                log.info("[LEVEL_4] flattenObfuscateNames=true zorla aktif edildi.");
                config.setFlattenObfuscateNames(true);
            }
        }

        ClassProcessor processor = new ClassProcessor(config, mavenLog);

        // ── Phase 0: Global pre-registration ──────────────────────────────────
        {
            List<Path> allClassPaths;
            try (Stream<Path> ps = Files.walk(classesDir.toPath())) {
                allClassPaths = ps
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !isExcluded(p))
                    .collect(Collectors.toList());
            }
            processor.preRegisterAllClasses(allClassPaths);
        }

        // ── Phase 1: LEVEL_1/2/3 obfuscation ──────────────────────────────────
        List<Path> partialClasses = processDirectory(classesDir.toPath(), processor);

        // ── Phase 2: Package flattening ────────────────────────────────────────
        if (config.isFlattenPackages()) {
            log.info("");
            PackageFlattenProcessor flattener = new PackageFlattenProcessor(
                    mavenLog, config.getFlattenTargetPackage(), config.isFlattenObfuscateNames());
            flattener.flatten(classesDir.toPath());
            log.info("[FLATTEN] Tamamlandi. " +
                    flattener.getRenameMap().size() + " sinif yeniden eslendi.");

            if (level == ObfuscationLevel.LEVEL_4_ENCRYPTED) {
                partialClasses = remapPartialClassPaths(
                        partialClasses, classesDir.toPath(), flattener.getRenameMap());
            }
        }

        // ── Phase 3: AES-256-GCM encryption (LEVEL_4 only) ────────────────────
        if (level == ObfuscationLevel.LEVEL_4_ENCRYPTED) {
            String flatPkgSlash = null;
            String flattenTarget = config.getFlattenTargetPackage();
            if (flattenTarget != null && !flattenTarget.isBlank()) {
                flatPkgSlash = flattenTarget.replace('.', '/');
            } else if (mainClass != null && mainClass.contains(".")) {
                flatPkgSlash = mainClass.substring(0, mainClass.lastIndexOf('.'))
                                        .replace('.', '/');
            }

            List<Path> allFlatClasses = collectFlatClasses(classesDir.toPath(), flatPkgSlash);

            injectLogPatternConfig(classesDir.toPath(), flattenTarget, level);

            // Refresh after injection
            allFlatClasses = collectFlatClasses(classesDir.toPath(), flatPkgSlash);

            // Include root-package app classes not yet in list
            if (mainClass != null && mainClass.contains(".")) {
                String rootPkgSlash = mainClass.substring(0, mainClass.lastIndexOf('.'))
                                               .replace('.', '/');
                Path rootPkgPath = classesDir.toPath().resolve(rootPkgSlash);
                if (Files.exists(rootPkgPath)) {
                    Set<Path> alreadyIn = new HashSet<>(allFlatClasses);
                    try (Stream<Path> stream = Files.walk(rootPkgPath)) {
                        stream.filter(p -> p.toString().endsWith(".class"))
                              .filter(p -> !alreadyIn.contains(p))
                              .forEach(allFlatClasses::add);
                    }
                }
            }

            if (!allFlatClasses.isEmpty()) {
                encryptClasses(allFlatClasses, classesDir.toPath());
            }
        } else {
            // Non-LEVEL_4: inject log pattern config only
            injectLogPatternConfig(classesDir.toPath(),
                    config.getFlattenTargetPackage(), level);
        }

        log.info("");
        log.info("Obfuscation tamamlandi!");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<Path> processDirectory(Path directory, ClassProcessor processor)
            throws IOException {
        List<Path> partialClasses = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(p -> p.toString().endsWith(".class"))
                 .filter(p -> !isExcluded(p))
                 .forEach(p -> {
                     try {
                         ProtectionLevel pl = processor.processClass(p);
                         if (pl == ProtectionLevel.PARTIAL) {
                             partialClasses.add(p);
                         }
                     } catch (Exception e) {
                         log.error("Hata: " + p.getFileName(), e);
                     }
                 });
        }
        return partialClasses;
    }

    private List<Path> collectFlatClasses(Path classesRoot, String flatPkgSlash)
            throws IOException {
        List<Path> result = new ArrayList<>();
        if (flatPkgSlash != null) {
            Path flatDir = classesRoot.resolve(flatPkgSlash.replace('/', File.separatorChar));
            if (Files.exists(flatDir)) {
                try (Stream<Path> stream = Files.walk(flatDir)) {
                    stream.filter(p -> p.toString().endsWith(".class"))
                          .forEach(result::add);
                }
            }
        } else {
            try (Stream<Path> stream = Files.walk(classesRoot)) {
                stream.filter(p -> p.toString().endsWith(".class"))
                      .filter(p -> {
                          String rel = classesRoot.relativize(p).toString()
                                                  .replace(File.separatorChar, '/');
                          return !rel.startsWith("META-INF/")
                              && !rel.startsWith("com/obfuscator/");
                      })
                      .forEach(result::add);
            }
        }
        return result;
    }

    private void encryptClasses(List<Path> classFiles, Path classesRoot)
            throws Exception {
        log.info("");
        log.info("[LEVEL_4] " + classFiles.size() +
                 " sinif AES-256-GCM ile sifreleniyor...");

        ClassEncryptionProcessor cep =
            (encryptionKey != null && !encryptionKey.isBlank())
                ? new ClassEncryptionProcessor(mavenLog, encryptionKey)
                : new ClassEncryptionProcessor(mavenLog);

        for (Path classFile : classFiles) {
            cep.encryptClass(classFile, classesRoot);
        }

        String mc = (mainClass != null && !mainClass.isBlank())
                ? mainClass : "UNKNOWN_MAIN_CLASS";
        cep.writeMetadata(classesRoot, mc);
    }

    private void injectLogPatternConfig(Path classesRoot,
                                        String flattenTargetPackage,
                                        ObfuscationLevel level) throws IOException {
        final String SOURCE_INTERNAL = "com/obfuscator/runtime/AppBeanConfig";
        final String INJECTED_SIMPLE = UUID.randomUUID().toString()
                                           .replace("-", "").substring(0, 8);

        final boolean isLevel4 = (level == ObfuscationLevel.LEVEL_4_ENCRYPTED);

        String targetSlashPkg;
        if (isLevel4) {
            targetSlashPkg = "com/obfuscator/runtime";
        } else if (flattenTargetPackage != null && !flattenTargetPackage.isBlank()) {
            targetSlashPkg = flattenTargetPackage.replace('.', '/');
        } else if (mainClass != null && mainClass.contains(".")) {
            String pkg = mainClass.substring(0, mainClass.lastIndexOf('.'));
            targetSlashPkg = pkg.replace('.', '/');
        } else {
            targetSlashPkg = SOURCE_INTERNAL.substring(0, SOURCE_INTERNAL.lastIndexOf('/'));
        }
        final String TARGET_INTERNAL = targetSlashPkg + "/" + INJECTED_SIMPLE;

        byte[] classBytes;
        try (InputStream is = getClass().getResourceAsStream("/" + SOURCE_INTERNAL + ".class")) {
            if (is == null) {
                log.warn("[INJECT] AppBeanConfig.class bulunamadi — atlaniyor.");
                return;
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            is.transferTo(buf);
            classBytes = buf.toByteArray();
        }

        ClassReader reader    = new ClassReader(classBytes);
        ClassWriter writer    = new ClassWriter(0);
        ClassRemapper remapper = new ClassRemapper(writer,
                new SimpleRemapper(SOURCE_INTERNAL, TARGET_INTERNAL));
        reader.accept(remapper, 0);
        byte[] remapped = writer.toByteArray();

        Path targetFile = classesRoot.resolve(TARGET_INTERNAL + ".class");
        Files.createDirectories(targetFile.getParent());
        Files.write(targetFile, remapped);
        log.info("[INJECT] " + TARGET_INTERNAL.replace('/', '.') + " enjekte edildi");

        Path importsFile = classesRoot.resolve(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        Files.createDirectories(importsFile.getParent());

        String entry = TARGET_INTERNAL.replace('/', '.');
        boolean alreadyRegistered = Files.exists(importsFile) &&
            Files.readString(importsFile).contains(entry);
        if (!alreadyRegistered) {
            Files.writeString(importsFile, entry + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private List<Path> remapPartialClassPaths(List<Path> partialClasses,
                                               Path classesRoot,
                                               Map<String, String> renameMap) {
        List<Path> remapped = new ArrayList<>();
        for (Path oldPath : partialClasses) {
            String relative = classesRoot.relativize(oldPath).toString()
                    .replace(File.separatorChar, '/');
            String oldInternal = relative.endsWith(".class")
                    ? relative.substring(0, relative.length() - 6) : relative;

            String newInternal = renameMap.get(oldInternal);
            if (newInternal != null) {
                remapped.add(classesRoot.resolve(newInternal + ".class"));
            } else {
                if (Files.exists(oldPath)) remapped.add(oldPath);
            }
        }
        return remapped;
    }

    private boolean isExcluded(Path path) {
        String[] excludePackages = config.getExcludePackages();
        if (excludePackages == null || excludePackages.length == 0) return false;
        String pathString = path.toString().replace(File.separator, ".");
        for (String pkg : excludePackages) {
            if (pathString.contains(pkg)) return true;
        }
        return false;
    }
}
