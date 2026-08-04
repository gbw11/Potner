package com.potner.conversation.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.conversation.application.ConversationQueryService;
import com.potner.conversation.dto.ConversationMessagesResponse;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱이 식물과 로봇이 나눈 대화를 읽는 API 다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/conversations")
@Tag(name = "대화 조회", description = "로봇과 나눈 LLM 대화 이력 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PlantConversationController {

    private final ConversationQueryService conversationQueryService;

    public PlantConversationController(ConversationQueryService conversationQueryService) {
        this.conversationQueryService = conversationQueryService;
    }

    @GetMapping("/messages")
    @Operation(
            summary = "대화 이력 조회",
            description = """
                    **시간순(오래된 것부터)** 으로 내려준다. 화면은 위에서 아래로 시간이 흐르므로
                    앱이 되집을 일이 없다.

                    스크롤백은 커서 방식이다. 처음에는 beforeSeq 없이 부르면 최근 한 페이지가 오고,
                    위로 올릴 때 응답의 nextBeforeSeq 를 beforeSeq 에 넣어 다시 부른다. hasMore 가
                    false 면 더 없다.

                    page/size 가 아닌 이유는 대화가 뒤에 계속 붙기 때문이다. 스크롤하는 동안 새
                    발화가 들어오면 offset 이 밀려 같은 발화가 두 번 보이거나 빠진다.

                    size 는 potner.conversation.max-page-size 를 넘으면 그 값으로 줄인다.

                    **날짜 구분선은 서버가 만들지 않는다.** createdAt 을 보고 앱이 묶는다 — 서버가
                    묶어 보내면 화면 구조가 응답 형식에 갇힌다. 시각은 모두 UTC 다.

                    대화가 없으면 빈 목록이다(404 아님). 남의 식물은 404
                    (PLANT_NOT_FOUND) 다.

                    한 식물에 갈래가 여럿이면(기기 교체 등) 가장 최근에 오간 갈래를 보여준다.
                    """
    )
    public ConversationMessagesResponse getMessages(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @RequestParam(required = false) Integer beforeSeq,
            @RequestParam(required = false) Integer size
    ) {
        return conversationQueryService.getMessages(principal.userId(), plantId, beforeSeq, size);
    }
}
