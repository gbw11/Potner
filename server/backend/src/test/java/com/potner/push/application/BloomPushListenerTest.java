package com.potner.push.application;

import com.potner.bloom.application.BloomRecordedEvent;
import com.potner.push.domain.FcmToken;
import com.potner.user.domain.NotificationCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BloomPushListenerTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final BloomRecordedEvent EVENT = new BloomRecordedEvent(
            "60000000-0000-0000-0000-0000000000ff",
            USER_ID,
            "20000000-0000-0000-0000-0000000000bb",
            "로지",
            java.time.LocalDate.parse("2026-08-10"),
            true
    );

    @Mock
    private PushTargetResolver pushTargetResolver;

    @Mock
    private PushSender pushSender;

    @Mock
    private FcmTokenService fcmTokenService;

    private BloomPushListener bloomPushListener;

    @BeforeEach
    void setUp() {
        bloomPushListener = new BloomPushListener(
                pushTargetResolver,
                new BloomPushMessageFactory(),
                pushSender,
                fcmTokenService
        );
    }

    @Test
    void plantCareCategoryDecidesWhetherToSend() {
        // 개화는 별도 카테고리가 아니다. 케어 알림을 끈 사용자에게 개화만 따로 가면 안 된다.
        // 목 생성과 스터빙을 thenReturn 인자 안에서 하면 Mockito 가 미완성 스터빙으로 본다.
        List<FcmToken> targets = List.of(token("device-1", "token-1"));
        when(pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE))
                .thenReturn(targets);
        when(pushSender.send(anyList(), any())).thenReturn(PushSendResult.none());

        bloomPushListener.onBloomRecorded(EVENT);

        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender).send(anyList(), captor.capture());
        assertThat(captor.getValue().body()).contains("로지의");
        assertThat(captor.getValue().data()).containsEntry("type", "BLOOM");
    }

    @Test
    void noTargetMeansNoSend() {
        when(pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE))
                .thenReturn(List.of());

        bloomPushListener.onBloomRecorded(EVENT);

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

        bloomPushListener.onBloomRecorded(EVENT);

        verify(fcmTokenService).deactivate(List.of("device-2"));
    }

    @Test
    void sendFailureDoesNotEscapeToThePublisher() {
        // 예외가 올라가면 커밋 이후 콜백을 타고 기록 요청을 처리하던 스레드까지 깬다.
        // 기록은 이미 저장됐고 사용자는 목록에서 확인할 수 있다.
        List<FcmToken> targets = List.of(token("device-1", "token-1"));
        when(pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE))
                .thenReturn(targets);
        when(pushSender.send(anyList(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> bloomPushListener.onBloomRecorded(EVENT)).doesNotThrowAnyException();
    }

    private FcmToken token(String installationId, String value) {
        FcmToken token = mock(FcmToken.class);
        org.mockito.Mockito.lenient().when(token.getInstallationId()).thenReturn(installationId);
        org.mockito.Mockito.lenient().when(token.getToken()).thenReturn(value);
        return token;
    }
}
