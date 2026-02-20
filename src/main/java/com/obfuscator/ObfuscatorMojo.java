package com.obfuscator;

import com.obfuscator.config.ObfuscationConfig;
import com.obfuscator.config.ProtectionLevel;
import com.obfuscator.processor.ClassEncryptionProcessor;
import com.obfuscator.processor.ClassProcessor;
import com.obfuscator.processor.PackageFlattenProcessor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Mojo(name = "obfuscate", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class ObfuscatorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "obfuscation.level", defaultValue = "LEVEL_1_BASIC")
    private ObfuscationLevel level;

    @Parameter(property = "obfuscation.enabled", defaultValue = "true")
    private boolean enabled;

    @Parameter(property = "obfuscation.excludePackages")
    private String[] excludePackages;

    @Parameter(property = "obfuscation.preserveSpringBeans", defaultValue = "true")
    private boolean preserveSpringBeans;

    /**
     * LEVEL_4_ENCRYPTED only — optional.
     * 64-character hex string (= 32 bytes) used as the AES-256 key.
     * If omitted, a cryptographically random key is generated per build and
     * stored at META-INF/obf/.key inside the compiled classes.
     */
    @Parameter(property = "obfuscation.encryptionKey")
    private String encryptionKey;

    /**
     * LEVEL_4_ENCRYPTED only — recommended.
     * Binary name of the @SpringBootApplication main class,
     * e.g. "com.example.MyApplication".
     * When packaging the fat-JAR, set spring-boot-maven-plugin's
     * &lt;mainClass&gt; to "com.obfuscator.runtime.EncryptedLauncher" and
     * this value is stored in META-INF/obf/.mainclass so it is found at runtime.
     */
    @Parameter(property = "obfuscation.mainClass")
    private String mainClass;

    /**
     * Tüm uygulama sınıflarını tek bir pakette topla (paket düzleştirme).
     * <p>true ise obfuscation tamamlandıktan sonra tüm .class dosyaları
     * {@link #flattenTargetPackage} altına taşınır ve bytecode referansları
     * ASM ClassRemapper ile güncellenir.
     * <p>false (default) ise paket yapısı değiştirilmez.
     */
    @Parameter(property = "obfuscation.flattenPackages", defaultValue = "false")
    private boolean flattenPackages;

    /**
     * Paket düzleştirmesinde kullanılacak hedef paket.
     * Örn: "tr.sesasis.app"  — boş bırakılsa kök pakete taşınır.
     * {@link #flattenPackages} = true olduğunda geçerlidir.
     */
    @Parameter(property = "obfuscation.flattenTargetPackage", defaultValue = "")
    private String flattenTargetPackage;

    @Override
    public void execute() throws MojoExecutionException {
        if (!enabled) {
            getLog().info("Obfuscation devre disi, atliyor...");
            return;
        }

        getLog().info("╔════════════════════════════════════════════╗");
        getLog().info("║   Spring Boot Obfuscator Plugin v1.0.0   ║");
        getLog().info("╚════════════════════════════════════════════╝");
        getLog().info("");
        getLog().info("Obfuscation seviyesi: " + level);
        getLog().info("Spring Bean korumasi: " + (preserveSpringBeans ? "Aktif" : "Devre disi"));
        getLog().info("Paket duzlestirme  : " + (flattenPackages ? "Aktif → " +
            (flattenTargetPackage == null || flattenTargetPackage.isBlank()
                ? "(kok paket)" : flattenTargetPackage)
            : "Devre disi"));

        if (level == ObfuscationLevel.LEVEL_4_ENCRYPTED) {
            getLog().info("Sinif sifrelemesi: AES-256-GCM etkin (LEVEL_4)");
            if (encryptionKey == null || encryptionKey.isBlank()) {
                getLog().info("  encryptionKey belirtilmedi — rastgele anahtar olusturuluyor.");
            }
            if (mainClass == null || mainClass.isBlank()) {
                getLog().warn("  obfuscation.mainClass belirtilmedi. " +
                    "EncryptedLauncher calisma zamaninda .mainclass kaynagini okuyacak.");
            }
        }

        String outputDirectory = project.getBuild().getOutputDirectory();
        File   classesDir      = new File(outputDirectory);

        if (!classesDir.exists()) {
            getLog().warn("Classes dizini bulunamadi: " + outputDirectory);
            return;
        }

        ObfuscationConfig config = new ObfuscationConfig();
        config.setLevel(level);
        config.setPreserveSpringBeans(preserveSpringBeans);
        config.setExcludePackages(excludePackages);
        config.setEncryptionKey(encryptionKey);
        config.setMainClass(mainClass);
        config.setFlattenPackages(flattenPackages);
        config.setFlattenTargetPackage(flattenTargetPackage);

        ClassProcessor processor = new ClassProcessor(config, getLog());

        try {
            List<Path> partialClasses = processDirectory(classesDir.toPath(), processor);

            // ── LEVEL_4: AES-256-GCM encrypt all PARTIAL-protected classes ──
            if (level == ObfuscationLevel.LEVEL_4_ENCRYPTED && !partialClasses.isEmpty()) {
                encryptPartialClasses(partialClasses, classesDir.toPath());
            }

            // ── Paket düzleştirme (obfuscation bittikten sonra) ──
            if (flattenPackages) {
                getLog().info("");
                PackageFlattenProcessor flattener =
                    new PackageFlattenProcessor(getLog(), flattenTargetPackage);
                flattener.flatten(classesDir.toPath());
                getLog().info("[FLATTEN] Tamamlandi. " +
                    flattener.getRenameMap().size() + " sinif yeniden eslendi.");
            }

            getLog().info("");
            getLog().info("Obfuscation tamamlandi!");

        } catch (Exception e) {
            throw new MojoExecutionException("Obfuscation sirasinda hata olustu", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Walks the classes directory, processes every .class file, and returns
     * the paths of files that were PARTIAL-protected (Services, Repositories, etc.).
     */
    private List<Path> processDirectory(Path directory, ClassProcessor processor)
            throws IOException {

        List<Path> partialClasses = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(path -> path.toString().endsWith(".class"))
                 .filter(path -> !isExcluded(path))
                 .forEach(path -> {
                     try {
                         ProtectionLevel pl = processor.processClass(path);
                         if (pl == ProtectionLevel.PARTIAL) {
                             partialClasses.add(path);
                         }
                     } catch (Exception e) {
                         getLog().error("Hata: " + path.getFileName(), e);
                     }
                 });
        }

        return partialClasses;
    }

    /**
     * Encrypts already-obfuscated (L1/2/3) PARTIAL class bytes with AES-256-GCM
     * and writes runtime bootstrap metadata + injected classloader classes.
     */
    private void encryptPartialClasses(List<Path> partialClasses, Path classesRoot)
            throws Exception {

        getLog().info("");
        getLog().info("[LEVEL_4] " + partialClasses.size() +
                      " PARTIAL sinif AES-256-GCM ile sifreleniyor...");

        ClassEncryptionProcessor cep =
            (encryptionKey != null && !encryptionKey.isBlank())
                ? new ClassEncryptionProcessor(getLog(), encryptionKey)
                : new ClassEncryptionProcessor(getLog());

        for (Path classFile : partialClasses) {
            cep.encryptClass(classFile, classesRoot);
        }

        // Resolve main class: explicit param → Maven property → fallback
        String mc = (mainClass != null && !mainClass.isBlank())
            ? mainClass
            : project.getProperties().getProperty("start-class",
              project.getProperties().getProperty("exec.mainClass", "UNKNOWN_MAIN_CLASS"));

        cep.writeMetadata(classesRoot, mc);
    }

    private boolean isExcluded(Path path) {
        if (excludePackages == null || excludePackages.length == 0) {
            return false;
        }
        String pathString = path.toString().replace(File.separator, ".");
        for (String pkg : excludePackages) {
            if (pathString.contains(pkg)) {
                return true;
            }
        }
        return false;
    }
}
