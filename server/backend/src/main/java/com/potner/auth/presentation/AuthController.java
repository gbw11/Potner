package com.potner.auth.presentation;

import com.potner.auth.application.AuthService;
import com.potner.auth.dto.EmailAvailabilityRequest;
import com.potner.auth.dto.EmailAvailabilityResponse;
import com.potner.auth.dto.LoginRequest;
import com.potner.auth.dto.SignupRequest;
import com.potner.auth.dto.SignupResponse;
import com.potner.auth.dto.TokenReissueRequest;
import com.potner.auth.dto.TokenResponse;
import com.potner.config.OpenApiConfig;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "인증", description = "회원가입, 로그인 및 JWT 관리 API")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 가입 화면의 "중복확인" 을 받쳐 준다.
     *
     * <p>GET 이 아니라 POST 다. 이메일을 쿼리스트링에 실으면 nginx 접근 로그에 남는다.
     *
     * <p>이 응답은 보장이 아니다. 확인 직후 다른 사용자가 같은 주소로 가입할 수 있으므로 앱은
     * 가입 API 의 409 {@code EMAIL_ALREADY_EXISTS} 처리를 그대로 유지해야 한다.
     */
    @PostMapping("/email-availability")
    @Operation(summary = "이메일 사용 가능 여부 확인")
    public EmailAvailabilityResponse checkEmailAvailability(
            @Valid @RequestBody EmailAvailabilityRequest request
    ) {
        return authService.checkEmailAvailability(request);
    }

    @PostMapping("/signup")
    @Operation(summary = "이메일 회원가입")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
    }

    @PostMapping("/login")
    @Operation(summary = "이메일 로그인")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/reissue")
    @Operation(summary = "Access Token 재발급")
    public TokenResponse reissue(@Valid @RequestBody TokenReissueRequest request) {
        return authService.reissue(request.refreshToken());
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody TokenReissueRequest request
    ) {
        authService.logout(principal, request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
