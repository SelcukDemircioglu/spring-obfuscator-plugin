package com.obfuscator.config;

import com.obfuscator.ObfuscationLevel;

public class ObfuscationConfig {
    private ObfuscationLevel level;
    private boolean preserveSpringBeans;
    private String[] excludePackages;
    /** Optional 64-char hex AES-256 key. If null, a random key is generated. */
    private String encryptionKey;
    /** Binary name of the @SpringBootApplication main class for LEVEL_4. */
    private String mainClass;

    /**
     * Tüm uygulama sınıflarını tek bir pakette topla.
     * true ise {@link #flattenTargetPackage} içine taşır.
     */
    private boolean flattenPackages = false;

    /**
     * Paket düzleştirmesi için hedef paket.
     * Örn: "tr.sesasis.app"  — boş bırakılırsa kök paket kullanılır.
     * {@link #flattenPackages} = true olduğunda geçerlidir.
     */
    private String flattenTargetPackage = "";

    public ObfuscationLevel getLevel() {
        return level;
    }

    public void setLevel(ObfuscationLevel level) {
        this.level = level;
    }

    public boolean isPreserveSpringBeans() {
        return preserveSpringBeans;
    }

    public void setPreserveSpringBeans(boolean preserveSpringBeans) {
        this.preserveSpringBeans = preserveSpringBeans;
    }

    public String[] getExcludePackages() {
        return excludePackages;
    }

    public void setExcludePackages(String[] excludePackages) {
        this.excludePackages = excludePackages;
    }

    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }

    public String getMainClass() { return mainClass; }
    public void setMainClass(String mainClass) { this.mainClass = mainClass; }

    public boolean isFlattenPackages() { return flattenPackages; }
    public void setFlattenPackages(boolean flattenPackages) { this.flattenPackages = flattenPackages; }

    public String getFlattenTargetPackage() { return flattenTargetPackage; }
    public void setFlattenTargetPackage(String flattenTargetPackage) { this.flattenTargetPackage = flattenTargetPackage; }
}
