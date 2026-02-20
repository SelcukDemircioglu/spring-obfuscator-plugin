package com.obfuscator.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Drop-in replacement Main-Class / Start-Class for Spring Boot fat-JAR.
 *
 * When LEVEL_4_ENCRYPTED is used, the spring-boot-maven-plugin's
 * <configuration><mainClass> should point to this launcher instead of the
 * actual application main class.
 *
 * Startup flow:
 *   1. Reads AES-256 key   from META-INF/obf/.key
 *   2. Reads class list    from META-INF/obf/.classes
 *   3. Reads real main     from META-INF/obf/.mainclass
 *   4. Installs EncryptedClassLoader as Thread context classloader
 *   5. Delegates to original SpringBootApplication.main(args) via reflection
 */
public class EncryptedLauncher {

    private static final String RES_KEY       = "META-INF/obf/.key";
    private static final String RES_CLASSES   = "META-INF/obf/.classes";
    private static final String RES_MAINCLASS = "META-INF/obf/.mainclass";

    public static void main(String[] args) throws Exception {
        // 1. Load encryption key (32 bytes, AES-256)
        byte[] keyBytes = loadResource(RES_KEY);

        // 2. Load list of encrypted binary class names (newline-separated)
        String classesText = new String(loadResource(RES_CLASSES), StandardCharsets.UTF_8);
        Set<String> encryptedClasses = new HashSet<>(
            Arrays.asList(classesText.trim().split("\\r?\\n")));
        encryptedClasses.remove(""); // remove blank lines if any

        // 3. Load original main class name
        String mainClassName = new String(loadResource(RES_MAINCLASS),
                                          StandardCharsets.UTF_8).trim();

        // 4. Install EncryptedClassLoader as the thread context classloader
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        if (parent == null) parent = ClassLoader.getSystemClassLoader();

        EncryptedClassLoader ecl = new EncryptedClassLoader(parent, keyBytes, encryptedClasses);
        Thread.currentThread().setContextClassLoader(ecl);

        System.out.println("[EncryptedLauncher] Encrypted classloader installed. " +
                           "Launching " + mainClassName + " ...");

        // 5. Load real main class through our classloader and invoke main()
        Class<?> mainClass  = ecl.loadClass(mainClassName);
        Method   mainMethod = mainClass.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) args);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static byte[] loadResource(String name) throws IOException {
        InputStream is = EncryptedLauncher.class.getClassLoader().getResourceAsStream(name);
        if (is == null) {
            // Fallback for unusual classloader hierarchies
            is = ClassLoader.getSystemResourceAsStream(name);
        }
        if (is == null) {
            throw new IOException("[EncryptedLauncher] Required resource missing: " + name);
        }
        try (InputStream resource = is) {
            return resource.readAllBytes();
        }
    }
}
