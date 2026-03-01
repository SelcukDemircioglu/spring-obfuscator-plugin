package com.obfuscator.gradle;

import com.obfuscator.ObfuscationLevel;
import com.obfuscator.ObfuscatorEngine;
import com.obfuscator.config.ObfuscationConfig;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;

import java.io.File;

/**
 * Gradle task that applies obfuscation to compiled .class files.
 *
 * <p>This task is registered automatically by {@link ObfuscatorGradlePlugin}.
 * For JVM Spring Boot projects it is named {@code obfuscateClasses} and runs
 * as a finaliser of {@code compileJava}.
 *
 * <p>For Android projects, the plugin injects a {@code doLast} block directly
 * onto {@code compile{Variant}JavaWithJavac} instead of registering a named task.
 */
public class ObfuscatorGradleTask extends DefaultTask {

    // ── Inputs ────────────────────────────────────────────────────────────────

    @InputDirectory
    @SkipWhenEmpty
    private File classesDir;

    @Input
    private String level = "LEVEL_1_BASIC";

    @Input
    private boolean preserveSpringBeans = true;

    @Input
    @Optional
    private String[] excludePackages = new String[0];

    @Input
    @Optional
    private String mainClass;

    @Input
    @Optional
    private String encryptionKey;

    @Input
    @Optional
    private String stringEncryptionKey;

    @Input
    private boolean flattenPackages = false;

    @Input
    @Optional
    private String flattenTargetPackage = "";

    @Input
    private boolean flattenObfuscateNames = false;

    /** True if the project is an Android library/app (disables LEVEL_4). */
    @Input
    private boolean androidProject = false;

    // The output is the same directory (in-place modification)
    @OutputDirectory
    public File getClassesDirOutput() { return classesDir; }

    // ── Task action ───────────────────────────────────────────────────────────

    @TaskAction
    public void obfuscate() throws Exception {
        Logger log = getLogger();

        if (!classesDir.exists() || !classesDir.isDirectory()) {
            log.warn("[Obfuscator] classesDir does not exist or is not a directory: {}", classesDir);
            return;
        }

        // Guard: LEVEL_4 is not supported on Android
        if (androidProject && "LEVEL_4_ENCRYPTED".equalsIgnoreCase(level.trim())) {
            throw new GradleException(
                "[Obfuscator] LEVEL_4_ENCRYPTED is not supported for Android projects. " +
                "Android ART runtime cannot use a custom JVM ClassLoader. " +
                "Use LEVEL_1_BASIC, LEVEL_2_MEDIUM, or LEVEL_3_ADVANCED instead."
            );
        }

        log.lifecycle("[Obfuscator] Starting obfuscation — level={}, classesDir={}", level, classesDir);

        // Build config object
        ObfuscationConfig config = buildConfig();

        // Adapt Gradle Logger to PluginLogger interface
        ObfuscatorEngine.PluginLogger pluginLogger = new ObfuscatorEngine.PluginLogger() {
            @Override public void info(String msg)              { log.info(msg); }
            @Override public void warn(String msg)              { log.warn(msg); }
            @Override public void error(String msg, Throwable t){ log.error(msg, t); }
        };

        ObfuscatorEngine engine = new ObfuscatorEngine(pluginLogger, config, mainClass, encryptionKey);
        engine.execute(classesDir);

        log.lifecycle("[Obfuscator] Obfuscation complete.");
    }

    // ── Config builder ────────────────────────────────────────────────────────

    private ObfuscationConfig buildConfig() {
        ObfuscationConfig cfg = new ObfuscationConfig();
        cfg.setLevel(ObfuscationLevel.fromString(level));
        cfg.setPreserveSpringBeans(preserveSpringBeans);
        cfg.setExcludePackages(excludePackages);
        cfg.setStringEncryptionKey(stringEncryptionKey);
        cfg.setFlattenPackages(flattenPackages);
        cfg.setFlattenTargetPackage(flattenTargetPackage);
        cfg.setFlattenObfuscateNames(flattenObfuscateNames);
        return cfg;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public File    getClassesDir()                       { return classesDir; }
    public void    setClassesDir(File classesDir)        { this.classesDir = classesDir; }

    public String  getLevel()                            { return level; }
    public void    setLevel(String level)                { this.level = level; }

    public boolean isPreserveSpringBeans()               { return preserveSpringBeans; }
    public void    setPreserveSpringBeans(boolean v)     { this.preserveSpringBeans = v; }

    public String[] getExcludePackages()                 { return excludePackages; }
    public void     setExcludePackages(String[] v)       { this.excludePackages = v; }

    public String  getMainClass()                        { return mainClass; }
    public void    setMainClass(String mainClass)        { this.mainClass = mainClass; }

    public String  getEncryptionKey()                    { return encryptionKey; }
    public void    setEncryptionKey(String key)          { this.encryptionKey = key; }

    public String  getStringEncryptionKey()              { return stringEncryptionKey; }
    public void    setStringEncryptionKey(String key)    { this.stringEncryptionKey = key; }

    public boolean isFlattenPackages()                   { return flattenPackages; }
    public void    setFlattenPackages(boolean v)         { this.flattenPackages = v; }

    public String  getFlattenTargetPackage()             { return flattenTargetPackage; }
    public void    setFlattenTargetPackage(String v)     { this.flattenTargetPackage = v; }

    public boolean isFlattenObfuscateNames()             { return flattenObfuscateNames; }
    public void    setFlattenObfuscateNames(boolean v)   { this.flattenObfuscateNames = v; }

    public boolean isAndroidProject()                    { return androidProject; }
    public void    setAndroidProject(boolean v)          { this.androidProject = v; }
}
