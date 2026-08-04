package com.potner.plant.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.push.application.FcmTokenService;
import com.potner.push.application.PushMessage;
import com.potner.push.application.PushSendResult;
import com.potner.push.application.PushSender;
import com.potner.push.application.PushTargetResolver;
import com.potner.push.application.RepottingPushMessageFactory;
import com.potner.push.domain.FcmToken;
import com.potner.user.domain.NotificationCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepottingReminderServiceTest {

    private static final String USER_ID = "user-1";
    private static final String PLANT_ID = "plant-1";

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PushTargetResolver pushTargetResolver;

    @Mock
    private PushSender pushSender;

    @Mock
    private FcmTokenService fcmTokenService;

    private RepottingReminderService service;

    @BeforeEach
    void setUp() {
        service = new RepottingReminderService(
                plantRepository,
                pushTargetResolver,
                new RepottingPushMessageFactory(),
                pushSender,
                fcmTokenService
        );
    }

    @Test
    void sendsTheRepottingCopyToEveryRegisteredDevice() {
        givenOwnedPlant("두두");
        List<FcmToken> targets = List.of(mock(FcmToken.class), mock(FcmToken.class));
        when(pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE))
                .thenReturn(targets);
        when(pushSender.send(anyList(), any())).thenReturn(new PushSendResult(2, List.of()));

        service.send(USER_ID, PLANT_ID);

        ArgumentCaptor<PushMessage> messages = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender).send(eq(targets), messages.capture());
        PushMessage message = messages.getValue();
        assertThat(message.title()).isEqualTo("분갈이 할 때가 됐어요");
        assertThat(message.body()).contains("두두");
        // 알림을 누르면 알림 목록이 아니라 방법 화면이 열려야 한다. 분갈이는 확인하고 넘기는
        // 알림이 아니라 지금 무엇을 어떻게 해야 하는지가 필요한 안내다.
        assertThat(message.data())
                .containsEntry("type", "REPOTTING")
                .containsEntry("plantId", PLANT_ID)
                .containsEntry("route", "/repotting");
    }

    @Test
    void invalidTokensAreDeactivated() {
        givenOwnedPlant("두두");
        when(pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE))
                .thenReturn(List.of(mock(FcmToken.class)));
        when(pushSender.send(anyList(), any()))
                .thenReturn(new PushSendResult(0, List.of("dead-device")));

        service.send(USER_ID, PLANT_ID);

        verify(fcmTokenService).deactivate(List.of("dead-device"));
    }

    @Test
    void otherUsersPlantIsNotFound() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send(USER_ID, PLANT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PLANT_NOT_FOUND);
        verify(pushSender, never()).send(anyList(), any());
    }

    @Test
    void noRegisteredDeviceIsReportedInsteadOfSilentlyDoingNothing() {
        // 이벤트로 나가는 알림은 대상이 없으면 조용히 넘어가지만, 여기서는 사람이 버튼을 눌러
        // 부른 것이라 아무 일도 안 일어난 이유를 알려 줘야 한다.
        //
        // 문구를 만들기 전에 끊기므로 별명·id 는 스터빙하지 않는다.
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(mock(Plant.class)));
        when(pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.send(USER_ID, PLANT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PUSH_TARGET_NOT_FOUND);
        verify(pushSender, never()).send(anyList(), any());
    }

    private void givenOwnedPlant(String nickname) {
        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(PLANT_ID);
        when(plant.getNickname()).thenReturn(nickname);
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(plant));
    }
}
