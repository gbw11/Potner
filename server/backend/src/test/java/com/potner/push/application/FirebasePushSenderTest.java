package com.potner.push.application;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import com.potner.push.domain.FcmToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirebasePushSenderTest {

    private static final PushMessage MESSAGE = new PushMessage("흙이 말랐어요", "로지의 흙이 말랐어요.", Map.of());

    @Mock
    private FirebaseMessaging messaging;

    private FirebasePushSender firebasePushSender;

    @BeforeEach
    void setUp() {
        firebasePushSender = new FirebasePushSender(messaging);
    }

    @ParameterizedTest
    @EnumSource(value = MessagingErrorCode.class, names = {"UNREGISTERED", "INVALID_ARGUMENT"})
    void permanentErrorsMarkTheTokenInvalid(MessagingErrorCode errorCode) {
        assertThat(FirebasePushSender.isPermanentlyInvalid(exception(errorCode))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = MessagingErrorCode.class,
            names = {"UNREGISTERED", "INVALID_ARGUMENT"},
            mode = EnumSource.Mode.EXCLUDE
    )
    void transientErrorsKeepTheToken(MessagingErrorCode errorCode) {
        // 브로커 장애 한 번에 멀쩡한 기기의 등록이 전부 꺼지면 안 된다.
        assertThat(FirebasePushSender.isPermanentlyInvalid(exception(errorCode))).isFalse();
    }

    @Test
    void onlyThePermanentlyInvalidDeviceIsReported() throws Exception {
        List<FcmToken> targets = List.of(token("device-1"), token("device-2"), token("device-3"));
        BatchResponse response = batchResponse(
                successResponse(),
                failureResponse(MessagingErrorCode.UNREGISTERED),
                failureResponse(MessagingErrorCode.UNAVAILABLE)
        );
        when(messaging.sendEach(anyList())).thenReturn(response);

        PushSendResult result = firebasePushSender.send(targets, MESSAGE);

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.invalidInstallationIds()).containsExactly("device-2");
    }

    @Test
    void aFailedRequestDeactivatesNothing() throws Exception {
        // 요청 자체가 실패하면 개별 토큰의 유효성은 알 수 없다.
        List<FcmToken> targets = List.of(token("device-1"));
        FirebaseMessagingException failure = mock(FirebaseMessagingException.class);
        when(messaging.sendEach(anyList())).thenThrow(failure);

        PushSendResult result = firebasePushSender.send(targets, MESSAGE);

        assertThat(result.successCount()).isZero();
        assertThat(result.invalidInstallationIds()).isEmpty();
    }

    @Test
    void moreThanFiveHundredTargetsAreSplitAcrossRequests() throws Exception {
        // FCM 이 한 요청에 500건까지만 받는다. 넘겨서 부르면 예외가 난다.
        List<FcmToken> targets = IntStream.range(0, 501)
                .mapToObj(index -> token("device-" + index))
                .toList();
        BatchResponse firstResponse = successBatchResponse(500);
        BatchResponse secondResponse = successBatchResponse(1);
        when(messaging.sendEach(anyList())).thenReturn(firstResponse, secondResponse);

        PushSendResult result = firebasePushSender.send(targets, MESSAGE);

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.captor();
        verify(messaging, times(2)).sendEach(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(500, 1);
        assertThat(result.successCount()).isEqualTo(501);
    }

    private FirebaseMessagingException exception(MessagingErrorCode errorCode) {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        org.mockito.Mockito.lenient().when(exception.getMessagingErrorCode()).thenReturn(errorCode);
        return exception;
    }

    /** 성공만 담긴 응답이다. 같은 mock 을 반복해 담는다. 개수만 의미가 있다. */
    private BatchResponse successBatchResponse(int count) {
        SendResponse success = successResponse();
        BatchResponse batchResponse = mock(BatchResponse.class);
        when(batchResponse.getResponses()).thenReturn(Collections.nCopies(count, success));
        when(batchResponse.getSuccessCount()).thenReturn(count);
        return batchResponse;
    }

    /**
     * mock 생성과 스터빙을 다른 스터빙의 인자 안에서 하면 Mockito 가 미완성 스터빙으로 본다.
     * 그래서 값을 먼저 만들어 두고 {@code when} 에는 완성된 것만 넘긴다.
     */
    private BatchResponse batchResponse(SendResponse... responses) {
        List<SendResponse> all = List.of(responses);
        int successCount = (int) all.stream().filter(SendResponse::isSuccessful).count();
        BatchResponse batchResponse = mock(BatchResponse.class);
        when(batchResponse.getResponses()).thenReturn(all);
        when(batchResponse.getSuccessCount()).thenReturn(successCount);
        return batchResponse;
    }

    private SendResponse successResponse() {
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(true);
        return response;
    }

    private SendResponse failureResponse(MessagingErrorCode errorCode) {
        FirebaseMessagingException failure = exception(errorCode);
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getException()).thenReturn(failure);
        return response;
    }

    private FcmToken token(String installationId) {
        FcmToken token = mock(FcmToken.class);
        org.mockito.Mockito.lenient().when(token.getInstallationId()).thenReturn(installationId);
        org.mockito.Mockito.lenient().when(token.getToken()).thenReturn("token-" + installationId);
        return token;
    }
}
