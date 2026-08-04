package com.potner.conversation.presentation;

import com.potner.conversation.application.DeviceConversationService;
import com.potner.conversation.dto.AppendTurnRequest;
import com.potner.conversation.dto.ConversationMessagesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 젯슨이 LLM 대화를 서버에 남기고 다시 불러오는 API 다.
 *
 * <p>사용자 JWT 가 아니라 업로드 토큰으로 인증한다. 사진 업로드·센서 조회와 같은 토큰이다.
 */
@RestController
@RequestMapping("/api/v1/device/conversations")
@Tag(name = "장치 대화", description = "젯슨이 LLM 대화 이력을 저장·복원하는 API")
public class DeviceConversationController {

    private final DeviceConversationService deviceConversationService;

    public DeviceConversationController(DeviceConversationService deviceConversationService) {
        this.deviceConversationService = deviceConversationService;
    }

    @PostMapping("/turns")
    @Operation(
            summary = "대화 한 턴 저장",
            description = """
                    사용자 발화와 로봇 답변을 한 쌍으로 덧붙인다. **턴이 끝날 때마다 부른다.**

                    이력 전체를 통째로 올리지 않는다. 그 방식은 대화가 길어질수록 페이로드가 계속
                    커지고, 대화 종료를 명시적으로 부르지 않으면(브라우저를 그냥 닫거나 로봇이
                    꺼지면) 그 대화가 통째로 사라진다.

                    sessionKey 는 젯슨 config 의 conversation.session_id 다. 한 기기 안에서 갈래를
                    나누는 이름이며 소유권 근거가 아니다 — 어느 로봇인지는 X-Device-Token 이 정하고,
                    어느 식물인지는 그 로봇의 활성 배정에서 서버가 정한다.

                    갈래가 없으면 서버가 만든다. 대화를 시작한다고 따로 알릴 필요가 없다.

                    **배정이 없어도 저장한다.** 식물을 붙이기 전에 대화를 시험할 수 있어야 하고,
                    그때 나눈 말을 버릴 이유가 없다. 다만 앱의 식물별 조회에서는 빠진다.

                    응답은 저장 직후의 최근 발화 목록이다(시간순). 토큰이 틀리거나 없으면 401 이다.
                    """
    )
    public ConversationMessagesResponse appendTurn(
            // required=false 인 이유: 전역 예외 처리기가 MissingRequestHeaderException 을 다루지
            // 않아 required=true 로 두면 헤더 누락이 401 이 아니라 500 으로 나간다. 누락은
            // 서비스의 blank 검사가 401 로 떨어뜨린다. 센서 조회와 같은 판단이다.
            @RequestHeader(value = "X-Device-Token", required = false) String uploadToken,
            @Valid @RequestBody AppendTurnRequest request
    ) {
        return deviceConversationService.appendTurn(uploadToken, request);
    }

    @GetMapping("/messages")
    @Operation(
            summary = "대화 복원",
            description = """
                    재시작 뒤 대화를 이어가려고 최근 발화를 받아 간다. **시간순(오래된 것부터)** 이라
                    LLM 메시지 목록에 그대로 넣을 수 있다. role 은 소문자다.

                    전체를 주지 않는다. 젯슨은 프롬프트에 최근 10턴만 쓰므로 전체를 내려보내면
                    대화가 길어질수록 응답만 무거워진다. 기본 개수는
                    potner.conversation.device-restore-size 이고 size 로 조절한다(상한을 넘으면
                    상한으로 줄인다).

                    더 오래된 것이 필요하면 응답의 nextBeforeSeq 를 beforeSeq 에 넣어 다시 부른다.
                    hasMore 가 false 면 더 없다.

                    **아직 대화가 없으면 빈 목록이다(404 아님).** 첫 대화를 시작하려는 로봇이
                    정상 흐름과 장애를 구분해야 하는 상황을 만들지 않는다.
                    """
    )
    public ConversationMessagesResponse getMessages(
            @RequestHeader(value = "X-Device-Token", required = false) String uploadToken,
            @RequestParam String sessionKey,
            @RequestParam(required = false) Integer beforeSeq,
            @RequestParam(required = false) Integer size
    ) {
        return deviceConversationService.getRecentMessages(uploadToken, sessionKey, beforeSeq, size);
    }
}
