package com.potner.user.presentation;

import com.potner.security.AuthenticatedUser;
import com.potner.config.OpenApiConfig;
import com.potner.user.application.UserService;
import com.potner.user.dto.UserMeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "사용자", description = "인증 사용자 API")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "현재 사용자 조회")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public UserMeResponse getMe(@AuthenticationPrincipal AuthenticatedUser principal) {
        return userService.getMe(principal);
    }
}
