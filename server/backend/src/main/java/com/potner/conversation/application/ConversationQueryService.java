package com.potner.conversation.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.conversation.config.ConversationProperties;
import com.potner.conversation.domain.RobotConversationRepository;
import com.potner.conversation.dto.ConversationMessagesResponse;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱이 식물과 로봇이 나눈 대화를 읽는 경로다.
 *
 * <p>장치 경로({@link DeviceConversationService})와 나눠 두는 이유는 인증과 대상 결정이 다르기
 * 때문이다. 앱은 사용자 JWT 로 들어와 소유권을 검사하고, 장치는 업로드 토큰으로 들어와 자기
 * 배정만 본다. 한 서비스에 합치면 두 인증 경로가 섞여 어느 쪽 규칙이 적용되는지 흐려진다.
 */
@Service
@Transactional(readOnly = true)
public class ConversationQueryService {

    private final PlantRepository plantRepository;
    private final RobotConversationRepository conversationRepository;
    private final ConversationMessageReader messageReader;
    private final ConversationProperties properties;

    public ConversationQueryService(
            PlantRepository plantRepository,
            RobotConversationRepository conversationRepository,
            ConversationMessageReader messageReader,
            ConversationProperties properties
    ) {
        this.plantRepository = plantRepository;
        this.conversationRepository = conversationRepository;
        this.messageReader = messageReader;
        this.properties = properties;
    }

    /**
     * 식물의 최근 대화를 읽는다. {@code beforeSeq} 를 주면 그보다 오래된 쪽으로 넘긴다.
     *
     * <p>대화가 없으면 빈 목록이다. 채팅 화면은 비어 있는 것이 정상 상태라 404 로 만들면 앱이
     * 오류 화면과 첫 대화 전을 구분해야 한다.
     */
    public ConversationMessagesResponse getMessages(
            String userId,
            String plantId,
            Integer beforeSeq,
            Integer size
    ) {
        requireOwnedPlant(userId, plantId);
        return conversationRepository.findFirstByPlantIdOrderByUpdatedAtDesc(plantId)
                .map(conversation -> messageReader.read(
                        conversation, beforeSeq, size, properties.defaultPageSize()))
                .orElseGet(() -> ConversationMessagesResponse.empty(plantId));
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }
}
