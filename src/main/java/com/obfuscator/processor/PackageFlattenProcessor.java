package com.obfuscator.processor;

import org.apache.maven.plugin.logging.Log;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * Tüm uygulama sınıflarını tek bir hedef pakette toplar.
 *
 * <p>Özellikler:
 * <ul>
 *   <li>ASM ClassRemapper ile tüm bytecode referansları güncellenir.</li>
 *   <li>İç sınıflar (Outer$Inner) dış sınıfıyla aynı hedef pakete taşınır.</li>
 *   <li>Ad çakışmaları, sayısal son ek eklenerek çözülür (MyClass → MyClass1).</li>
 *   <li>META-INF/ dizini hiçbir zaman değiştirilmez.</li>
 *   <li>Boş dizinler taşıma sonrasında temizlenir.</li>
 * </ul>
 *
 * <p>Kullanım:
 * <pre>
 *   new PackageFlattenProcessor(log, "tr.sesasis.flat").flatten(classesRoot);
 * </pre>
 */
public class PackageFlattenProcessor {

    private final Log    log;
    /** Hedef paket — iç ad (slash), boş ise kök paket. Örn: "tr/sesasis/flat" */
    private final String targetPackageInternal;

    /** oldInternal → newInternal (META-INF hariç, yalnızca gerçekten taşınanlar) */
    private final Map<String, String> renameMap = new LinkedHashMap<>();

    /**
     * Sınıf adları da anlamsızlaştırılsın mı?
     * true → her taşınan sınıf a, b, c, … şeklinde kısa bir isim alır.
     * false → orijinal basit ad korunur (yalnızca paket taşınır).
     */
    private final boolean obfuscateNames;

    /** obfuscateNames=true iken sınıf ismi sayacı */
    private int classNameCounter = 0;

    /**
     * Bu annotasyonlardan herhangi birini taşıyan sınıflar asla taşınmaz:
     *  - @SpringBootApplication  → JAR Start-Class MANIFEST'e yazılır, sabit kalmalı
     *  - @Entity / @MappedSuperclass → Hibernate alan adı = kolon adı, değişemez
     *  - @Configuration          → Spring proxy'si sınıf adına göre çalışır
     */
    private static final Set<String> FIXED_ANNOTATIONS = new HashSet<>(Arrays.asList(
        "Lorg/springframework/boot/autoconfigure/SpringBootApplication;",
        "Lorg/springframework/context/annotation/Configuration;",
        "Ljakarta/persistence/Entity;",
        "Ljakarta/persistence/Table;",
        "Ljakarta/persistence/MappedSuperclass;"
    ));

    // ───────────────────────────────────────────────────────────────────────
    public PackageFlattenProcessor(Log log, String targetPackage) {
        this(log, targetPackage, false);
    }

    public PackageFlattenProcessor(Log log, String targetPackage, boolean obfuscateNames) {
        this.log = log;
        // "tr.sesasis.flat" → "tr/sesasis/flat", boş → ""
        this.targetPackageInternal = (targetPackage == null || targetPackage.isBlank())
            ? ""
            : targetPackage.replace('.', '/').replaceAll("/$", "");
        this.obfuscateNames = obfuscateNames;
    }

    /**
     * Sıralı sayaç tabanlı kısa isim üretir: a, b, …, z, aa, ab, …, az, ba, …
     *
     * <p>Yalnızca küçük harf kullanılır — macOS case-insensitive dosya sistemi
     * nedeniyle 'a' ve 'A' aynı dosyaya yazılır; bu da ClassNotFoundException'a
     * yol açar. Tüm küçük harf kombinasyonları bu sorunu tamamen önler.
     */
    private String nextObfuscatedName() {
        int n = classNameCounter++;
        StringBuilder sb = new StringBuilder();
        do {
            sb.append((char) ('a' + (n % 26)));
            n /= 26;
        } while (n > 0);
        return sb.reverse().toString();
    }

    // ───────────────────────────────────────────────────────────────────────
    // Public API
    // ───────────────────────────────────────────────────────────────────────

