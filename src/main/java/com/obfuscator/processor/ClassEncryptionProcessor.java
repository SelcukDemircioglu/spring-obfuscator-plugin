package com.obfuscator.processor;

import com.obfuscator.runtime.EncryptedClassLoader;
import org.apache.maven.plugin.logging.Log;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Build-time processor for LEVEL_4_ENCRYPTED.
 *
 * Responsibilities:
 *  1. Collects all obfuscated .class files into an in-memory ZIP.
 *  2. writeMetadata() finalises the ZIP, AES-256-GCM encrypts the whole blob,
 *     and writes it as a single file: META-INF/obf/classes.zip.enc
 *     Also writes:
 *       META-INF/obf/.key         — 32-byte raw AES key
 *       META-INF/obf/.mainclass   — original Spring Boot main class binary name
 *       META-INF/obf/.pkg         — app root package for child-first ECL scope
 *  3. Injects two runtime classes from the plugin JAR into the classes root:
 *       com/obfuscator/runtime/EncryptedClassLoader.class
 *       com/obfuscator/runtime/EncryptedLauncher.class
 *  4. Generates META-INF/spring.components component index so Spring skips
 *     classpath scanning and uses ClassLoader.loadClass() instead → ECL.
 *  5. Deletes all original .class files that were packed into the ZIP
 *     → the final JAR contains NO plain bytecode for application classes.
 *
 * Security advantage over per-class .enc approach:
 *  - Single opaque blob — class boundaries are not visible to an attacker.
 *  - No individual .enc files that can be cracked one-by-one.
 *  - Runtime: ECL decrypts the ZIP ONCE at startup, caches all bytes in memory.

 */
public class ClassEncryptionProcessor {

    /** AES-256 requires a 32-byte key. */
    private static final int KEY_BYTES = 32;

    private final Log    log;
    private final byte[] keyBytes;

    /** In-memory ZIP accumulator — all obfuscated class bytes go here. */
    private final ByteArrayOutputStream zipBuffer      = new ByteArrayOutputStream(1 << 20); // 1 MB initial
    private final ZipOutputStream       zipOut;
    private int                         classCount     = 0;

    /** Spring stereotype index: binaryClassName → stereotype FQCN */
    private final Map<String, String> springIndex    = new LinkedHashMap<>();
    /** All .class files collected into the ZIP (to be deleted after encryption). */
    private final List<Path>          collectedFiles = new ArrayList<>();
    /** Binary names of interface classes (for META-INF/obf/.interfaces). */
    private final Set<String>         interfaceNames = new LinkedHashSet<>();

    /** Constructor that generates a fresh random AES-256 key. */
    public ClassEncryptionProcessor(Log log) {
        this.log      = log;
        this.keyBytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(this.keyBytes);
        this.zipOut   = new ZipOutputStream(zipBuffer);
    }

    /** Constructor that uses a caller-supplied key (hex-encoded, 64 chars = 32 bytes). */
    public ClassEncryptionProcessor(Log log, String hexKey) {
        this.log      = log;
        this.keyBytes = hexToBytes(hexKey);
        if (this.keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                "encryptionKey must be a 64-character hex string (32 bytes / AES-256)");
        }
        this.zipOut = new ZipOutputStream(zipBuffer);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Adds {@code classFile} into the internal ZIP buffer.
     * ZIP entry path: "tr/sesasis/app/a.class" (forward-slash, relative to classesRoot).
     *
     * Interface .class files are intentionally NOT added to collectedFiles (deleted list).
     * Spring Data JPA repository interfaces must remain as physical .class files so that
     * PathMatchingResourcePatternResolver can enumerate and discover them during scanning.
     * Interface files contain only method signatures — no implementation logic.
     *
     * @param classFile   absolute path to the already-obfuscated .class file
     * @param classesRoot Maven build output directory (target/classes)
     */
    public void encryptClass(Path classFile, Path classesRoot) throws Exception {
        byte[] classBytes = Files.readAllBytes(classFile);

        // Forward-slash path relative to classesRoot, e.g. "tr/sesasis/app/Foo.class"
        String entryName = classesRoot.relativize(classFile)
                                      .toString()
                                      .replace(File.separatorChar, '/');

        ZipEntry entry = new ZipEntry(entryName);
        zipOut.putNextEntry(entry);
        zipOut.write(classBytes);
        zipOut.closeEntry();

        classCount++;

        // Logging: binary name form
        String binaryName = entryName.endsWith(".class")
            ? entryName.substring(0, entryName.length() - 6).replace('/', '.')
            : entryName;
        log.debug("  [LEVEL_4] ZIP'e eklendi: " + binaryName);

        // HEPSİNİ ZIP'e al ve silinecekler listesine ekle.
        // Interface'ler artık ECL'in buildInterfaceJar() ile temp JAR'dan sunulur;
        // bu sayede Spring Data JPA repository'leri sorunsuz bulunur.
        collectedFiles.add(classFile);

        // Interface ise binary adını .interfaces listesine ekle (ECL okuyacak)
        boolean isInterface = (new ClassReader(classBytes).getAccess() & Opcodes.ACC_INTERFACE) != 0;
        if (isInterface) {
            interfaceNames.add(binaryName);
        }

        // Spring annotation tarama → spring.components index için
        scanSpringAnnotations(classBytes, binaryName);
    }

