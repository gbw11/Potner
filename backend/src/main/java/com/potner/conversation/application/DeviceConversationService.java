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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 로봇이 LLM 대화를 서버에 남기고 다시 불러오는 경로다.
 *
 * <p>지금 대화 이력은 젯슨 로컬 JSON 하나뿐이라 기기를 다시 이미징하면 사라지고, 앱은 볼 곳이
 * 없다. 이 서비스가 그 저장소를 서버로 옮긴다.
 *
 * <p><strong>턴 단위로 받는다.</strong> 젯슨의 기존 방식은 대화가 끝날 때 이력 전체를 통째로
 * 올리는 것인데 결함이 둘이다 — 대화가 길어질수록 페이로드가 계속 커지고, 대화 종료를 명시적으로
 * 부르지 않으면(브라우저를 그냥 닫거나 로봇이 꺼지면) 그 대화가 통째로 사라진다. 턴이 끝날 때마다
 * 두 줄만 받으면 둘 다 없어진다.
 *
 * <p>인증은 업로드 토큰(사진 업로드·센서 조회와 같은 토큰)이고, 어느 식물인지는 요청이 아니라
 * <strong>로봇의 활성 배정에서 서버가 정한다</strong>. 장치가 session_key 로 대상을 지정할 수
 * 있으면 토큰 하나로 남의 대화를 읽거나 덮어쓸 수 있다.
 *
 * <p>토큰 검증은 {@code DeviceSensorQueryService} 와 같은 규칙의 의도적 중복이다 — 이 레포는
 * 검증을 공용 컴포넌트로 빼는 대신 서비스마다 자기 손으로 하는 관례를 따른다.
 */
@Service
@Transactional(readOnly = true)
public class DeviceConversationService {

    private final RobotRepository robotRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final DeviceUploadToken uploadTokenIssuer;
    private final RobotConversationRepository conversationRepository;
    private final RobotConversationMessageRepository messageRepository;
    private final ConversationMessageReader messageReader;
    private final ConversationProperties properties;

    public DeviceConversationService(
            RobotRepository robotRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            DeviceUploadToken uploadTokenIssuer,
            RobotConversationRepository conversationRepository,
            RobotConversationMessageRepository messageRepository,
            ConversationMessageReader messageReader,
            ConversationProperties properties
    ) {
        this.robotRepository = robotRepository;
        this.assignmentRepository = assignmentRepository;
        this.uploadTokenIssuer = uploadTokenIssuer;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.messageReader = messageReader;
        this.properties = properties;
    }

    /**
     * 한 턴(사용자 발화 + 로봇 답변)을 덧붙인다.
     *
     * <p>갈래가 없으면 여기서 만든다. 장치가 대화를 시작하겠다고 따로 알릴 필요가 없어야 한다 —
     * 그런 단계를 두면 그 요청이 실패했을 때 발화가 갈 곳을 잃는다.
     */
    @Transactional
    public ConversationMessagesResponse appendTurn(String uploadToken, AppendTurnRequest request) {
        Robot robot = findRobotByToken(uploadToken);
        RobotConversation conversation = findOrOpen(robot, request.sessionKey());

        // seq 는 최대값 + 1 이다. UNIQUE (conversation_id, seq) 가 최후의 방어선이라, 같은
        // 갈래에 동시 요청이 겹치면 뒤엣것이 제약 위반으로 실패한다. 한 갈래는 로봇 하나가
        // 순차로 쓰는 것이므로 실제로 겹치지 않는다.
        int nextSeq = messageRepository.findMaxSeq(conversation.getId()).orElse(-1) + 1;

        messageRepository.saveAll(List.of(
                RobotConversationMessage.of(
                        conversation.getId(), nextSeq, ConversationRole.USER, request.userText()),
                RobotConversationMessage.of(
                        conversation.getId(), nextSeq + 1, ConversationRole.ASSISTANT, request.assistantText())
        ));

        return messageReader.read(conversation, null, null, properties.deviceRestoreSize());
    }

    /**
     * 로봇이 재시작 뒤 대화를 이어가려고 최근 발화를 받아 간다.
     *
     * <p>전체를 주지 않는다. 젯슨은 프롬프트에 최근 10턴만 쓰므로 전체를 내려보내면 대화가
     * 길어질수록 응답만 무거워진다.
     */
    public ConversationMessagesResponse getRecentMessages(
            String uploadToken,
            String sessionKey,
            Integer beforeSeq,
            Integer size
    ) {
        Robot robot = findRobotByToken(uploadToken);
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return conversationRepository.findByRobotIdAndSessionKey(robot.getId(), sessionKey)
                .map(conversation -> messageReader.read(
                        conversation, beforeSeq, size, properties.deviceRestoreSize()))
                // 아직 대화가 없는 것은 오류가 아니다. 첫 대화를 시작하려는 로봇에게 404 를
                // 주면 그쪽에서 정상 흐름과 장애를 구분해야 한다.
                .orElseGet(() -> ConversationMessagesResponse.empty(null));
    }

    private RobotConversation findOrOpen(Robot robot, String sessionKey) {
        return conversationRepository.findByRobotIdAndSessionKey(robot.getId(), sessionKey)
                .orElseGet(() -> conversationRepository.save(RobotConversation.open(
                        robot.getId(),
                        // 배정이 없어도 대화는 받는다. 로봇에 식물을 붙이기 전에 대화를 시험할
                        // 수 있어야 하고, 그때 나눈 말을 버릴 이유가 없다. 앱 조회에서만 빠진다.
                        activePlantIdOrNull(robot.getId()),
                        sessionKey
                )));
    }

    private String activePlantIdOrNull(String robotId) {
        return assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(robotId)
                .map(PlantDeviceAssignment::getPlantId)
                .orElse(null);
    }

    /** 토큰이 비었거나 맞는 로봇이 없으면 401 이다. 어느 쪽인지는 구분해 알려주지 않는다. */
    private Robot findRobotByToken(String uploadToken) {
        if (uploadToken == null || uploadToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_DEVICE_TOKEN);
        }
        return robotRepository
                .findByUploadTokenHashAndReleasedAtIsNull(uploadTokenIssuer.hash(uploadToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_DEVICE_TOKEN));
    }
}
