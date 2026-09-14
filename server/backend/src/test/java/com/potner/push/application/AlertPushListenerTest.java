package com.potner.push.application;

import com.potner.alert.application.AlertOpenedEvent;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.push.domain.FcmToken;
import com.potner.user.domain.NotificationCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertPushListenerTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final AlertOpenedEvent EVENT = new AlertOpenedEvent(
            "50000000-0000-0000-0000-0000000000ee",
            USER_ID,
            "20000000-0000-0000-0000-0000000000bb",
            "로지",
            AlertMetricType.SOIL_MOISTURE,
            AlertDeviation.LOW
    );

    @Mock
    private PushTargetResolver pushTargetResolver;

    @Mock
    private PushSender pushSender;

    @Mock
    private FcmTokenService fcmTokenService;

    private AlertPushListener alertPushListener;

    @BeforeEach
    void setUp() {
        alertPushListener = new AlertPushListener(
                pushTargetResolver,
                new AlertPushMessageFactory(),
                pushSender,
                fcmTokenService
        );
    }

    @Test
    void plantCareCategoryDecidesWhetherToSend() {
        List<FcmToken> targets = List.of(token("device-1", "token-1"));
        when(pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE))
                .thenReturn(targets);
        when(pushSender.send(anyList(), any())).thenReturn(PushSendResult.none());

        alertPushListener.onAlertOpened(EVENT);

        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender).send(anyList(), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().body()).contains("로지의");
    }

    @Test
    void noTargetMeansNoSend() {
        // 수신을 껐거나 등록된 기기가 없는 사용자다. 발송기를 부를 이유가 없다.
        when(pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE))
                .thenReturn(List.of());

        alertPushListener.onAlertOpened(EVENT);

        verify(pushSender, never()).send(anyList(), any());
        verify(fcmTokenService, never()).deactivate(any());
    }

    @Test
    void invalidTokensReportedBySenderAreDeactivated() {
        List<FcmToken> targets = List.of(token("device-1", "token-1"), token("device-2", "token-2"));
        when(pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE))
                .thenReturn(targets);
        when(pushSender.send(anyList(), any()))
                .thenReturn(new PushSendResult(1, List.of("device-2")));

        alertPushListener.onAlertOpened(EVENT);

        verify(fcmTokenService).deactivate(List.of("device-2"));
    }

    @Test
    void sendFailureDoesNotEscapeToThePublisher() {
        // 발송 실패로 예외가 올라가면 커밋 이후 콜백을 타고 MQTT 수집 스레드까지 깬다.
        List<FcmToken> targets = List.of(token("device-1", "token-1"));
        when(pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE))
                .thenReturn(targets);
        when(pushSender.send(anyList(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> alertPushListener.onAlertOpened(EVENT)).doesNotThrowAnyException();
    }

    private FcmToken token(String installationId, String value) {
        FcmToken token = mock(FcmToken.class);
        org.mockito.Mockito.lenient().when(token.getInstallationId()).thenReturn(installationId);
        org.mockito.Mockito.lenient().when(token.getToken()).thenReturn(value);
        return token;
    }
}
