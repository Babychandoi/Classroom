package com.classroom.modules.identity.repository;

import com.classroom.config.RevokedToken;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {
    boolean existsByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);
    void deleteByExpiresAtBefore(Instant now);
}
