package com.obfuscator.config;

/**
 * Determines how a class should be treated by the obfuscation pipeline.
 *
 * FULL    — Skip the class entirely (e.g. @Entity, @Configuration, @SpringBootApplication).
 *           These classes have framework contracts that depend on exact field/method names.
 *
 * PARTIAL — Obfuscate private internals only: private fields, private methods, local
 *           variables and string literals. Public API is preserved so Spring wiring,
 *           JSON serialisation and Spring Data query derivation continue to work.
 *           Applied to @Service, @Repository, @Controller, @RestController.
 *
 * NONE    — No spring-framework constraint detected; full obfuscation pipeline runs.
 */
public enum ProtectionLevel {
    NONE,
    PARTIAL,
    FULL
}
