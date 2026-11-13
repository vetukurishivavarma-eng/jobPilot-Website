package com.jobpilot.config;

import com.jobpilot.auth.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter, AppProperties props) throws Exception {
        CorsConfiguration cors = new CorsConfiguration();
        // the JobPilot browser extension calls the API from its own chrome-extension:// origin (bearer token, no cookies)
        List<String> origins = new java.util.ArrayList<>(props.corsOrigins());
        origins.add("chrome-extension://*");
        cors.setAllowedOriginPatterns(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource corsSource = new UrlBasedCorsConfigurationSource();
        corsSource.registerCorsConfiguration("/**", cors);

        return http
                .csrf(c -> c.disable()) // stateless bearer tokens, no cookies
                .cors(c -> c.configurationSource(corsSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Sign in required\"}");
                }))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error", "/api/auth/register", "/api/auth/login", "/api/auth/forgot", "/reset", "/unsubscribe", "/eye.html", "/actuator/health").permitAll()
                        .requestMatchers("/api/**", "/actuator/**").authenticated()
                        .anyRequest().permitAll()) // the rest is the web app's static files (no data behind them)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
