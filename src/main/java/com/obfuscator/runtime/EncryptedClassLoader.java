package com.obfuscator.runtime;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Runtime custom ClassLoader for LEVEL_4_ENCRYPTED.
 *
 * At construction time:
 *   1. Reads  META-INF/obf/classes.zip.enc  (single AES-256-GCM encrypted ZIP blob).
 *   2. Decrypts the blob into raw ZIP bytes.
 *   3. Unzips all .class entries into an in-memory Map<binaryName, classBytes>.
 *
 * Thereafter, findClass() is a simple Map lookup — no per-class I/O at runtime.
 *
 * Encryption format (same as before):
 *   [12 bytes IV] [AES-GCM ciphertext + 16-byte auth tag]
 */
public class EncryptedClassLoader extends ClassLoader {

    private static final int    GCM_IV_LENGTH    = 12;
    private static final int    GCM_TAG_BITS     = 128;
    private static final String ZIP_ENC_RESOURCE = "META-INF/obf/classes.zip.enc";
    private static final String IFACES_RESOURCE  = "META-INF/obf/.interfaces";

    /** Binary name → decrypted class bytes. Populated once at construction. */
    private final Map<String, byte[]> classCache;

    /**
     * Package prefix for child-first class definition.
     * All app classes (encrypted or FIXED) under this prefix are defined by THIS
     * loader, preventing ClassCastException when Spring wires beans across
     * original-package and flat-package boundaries.
     */
    private final String appRootPackage;

    /**
     * Temporary JAR on disk containing ONLY interface .class bytes.
     * Created once at startup so PathMatchingResourcePatternResolver (Spring Data JPA)
     * can enumerate repository interfaces even though they were deleted from the JAR.
     * File is marked deleteOnExit — removed when JVM terminates.
     */
    private final Path interfaceJar;

    /**
     * @param parent          parent classloader (usually the app classloader)
     * @param keyBytes        32-byte AES-256 key
     * @param appRootPackage  package prefix for child-first scope (e.g. "tr.sesasis")
     */
    public EncryptedClassLoader(ClassLoader parent, byte[] keyBytes, String appRootPackage) {
        super(parent);
        this.appRootPackage = (appRootPackage != null) ? appRootPackage : "";
        this.classCache     = loadClassCache(parent, keyBytes);
        this.interfaceJar   = buildInterfaceJar(parent);
    }

    // ── Class loading ─────────────────────────────────────────────────────────

