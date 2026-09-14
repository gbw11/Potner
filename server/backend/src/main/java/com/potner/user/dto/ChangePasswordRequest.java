package com.potner.user.dto;

import com.potner.common.validation.PasswordConstraints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "현재 비밀번호는 필수입니다.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(
                min = PasswordConstraints.MIN_LENGTH,
                max = PasswordConstraints.MAX_LENGTH,
                message = PasswordConstraints.LENGTH_MESSAGE
        )
        @Pattern(
                regexp = PasswordConstraints.PATTERN,
                message = PasswordConstraints.PATTERN_MESSAGE
        )
        String newPassword
) {
}
