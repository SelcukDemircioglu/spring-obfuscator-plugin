package com.obfuscator.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Drop-in replacement Main-Class / Start-Class for Spring Boot fat-JAR.
 *
 * When LEVEL_4_ENCRYPTED is used, the spring-boot-maven-plugin's
 * <configuration><mainClass> should point to this launcher instead of the
 * actual application main class.
 *
 * Startup flow:
 *   1. Reads AES-256 key   from META-INF/obf/.key
 *   2. Reads real main     from META-INF/obf/.mainclass
 *   3. Reads root package  from META-INF/obf/.pkg  (for child-first ECL scope)
 *   4. Creates EncryptedClassLoader — which decrypts classes.zip.enc at init
 *   5. Installs ECL as Thread context classloader
 *   6. Delegates to original SpringBootApplication.main(args) via reflection
 *
 * NOTE: .classes list is no longer needed — EncryptedClassLoader derives the
 * encrypted class set by unzipping the classes.zip.enc blob at construction.
 */
public class EncryptedLauncher {

    private static final String RES_KEY       = "META-INF/obf/.key";
    private static final String RES_MAINCLASS = "META-INF/obf/.mainclass";
    private static final String RES_PKG       = "META-INF/obf/.pkg";

    public static void main(String[] args) throws Exception {
        // 1. Load encryption key (32 bytes, AES-256)
        byte[] keyBytes = loadResource(RES_KEY);

        // 2. Load original main class name
        String mainClassName = new String(loadResource(RES_MAINCLASS),
                                          StandardCharsets.UTF_8).trim();

        // 3. Load app root package (e.g. "tr.sesasis") for child-first ECL scope
        String appRootPackage = "";
        try {
            appRootPackage = new String(loadResource(RES_PKG),
                                        StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            // .pkg absent in older builds; ECL falls back to parent-first for all
        }

        // 4. Create EncryptedClassLoader — it will decrypt classes.zip.enc internally
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        if (parent == null) parent = ClassLoader.getSystemClassLoader();

        EncryptedClassLoader ecl = new EncryptedClassLoader(parent, keyBytes, appRootPackage);

        // 5. Install as thread context classloader
        Thread.currentThread().setContextClassLoader(ecl);

        System.out.println("[EncryptedLauncher] Encrypted classloader hazır. "
                           + "Başlatılıyor: " + mainClassName + " ...");

        // 6. Load real main class through our classloader and invoke main()
        Class<?> mainClass  = ecl.loadClass(mainClassName);
        Method   mainMethod = mainClass.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) args);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static byte[] loadResource(String name) throws IOException {
        InputStream is = EncryptedLauncher.class.getClassLoader().getResourceAsStream(name);
        if (is == null) {
            is = ClassLoader.getSystemResourceAsStream(name);
        }
        if (is == null) {
            throw new IOException("[EncryptedLauncher] Gerekli kaynak bulunamadı: " + name);
        }
        try (InputStream resource = is) {
            return resource.readAllBytes();
        }
    }
}
