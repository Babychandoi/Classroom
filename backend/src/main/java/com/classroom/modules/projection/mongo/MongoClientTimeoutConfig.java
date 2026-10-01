package com.classroom.modules.projection.mongo;

import com.mongodb.MongoClientSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * R20-04a: short MongoDB client timeouts. The driver defaults - 30 s server selection, 10 s connect, no socket timeout - meant that with
 * MongoDB stopped every call blocked its thread for ~30 s. The outbox now does that on its own executor, but a call that hangs for half a
 * minute is still wrong for a projection store that is allowed to be late. Applied on top of whatever the connection string says, from
 * {@code classroom.projection.mongo.*} (a value {@code <= 0} leaves the URI / driver value alone).
 */
@Configuration
@ConditionalOnClass(MongoClientSettings.class)
public class MongoClientTimeoutConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer classroomMongoTimeouts(
            @Value("${classroom.projection.mongo.server-selection-timeout-ms:3000}") long serverSelectionTimeoutMs,
            @Value("${classroom.projection.mongo.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${classroom.projection.mongo.socket-timeout-ms:10000}") long socketTimeoutMs) {
        return builder -> {
            if (serverSelectionTimeoutMs > 0) {
                builder.applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(serverSelectionTimeoutMs, TimeUnit.MILLISECONDS));
                // Waiting for a pooled connection must not outlive the selection budget either (driver default: 2 minutes).
                builder.applyToConnectionPoolSettings(pool -> pool.maxWaitTime(serverSelectionTimeoutMs, TimeUnit.MILLISECONDS));
            }
            builder.applyToSocketSettings(socket -> {
                if (connectTimeoutMs > 0) {
                    socket.connectTimeout((int) connectTimeoutMs, TimeUnit.MILLISECONDS);
                }
                if (socketTimeoutMs > 0) {
                    socket.readTimeout((int) socketTimeoutMs, TimeUnit.MILLISECONDS);
                }
            });
        };
    }
}
