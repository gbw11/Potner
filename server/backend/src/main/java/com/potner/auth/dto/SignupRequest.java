package com.potner.auth.dto;

import com.potner.common.validation.PasswordConstraints;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이어야 합니다.")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(
                min = PasswordConstraints.MIN_LENGTH,
                max = PasswordConstraints.MAX_LENGTH,
                message = PasswordConstraints.LENGTH_MESSAGE
        )
        @Pattern(
                regexp = PasswordConstraints.PATTERN,
                message = PasswordConstraints.PATTERN_MESSAGE
        )
        String password,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 50, message = "닉네임은 50자 이하여야 합니다.")
        String nickname
) {
}
