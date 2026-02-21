package com.obfuscator.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Obfuscation sonrası loglarda görünen kısaltılmış paket/class adlarını
 * (tr.sesasis.app.km gibi) gizlemek için log pattern'ını override eder.
 *
 * Bu sınıf spring-obfuscator-maven-plugin tarafından otomatik olarak
 * projeye enjekte edilir. Manuel ekleme gerekmez.
 */
@Configuration
public class AppBeanConfig {

    /**
     * Logger pattern'ında class adı kolonunu kaldırır.
     * Orijinal Spring Boot pattern'ının tüm renklendirmeleri ve alanları
     * korunur; yalnızca "%-40.40logger{39}" (class/paket adı) çıkarılır.
     */
    private static final String PATTERN =
            "%clr(%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}){faint} " +
            "%clr(%5p) " +
            "%clr(${PID:- }){magenta} " +
            "%clr(---){faint} " +
            "%clr([%15.15t]){faint} " +
            "%clr(:){faint} " +
            "%m%n%wEx";

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void removeClassNameFromLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        rootLogger.iteratorForAppenders().forEachRemaining(appender -> {
            if (appender instanceof ConsoleAppender) {
                ConsoleAppender<ILoggingEvent> consoleAppender =
                        (ConsoleAppender<ILoggingEvent>) appender;
                PatternLayoutEncoder encoder = new PatternLayoutEncoder();
                encoder.setContext(context);
                encoder.setPattern(PATTERN);
                encoder.start();

                consoleAppender.stop();
                consoleAppender.setEncoder((Encoder<ILoggingEvent>) encoder);
                consoleAppender.start();
            }
        });
    }
}
