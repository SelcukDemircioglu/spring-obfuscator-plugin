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
     * <p><b>Not supported on Android</b> (ART runtime).</p>
     */
    LEVEL_4_ENCRYPTED;

    /**
     * Case-insensitive lookup.  Falls back to {@link #LEVEL_1_BASIC} on unknown input.
     *
     * @param value string such as "LEVEL_2_MEDIUM" or "level_3_advanced"
     * @return the matching enum constant, or LEVEL_1_BASIC if not found
     */
    public static ObfuscationLevel fromString(String value) {
        if (value == null) return LEVEL_1_BASIC;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LEVEL_1_BASIC;
        }
    }
}