    /**
     * classesRoot altındaki tüm .class dosyalarını hedef pakete düzleştirir.
     */
    public void flatten(Path classesRoot) throws IOException {
        // Faz-0: Hedef paketteki eski flatten çıktılarını temizle.
        //   "mvn clean" kullanılmadan ardışık derlemelerde, önceki flatten çalışmasından
        //   kalan sınıf dosyaları hedef pakette kalır ve basit ad tahsisini bölmez
        //   (ör. ContentCategoryDto bir önceki çalışmadan var → yenisi ContentCategoryDto1 olur).
        //   Temizlik sayesinde Faz-1 her zaman tutarlı bir harita üretir.
        cleanTargetPackageDir(classesRoot);

        // Faz-1: yeniden adlandırma haritasını oluştur
        buildRenameMap(classesRoot);

        if (renameMap.isEmpty()) {
            log.info("[FLATTEN] Taşınacak sınıf bulunamadı, atlanıyor.");
            return;
        }

        log.info("[FLATTEN] " + renameMap.size() + " sınıf → "
            + (targetPackageInternal.isEmpty()
               ? "(kök paket)"
               : targetPackageInternal.replace('/', '.')));

        // Tüm .class yollarını önce listele (walk sırasında dosya taşıma olmasin)
        List<Path> allClassFiles;
        try (Stream<Path> stream = Files.walk(classesRoot)) {
            allClassFiles = stream
                .filter(p -> p.toString().endsWith(".class"))
                .toList();
        }

        // Faz-2: bytecode referanslarını yeniden eşle
        for (Path classFile : allClassFiles) {
            remapBytecode(classFile);
        }

        // Faz-3: dosyaları hedef konuma taşı
        moveFiles(allClassFiles, classesRoot);

        // Faz-4: boş dizinleri temizle
        removeEmptyDirs(classesRoot);
    }

