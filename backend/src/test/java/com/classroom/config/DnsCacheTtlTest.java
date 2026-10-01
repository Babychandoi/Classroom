package com.classroom.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Security;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R20-12: after a dependency restart the JVM must stop answering UnknownHost from its DNS cache within seconds. */
class DnsCacheTtlTest {

    private String savedNegative;
    private String savedPositive;

    @BeforeEach
    void save() {
        savedNegative = Security.getProperty(DnsCacheTtl.NEGATIVE_PROPERTY);
        savedPositive = Security.getProperty(DnsCacheTtl.POSITIVE_PROPERTY);
    }

    @AfterEach
    void restore() {
        Security.setProperty(DnsCacheTtl.NEGATIVE_PROPERTY, savedNegative == null ? "" : savedNegative);
        Security.setProperty(DnsCacheTtl.POSITIVE_PROPERTY, savedPositive == null ? "" : savedPositive);
    }

    @Test
    @DisplayName("defaults: failed lookups are cached for 5 s (JVM default 10 s), successful ones for 10 s (JVM default 30 s) - even though the JDK's java.security sets 10 explicitly")
    void defaultsOverrideTheJdkSetting() {
        Security.setProperty(DnsCacheTtl.NEGATIVE_PROPERTY, "10"); // what Temurin's java.security ships
        Security.setProperty(DnsCacheTtl.POSITIVE_PROPERTY, "30");
        DnsCacheTtl.configure(name -> null, name -> null);
        assertEquals("5", Security.getProperty(DnsCacheTtl.NEGATIVE_PROPERTY));
        assertEquals("10", Security.getProperty(DnsCacheTtl.POSITIVE_PROPERTY));
    }

    @Test
    @DisplayName("environment variables override the defaults; junk is ignored; 0 disables caching")
    void environmentOverrides() {
        DnsCacheTtl.configure(Map.of("DNS_NEGATIVE_TTL_SECONDS", "0", "DNS_POSITIVE_TTL_SECONDS", "junk")::get, name -> null);
        assertEquals("0", Security.getProperty(DnsCacheTtl.NEGATIVE_PROPERTY));
        assertEquals("10", Security.getProperty(DnsCacheTtl.POSITIVE_PROPERTY));
    }

    @Test
    @DisplayName("an operator's -Dsun.net.inetaddr.* flag is honoured (the environment variable still wins over it)")
    void jvmFlagIsHonoured() {
        DnsCacheTtl.configure(name -> null, Map.of("sun.net.inetaddr.negative.ttl", "1", "sun.net.inetaddr.ttl", "60")::get);
        assertEquals("1", Security.getProperty(DnsCacheTtl.NEGATIVE_PROPERTY));
        assertEquals("60", Security.getProperty(DnsCacheTtl.POSITIVE_PROPERTY));

        DnsCacheTtl.configure(Map.of("DNS_NEGATIVE_TTL_SECONDS", "2")::get, Map.of("sun.net.inetaddr.negative.ttl", "1")::get);
        assertEquals("2", Security.getProperty(DnsCacheTtl.NEGATIVE_PROPERTY));
    }
}