    /**
     * Finalises the ZIP, encrypts the blob, and writes all metadata files.
     *
     * @param classesRoot       Maven build output directory
     * @param originalMainClass binary name of the @SpringBootApplication main class
     *                          (e.g. "tr.sesasis.MediaAdminApplication")
     */
    public void writeMetadata(Path classesRoot, String originalMainClass) throws Exception {
        // 1. Finalise the ZIP stream
        zipOut.finish();
        zipOut.flush();
        byte[] zipBytes = zipBuffer.toByteArray();

        log.info("  [LEVEL_4] ZIP hazır: " + classCount + " sınıf, "
                 + (zipBytes.length / 1024) + " KB");

        // 2. AES-256-GCM encrypt the entire ZIP blob
        byte[] encryptedBlob = EncryptedClassLoader.encrypt(zipBytes, keyBytes);

        Path obfDir = classesRoot.resolve("META-INF").resolve("obf");
        Files.createDirectories(obfDir);

        // 3. Write encrypted ZIP blob
        Files.write(obfDir.resolve("classes.zip.enc"), encryptedBlob);
        log.info("  [LEVEL_4] Şifreli blob yazıldı → META-INF/obf/classes.zip.enc ("
                 + (encryptedBlob.length / 1024) + " KB)");

        // 4. .key  — raw 32-byte AES-256 key
        Files.write(obfDir.resolve(".key"), keyBytes);

        // 5. .mainclass  — original main application class
        Files.write(obfDir.resolve(".mainclass"),
                    originalMainClass.getBytes(StandardCharsets.UTF_8));

        // 6. .pkg — root package for EncryptedClassLoader child-first scope
        //    e.g. "tr.sesasis.MediaAdminApplication" → "tr.sesasis"
        String rootPkg = originalMainClass.contains(".")
            ? originalMainClass.substring(0, originalMainClass.lastIndexOf('.'))
            : originalMainClass;
        Files.write(obfDir.resolve(".pkg"),
                    rootPkg.getBytes(StandardCharsets.UTF_8));

        // 7. Inject runtime classloader & launcher from plugin JAR
        injectRuntimeClass("com/obfuscator/runtime/EncryptedClassLoader.class", classesRoot);
        injectRuntimeClass("com/obfuscator/runtime/EncryptedLauncher.class",    classesRoot);

        // 7b. Write interface names list for ECL's buildInterfaceJar()
        if (!interfaceNames.isEmpty()) {
            Files.write(obfDir.resolve(".interfaces"),
                        String.join("\n", interfaceNames).getBytes(StandardCharsets.UTF_8));
            log.info("  [LEVEL_4] .interfaces: " + interfaceNames.size()
                     + " interface binary adı kaydedildi → META-INF/obf/.interfaces");
        }

        // 8. Spring component index ─ Spring bunu bulunca classpath taraması yapmaz,
        //    direkt loadClass() çağırır → EncryptedClassLoader devreye girer.
        if (!springIndex.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            springIndex.forEach((cls, stereotype) ->
                sb.append(cls).append('=').append(stereotype).append('\n'));
            Path metaInf = classesRoot.resolve("META-INF");
            Files.createDirectories(metaInf);
            Files.write(metaInf.resolve("spring.components"),
                        sb.toString().getBytes(StandardCharsets.UTF_8));
            log.info("  [LEVEL_4] spring.components: " + springIndex.size()
                     + " bileşen indekslendi (classpath taraması atlanacak)");
        }

        // 9. Orijinal .class dosyalarını sil ─ artık classes.zip.enc içinde şifreli.
        //    spring.components index sayesinde Spring'in component scanning'i
        //    loadClass() üzerinden ECL'e yönlendirilir → .class dosyasına gerek yok.
        int deletedCount = 0;
        for (Path f : collectedFiles) {
            try {
                Files.deleteIfExists(f);
                deletedCount++;
            } catch (IOException e) {
                log.warn("  [LEVEL_4] Silinemedi: " + f + " — " + e.getMessage());
            }
        }
        if (deletedCount > 0) {
            log.info("  [LEVEL_4] " + deletedCount
                     + " .class dosyası silindi → JAR'da şifreli blob dışında kaynak kod kalmadı");
        }

        // 9b. .class dosyaları silindikten sonra geride kalan boş uygulama
        //     alt dizinlerini sil. Spring Boot JAR paketi bunları 0-byte entry
        //     olarak ekler; gerekli değiller (ECL getResources override'ı
        //     temp interface JAR'ı kullanır, fiziksel dizin entry'lerine ihtiyaç yok).
        String pkgRelPath = originalMainClass.contains(".")
            ? originalMainClass.substring(0, originalMainClass.lastIndexOf('.'))
                               .replace('.', File.separatorChar)
            : "";
        if (!pkgRelPath.isEmpty()) {
            Path pkgRoot = classesRoot.resolve(pkgRelPath);
            if (Files.isDirectory(pkgRoot)) {
                int[] emptyDirs = {0};
                try (Stream<Path> walked = Files.walk(pkgRoot)) {
                    walked.filter(Files::isDirectory)
                          .sorted(Comparator.reverseOrder())   // yapraktan köke
                          .forEach(dir -> {
                              try (Stream<Path> children = Files.list(dir)) {
                                  if (children.findAny().isEmpty()) {
                                      Files.deleteIfExists(dir);
                                      emptyDirs[0]++;
                                  }
                              } catch (IOException ignored) {}
                          });
                } catch (IOException e) {
                    log.debug("  [LEVEL_4] Dizin temizleme hatası: " + e.getMessage());
                }
                if (emptyDirs[0] > 0) {
                    log.info("  [LEVEL_4] " + emptyDirs[0]
                             + " boş uygulama dizini silindi → JAR'da gereksiz entry kalmadı");
                }
            }
        }

        // 10. Ana class'ın yerine minimal stub yaz.
        //     Spring Boot JarLauncher MANIFEST.MF'deki Start-Class'ı yüklemek için
        //     bu stub'ı bulur. Stub sadece EncryptedLauncher.main()'e delege eder.
        //     Gerçek @SpringBootApplication ise classes.zip.enc içinde şifreli → ECL yükler.
        String mainClassSlash = originalMainClass.replace('.', '/');
        Path   stubPath       = classesRoot.resolve(
                                    mainClassSlash.replace('/', File.separatorChar) + ".class");
        Files.createDirectories(stubPath.getParent());
        Files.write(stubPath, generateMainClassStub(mainClassSlash));
        log.info("  [LEVEL_4] Start-Class stub yazıldı → " + originalMainClass
                 + " (EncryptedLauncher.main() delegate)");

        log.info("");
        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║  LEVEL_4:  " + classCount + " sınıf → tek şifreli ZIP blobu          ║");
        log.info("║  classes.zip.enc → AES-256-GCM, tek seferlik decrypt    ║");
        log.info("║  spring.components index → ECL üzerinden yükleme        ║");
        log.info("║  Orijinal .class dosyaları JAR'dan kaldırıldı           ║");
        log.info("╚══════════════════════════════════════════════════════════╝");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * ASM ile minimal bir stub class üretir.
     * <p>
     * Stub'ın görevi: Spring Boot JarLauncher'ın MANIFEST.MF Start-Class'ı
     * (örn. "tr.sesasis.MediaAdminApplication") sınıfını bulabilmesi ve
     * EncryptedLauncher'a delege etmesi. Gerçek main class ZIP'te şifrelidir.
     *
     * @param mainClassSlash iç adı, "/" ile ayrılmış, ör. "tr/sesasis/MediaAdminApplication"
     */
    private byte[] generateMainClassStub(String mainClassSlash) {
        ClassWriter cw = new ClassWriter(0);
        // public class tr/sesasis/MediaAdminApplication extends java/lang/Object
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, mainClassSlash, null, "java/lang/Object", null);

        // public <init>() { super(); }
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // public static void main(String[] args) throws Exception {
        //     com.obfuscator.runtime.EncryptedLauncher.main(args);
        // }
        MethodVisitor main = cw.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "main", "([Ljava/lang/String;)V", null,
            new String[]{"java/lang/Exception"});
        main.visitCode();
        main.visitVarInsn(Opcodes.ALOAD, 0);
        main.visitMethodInsn(Opcodes.INVOKESTATIC,
            "com/obfuscator/runtime/EncryptedLauncher",
            "main", "([Ljava/lang/String;)V", false);
        main.visitInsn(Opcodes.RETURN);
        main.visitMaxs(1, 1);
        main.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Copies a .class resource from the plugin JAR into the target classes directory.
     */
    private void injectRuntimeClass(String resourcePath, Path classesRoot) throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException(
                "Runtime class not found inside plugin JAR: " + resourcePath +
                " — did the plugin compile correctly?");
        }
        String nativeSep = resourcePath.replace('/', File.separatorChar);
        Path   target    = classesRoot.resolve(nativeSep);
        Files.createDirectories(target.getParent());
        try (is) {
            Files.write(target, is.readAllBytes());
        }
        log.debug("  Runtime class injected → " + resourcePath);
    }

    /**
     * ASM ile class byte'larını okur:
     * - Spring/JPA annotation varsa springIndex'e ekler (component indexing).
     * - Sınıf doğrudan bir Spring Data Repository interface'i extend eden interface ise
     *   "org.springframework.data.repository.Repository" stereotipiyle indeksler.
     *   Bu sayede spring.components index mevcut olduğunda Spring Data JPA da
     *   doğru repository interface'lerini bulabilir.
     */
    private void scanSpringAnnotations(byte[] classBytes, String binaryName) {
        try {
            ClassReader cr    = new ClassReader(classBytes);
            String[]    found = {null};

            cr.accept(new ClassVisitor(Opcodes.ASM9) {

                /** Class header: interface ← Spring Data Repository kontrolü */
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    if (found[0] == null && (access & Opcodes.ACC_INTERFACE) != 0
                            && interfaces != null) {
                        for (String iface : interfaces) {
                            // Doğrudan Spring Data repository type'ı extend eden interface'ler:
                            // JpaRepository, CrudRepository, PagingAndSortingRepository, etc.
                            if (iface.startsWith("org/springframework/data/")) {
                                found[0] = "org.springframework.data.repository.Repository";
                                break;
                            }
                        }
                    }
                }

                /** Class annotation */
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (found[0] == null) {
                        String s = toSpringStereotype(descriptor);
                        if (s != null) found[0] = s;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            if (found[0] != null) {
                springIndex.put(binaryName, found[0]);
            }
        } catch (Exception ignored) {
            // annotation tarama başarısız olursa sessizce geç
        }
    }

    /**
     * ASM annotation descriptor → Spring component index stereotype.
     *
     * Spring'in CandidateComponentsIndex.getCandidates() metodu, spring.components
     * dosyasını okurken stereotype olarak "org.springframework.stereotype.Component" sorgular.
     * @Configuration, @Service, @Repository, @Controller hepsi @Component meta-annotation
     * taşıdığından spring.components dosyasında HEPSI Component olarak kaydedilmelidir.
     *
     * Yanlış kullanım: @Configuration → "org.springframework.context.annotation.Configuration"
     * Bu durumda Spring bileşeni indekste bulamaz ve @Bean metotları hiç çalıştırılmaz →
     * MinioClient, SecurityConfig vb. @Bean'ler kayıt edilemez.
     *
     * JPA annotation'lar (Entity, MappedSuperclass, Embeddable) kendi namespace'leriyle
     * kaydedilir çünkü Hibernate/Spring Data bu türleri özel olarak sorgular.
     */
    private static String toSpringStereotype(String descriptor) {
        switch (descriptor) {
            case "Lorg/springframework/stereotype/Component;":
            case "Lorg/springframework/stereotype/Service;":
            case "Lorg/springframework/stereotype/Repository;":
            case "Lorg/springframework/stereotype/Controller;":
            case "Lorg/springframework/web/bind/annotation/RestController;":
            case "Lorg/springframework/web/bind/annotation/RequestMapping;":
            // @ControllerAdvice and @RestControllerAdvice are @Component meta-annotated.
            // Without these, @RestControllerAdvice classes (e.g. GlobalExceptionHandler)
            // are not written to spring.components → Spring Boot can't find them as beans
            // → @ExceptionHandler methods not registered → exceptions propagate to /error
            // → Spring Security blocks /error → client gets 403 instead of the real error code.
            case "Lorg/springframework/web/bind/annotation/ControllerAdvice;":
            case "Lorg/springframework/web/bind/annotation/RestControllerAdvice;":
            // @Configuration IS a @Component meta-annotation — must use Component stereotype
            // so Spring's CandidateComponentsIndex.getCandidates("...Component") finds it
            case "Lorg/springframework/context/annotation/Configuration;":
            case "Lorg/springframework/boot/autoconfigure/SpringBootApplication;":
                return "org.springframework.stereotype.Component";
            // @ConfigurationProperties: registered via @ConfigurationPropertiesScan.
            // CandidateComponentsIndex uses the annotation FQCN as stereotype key.
            case "Lorg/springframework/boot/context/properties/ConfigurationProperties;":
                return "org.springframework.boot.context.properties.ConfigurationProperties";
            case "Ljakarta/persistence/Entity;":
            case "Ljavax/persistence/Entity;":
                return "jakarta.persistence.Entity";
            case "Ljakarta/persistence/MappedSuperclass;":
            case "Ljavax/persistence/MappedSuperclass;":
                return "jakarta.persistence.MappedSuperclass";
            case "Ljakarta/persistence/Embeddable;":
            case "Ljavax/persistence/Embeddable;":
                return "jakarta.persistence.Embeddable";
            default:
                return null;
        }
    }

    private static byte[] hexToBytes(String hex) {
        int    len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }
}