    // ───────────────────────────────────────────────────────────────────────
    // Phase 0 — Clean stale classes from previous flatten run
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Hedef paketteki tüm .class dosyalarını siler.
     *
     * <p>Amaç: "mvn clean" kullanılmadan yapılan ardışık derlemelerde, önceki
     * flatten çalışmasından kalan eski sınıf dosyaları basit-ad çakışmasına
     * (ContentCategoryDto → ContentCategoryDto1) ve dolayısıyla runtime
     * {@code NoSuchMethodError}'a yol açar. Bu metot hedef paketi sıfırlayarak
     * Faz-1'in her seferinde tutarlı bir yeniden adlandırma haritası üretmesini sağlar.
     */
    private void cleanTargetPackageDir(Path classesRoot) throws IOException {
        if (targetPackageInternal.isEmpty()) return; // kök paket — temizleme

        Path targetDir = classesRoot.resolve(targetPackageInternal);
        if (!Files.isDirectory(targetDir)) return;

        try (Stream<Path> stream = Files.walk(targetDir)) {
            long deleted = stream
                .filter(p -> p.toString().endsWith(".class"))
                .peek(p -> log.debug("[FLATTEN] Eski sinif siliniyor: " + classesRoot.relativize(p)))
                .mapToLong(p -> {
                    try { Files.delete(p); return 1; } catch (IOException ignored) { return 0; }
                })
                .sum();
            if (deleted > 0) {
                log.info("[FLATTEN] Onceki flatten calismasinden " + deleted
                    + " eski sinif hedef paketten temizlendi.");
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Phase 1 — Build rename map
    // ───────────────────────────────────────────────────────────────────────

    private void buildRenameMap(Path classesRoot) throws IOException {
        // Kullanılan basit adları takip et (çakışma önleme)
        // key: basit ad (ör. "MyClass"), value: kaç kez kullanıldı
        Map<String, Integer> usedSimpleNames = new LinkedHashMap<>();

        List<Path> classFiles;
        try (Stream<Path> stream = Files.walk(classesRoot)) {
            classFiles = stream
                .filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !isMetaInf(classesRoot, p))
                .sorted()
                .toList();
        }

        // İki geçiş: önce toplevel sınıfları, sonra inner sınıfları
        // böylece inner sınıf mapping'i outer sınıf mapping'ine göre türetilebilir.
        List<Path> topLevel = new ArrayList<>();
        List<Path> innerClasses = new ArrayList<>();

        for (Path p : classFiles) {
            String internal = toInternalName(classesRoot, p);
            String simpleName = simpleNameOf(internal);
            if (simpleName.contains("$")) {
                innerClasses.add(p);
            } else {
                topLevel.add(p);
            }
        }

        // Top-level sınıfları işle
        for (Path p : topLevel) {
            String oldInternal = toInternalName(classesRoot, p);

            // FIXED annotasyonlu veya enum sınıfları asla taşıma:
            // - @SpringBootApplication / @Configuration / @Entity → Spring/JPA zorunluluğu
            // - enum → @Query JPQL string'leri içinde FQDN ile referans edilir;
            //   ASM ClassRemapper String değerlerini güncellemez, bu yüzden
            //   taşınan enum'lar JPQL doğrulamasını kırar.
            if (shouldKeepFixed(p)) {
                log.debug("[FLATTEN] Sabit sinif atlandi (MANIFEST/JPA/Config/Enum): " + oldInternal);
                continue;
            }
            String simpleName  = simpleNameOf(oldInternal);

            // Zaten hedef paketteyse atla
            String expectedPrefix = targetPackageInternal.isEmpty()
                ? "" : targetPackageInternal + "/";
            if (oldInternal.startsWith(expectedPrefix)
                    && oldInternal.indexOf('/', expectedPrefix.length()) == -1) {
                // Sınıf zaten hedef pakette — kayıt et ama taşıma yok
                usedSimpleNames.put(simpleName,
                    usedSimpleNames.getOrDefault(simpleName, 0) + 1);
                continue;
            }

            String newSimpleName = resolveSimpleName(simpleName, usedSimpleNames);
            String newInternal   = targetPackageInternal.isEmpty()
                ? newSimpleName
                : targetPackageInternal + "/" + newSimpleName;

            if (!oldInternal.equals(newInternal)) {
                renameMap.put(oldInternal, newInternal);
            }
        }

        // Inner sınıfları işle: outer class adına göre türet
        for (Path p : innerClasses) {
            String oldInternal = toInternalName(classesRoot, p);
            // "com/example/Outer$Inner$Deep" → outerPart="com/example/Outer", innerSuffix="$Inner$Deep"
            int dollarIdx = oldInternal.indexOf('$');
            String outerPart    = oldInternal.substring(0, dollarIdx);
            String innerSuffix  = oldInternal.substring(dollarIdx); // "$Inner" 부분

            // Outer sınıf taşınmadıysa (FIXED veya zaten hedef pakette) inner da taşınmaz
            String mappedOuter = renameMap.get(outerPart);
            if (mappedOuter == null) continue; // outer sabit kaldı → inner de sabit

            String newInternal  = mappedOuter + innerSuffix;

            if (!oldInternal.equals(newInternal)) {
                renameMap.put(oldInternal, newInternal);
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // ───────────────────────────────────────────────────────────────────────
    // FIXED sınıf kontrolü (annotation + enum — tek geçiş)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Bir sınıfın düzleştirmeden muaf tutulup tutulmayacağını belirler.
     *
     * <p>Muafiyet koşulları:
     * <ol>
     *   <li>FIXED_ANNOTATIONS kümesindeki herhangi bir annotasyon taşıyor
     *       ({@code @SpringBootApplication}, {@code @Entity}, vb.)</li>
     *   <li>Sınıf bir <b>enum</b> ({@code ACC_ENUM}) — JPQL {@code @Query}
     *       string'leri enum'ları FQDN ile referans eder; ASM ClassRemapper
     *       annotation String değerlerini güncellemez, dolayısıyla taşınan
     *       enum'lar {@code Validation failed for query} hatasına yol açar.</li>
     * </ol>
     */
    private boolean shouldKeepFixed(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        ClassReader cr = new ClassReader(bytes);

        // Enum kontrolü: class access flags doğrudan ClassReader'dan okunur
        if ((cr.getAccess() & Opcodes.ACC_ENUM) != 0) {
            return true;
        }

        // Annotation kontrolü
        boolean[] found = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (FIXED_ANNOTATIONS.contains(descriptor)) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return found[0];
    }

    /** @deprecated hasFixedAnnotation yerine shouldKeepFixed kullanın */
    private boolean hasFixedAnnotation(Path classFile) throws IOException {
        return shouldKeepFixed(classFile);
    }

    /**
     * Basit adı çözümler.
     *
     * <p>obfuscateNames=false: Orijinal basit ad korunur; çakışmada sayısal son ek eklenir.
     * <p>obfuscateNames=true : Her sınıf için sayaçtan a/b/c/… şeklinde benzersiz kısa ad üretilir.
     */
    private String resolveSimpleName(String simpleName,
                                      Map<String, Integer> usedSimpleNames) {
        if (obfuscateNames) {
            String candidate;
            do {
                candidate = nextObfuscatedName();
            } while (usedSimpleNames.containsKey(candidate));
            usedSimpleNames.put(candidate, 1);
            return candidate;
        }

        // obfuscateNames=false — orijinal ad + çakışma yönetimi
        int count = usedSimpleNames.getOrDefault(simpleName, 0);
        usedSimpleNames.put(simpleName, count + 1);

        if (count == 0) {
            return simpleName;
        }
        // Çakışma: sayı ekle
        String candidate;
        int suffix = 1;
        do {
            candidate = simpleName + suffix;
            suffix++;
        } while (usedSimpleNames.containsKey(candidate));

        log.warn("[FLATTEN] Ad çakışması: '" + simpleName
            + "' → '" + candidate + "' olarak yeniden adlandırıldı.");
        usedSimpleNames.put(candidate, 1);
        return candidate;
    }

    // ───────────────────────────────────────────────────────────────────────
    // Phase 2 — Remap bytecode
    // ───────────────────────────────────────────────────────────────────────

    private void remapBytecode(Path classFile) throws IOException {
        byte[] original = Files.readAllBytes(classFile);

        ClassReader  cr = new ClassReader(original);
        ClassWriter  cw = new ClassWriter(0);
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                return renameMap.getOrDefault(internalName, internalName);
            }
        };

        cr.accept(new ClassRemapper(cw, remapper), ClassReader.EXPAND_FRAMES);
        Files.write(classFile, cw.toByteArray());
    }

    // ───────────────────────────────────────────────────────────────────────
    // Phase 3 — Move files
    // ───────────────────────────────────────────────────────────────────────

    private void moveFiles(List<Path> classFiles, Path classesRoot) throws IOException {
        for (Path classFile : classFiles) {
            String oldInternal = toInternalName(classesRoot, classFile);
            String newInternal = renameMap.get(oldInternal);
            if (newInternal == null) continue; // taşınmıyor

            Path newFile = classesRoot.resolve(newInternal + ".class");
            Files.createDirectories(newFile.getParent());
            Files.move(classFile, newFile, StandardCopyOption.REPLACE_EXISTING);
            log.debug("[FLATTEN] " + oldInternal + " → " + newInternal);
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Phase 4 — Remove empty dirs
    // ───────────────────────────────────────────────────────────────────────

    private void removeEmptyDirs(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                .filter(p -> !p.equals(root))
                .filter(Files::isDirectory)
                .forEach(dir -> {
                    try (Stream<Path> children = Files.list(dir)) {
                        if (children.findAny().isEmpty()) {
                            Files.delete(dir);
                        }
                    } catch (IOException ignored) {}
                });
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────

    /** Dosyayı ASM iç adına çevirir: "com/example/Foo" (.class uzantısı olmadan) */
    private String toInternalName(Path classesRoot, Path classFile) {
        String relative = classesRoot.relativize(classFile)
            .toString()
            .replace(File.separatorChar, '/');
        // ".class" uzantısını kaldır
        return relative.substring(0, relative.length() - 6);
    }

    /** "com/example/Foo" → "Foo" */
    private String simpleNameOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }

    /** Dosya META-INF altında mı? */
    private boolean isMetaInf(Path classesRoot, Path p) {
        return classesRoot.relativize(p).toString()
            .replace(File.separatorChar, '/')
            .startsWith("META-INF/");
    }

    // ───────────────────────────────────────────────────────────────────────
    // Accessors (test için)
    // ───────────────────────────────────────────────────────────────────────

    /** Oluşturulan yeniden adlandırma haritasının kopyasını döndürür. */
    public Map<String, String> getRenameMap() {
        return Collections.unmodifiableMap(renameMap);
    }
}
