package com.potner.conversation.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.conversation.config.ConversationProperties;
import com.potner.conversation.domain.ConversationRole;
import com.potner.conversation.domain.RobotConversation;
import com.potner.conversation.domain.RobotConversationMessage;
import com.potner.conversation.domain.RobotConversationMessageRepository;
import com.potner.conversation.domain.RobotConversationRepository;
import com.potner.conversation.dto.ConversationMessagesResponse;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationQueryServiceTest {

    private static final String USER_ID = "user-1";
    private static final String PLANT_ID = "plant-1";
    private static final String ROBOT_ID = "robot-1";

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private RobotConversationRepository conversationRepository;

    @Mock
    private RobotConversationMessageRepository messageRepository;

    private ConversationQueryService service;

    @BeforeEach
    void setUp() {
        ConversationProperties properties = new ConversationProperties(50, 200, 40);
        service = new ConversationQueryService(
                plantRepository,
                conversationRepository,
                new ConversationMessageReader(messageRepository, properties),
                properties
        );
    }

    @Test
    void returnsMessagesOldestFirstWithACursorForOlderOnes() {
        givenOwnedPlant();
        RobotConversation conversation = givenConversation();
        givenMessages(conversation.getId(), 10, 9, 8);

        ConversationMessagesResponse response =
                service.getMessages(USER_ID, PLANT_ID, null, 3);

        // 화면은 위에서 아래로 시간이 흐른다.
        assertThat(response.messages()).extracting("seq").containsExactly(8, 9, 10);
        assertThat(response.nextBeforeSeq()).isEqualTo(8);
        assertThat(response.plantId()).isEqualTo(PLANT_ID);
    }

    /**
     * 한 건 더 읽어 판단한다. 별도 count 쿼리를 쓰면 그 사이에 발화가 들어와 hasMore 와 실제가
     * 어긋날 수 있다.
     */
    @Test
    void hasMoreIsTrueWhenOlderMessagesRemain() {
        givenOwnedPlant();
        RobotConversation conversation = givenConversation();
        // size 2 를 요청했으므로 리더는 3건을 읽는다. 3건이 오면 더 있다는 뜻이다.
        givenMessages(conversation.getId(), 10, 9, 8);

        ConversationMessagesResponse response =
                service.getMessages(USER_ID, PLANT_ID, null, 2);

        assertThat(response.hasMore()).isTrue();
        // 넘겨받은 여분 한 건은 페이지에 담지 않는다.
        assertThat(response.messages()).extracting("seq").containsExactly(9, 10);
        assertThat(response.nextBeforeSeq()).isEqualTo(9);
    }

    /** 상한을 넘겨 달라고 하면 거절하지 않고 상한으로 줄인다. alert 목록과 같은 방침이다. */
    @Test
    void oversizedRequestIsClampedToTheMaximum() {
        givenOwnedPlant();
        RobotConversation conversation = givenConversation();
        givenMessages(conversation.getId());

        service.getMessages(USER_ID, PLANT_ID, null, 5000);

        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(messageRepository).findByConversationIdAndSeqLessThanOrderBySeqDesc(
                eq(conversation.getId()), anyInt(), limit.capture());
        // 상한 200 + 더 있는지 판단할 여분 1
        assertThat(limit.getValue().max()).isEqualTo(201);
    }

    @Test
    void cursorIsPassedThroughAsAnExclusiveUpperBound() {
        givenOwnedPlant();
        RobotConversation conversation = givenConversation();
        givenMessages(conversation.getId());

        service.getMessages(USER_ID, PLANT_ID, 8, 50);

        ArgumentCaptor<Integer> before = ArgumentCaptor.forClass(Integer.class);
        verify(messageRepository).findByConversationIdAndSeqLessThanOrderBySeqDesc(
                eq(conversation.getId()), before.capture(), any(Limit.class));
        assertThat(before.getValue()).isEqualTo(8);
    }

    /** 채팅 화면은 비어 있는 것이 정상 상태다. 404 로 만들면 앱이 오류와 첫 대화 전을 구분해야 한다. */
    @Test
    void plantWithoutAnyConversationReturnsAnEmptyList() {
        givenOwnedPlant();
        when(conversationRepository.findFirstByPlantIdOrderByUpdatedAtDesc(PLANT_ID))
                .thenReturn(Optional.empty());

        ConversationMessagesResponse response =
                service.getMessages(USER_ID, PLANT_ID, null, null);

        assertThat(response.messages()).isEmpty();
        assertThat(response.hasMore()).isFalse();
        assertThat(response.conversationId()).isNull();
        assertThat(response.plantId()).isEqualTo(PLANT_ID);
    }

    @Test
    void otherUsersPlantIsNotFound() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMessages(USER_ID, PLANT_ID, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PLANT_NOT_FOUND);
    }

    private void givenOwnedPlant() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(mock(Plant.class)));
    }

    private RobotConversation givenConversation() {
        RobotConversation conversation = RobotConversation.open(ROBOT_ID, PLANT_ID, "potner-01");
        when(conversationRepository.findFirstByPlantIdOrderByUpdatedAtDesc(PLANT_ID))
                .thenReturn(Optional.of(conversation));
        return conversation;
    }

    /** 조회는 내림차순이므로 인자도 내림차순으로 준다. */
    private void givenMessages(String conversationId, int... seqsDesc) {
        List<RobotConversationMessage> messages = new ArrayList<>();
        for (int seq : seqsDesc) {
            messages.add(RobotConversationMessage.of(
                    conversationId,
                    seq,
                    seq % 2 == 0 ? ConversationRole.USER : ConversationRole.ASSISTANT,
                    "발화 " + seq
            ));
        }
        when(messageRepository.findByConversationIdAndSeqLessThanOrderBySeqDesc(
                eq(conversationId), anyInt(), any(Limit.class)))
                .thenReturn(messages);
    }
}
