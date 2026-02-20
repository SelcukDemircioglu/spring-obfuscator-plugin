package com.obfuscator.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class NameGenerator {

    private final AtomicInteger counter = new AtomicInteger(0);
    private final Map<String, String> nameCache = new HashMap<>();

    public String generateObfuscatedName(String originalName, String prefix) {
        return nameCache.computeIfAbsent(originalName,
            k -> prefix + "_" + encode(counter.getAndIncrement()));
    }

    /**
     * Returns the already-mapped obfuscated name, or null if not mapped yet.
     * Used to remap GETFIELD/PUTFIELD references without creating new entries.
     */
    public String getMappedName(String originalName) {
        return nameCache.get(originalName);
    }

    private String encode(int num) {
        StringBuilder sb = new StringBuilder();
        do {
            int remainder = num % 52;
            if (remainder < 26) {
                sb.append((char) ('a' + remainder));
            } else {
                sb.append((char) ('A' + (remainder - 26)));
            }
            num /= 52;
        } while (num > 0);
        return sb.toString();
    }

    public void reset() {
        counter.set(0);
        nameCache.clear();
    }
}
