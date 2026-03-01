package com.obfuscator.gradle;

/**
 * Gradle DSL extension for the obfuscator plugin.
 *
 * <p>Usage in build.gradle:
 * <pre>
 * obfuscation {
 *     level                = 'LEVEL_1_BASIC'   // LEVEL_1_BASIC | LEVEL_2_MEDIUM | LEVEL_3_ADVANCED
 *     enabled              = true
 *     preserveSpringBeans  = true              // keep for Spring Boot projects
 *     excludePackages      = ['com.example.generated']
 *     mainClass            = 'com.example.Main' // Spring Boot only
 * }
 * </pre>
 *
 * <p><b>Android limitation:</b> LEVEL_4_ENCRYPTED is not supported on Android (ART runtime).
 * For Android projects, use LEVEL_1_BASIC through LEVEL_3_ADVANCED only.
 */
public class ObfuscatorGradleExtension {

    /** Obfuscation level. Default: LEVEL_1_BASIC */
    private String level = "LEVEL_1_BASIC";

    /** Enable or disable obfuscation entirely. Default: true */
    private boolean enabled = true;

    /**
     * Keep Spring stereotype annotations (Component, Service, Repository…) intact
     * so Spring can find beans after obfuscation. Default: true.
     * Set to false for non-Spring projects (e.g., Android libraries).
     */
    private boolean preserveSpringBeans = true;

    /** Package prefixes to exclude from obfuscation (dot-separated). */
    private String[] excludePackages = new String[0];

    /**
     * Binary name of the @SpringBootApplication main class.
     * Required for LEVEL_4_ENCRYPTED. Ignored on Android.
     */
    private String mainClass;

    /**
     * Optional 64-char hex AES-256 key (LEVEL_4_ENCRYPTED only).
     * If omitted, a random key is generated per build.
     */
    private String encryptionKey;

    /**
     * Optional 16-char AES-128 key for LEVEL_2 string encryption.
     * If omitted, a built-in default key is used.
     */
    private String stringEncryptionKey;

    /** Flatten all application classes into a single package. Default: false */
    private boolean flattenPackages = false;

    /**
     * Target package for flattening (dot-separated).
     * E.g. "tr.sesasis.app". Empty → root package.
     */
    private String flattenTargetPackage = "";

    /**
     * Obfuscate class names when flattening (a, b, c … style).
     * Default: false (original simple name kept).
     */
    private boolean flattenObfuscateNames = false;

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getLevel()                          { return level; }
    public void   setLevel(String level)              { this.level = level; }

    public boolean isEnabled()                        { return enabled; }
    public void    setEnabled(boolean enabled)        { this.enabled = enabled; }

    public boolean isPreserveSpringBeans()            { return preserveSpringBeans; }
    public void    setPreserveSpringBeans(boolean v)  { this.preserveSpringBeans = v; }

    public String[] getExcludePackages()              { return excludePackages; }
    public void     setExcludePackages(String[] v)    { this.excludePackages = v; }

    public String getMainClass()                      { return mainClass; }
    public void   setMainClass(String mainClass)      { this.mainClass = mainClass; }

    public String getEncryptionKey()                  { return encryptionKey; }
    public void   setEncryptionKey(String key)        { this.encryptionKey = key; }

    public String getStringEncryptionKey()            { return stringEncryptionKey; }
    public void   setStringEncryptionKey(String key)  { this.stringEncryptionKey = key; }

    public boolean isFlattenPackages()                { return flattenPackages; }
    public void    setFlattenPackages(boolean v)      { this.flattenPackages = v; }

    public String getFlattenTargetPackage()           { return flattenTargetPackage; }
    public void   setFlattenTargetPackage(String v)   { this.flattenTargetPackage = v; }

    public boolean isFlattenObfuscateNames()          { return flattenObfuscateNames; }
    public void    setFlattenObfuscateNames(boolean v){ this.flattenObfuscateNames = v; }
}
