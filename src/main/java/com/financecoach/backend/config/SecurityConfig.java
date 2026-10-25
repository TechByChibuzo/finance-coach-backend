package com.financecoach.backend.config;

import com.financecoach.backend.security.JwtAuthenticationFilter;
import com.financecoach.backend.security.MetricsAccessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final MetricsAccessManager metricsAccessManager;

    // The deployed frontend(s), comma-separated. Not *.vercel.app: anyone can deploy there.
    @Value("${app.frontend-url}")
    private String frontendUrls;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          MetricsAccessManager metricsAccessManager) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.metricsAccessManager = metricsAccessManager;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/users/health").permitAll()
                        // Health/info stay public for load balancer checks; metrics need the scrape token
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").access(metricsAccessManager)

                        // Swagger/OpenAPI endpoints (PUBLIC)
                        .requestMatchers(
                                "/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/swagger-resources/**",
                                "/webjars/**"
                        ).permitAll()

                        // Subscription public endpoints
                        .requestMatchers("/api/subscriptions/plans").permitAll()
                        .requestMatchers("/api/subscriptions/webhook").permitAll()

                        // All other endpoints require authentication
                        .anyRequest().authenticated()
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                // No/expired/revoked token -> 401 so clients send the user to log in.
                // 403 stays for authenticated users who aren't allowed (other users' data, plan limits).
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins = new ArrayList<>(List.of("http://localhost:*"));
        Arrays.stream(frontendUrls.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .forEach(origins::add);
        configuration.setAllowedOriginPatterns(origins);

        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"
        ));

        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        logger.info("CORS allowed origins: {}", origins);
        return source;
    }
}