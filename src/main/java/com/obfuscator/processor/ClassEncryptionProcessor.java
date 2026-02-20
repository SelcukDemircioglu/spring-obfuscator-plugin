package com.obfuscator.processor;

import com.obfuscator.runtime.EncryptedClassLoader;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Build-time processor for LEVEL_4_ENCRYPTED.
 *
 * Responsibilities:
 *  1. Encrypts a class file with AES-256-GCM and writes the result to
 *     META-INF/obf/{binaryName}.enc inside the classes root.
 *  2. After all classes are encrypted, writeMetadata() writes:
 *       META-INF/obf/.key         — 32-byte raw AES key
 *       META-INF/obf/.classes     — newline-separated list of encrypted binary names
 *       META-INF/obf/.mainclass   — original Spring Boot main class binary name
 *  3. Injects two runtime classes from the plugin JAR into the classes root:
 *       com/obfuscator/runtime/EncryptedClassLoader.class
 *       com/obfuscator/runtime/EncryptedLauncher.class
 *
 * Usage pattern in ObfuscatorMojo:
 * <pre>
 *   ClassEncryptionProcessor cep = new ClassEncryptionProcessor(log);
 *   // ... for each PARTIAL class after Level1/2/3 obfuscation:
 *   cep.encryptClass(classFile, classesRoot);
 *   // ... once all classes are done:
 *   cep.writeMetadata(classesRoot, "com.example.MyApplication");
 * </pre>
 */
public class ClassEncryptionProcessor {

    /** AES-256 requires a 32-byte key. */
    private static final int KEY_BYTES = 32;

    private final Log    log;
    private final byte[] keyBytes;
    private final List<String> encryptedNames = new ArrayList<>();

    /** Constructor that generates a fresh random AES-256 key. */
    public ClassEncryptionProcessor(Log log) {
        this.log      = log;
        this.keyBytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(this.keyBytes);
    }

    /** Constructor that uses a caller-supplied key (hex-encoded, 64 chars = 32 bytes). */
    public ClassEncryptionProcessor(Log log, String hexKey) {
        this.log      = log;
        this.keyBytes = hexToBytes(hexKey);
        if (this.keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                "encryptionKey must be a 64-character hex string (32 bytes / AES-256)");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Encrypts {@code classFile} and writes the result to
     * {@code classesRoot/META-INF/obf/{binaryName}.enc}.
     *
     * @param classFile   absolute path to the already-obfuscated .class file
     * @param classesRoot Maven build output directory (target/classes)
     */
    public void encryptClass(Path classFile, Path classesRoot) throws Exception {
        byte[] classBytes = Files.readAllBytes(classFile);

        // Derive binary name from relative path
        String relativePath = classesRoot.relativize(classFile).toString();
        // Normalize path separators
        String binaryName = relativePath.replace(File.separatorChar, '.')
                                        .replace('\\', '.');
        if (binaryName.endsWith(".class")) {
            binaryName = binaryName.substring(0, binaryName.length() - 6);
        }

        // AES-256-GCM encrypt
        byte[] encryptedBytes = EncryptedClassLoader.encrypt(classBytes, keyBytes);

        // Write  META-INF/obf/<binary/path>.enc
        String encRelPath = binaryName.replace('.', '/') + ".enc";
        Path   encFile    = classesRoot.resolve("META-INF").resolve("obf")
                                       .resolve(encRelPath.replace('/', File.separatorChar));
        Files.createDirectories(encFile.getParent());
        Files.write(encFile, encryptedBytes);

        encryptedNames.add(binaryName);
        log.info("  [LEVEL_4] Sınıf şifrelendi: " + binaryName);
    }

    /**
     * Writes key/metadata files and injects the runtime classloader classes.
     *
     * @param classesRoot      Maven build output directory
     * @param originalMainClass binary name of the @SpringBootApplication main class
     *                          (e.g. "tr.sesasis.MediaAdminApplication")
     */
    public void writeMetadata(Path classesRoot, String originalMainClass) throws Exception {
        Path obfDir = classesRoot.resolve("META-INF").resolve("obf");
        Files.createDirectories(obfDir);

        // .key  — raw 32-byte AES-256 key
        Files.write(obfDir.resolve(".key"), keyBytes);

        // .classes  — newline-separated binary class names
        String classList = String.join("\n", encryptedNames);
        Files.write(obfDir.resolve(".classes"),
                    classList.getBytes(StandardCharsets.UTF_8));

        // .mainclass  — original main application class
        Files.write(obfDir.resolve(".mainclass"),
                    originalMainClass.getBytes(StandardCharsets.UTF_8));

        // Inject runtime classloader & launcher from plugin JAR
        injectRuntimeClass("com/obfuscator/runtime/EncryptedClassLoader.class", classesRoot);
        injectRuntimeClass("com/obfuscator/runtime/EncryptedLauncher.class",    classesRoot);

        log.info("");
        log.info("╔═══════════════════════════════════════════════════════╗");
        log.info("║  LEVEL_4: " + encryptedNames.size() + " sınıf AES-256-GCM ile şifrelendi.         ║");
        log.info("║  Runtime: EncryptedClassLoader + EncryptedLauncher    ║");
        log.info("║  Başlatmak için Start-Class şunu kullanın:            ║");
        log.info("║    com.obfuscator.runtime.EncryptedLauncher           ║");
        log.info("╚═══════════════════════════════════════════════════════╝");
    }

    public List<String> getEncryptedClassNames() {
        return Collections.unmodifiableList(encryptedNames);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
        String  nativeSep = resourcePath.replace('/', File.separatorChar);
        Path    target    = classesRoot.resolve(nativeSep);
        Files.createDirectories(target.getParent());
        try (is) {
            Files.write(target, is.readAllBytes());
        }
        log.debug("  Runtime class injected → " + resourcePath);
    }

    private static byte[] hexToBytes(String hex) {
        int len   = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }
}
