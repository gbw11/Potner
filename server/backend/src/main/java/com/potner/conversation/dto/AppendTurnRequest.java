package com.potner.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 대화 한 턴(사용자 발화 + 로봇 답변)을 덧붙이는 요청이다.
 *
 * <p>턴 단위인 이유는 이력 전체를 매번 올리는 방식의 두 결함을 피하려는 것이다. 그쪽은 대화가
 * 길어질수록 페이로드가 계속 커지고, 대화가 끝날 때만 저장하므로 로봇이 도중에 꺼지면 그
 * 대화가 통째로 사라진다. 턴이 끝날 때마다 두 줄만 보내면 둘 다 없어진다.
 *
 * @param sessionKey 젯슨 config 의 {@code conversation.session_id} 다. 한 기기 안에서 갈래를
 *                   나누는 이름이며 <strong>소유권 근거가 아니다</strong> — 어느 로봇인지는
 *                   장치 토큰이 정한다
 */
public record AppendTurnRequest(

        @NotBlank(message = "세션 키는 필수입니다.")
        @Size(max = 100, message = "세션 키는 100자 이하여야 합니다.")
        String sessionKey,

        @NotBlank(message = "사용자 발화는 필수입니다.")
        @Size(max = 2000, message = "사용자 발화는 2000자 이하여야 합니다.")
        String userText,

        @NotBlank(message = "로봇 답변은 필수입니다.")
        @Size(max = 2000, message = "로봇 답변은 2000자 이하여야 합니다.")
        String assistantText
) {
}
