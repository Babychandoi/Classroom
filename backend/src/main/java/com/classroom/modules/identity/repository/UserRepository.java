package com.classroom.modules.identity.repository;

import com.classroom.modules.identity.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    interface AuthenticationView {
        String getId(); String getEmail(); String getFullName(); String getRole(); String getStatus();
    }
    // A single autocommit SELECT for the authentication filter; do not load profile/password
    // fields or open a separate read transaction before every business request.
    @org.springframework.data.jpa.repository.Query("SELECT u.id AS id, u.email AS email, u.fullName AS fullName, u.role AS role, u.status AS status FROM User u WHERE u.id = :id")
    Optional<AuthenticationView> findAuthenticationById(@org.springframework.data.repository.query.Param("id") String id);
}
