package com.potner.command.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 장치가 회신하는 명령 결과다. 라즈베리 구현이 기준이며 서버가 그 형식을 따른다.
 *
 * <p>급수 성공은 {@code status=OK} 에 {@code requestedMl}/{@code dispensedMl}/{@code durationSec}/
 * {@code capped} 가, 실패는 {@code status=ERROR} 에 {@code error} 문구가, 작업 중 거절은
 * {@code status=BUSY} 가 실린다. 촬영은 실패에 {@code code} 가 추가된다. 여기 없는 필드는
 * 무시한다 — 장치가 필드를 늘려도 수신이 깨지면 안 된다.
 *
 * <p>{@code requestId} 에 {@code @NotNull} 을 걸지 않는다. 장치는 명령에 requestId 가 없었어도
 * 회신을 보내는데, 그 회신은 형식 오류가 아니라 "대조할 수 없음" 으로 따로 다뤄야 원인이
 * 로그에 남는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommandResultMessage(
        @NotNull UUID messageId,
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}\\s]+$")
        String deviceId,
        @Size(max = 100) String requestId,
        @NotBlank String status,
        BigDecimal requestedMl,
        BigDecimal dispensedMl,
        BigDecimal durationSec,
        Boolean capped,
        String error,
        String code
) {
}