    /**
     * Child-first class loading for application classes.
     *
     * For classes under appRootPackage:
     *   - Encrypted  → return from classCache (already defined by ECL)
     *   - Non-encrypted (FIXED) → re-read .class bytes from parent resource and define via ECL
     *     so that FIXED original-package classes share the same defining loader as
     *     encrypted flat-package classes (avoids ClassCastException).
     *
     * For everything else (JDK, Spring, libs): standard parent-first delegation.
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // Fast path: already defined by this loader
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                if (resolve) resolveClass(c);
                return c;
            }

            // App classes: ECL is the defining loader (child-first)
            if (!appRootPackage.isEmpty() && name.startsWith(appRootPackage + ".")) {
                if (classCache.containsKey(name)) {
                    // Encrypted class: bytes already in cache
                    c = findClass(name);
                } else {
                    // Non-encrypted app class (FIXED): re-define from parent's .class bytes
                    String resourcePath = name.replace('.', '/') + ".class";
                    InputStream is = getParent().getResourceAsStream(resourcePath);
                    if (is != null) {
                        try (is) {
                            byte[] bytes = is.readAllBytes();
                            c = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException e) {
                            throw new ClassNotFoundException(
                                "Failed to read class bytes for: " + name, e);
                        }
                    }
                }
                if (c != null) {
                    if (resolve) resolveClass(c);
                    return c;
                }
            }

            // JDK, Spring framework, third-party libs: parent-first
            return super.loadClass(name, resolve);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] classBytes = classCache.get(name);
        if (classBytes == null) {
            throw new ClassNotFoundException("Not in encrypted cache: " + name);
        }
        return defineClass(name, classBytes, 0, classBytes.length);
    }

    /**
     * Serves .class file bytes directly from the in-memory cache.
     *
     * Spring's ClassPathScanningCandidateComponentProvider (used with spring.components index)
     * calls ClassLoader.getResourceAsStream("tr/sesasis/app/Foo.class") to parse annotation
     * metadata via ASM MetadataReader. Since we deleted the original .class files from the JAR,
     * this override serves those bytes from classCache instead.
     *
     * Without this, Spring throws FileNotFoundException even when spring.components is present:
     *   java.io.FileNotFoundException: class path resource [tr/sesasis/app/Foo.class]
     *   cannot be opened because it does not exist
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        if (name != null && name.endsWith(".class")) {
            String binaryName = name.substring(0, name.length() - 6).replace('/', '.');
            byte[] bytes = classCache.get(binaryName);
            if (bytes != null) {
                return new ByteArrayInputStream(bytes);
            }
        }
        return super.getResourceAsStream(name);
    }

    /**
     * Intercepts classpath directory scan requests from PathMatchingResourcePatternResolver.
     *
     * Spring Data JPA calls getResources("tr/sesasis/app/") to list .class files for
     * repository interface discovery. Since interface files were deleted from the JAR
     * (they are inside classes.zip.enc), we append our temp interface JAR as an
     * additional URL source so Spring can still find them.
     *
     * Only app-root-package paths are intercepted; everything else delegates to parent.
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        // Only intercept package directory requests under our root package
        if (interfaceJar != null && name != null && name.endsWith("/")
                && !appRootPackage.isEmpty()
                && name.startsWith(appRootPackage.replace('.', '/') + "/")) {

            List<URL> urls = new ArrayList<>();

            // 1. Parent loader's URLs (original fat-jar directory — may be empty now)
            Enumeration<URL> parentUrls = super.getResources(name);
            while (parentUrls.hasMoreElements()) {
                urls.add(parentUrls.nextElement());
            }

            // 2. Temp interface JAR URL for this sub-package.
            //    Format: "jar:file:/tmp/ecl-ifaces-XXX.jar!/tr/sesasis/app/"
            //    Spring's JarURLConnection opens this and iterates entries under this path.
            String jarPath    = interfaceJar.toAbsolutePath().toString();
            String urlStr     = "jar:file:" + jarPath + "!/" + name;
            urls.add(new URL(urlStr));

            return Collections.enumeration(urls);
        }
        return super.getResources(name);
    }

    // ── Build-time utility (called by ClassEncryptionProcessor) ──────────────

    /**
     * Encrypts raw bytes using AES-256-GCM with a fresh random IV.
     * Output: [12-byte IV] + [ciphertext + 16-byte auth tag]
     */
    public static byte[] encrypt(byte[] data, byte[] keyBytes) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));

        byte[] cipherText = cipher.doFinal(data);
        byte[] result     = new byte[GCM_IV_LENGTH + cipherText.length];
        System.arraycopy(iv,         0, result, 0,             GCM_IV_LENGTH);
        System.arraycopy(cipherText, 0, result, GCM_IV_LENGTH, cipherText.length);
        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Reads META-INF/obf/.interfaces (list of interface binary names produced at
     * build-time) and writes a temporary JAR file containing those interface .class
     * bytes from classCache. Spring Data JPA's PathMatchingResourcePatternResolver
     * will scan this temp JAR to discover repository interfaces even though their
     * physical .class files were deleted from the application JAR.
     *
     * Returns null if no .interfaces resource is present (e.g. older build) or if
     * no matching entries were found in classCache.
     */
    private Path buildInterfaceJar(ClassLoader parent) {
        try {
            // 1. Read the interface names list written by ClassEncryptionProcessor
            InputStream is = parent.getResourceAsStream(IFACES_RESOURCE);
            if (is == null) is = ClassLoader.getSystemResourceAsStream(IFACES_RESOURCE);
            if (is == null) return null;

            Set<String> ifaceNames = new HashSet<>();
            try (InputStream in = is) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    for (String line : content.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) ifaceNames.add(trimmed);
                    }
                }
            }
            if (ifaceNames.isEmpty()) return null;

            // 2. Create a temp JAR containing the interface class bytes from classCache
            Path tmp = Files.createTempFile("ecl-ifaces-", ".jar");
            tmp.toFile().deleteOnExit();

            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
                for (String binaryName : ifaceNames) {
                    byte[] bytes = classCache.get(binaryName);
                    if (bytes == null) continue;
                    String entryPath = binaryName.replace('.', '/') + ".class";
                    zos.putNextEntry(new java.util.zip.ZipEntry(entryPath));
                    zos.write(bytes);
                    zos.closeEntry();
                }
            }

            System.out.println("[EncryptedClassLoader] Interface JAR hazır: "
                               + ifaceNames.size() + " interface → " + tmp.getFileName());
            return tmp;

        } catch (Exception e) {
            System.err.println("[EncryptedClassLoader] Interface JAR oluşturulamadı: "
                               + e.getMessage());
            return null;
        }
    }

    /**
     * Reads META-INF/obf/classes.zip.enc, decrypts it, and unzips all .class
     * entries into the returned map.  Called once at construction.
     */
    private static Map<String, byte[]> loadClassCache(ClassLoader parent, byte[] keyBytes) {
        Map<String, byte[]> cache = new HashMap<>(512);
        try {
            // Locate the encrypted ZIP blob inside the fat-jar
            InputStream is = parent.getResourceAsStream(ZIP_ENC_RESOURCE);
            if (is == null) is = ClassLoader.getSystemResourceAsStream(ZIP_ENC_RESOURCE);
            if (is == null) {
                throw new IOException(
                    "[EncryptedClassLoader] Encrypted blob not found: " + ZIP_ENC_RESOURCE);
            }

            byte[] encryptedBlob;
            try (InputStream enc = is) {
                encryptedBlob = enc.readAllBytes();
            }

            // Decrypt blob → raw ZIP bytes
            byte[] zipBytes = decrypt(encryptedBlob, keyBytes);

            // Unzip into memory
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String entryName = entry.getName(); // e.g. "tr/sesasis/app/a.class"
                    if (entryName.endsWith(".class")) {
                        byte[] classBytes = zis.readAllBytes();
                        // Convert path to binary name: "tr/sesasis/app/Foo.class" → "tr.sesasis.app.Foo"
                        String binaryName = entryName
                            .substring(0, entryName.length() - 6)
                            .replace('/', '.');
                        cache.put(binaryName, classBytes);
                    }
                    zis.closeEntry();
                }
            }

            System.out.println("[EncryptedClassLoader] Hazır: " + cache.size()
                               + " sınıf şifreli ZIP'ten yüklendi.");
        } catch (Exception e) {
            throw new RuntimeException(
                "[EncryptedClassLoader] Sınıf önbelleği başlatılamadı: " + e.getMessage(), e);
        }
        return cache;
    }

    private static byte[] decrypt(byte[] data, byte[] keyBytes) throws Exception {
        byte[] iv         = Arrays.copyOfRange(data, 0, GCM_IV_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(data, GCM_IV_LENGTH, data.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(cipherText);
    }
}
