package com.paiban.common.config;

import com.paiban.identity.security.JwtAuthenticationFilter;
import com.paiban.identity.security.RestAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
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

    private final List<String> allowedOrigins;
    private final boolean h2ConsoleEnabled;

    public SecurityConfig(
            @Value("${paiban.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
            String allowedOrigins,
            @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        this.h2ConsoleEnabled = h2ConsoleEnabled;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(
                allowedOrigins.isEmpty() ? List.of("http://localhost:5173") : allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Content-Disposition"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers(
                                    "/api/auth/login",
                                    "/api/auth/dingtalk",
                                    "/api/auth/dingtalk/config",
                                    "/api/auth/feishu",
                                    "/api/auth/feishu/config",
                                    "/api/auth/feishu/bind",
                                    "/api/health",
                                    "/actuator/**")
                            .permitAll()
                            .requestMatchers("/uploads/**").permitAll();
                    // H2 控制台只在显式启用的环境（e2e 造历史数据用）放行；生产
                    // 未启用控制台时该路径不放行，servlet 也不会注册。
                    if (h2ConsoleEnabled) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    auth.requestMatchers("/api/integrations/dingtalk/event").permitAll()
                            .requestMatchers("/api/integrations/feishu/event").permitAll()
                            .anyRequest().authenticated();
                })
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
