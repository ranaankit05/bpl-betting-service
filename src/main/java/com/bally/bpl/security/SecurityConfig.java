package com.bally.bpl.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Stateless API-key security. Coarse authorisation (which role may call which endpoint) is declared here;
 * fine-grained, data-dependent checks (is this bet yours?) live in the service layer.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyRegistry registry, ObjectMapper mapper,
                                            @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)            // stateless, header-authenticated API: no cookies, no CSRF surface
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(registry), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint(mapper))
                        .accessDeniedHandler(accessDeniedHandler(mapper)))
                .authorizeHttpRequests(auth -> {
                    // dev only: the H2 web console is reachable solely when spring.h2.console.enabled=true (profile "dev")
                    if (h2ConsoleEnabled) {
                        auth.requestMatchers(PathRequest.toH2Console()).permitAll();
                    }
                    auth
                            .requestMatchers("/actuator/health/**", "/actuator/health", "/error").permitAll()
                            // match management is restricted to operations / the score feed
                            .requestMatchers(HttpMethod.PUT, "/matches/**").hasRole("ADMIN")
                            // everything that exposes other users' bets is admin-only
                            .requestMatchers(HttpMethod.GET, "/matches/*/bets").hasRole("ADMIN")
                            // match data is readable by any authenticated caller
                            .requestMatchers(HttpMethod.GET, "/matches", "/matches/*").hasAnyRole("USER", "ADMIN")
                            // only app users bet, and only as themselves (enforced in BetService)
                            .requestMatchers(HttpMethod.POST, "/bets").hasRole("USER")
                            .requestMatchers(HttpMethod.GET, "/bets/*", "/users/*/bets").hasAnyRole("USER", "ADMIN")
                            .anyRequest().denyAll();
                });
        if (h2ConsoleEnabled) {
            http.headers(h -> h.frameOptions(f -> f.sameOrigin())); // the console renders inside frames
        }
        return http.build();
    }

    private static AuthenticationEntryPoint entryPoint(ObjectMapper mapper) {
        return (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "ApiKey realm=\"bpl\"");
            writeProblem(mapper, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "A valid X-API-Key header is required", request.getRequestURI());
        };
    }

    private static AccessDeniedHandler accessDeniedHandler(ObjectMapper mapper) {
        return (request, response, ex) -> writeProblem(mapper, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You are not allowed to perform this operation", request.getRequestURI());
    }

    private static void writeProblem(ObjectMapper mapper, HttpServletResponse response, HttpStatus status,
                                     String code, String detail, String path) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("path", path);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
