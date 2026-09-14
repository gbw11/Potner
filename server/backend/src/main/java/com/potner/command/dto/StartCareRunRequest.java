package com.potner.command.dto;

import com.potner.command.domain.CommandPurpose;
import jakarta.validation.constraints.NotNull;

/**
 * 자동 케어 한 회차를 지금 시작하라는 요청이다.
 *
 * <p>어떤 명령을 보낼지는 받지 않는다. {@code purpose} 만 정하면 첫 단계와 이후 순서는 서버의
 * 오케스트레이터가 정한다 — 그 판단을 앱이 대신하기 시작하면 자동 케어와 수동 조작이 두 벌로
 * 갈린다.
 */
public record StartCareRunRequest(
        @NotNull(message = "케어 종류는 필수입니다.") CommandPurpose purpose
) {
}
