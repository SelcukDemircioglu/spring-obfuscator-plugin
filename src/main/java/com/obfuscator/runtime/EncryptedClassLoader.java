package com.obfuscator.runtime;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;

/**
 * Runtime custom ClassLoader that transparently decrypts AES-256-GCM encrypted
 * class files stored under META-INF/obf/{binaryName}.enc.
 *
 * This class is compiled into the plugin JAR and injected verbatim into the
 * target project's classes directory by ClassEncryptionProcessor.
 *
 * Encryption format (per class file):
 *   [12 bytes IV] [AES-GCM ciphertext + 16-byte auth tag]
 */
public class EncryptedClassLoader extends ClassLoader {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS  = 128;

    private final SecretKeySpec secretKey;
    private final Set<String>   encryptedClasses;

    /**
     * @param parent           parent classloader (usually the app classloader)
     * @param keyBytes         32-byte AES-256 key
     * @param encryptedClasses set of binary class names managed by this loader
     */
    public EncryptedClassLoader(ClassLoader parent, byte[] keyBytes,
                                Set<String> encryptedClasses) {
        super(parent);
        this.secretKey       = new SecretKeySpec(keyBytes, "AES");
        this.encryptedClasses = encryptedClasses;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!encryptedClasses.contains(name)) {
            throw new ClassNotFoundException(name);
        }

        String resourcePath = "META-INF/obf/" + name.replace('.', '/') + ".enc";

        // Try parent's resource stream first (works inside fat-jar)
        InputStream is = getParent().getResourceAsStream(resourcePath);
        if (is == null) {
            is = ClassLoader.getSystemResourceAsStream(resourcePath);
        }
        if (is == null) {
            throw new ClassNotFoundException(
                "Encrypted resource not found: " + resourcePath);
        }

        try (InputStream enc = is) {
            byte[] classBytes = decrypt(enc.readAllBytes());
            return defineClass(name, classBytes, 0, classBytes.length);
        } catch (Exception e) {
            throw new ClassNotFoundException(
                "Failed to decrypt class: " + name, e);
        }
    }

    // ── Build-time utility (called by ClassEncryptionProcessor) ──────────────

    /**
     * Encrypts raw class bytes using AES-256-GCM with a fresh random IV.
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
        System.arraycopy(iv,         0, result, 0,              GCM_IV_LENGTH);
        System.arraycopy(cipherText, 0, result, GCM_IV_LENGTH,  cipherText.length);
        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private byte[] decrypt(byte[] data) throws Exception {
        byte[] iv         = Arrays.copyOfRange(data, 0, GCM_IV_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(data, GCM_IV_LENGTH, data.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(cipherText);
    }
}
