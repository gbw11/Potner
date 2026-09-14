package com.potner.conversation.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.conversation.config.ConversationProperties;
import com.potner.conversation.domain.ConversationRole;
import com.potner.conversation.domain.RobotConversation;
import com.potner.conversation.domain.RobotConversationMessage;
import com.potner.conversation.domain.RobotConversationMessageRepository;
import com.potner.conversation.domain.RobotConversationRepository;
import com.potner.conversation.dto.AppendTurnRequest;
import com.potner.conversation.dto.ConversationMessagesResponse;
import com.potner.device.domain.DeviceUploadToken;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceConversationServiceTest {

    private static final String TOKEN = "upload-token-plain";
    private static final String TOKEN_HASH = "upload-token-hash";
    private static final String ROBOT_ID = "robot-1";
    private static final String PLANT_ID = "plant-1";
    private static final String SESSION = "potner-01";

    @Mock
    private RobotRepository robotRepository;

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private DeviceUploadToken uploadTokenIssuer;

    @Mock
    private RobotConversationRepository conversationRepository;

    @Mock
    private RobotConversationMessageRepository messageRepository;

    private DeviceConversationService service;

    @BeforeEach
    void setUp() {
        ConversationProperties properties = new ConversationProperties(50, 200, 40);
        service = new DeviceConversationService(
                robotRepository,
                assignmentRepository,
                uploadTokenIssuer,
                conversationRepository,
                messageRepository,
                new ConversationMessageReader(messageRepository, properties),
                properties
        );
        when(uploadTokenIssuer.hash(TOKEN)).thenReturn(TOKEN_HASH);
    }

    @Test
    void appendsTheTurnAsTwoMessagesInOrder() {
        givenRobot();
        givenAssignment(PLANT_ID);
        RobotConversation conversation = givenExistingConversation();
        when(messageRepository.findMaxSeq(conversation.getId())).thenReturn(Optional.of(3));

        service.appendTurn(TOKEN, new AppendTurnRequest(SESSION, "지금 온도 어때?", "22도야, 딱 좋아!"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RobotConversationMessage>> saved = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(saved.capture());

        List<RobotConversationMessage> messages = saved.getValue();
        assertThat(messages).hasSize(2);
        // 사용자 발화가 먼저, 답변이 다음이다. 뒤바뀌면 다음 복원에서 대화가 거꾸로 읽힌다.
        assertThat(messages.get(0).getRole()).isEqualTo(ConversationRole.USER);
        assertThat(messages.get(0).getSeq()).isEqualTo(4);
        assertThat(messages.get(0).getContent()).isEqualTo("지금 온도 어때?");
        assertThat(messages.get(1).getRole()).isEqualTo(ConversationRole.ASSISTANT);
        assertThat(messages.get(1).getSeq()).isEqualTo(5);
    }

    /** 첫 발화의 seq 는 0 이어야 한다. 1 부터 시작하면 커서 계산이 한 칸씩 어긋난다. */
    @Test
    void firstTurnStartsFromSeqZero() {
        givenRobot();
        givenAssignment(PLANT_ID);
        RobotConversation conversation = givenExistingConversation();
        when(messageRepository.findMaxSeq(conversation.getId())).thenReturn(Optional.empty());

        service.appendTurn(TOKEN, new AppendTurnRequest(SESSION, "안녕", "안녕!"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RobotConversationMessage>> saved = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(saved.capture());
        assertThat(saved.getValue().get(0).getSeq()).isZero();
        assertThat(saved.getValue().get(1).getSeq()).isEqualTo(1);
    }

    /** 갈래가 없으면 첫 발화에서 만든다. 장치가 시작을 따로 알릴 필요가 없어야 한다. */
    @Test
    void opensTheConversationOnTheFirstTurn() {
        givenRobot();
        givenAssignment(PLANT_ID);
        when(conversationRepository.findByRobotIdAndSessionKey(ROBOT_ID, SESSION))
                .thenReturn(Optional.empty());
        when(conversationRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(messageRepository.findMaxSeq(anyString())).thenReturn(Optional.empty());

        service.appendTurn(TOKEN, new AppendTurnRequest(SESSION, "안녕", "안녕!"));

        ArgumentCaptor<RobotConversation> opened = ArgumentCaptor.forClass(RobotConversation.class);
        verify(conversationRepository).save(opened.capture());
        assertThat(opened.getValue().getRobotId()).isEqualTo(ROBOT_ID);
        assertThat(opened.getValue().getSessionKey()).isEqualTo(SESSION);
        // plantId 는 요청이 아니라 활성 배정에서 서버가 정한다.
        assertThat(opened.getValue().getPlantId()).isEqualTo(PLANT_ID);
    }

    /**
     * 배정이 없어도 저장한다. 식물을 붙이기 전에 대화를 시험할 수 있어야 하고, 그때 나눈 말을
     * 버릴 이유가 없다.
     */
    @Test
    void appendsEvenWhenTheRobotHasNoAssignedPlant() {
        givenRobot();
        when(assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(ROBOT_ID))
                .thenReturn(Optional.empty());
        when(conversationRepository.findByRobotIdAndSessionKey(ROBOT_ID, SESSION))
                .thenReturn(Optional.empty());
        when(conversationRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(messageRepository.findMaxSeq(anyString())).thenReturn(Optional.empty());

        service.appendTurn(TOKEN, new AppendTurnRequest(SESSION, "안녕", "안녕!"));

        ArgumentCaptor<RobotConversation> opened = ArgumentCaptor.forClass(RobotConversation.class);
        verify(conversationRepository).save(opened.capture());
        assertThat(opened.getValue().getPlantId()).isNull();
        verify(messageRepository).saveAll(any());
    }

    @Test
    void restoreReturnsMessagesOldestFirst() {
        givenRobot();
        RobotConversation conversation = givenExistingConversation();
        // 조회는 내림차순으로 오지만 응답은 시간순이어야 LLM 메시지 목록에 그대로 들어간다.
        when(messageRepository.findByConversationIdAndSeqLessThanOrderBySeqDesc(
                eq(conversation.getId()), anyInt(), any(Limit.class)))
                .thenReturn(List.of(
                        message(conversation.getId(), 3, ConversationRole.ASSISTANT, "셋"),
                        message(conversation.getId(), 2, ConversationRole.USER, "둘"),
                        message(conversation.getId(), 1, ConversationRole.ASSISTANT, "하나")
                ));

        ConversationMessagesResponse response =
                service.getRecentMessages(TOKEN, SESSION, null, null);

        assertThat(response.messages()).extracting("content")
                .containsExactly("하나", "둘", "셋");
        assertThat(response.messages()).extracting("role")
                .containsExactly("assistant", "user", "assistant");
        // 더 오래된 쪽으로 넘길 커서는 이 페이지에서 가장 오래된 seq 다.
        assertThat(response.nextBeforeSeq()).isEqualTo(1);
        assertThat(response.hasMore()).isFalse();
    }

    /** 아직 대화가 없는 것은 오류가 아니다. 첫 대화를 시작하려는 로봇에게 404 를 주면 안 된다. */
    @Test
    void restoreOnAFreshRobotReturnsAnEmptyList() {
        givenRobot();
        when(conversationRepository.findByRobotIdAndSessionKey(ROBOT_ID, SESSION))
                .thenReturn(Optional.empty());

        ConversationMessagesResponse response =
                service.getRecentMessages(TOKEN, SESSION, null, null);

        assertThat(response.messages()).isEmpty();
        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextBeforeSeq()).isNull();
    }

    @Test
    void blankTokenIsUnauthorized() {
        assertThatThrownBy(() -> service.appendTurn(
                "  ", new AppendTurnRequest(SESSION, "안녕", "안녕!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_DEVICE_TOKEN);
        verify(messageRepository, never()).saveAll(any());
    }

    @Test
    void unknownTokenIsUnauthorized() {
        when(robotRepository.findByUploadTokenHashAndReleasedAtIsNull(TOKEN_HASH))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.appendTurn(
                TOKEN, new AppendTurnRequest(SESSION, "안녕", "안녕!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_DEVICE_TOKEN);
        verify(messageRepository, never()).saveAll(any());
    }

    @Test
    void restoreWithoutSessionKeyIsRejected() {
        givenRobot();

        assertThatThrownBy(() -> service.getRecentMessages(TOKEN, " ", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    private void givenRobot() {
        Robot robot = mock(Robot.class);
        when(robot.getId()).thenReturn(ROBOT_ID);
        when(robotRepository.findByUploadTokenHashAndReleasedAtIsNull(TOKEN_HASH))
                .thenReturn(Optional.of(robot));
    }

    private void givenAssignment(String plantId) {
        PlantDeviceAssignment assignment = mock(PlantDeviceAssignment.class);
        when(assignment.getPlantId()).thenReturn(plantId);
        when(assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(ROBOT_ID))
                .thenReturn(Optional.of(assignment));
    }

    private RobotConversation givenExistingConversation() {
        RobotConversation conversation = RobotConversation.open(ROBOT_ID, PLANT_ID, SESSION);
        when(conversationRepository.findByRobotIdAndSessionKey(ROBOT_ID, SESSION))
                .thenReturn(Optional.of(conversation));
        return conversation;
    }

    private RobotConversationMessage message(
            String conversationId,
            int seq,
            ConversationRole role,
            String content
    ) {
        return RobotConversationMessage.of(conversationId, seq, role, content);
    }
}
