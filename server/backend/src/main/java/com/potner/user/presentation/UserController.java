package com.potner.user.presentation;

import com.potner.security.AuthenticatedUser;
import com.potner.config.OpenApiConfig;
import com.potner.user.application.UserService;
import com.potner.user.dto.ChangePasswordRequest;
import com.potner.user.dto.UpdateNicknameRequest;
import com.potner.user.dto.UserMeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "사용자", description = "인증 사용자 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "현재 사용자 조회")
    public UserMeResponse getMe(@AuthenticationPrincipal AuthenticatedUser principal) {
        return userService.getMe(principal);
    }

    @PatchMapping("/me")
    @Operation(summary = "닉네임 변경")
    public UserMeResponse changeNickname(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateNicknameRequest request
    ) {
        return userService.changeNickname(principal.userId(), request);
    }

    @PatchMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "비밀번호 변경",
            description = """
                    현재 비밀번호가 일치해야 하고 새 비밀번호는 회원가입과 같은 규칙을 따른다.
                    변경에 성공하면 살아 있는 Refresh Token 을 모두 폐기하므로 변경한 기기도
                    재로그인이 필요하다. 유출된 Refresh Token 을 무효화하기 위한 동작이다.
                    """
    )
    public void changePassword(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        userService.changePassword(principal.userId(), request);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "회원 탈퇴",
            description = """
                    계정을 탈퇴 상태로 바꾸고 Refresh Token 을 모두 폐기한다.
                    인증 필터가 매 요청 계정 상태를 확인하므로 탈퇴 직후부터 모든 요청이 거부되고
                    다시 로그인할 수도 없다. 데이터 행을 물리적으로 삭제하지는 않는다.
                    """
    )
    public void withdraw(@AuthenticationPrincipal AuthenticatedUser principal) {
        userService.withdraw(principal.userId());
    }
}
