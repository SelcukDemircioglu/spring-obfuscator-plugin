package com.obfuscator.gradle;

import com.obfuscator.ObfuscatorEngine;
import com.obfuscator.config.ObfuscationConfig;
import com.obfuscator.ObfuscationLevel;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;

/**
 * Gradle plugin entry point for the spring-obfuscator-plugin.
 *
 * <p>Apply in your build file:
 * <pre>
 * // Groovy DSL
 * apply plugin: 'com.obfuscator.gradle'
 *
 * // Kotlin DSL
 * apply(plugin = "com.obfuscator.gradle")
 * </pre>
 *
 * <h3>JVM Spring Boot projects</h3>
 * Adds a task named {@code obfuscateClasses} that is set as a finaliser of
 * {@code compileJava}.  Class files in {@code build/classes/java/main} are
 * obfuscated in-place after compilation.
 *
 * <h3>Android library / application projects</h3>
 * Registers a {@code doLast} hook on {@code compileDebugJavaWithJavac} and
 * {@code compileReleaseJavaWithJavac}.  Class files in
 * {@code build/intermediates/javac/{variant}/classes} are obfuscated in-place.
 * <b>LEVEL_4_ENCRYPTED is not supported for Android.</b>
 */
public class ObfuscatorGradlePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // Register the DSL extension: obfuscation { … }
        ObfuscatorGradleExtension ext =
            project.getExtensions().create("obfuscation", ObfuscatorGradleExtension.class);

        // Detect project type and wire tasks AFTER all other plugins have been applied.
        project.afterEvaluate(proj -> {
            if (!ext.isEnabled()) {
                proj.getLogger().info("[Obfuscator] Obfuscation disabled — skipping.");
                return;
            }

            boolean isAndroid = isAndroidProject(proj);
            boolean isJava    = proj.getPlugins().hasPlugin("java")
                             || proj.getPlugins().hasPlugin("java-library");

            if (isAndroid) {
                setupAndroid(proj, ext);
            } else if (isJava) {
                setupJvm(proj, ext);
            } else {
                proj.getLogger().warn(
                    "[Obfuscator] No recognised Java/Android plugin found on project '{}'. " +
                    "Apply 'java', 'java-library', 'com.android.application', or " +
                    "'com.android.library' before 'com.obfuscator.gradle'.",
                    proj.getName()
                );
            }
        });
    }

    // ── Android setup ──────────────────────────────────────────────────────────

    private void setupAndroid(Project project, ObfuscatorGradleExtension ext) {
        Logger log = project.getLogger();

        // Android: LEVEL_4 guard
        if ("LEVEL_4_ENCRYPTED".equalsIgnoreCase(ext.getLevel().trim())) {
            throw new GradleException(
                "[Obfuscator] LEVEL_4_ENCRYPTED is not supported for Android projects. " +
                "ART runtime cannot use a custom JVM ClassLoader. " +
                "Use LEVEL_1_BASIC, LEVEL_2_MEDIUM, or LEVEL_3_ADVANCED."
            );
        }

        for (String variant : new String[]{"debug", "release"}) {
            String cap          = Character.toUpperCase(variant.charAt(0)) + variant.substring(1);
            String compileTask  = "compile" + cap + "JavaWithJavac";
            Task   task         = project.getTasks().findByName(compileTask);

            if (task == null) {
                log.info("[Obfuscator] Task '{}' not found in project '{}' — skipping variant.",
                         compileTask, project.getName());
                continue;
            }

            // Resolve destination directory from the JavaCompile task
            File classesDir = resolveAndroidClassesDir(project, task, variant);
            log.lifecycle("[Obfuscator] Hooking into '{}', classesDir={}", compileTask, classesDir);

            task.doLast(t -> {
                if (!classesDir.exists()) {
                    log.warn("[Obfuscator] classesDir '{}' does not exist after compilation — skipping.",
                             classesDir);
                    return;
                }
                runEngine(project, ext, classesDir, true /* androidProject */);
            });
        }
    }

    /**
     * Resolves the Java class output directory from the Android JavaCompile task.
     * AGP 7.x: task instanceof JavaCompile → destinationDirectory
     * Fallback: build/intermediates/javac/{variant}/classes
     */
    private File resolveAndroidClassesDir(Project project, Task task, String variant) {
        if (task instanceof JavaCompile) {
            try {
                return ((JavaCompile) task).getDestinationDirectory().getAsFile().get();
            } catch (Exception ignored) { /* fall through */ }
        }
        return new File(project.getBuildDir(),
                        "intermediates/javac/" + variant + "/classes");
    }

    // ── JVM (Spring Boot) setup ───────────────────────────────────────────────

    private void setupJvm(Project project, ObfuscatorGradleExtension ext) {
        Logger log = project.getLogger();

        Task compileJava = project.getTasks().findByName("compileJava");
        if (compileJava == null) {
            log.warn("[Obfuscator] Task 'compileJava' not found — skipping JVM setup.");
            return;
        }

        // Resolve class output directory
        File classesDir = resolveJvmClassesDir(project, compileJava);
        log.lifecycle("[Obfuscator] Registering obfuscateClasses task, classesDir={}", classesDir);

        // Create named task so it shows up in `./gradlew tasks`
        ObfuscatorGradleTask obfTask = project.getTasks()
                .create("obfuscateClasses", ObfuscatorGradleTask.class, task -> {
                    task.setGroup("obfuscation");
                    task.setDescription("Obfuscates compiled .class files in the main source set.");
                    task.setClassesDir(classesDir);
                    task.setLevel(ext.getLevel());
                    task.setPreserveSpringBeans(ext.isPreserveSpringBeans());
                    task.setExcludePackages(ext.getExcludePackages());
                    task.setMainClass(ext.getMainClass());
                    task.setEncryptionKey(ext.getEncryptionKey());
                    task.setStringEncryptionKey(ext.getStringEncryptionKey());
                    task.setFlattenPackages(ext.isFlattenPackages());
                    task.setFlattenTargetPackage(ext.getFlattenTargetPackage());
                    task.setFlattenObfuscateNames(ext.isFlattenObfuscateNames());
                    task.setAndroidProject(false);
                    task.dependsOn(compileJava);
                });

        // Make compileJava always run obfuscation after itself
        compileJava.finalizedBy(obfTask);

        // If Spring Boot plugin is present, also hook before bootJar/bootWar
        project.getPlugins().withId("org.springframework.boot", p -> {
            Task bootJar = project.getTasks().findByName("bootJar");
            if (bootJar != null) bootJar.dependsOn(obfTask);
            Task bootWar = project.getTasks().findByName("bootWar");
            if (bootWar != null) bootWar.dependsOn(obfTask);
        });
    }

    /**
     * Resolves the main Java classes directory.
     * Tries JavaCompile.getDestinationDirectory() first, then falls back to the
     * conventional build/classes/java/main path.
     */
    private File resolveJvmClassesDir(Project project, Task compileTask) {
        if (compileTask instanceof JavaCompile) {
            try {
                return ((JavaCompile) compileTask).getDestinationDirectory().getAsFile().get();
            } catch (Exception ignored) { /* fall through */ }
        }
        return new File(project.getBuildDir(), "classes/java/main");
    }

    // ── Shared engine runner ──────────────────────────────────────────────────

    /**
     * Builds an {@link ObfuscatorEngine} from the DSL extension and runs it
     * against {@code classesDir}.  Wraps any checked exception as
     * {@link GradleException}.
     */
    private void runEngine(Project project,
                           ObfuscatorGradleExtension ext,
                           File classesDir,
                           boolean isAndroid) {
        Logger log = project.getLogger();

        ObfuscationConfig cfg = new ObfuscationConfig();
        cfg.setLevel(ObfuscationLevel.fromString(ext.getLevel()));
        cfg.setPreserveSpringBeans(ext.isPreserveSpringBeans());
        cfg.setExcludePackages(ext.getExcludePackages());
        cfg.setStringEncryptionKey(ext.getStringEncryptionKey());
        cfg.setFlattenPackages(ext.isFlattenPackages());
        cfg.setFlattenTargetPackage(ext.getFlattenTargetPackage());
        cfg.setFlattenObfuscateNames(ext.isFlattenObfuscateNames());

        ObfuscatorEngine.PluginLogger logger = new ObfuscatorEngine.PluginLogger() {
            @Override public void info(String msg)               { log.info(msg);   }
            @Override public void warn(String msg)               { log.warn(msg);   }
            @Override public void error(String msg, Throwable t) { log.error(msg, t); }
        };

        ObfuscatorEngine engine = new ObfuscatorEngine(
            logger, cfg, ext.getMainClass(), ext.getEncryptionKey()
        );

        try {
            engine.execute(classesDir);
        } catch (Exception e) {
            throw new GradleException("[Obfuscator] Obfuscation failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAndroidProject(Project project) {
        return project.getPlugins().hasPlugin("com.android.application")
            || project.getPlugins().hasPlugin("com.android.library")
            || project.getPlugins().hasPlugin("com.android.dynamic-feature");
    }
}
