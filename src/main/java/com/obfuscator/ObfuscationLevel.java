package com.obfuscator;

public enum ObfuscationLevel {
    LEVEL_1_BASIC,
    LEVEL_2_MEDIUM,
    LEVEL_3_ADVANCED,
    /**
     * LEVEL_4_ENCRYPTED: Level3 obfuscation + AES-256-GCM class encryption.
     * Encrypted class bytes are stored under META-INF/obf/ in the JAR.
     * At runtime, EncryptedClassLoader (injected into the JAR) decrypts and
     * defines classes transparently. Use EncryptedLauncher as the Start-Class.
     */
    LEVEL_4_ENCRYPTED
}
