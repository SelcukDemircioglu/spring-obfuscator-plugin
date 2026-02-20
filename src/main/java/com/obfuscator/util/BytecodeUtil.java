package com.obfuscator.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BytecodeUtil {

    public static ClassReader readClass(Path classFile) throws IOException {
        byte[] classBytes = Files.readAllBytes(classFile);
        return new ClassReader(classBytes);
    }

    public static void writeClass(Path classFile, byte[] classBytes) throws IOException {
        Files.write(classFile, classBytes);
    }

    public static String toBinaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    public static String toInternalName(String binaryName) {
        return binaryName.replace('.', '/');
    }
}
