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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    /**
     * Düzleştirilen sınıfların dosya adları da anlamsızlaştırılsın mı?
     * true ise her taşınan sınıf a, b, c, … şeklinde kısa isim alır.
     * false (default) ise orijinal basit ad korunur.
     * {@link #flattenPackages} = true olduğunda geçerlidir.
     */
    @Parameter(property = "obfuscation.flattenObfuscateNames", defaultValue = "false")
    private boolean flattenObfuscateNames;

    /**
     * LEVEL_2 string şifreleme için AES anahtarı.
     * Tam olarak 16 karakter olmalıdır (AES-128).
     * Belirtilmezse varsayılan sabit anahtar kullanılır.
     * Maven property: -Dobfuscation.stringEncryptionKey=MySecretKey1234
     */
    @Parameter(property = "obfuscation.stringEncryptionKey")
    private String stringEncryptionKey;

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
                + (flattenObfuscateNames ? " [isim obfuscation AKTIF]" : "")
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

        // ── LEVEL_4: flatten + isim karartma ZORLA aktif ──────────────────────
        // Şifreleme öncesi sınıflar önce LEVEL_3 ile obfuscate edilmeli,
        // ardından tek pakete düzleştirilip isimler anlamsızlaştırılmalı.
        // Böylece saldırgan şifreyi çözse bile a/b/ba.class gibi anlamsız
        // bytecode'larla karşılaşır.
        if (level == ObfuscationLevel.LEVEL_4_ENCRYPTED) {
            if (!flattenPackages) {
                getLog().info("[LEVEL_4] flattenPackages=true zorla aktif edildi.");
                flattenPackages = true;
            }
            if (!flattenObfuscateNames) {
                getLog().info("[LEVEL_4] flattenObfuscateNames=true zorla aktif edildi.");
                flattenObfuscateNames = true;
            }
        }

        ObfuscationConfig config = new ObfuscationConfig();
        config.setLevel(level);
        config.setPreserveSpringBeans(preserveSpringBeans);
        config.setExcludePackages(excludePackages);
        config.setEncryptionKey(encryptionKey);
        config.setMainClass(mainClass);
        config.setFlattenPackages(flattenPackages);
        config.setFlattenTargetPackage(flattenTargetPackage);
        config.setFlattenObfuscateNames(flattenObfuscateNames);
        config.setStringEncryptionKey(stringEncryptionKey);

        ClassProcessor processor = new ClassProcessor(config, getLog());

        try {
            // ── Phase 0: Global pre-registration of private field & method renames ──
            // Level1BasicObfuscator registers field/method renames lazily when each class
            // is visited (visitField / visitMethodDeclaration). If an inner class (e.g.
            // mg$DevicePollTask) is processed before its outer class (mg), the outer
            // class's field renames are not yet in the NameGenerator when the inner
            // class's GETFIELD instructions are rewritten → the inner class keeps the
            // original field name → NoSuchFieldError at runtime.
            //
            // Fix: scan ALL class files first to register every private field and method
            // rename, then do the actual transformation pass (Phase 1).
            {
                List<Path> allClassPaths;
                try (Stream<Path> ps = Files.walk(classesDir.toPath())) {
                    allClassPaths = ps
                        .filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !isExcluded(p))
                        .collect(java.util.stream.Collectors.toList());
                }
                processor.preRegisterAllClasses(allClassPaths);
            }

            // ── Phase 1: LEVEL_3 obfuscation (alan/metot renaming + string şifreleme + control flow) ──
            List<Path> partialClasses = processDirectory(classesDir.toPath(), processor);

            // ── Phase 2: Paket düzleştirme (şifrelemeden ÖNCE yapılmalı) ──────
            // LEVEL_4'te zorunlu: düzleştirme sonrası sınıflar a/b/ba.class gibi
            // anlamsız adlar alır. Şifre çözülse bile kaynak analizi imkânsızlaşır.
            if (flattenPackages) {
                getLog().info("");
                PackageFlattenProcessor flattener =
                    new PackageFlattenProcessor(getLog(), flattenTargetPackage, flattenObfuscateNames);
                flattener.flatten(classesDir.toPath());
                getLog().info("[FLATTEN] Tamamlandi. " +
                    flattener.getRenameMap().size() + " sinif yeniden eslendi.");

                // Flatten partial class path'lerini güncelle —
                // eski path'ler artık geçersiz, rename map ile yeni path'leri hesapla
                if (level == ObfuscationLevel.LEVEL_4_ENCRYPTED) {
                    partialClasses = remapPartialClassPaths(
                            partialClasses, classesDir.toPath(), flattener.getRenameMap());
                }
            }

            // ── Phase 3: AES-256-GCM şifreleme (flatten + obfuscation SONRASI) ─
            // partialClasses sadece PARTIAL koruma alan ana sınıfları içerir;
            // inner class'lar ($...) STANDARD alır ve listeye girmez → şifrelenmez → silinmez.
            // Bu nedenle flat hedef dizindeki TÜM .class dosyaları şifrelenir
            // ve ardından flat dizinin tamamı diskten kaldırılır.
            if (level == ObfuscationLevel.LEVEL_4_ENCRYPTED) {
                // Flat hedef paketini belirle (herhangi bir proje için generic):
                //  1. flattenTargetPackage açıkça verilmişse onu kullan
                //  2. mainClass'tan türet (com.example.Main → com/example)
                //  3. İkisi de yoksa tüm classes dizini taranır (META-INF + obfuscator runtime hariç)
                String flatPkgSlash = null;
                if (flattenTargetPackage != null && !flattenTargetPackage.isBlank()) {
                    flatPkgSlash = flattenTargetPackage.replace('.', '/');
                } else if (mainClass != null && mainClass.contains(".")) {
                    flatPkgSlash = mainClass.substring(0, mainClass.lastIndexOf('.')).replace('.', '/');
                }

                List<Path> allFlatClasses = new ArrayList<>();
                Path flatDir = null;

                if (flatPkgSlash != null) {
                    flatDir = classesDir.toPath().resolve(flatPkgSlash.replace('/', File.separatorChar));
                    if (Files.exists(flatDir)) {
                        try (Stream<Path> stream = Files.walk(flatDir)) {
                            stream.filter(p -> p.toString().endsWith(".class"))
                                  .forEach(allFlatClasses::add);
                        }
                    }
                } else {
                    // fallback: tüm classesDir — META-INF ve com/obfuscator/runtime hariç
                    final Path cr = classesDir.toPath();
                    try (Stream<Path> stream = Files.walk(cr)) {
                        stream.filter(p -> p.toString().endsWith(".class"))
                              .filter(p -> {
                                  String rel = cr.relativize(p).toString().replace(File.separatorChar, '/');
                                  return !rel.startsWith("META-INF/")
                                      && !rel.startsWith("com/obfuscator/");
                              })
                              .forEach(allFlatClasses::add);
                    }
                }

                // ── Log pattern injection şifrelemeden ÖNCE yapılır ──────────
                // Enjekte edilen @Configuration sınıfı (AppBeanConfig → random isim)
                // flat dizine yazılır; böylece Phase 3 onu da şifreler/siler.
                injectLogPatternConfig(classesDir.toPath());

                // Enjeksiyon sonrası flat dizin listesini yenile (yeni .class eklendi)
                allFlatClasses.clear();
                if (flatPkgSlash != null && flatDir != null && Files.exists(flatDir)) {
                    try (Stream<Path> stream = Files.walk(flatDir)) {
                        stream.filter(p -> p.toString().endsWith(".class"))
                              .forEach(allFlatClasses::add);
                    }
                } else if (flatPkgSlash == null) {
                    final Path cr2 = classesDir.toPath();
                    try (Stream<Path> stream = Files.walk(cr2)) {
                        stream.filter(p -> p.toString().endsWith(".class"))
                              .filter(p -> {
                                  String rel = cr2.relativize(p).toString().replace(File.separatorChar, '/');
                                  return !rel.startsWith("META-INF/")
                                      && !rel.startsWith("com/obfuscator/");
                              })
                              .forEach(allFlatClasses::add);
                    }
                }

                // LEVEL_4: Flat paket dışında kalan app sınıflarını da ekle
                // (enum'lar, @Configuration, main class — bunlar FULL-protected, flat'e taşınmadı)
                // Tüm tr/sesasis/** gizlenmeli; ECL loadClass() üzerinden sağlar.
                {
                    String mc = (mainClass != null && !mainClass.isBlank())
                        ? mainClass
                        : project.getProperties().getProperty("start-class",
                          project.getProperties().getProperty("exec.mainClass", ""));
                    if (mc.contains(".")) {
                        String rootPkgSlash = mc.substring(0, mc.lastIndexOf('.'))
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
                }

                if (!allFlatClasses.isEmpty()) {
                    encryptPartialClasses(allFlatClasses, classesDir.toPath());
                }

                // NOT: encryptPartialClasses() içinde ClassEncryptionProcessor:
                //  1. Tüm .class dosyalarını AES-256-GCM şifreli classes.zip.enc'e ekler.
                //  2. META-INF/spring.components index dosyası oluşturur
                //     → Spring classpath taraması yapmaz, loadClass() üzerinden ECL'e yönlenir.
                //  3. Orijinal .class dosyalarını siler → JAR'da düz bytecode kalmaz.
                //  4. MANIFEST.MF Start-Class (mainClass) yerine minimal stub yazar.
                //     Stub → EncryptedLauncher.main() → ECL → gerçek main class (ZIP'ten)
            } else {
                // LEVEL_4 dışında enjeksiyon burada yapılır
                injectLogPatternConfig(classesDir.toPath());
            }

            getLog().info("");
            getLog().info("Obfuscation tamamlandi!");

            // JAR'a gömülecek pom.xml'den com.obfuscator plugin/profile referanslarını temizle
            sanitizeProjectPom();

        } catch (Exception e) {
            throw new MojoExecutionException("Obfuscation sirasinda hata olustu", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Flatten sonrası partial class Path listesini günceller.
     * PackageFlattenProcessor sınıfları taşıdıktan sonra eski path'ler geçersizleşir.
     * renameMap (oldInternal → newInternal) kullanarak her path'i yeniden hesaplar.
     */
    private List<Path> remapPartialClassPaths(List<Path> partialClasses,
                                               Path classesRoot,
                                               java.util.Map<String, String> renameMap) {
        List<Path> remapped = new ArrayList<>();
        for (Path oldPath : partialClasses) {
            // Path → internal name (e.g. "tr/sesasis/tren/service/TripService")
            String relative = classesRoot.relativize(oldPath).toString()
                    .replace(File.separatorChar, '/');
            String oldInternal = relative.endsWith(".class")
                    ? relative.substring(0, relative.length() - 6) : relative;

            String newInternal = renameMap.get(oldInternal);
            if (newInternal != null) {
                remapped.add(classesRoot.resolve(newInternal + ".class"));
            } else {
                // Taşınmamış sınıf (FULL korumalı vs.) — eski path hâlâ geçerli
                if (java.nio.file.Files.exists(oldPath)) {
                    remapped.add(oldPath);
                }
            }
        }
        return remapped;
    }

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

    /**
     * AppBeanConfig.class'ı plugin JAR'ından alıp target projenin kendi paketine enjekte eder.
     * ASM ClassRemapper ile sınıfın internal adı ve paketi target projenin paketine taşınır,
     * böylece projenin kendi sınıfı gibi görünür (com.obfuscator.runtime paketinde kalmaz).
     *
     * LEVEL_4_ENCRYPTED özel durumu:
     *   Flat paketteki sınıflar şifrelenir → Spring'in AutoConfigurationSorter'ı
     *   şifreli bytecode'dan metadata okuyamaz → IllegalStateException.
     *   Bu nedenle LEVEL_4'te class, Phase 3 şifrelemesinden muaf tutulan
     *   com/obfuscator/runtime/ altına yazılır.
     */
    private void injectLogPatternConfig(Path classesRoot) throws IOException {
        final String SOURCE_INTERNAL = "com/obfuscator/runtime/AppBeanConfig";

        // Her build'de farklı rastgele sınıf adı üret (UUID'nin ilk 8 hex karakteri)
        final String INJECTED_SIMPLE = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // LEVEL_4: flat paketteki sınıflar şifrelenir, Spring metadata okuyamaz.
        // Bu nedenle LEVEL_4'te sınıf şifreleme dışındaki com/obfuscator/runtime/ altına yazılır.
        final boolean isLevel4 = (level == ObfuscationLevel.LEVEL_4_ENCRYPTED);

        String targetSlashPkg;
        if (isLevel4) {
            // com/obfuscator/runtime/ → Phase 3 tarafından şifrelenmez
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

        // Plugin JAR'ından raw bytes oku
        byte[] classBytes;
        try (InputStream is = getClass().getResourceAsStream("/" + SOURCE_INTERNAL + ".class")) {
            if (is == null) {
                getLog().warn("[INJECT] AppBeanConfig.class bulunamadı — atlıyor.");
                return;
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            is.transferTo(buf);
            classBytes = buf.toByteArray();
        }

        // ASM ile paketi/sınıf adını target projenin paketine taşı
        ClassReader reader   = new ClassReader(classBytes);
        ClassWriter writer   = new ClassWriter(0);
        ClassRemapper remapper = new ClassRemapper(writer,
                new SimpleRemapper(SOURCE_INTERNAL, TARGET_INTERNAL));
        reader.accept(remapper, 0);
        byte[] remapped = writer.toByteArray();

        // Yaz
        Path targetFile = classesRoot.resolve(TARGET_INTERNAL + ".class");
        Files.createDirectories(targetFile.getParent());
        Files.write(targetFile, remapped);
        getLog().info("[INJECT] " + TARGET_INTERNAL.replace('/', '.') + " enjekte edildi");

        // Spring Boot auto-configuration kaydı
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

    // ── Embedded pom.xml sanitizer ──────────────────────────────────────────────

    /**
     * JAR içine gömülecek pom.xml'den com.obfuscator plugin ve boş kalan
     * profile/build/plugins elementlerini kaldırır.
     *
     * <p>Maven'ın maven-archiver'ı, jar oluştururken {@code project.getFile()}'dan
     * pom.xml okur. Bu metod temizlenmiş bir kopyayı {@code target/sanitized-pom.xml}
     * olarak yazar ve {@code project.setFile()} ile Maven'ın bu kopyayı kullanmasını sağlar.
     * Böylece obfuscated jar içinde obfuscator konfigürasyonu görünmez.
     */
    private void sanitizeProjectPom() {
        try {
            File originalPom = project.getFile();
            if (originalPom == null || !originalPom.exists()) {
                getLog().debug("[POM-SANITIZE] pom.xml bulunamadı — atlanıyor.");
                return;
            }

            byte[] pomBytes = Files.readAllBytes(originalPom.toPath());

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            // XXE koruması
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(pomBytes));

            // 1) com.obfuscator i&#231;eren t&#252;m &lt;profile&gt; nodlar&#305;n&#305; b&#252;t&#252;n&#252;yle kald&#305;r
            removeObfuscatorProfiles(doc);

            // 2) &lt;plugin&gt; d&#252;zeyinde kalan ba&#351;ka com.obfuscator referanslar&#305;n&#305; kald&#305;r
            removeObfuscatorPluginNodes(doc);

            // 3) Bo&#351; kalan container elementlerini temizle
            removeEmptyContainerNodes(doc, "plugins");
            removeEmptyContainerNodes(doc, "build");
            removeEmptyContainerNodes(doc, "profiles");

            // 3) Serialize
            Transformer tf = TransformerFactory.newInstance().newTransformer();
            tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            tf.setOutputProperty(OutputKeys.INDENT, "yes");
            tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            StringWriter sw = new StringWriter();
            tf.transform(new DOMSource(doc), new StreamResult(sw));

            // 4) target/sanitized-pom.xml'e yaz + Maven'a bildir
            File sanitizedPom = new File(project.getBuild().getDirectory(), "sanitized-pom.xml");
            sanitizedPom.getParentFile().mkdirs();
            Files.writeString(sanitizedPom.toPath(), sw.toString());

            project.setFile(sanitizedPom);
            getLog().info("[POM-SANITIZE] Embedded pom.xml'den com.obfuscator referanslari temizlendi.");

        } catch (Exception e) {
            getLog().warn("[POM-SANITIZE] pom.xml temizleme atlanıyor: " + e.getMessage());
        }
    }

    /**
     * com.obfuscator groupId'li plugin içeren tüm &lt;profile&gt; nodlarını
     * (id, activation, build, properties dahil) bütünüyle kaldırır.
     */
    private void removeObfuscatorProfiles(Document doc) {
        NodeList profileNodes = doc.getElementsByTagName("profile");
        List<Node> toRemove = new ArrayList<>();

        for (int i = 0; i < profileNodes.getLength(); i++) {
            Node profile = profileNodes.item(i);
            if (profile instanceof org.w3c.dom.Element
                    && profileContainsObfuscator((org.w3c.dom.Element) profile)) {
                toRemove.add(profile);
            }
        }

        for (Node node : toRemove) {
            Node parent = node.getParentNode();
            if (parent != null) {
                Node prev = node.getPreviousSibling();
                if (prev != null && prev.getNodeType() == Node.TEXT_NODE
                        && prev.getNodeValue().isBlank()) {
                    parent.removeChild(prev);
                }
                parent.removeChild(node);
            }
        }

        if (!toRemove.isEmpty()) {
            getLog().info("[POM-SANITIZE] " + toRemove.size()
                + " com.obfuscator profili kaldırıldı.");
        }
    }

    /** Profile altındaki herhangi bir &lt;plugin&gt;'in groupId'si com.obfuscator mu? */
    private boolean profileContainsObfuscator(org.w3c.dom.Element profile) {
        NodeList plugins = profile.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            NodeList children = plugins.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if ("groupId".equals(child.getNodeName())
                        && "com.obfuscator".equals(child.getTextContent().trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * DOM ağacında groupId = com.obfuscator olan tüm &lt;plugin&gt; düğümlerini kaldırır.
     */
    private void removeObfuscatorPluginNodes(Document doc) {
        NodeList pluginNodes = doc.getElementsByTagName("plugin");
        List<Node> toRemove = new ArrayList<>();

        for (int i = 0; i < pluginNodes.getLength(); i++) {
            Node plugin = pluginNodes.item(i);
            NodeList children = plugin.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if ("groupId".equals(child.getNodeName())
                        && "com.obfuscator".equals(child.getTextContent().trim())) {
                    toRemove.add(plugin);
                    break;
                }
            }
        }

        for (Node node : toRemove) {
            Node parent = node.getParentNode();
            if (parent != null) {
                // satır sonu whitespace text düğümünü de kaldır
                Node prev = node.getPreviousSibling();
                if (prev != null && prev.getNodeType() == Node.TEXT_NODE
                        && prev.getNodeValue().isBlank()) {
                    parent.removeChild(prev);
                }
                parent.removeChild(node);
            }
        }
    }

    /**
     * Verilen tag adındaki elementleri: sadece whitespace içeriyorsa (gerçek alt element yok)
     * DOM'dan kaldırır.
     */
    private void removeEmptyContainerNodes(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        List<Node> toRemove = new ArrayList<>();

        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            boolean hasElementChild = false;
            NodeList children = node.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                if (children.item(j).getNodeType() == Node.ELEMENT_NODE) {
                    hasElementChild = true;
                    break;
                }
            }
            if (!hasElementChild) {
                toRemove.add(node);
            }
        }

        for (Node node : toRemove) {
            Node parent = node.getParentNode();
            if (parent != null) {
                Node prev = node.getPreviousSibling();
                if (prev != null && prev.getNodeType() == Node.TEXT_NODE
                        && prev.getNodeValue().isBlank()) {
                    parent.removeChild(prev);
                }
                parent.removeChild(node);
            }
        }
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
