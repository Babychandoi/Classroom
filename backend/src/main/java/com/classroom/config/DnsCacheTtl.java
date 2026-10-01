package com.classroom.config;

import java.security.Security;
import java.util.function.Function;

/**
 * R20-12: shortens the JVM's DNS cache so the backend follows the container network.
 *
 * <p>By default the JVM caches a FAILED lookup for 10 s and a successful one for 30 s. In the Docker stack every dependency
 * (MinIO, MySQL, ...) is a service name resolved by the embedded DNS: while a container is being restarted the name does not
 * resolve, and after it returns the JVM kept answering {@code UnknownHostException: minio} from its negative cache for another
 * 10 s (and could keep using a stale address for up to 30 s if the container came back on a new IP). Recovery after a
 * dependency outage therefore took 10 s+ longer than the dependency itself. Negative answers are now remembered for 5 s and
 * positive ones for 10 s.
 *
 * <p>Precedence: the environment variables {@code DNS_NEGATIVE_TTL_SECONDS} / {@code DNS_POSITIVE_TTL_SECONDS} (0 = do not cache),
 * then the JVM's own {@code -Dsun.net.inetaddr.negative.ttl} / {@code -Dsun.net.inetaddr.ttl}, then the defaults above. The JDK
 * ships {@code networkaddress.cache.negative.ttl=10} explicitly in its {@code java.security} file and consults that security
 * property BEFORE the system property, so the value has to be written into the security property to take effect at all.
 *
 * <p>Must run before the first hostname lookup of the JVM, hence {@code main()} calls it before Spring starts.
 */
public final class DnsCacheTtl {

    static final String NEGATIVE_PROPERTY = "networkaddress.cache.negative.ttl";
    static final String POSITIVE_PROPERTY = "networkaddress.cache.ttl";
    static final int DEFAULT_NEGATIVE_SECONDS = 5;
    static final int DEFAULT_POSITIVE_SECONDS = 10;

    private DnsCacheTtl() {
    }

    public static void configure() {
        configure(System::getenv, System::getProperty);
    }

    static void configure(Function<String, String> env, Function<String, String> systemProperty) {
        apply(NEGATIVE_PROPERTY, env.apply("DNS_NEGATIVE_TTL_SECONDS"), systemProperty.apply("sun.net.inetaddr.negative.ttl"),
                DEFAULT_NEGATIVE_SECONDS);
        apply(POSITIVE_PROPERTY, env.apply("DNS_POSITIVE_TTL_SECONDS"), systemProperty.apply("sun.net.inetaddr.ttl"),
                DEFAULT_POSITIVE_SECONDS);
    }

    private static void apply(String securityProperty, String envValue, String jvmFlagValue, int defaultSeconds) {
        Integer seconds = parse(envValue);
        if (seconds == null) {
            seconds = parse(jvmFlagValue);
        }
        Security.setProperty(securityProperty, Integer.toString(seconds != null ? seconds : defaultSeconds));
    }

    /** A non-negative number of seconds, or null when absent / not a number. */
    private static Integer parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
