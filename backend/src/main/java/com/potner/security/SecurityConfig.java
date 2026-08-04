package com.potner.security;

import com.potner.auth.jwt.JwtProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**").permitAll()
                        // email-availability 는 가입 전에 쓰이므로 토큰이 있을 수 없다.
                        //
                        // 이 경로는 어떤 주소가 가입되어 있는지 알려 주므로 계정 열거에 쓰일 수
                        // 있다. 다만 signup 이 이미 409 EMAIL_ALREADY_EXISTS 로 같은 사실을
                        // 알려 주고 있어 노출 면적이 새로 생기는 것은 아니다. 열거를 막으려면
                        // 두 경로에 함께 요청 제한을 걸어야 하며, 한쪽만 막는 것은 의미가 없다.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login",
                                "/api/v1/auth/reissue",
                                "/api/v1/auth/email-availability").permitAll()
                        // 장치는 사용자 JWT 를 가질 수 없다. X-Device-Token 을 서비스가 직접
                        // 검증하므로 인증이 없는 것이 아니라 인증 수단이 다른 경로다.
                        .requestMatchers(HttpMethod.POST, "/api/v1/device/photos").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/device/sensors/current").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/device/conversations/turns").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/device/conversations/messages").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/health",
                                "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
