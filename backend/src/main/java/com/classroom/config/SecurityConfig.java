package com.classroom.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final ExamRateLimitFilter examRateLimitFilter;
    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;
    private final ApiAccessDeniedHandler apiAccessDeniedHandler;

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:80,http://127.0.0.1:3000}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter, AuthRateLimitFilter authRateLimitFilter,
                           ExamRateLimitFilter examRateLimitFilter,
                           ApiAuthenticationEntryPoint apiAuthenticationEntryPoint,
                           ApiAccessDeniedHandler apiAccessDeniedHandler) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authRateLimitFilter = authRateLimitFilter;
        this.examRateLimitFilter = examRateLimitFilter;
        this.apiAuthenticationEntryPoint = apiAuthenticationEntryPoint;
        this.apiAccessDeniedHandler = apiAccessDeniedHandler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        // QA-07 fix: return 401 for unauthenticated requests, not 403.
                        // R12-05: previously HttpStatusEntryPoint(UNAUTHORIZED), which writes an
                        // empty body - every other error in the app is the standard ApiResponse
                        // envelope, so a plain 401 here couldn't be parsed the same way by the
                        // frontend's ApiException handling. Same reasoning for the 403 path below.
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // R2-01 fix: the REQUEST dispatch for every endpoint below is already
                        // authorized by JwtAuthenticationFilter. JwtAuthenticationFilter is a
                        // OncePerRequestFilter, so it never runs again on the ASYNC dispatch that
                        // completes a StreamingResponseBody (e.g. media download): the stateless
                        // SecurityContext is empty on that dispatch, and without this permit,
                        // anyRequest().authenticated() below throws AuthorizationDeniedException
                        // after the response has already been committed, truncating the stream.
                        // ERROR dispatch is included so the /error endpoint remains reachable when
                        // an async request fails.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // Public endpoints
                        .requestMatchers("/api/v1/health", "/api/v1/health/**", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh").permitAll()
                        // R8-06: logout must still clear the refresh cookie / revoke the refresh
                        // token family even when the access token has already expired (the
                        // controller already tolerated a missing/invalid Authorization header
                        // before this change; it just never revoked anything in that case).
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/*/webhook").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/payments/sandbox-status").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/classes").permitAll()
                        // D-19: an invite code is the credential, so the preview of a class behind one is public (rate limited per address by
                        // AuthRateLimitFilter, and one 404 for every invalid code). The join under it stays behind authentication.
                        .requestMatchers(HttpMethod.GET, "/api/v1/classes/invites/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/classes/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/classes/*/posts").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/classes/slug/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/classes/{id}/about").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/classes/{id}/products").permitAll()
                        .requestMatchers("/error").permitAll()
                        // All other APIs require authentication (including /members and /leaderboard)
                        .requestMatchers("/api/v1/**").authenticated()
                        // Deny all other unmapped and non-API requests by default (fail closed per Review 19 Finding 1)
                        .anyRequest().denyAll()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authRateLimitFilter, JwtAuthenticationFilter.class)
                // R13-11(a): must run AFTER jwtAuthFilter so the authenticated principal (used as
                // the per-user rate-limit key) is already on the SecurityContext.
                .addFilterAfter(examRateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .toList();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
