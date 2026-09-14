package com.potner.command.application;

/**
 * 결과 회신 한 건을 반영한 결과다.
 *
 * <p>{@code SensorReadingSaveResult} 와 같은 방식이다. 어느 단계에서 멈췄는지가 로그로 남아야
 * MQTT 처럼 응답 없는 경로에서 원인을 찾을 수 있다.
 */
public enum CommandResultApplyOutcome {

    /** 반영됐다. */
    APPLIED,

    /** 같은 회신이 다시 왔다(QoS 1 재전송). 처음 것이 이미 반영됐다. */
    DUPLICATE,

    /** 회신의 requestId 에 해당하는 명령이 없다. 서버가 보낸 적 없는 명령이다. */
    UNKNOWN_REQUEST,

    /** 명령을 받은 장치와 회신한 장치가 다르다. 페이로드 위조이거나 장치 설정 오류다. */
    DEVICE_MISMATCH,

    /** 명령 종류와 회신 토픽의 종류가 다르다. */
    TYPE_MISMATCH,

    /** 이미 다른 회신이 반영된 명령이다. */
    ALREADY_COMPLETED,

    /** 서버가 모르는 status 값이다. 장치와 서버의 어휘가 어긋났다. */
    INVALID_STATUS
}
