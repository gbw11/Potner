package com.potner.command.domain;

/**
 * 앱의 방향 버튼 하나가 뜻하는 방향이다.
 *
 * <p>부호만 갖는다. 크기(속도)는 {@code potner.drive.*} 가 정한다. 앱이 속도를 보내지 않는
 * 이유는 로봇이 안전하게 낼 수 있는 속도가 하드웨어의 사정이기 때문이다 — 앱에 숫자를 박아
 * 두면 로봇을 바꿀 때 앱을 다시 배포해야 하고, 조작하는 사람이 상한을 올릴 수도 있다.
 *
 * <p>부호는 ROS REP-103 을 따른다. 선속도 +x 가 앞, 각속도 +z 가 반시계(왼쪽)다. 젯슨이
 * {@code Twist} 로 그대로 옮길 수 있어야 하므로 여기서 규약을 어기면 로봇이 반대로 돈다.
 */
public enum DriveDirection {

    /** 전진. */
    FORWARD(1, 0),

    /** 후진. 앞과 달리 카메라가 없는 방향이므로 앱이 더 짧게 끊어 보낼 수 있다. */
    BACKWARD(-1, 0),

    /** 제자리 좌회전(반시계). */
    LEFT(0, 1),

    /** 제자리 우회전(시계). */
    RIGHT(0, -1),

    /**
     * 정지. 선·각속도가 모두 0 이고 지속 시간도 0 이다.
     *
     * <p>다른 방향에 시간 제한이 있어도 정지를 따로 두는 이유는, 버튼에서 손을 뗀 순간
     * 남은 시간을 기다리지 않고 즉시 세울 수 있어야 하기 때문이다.
     */
    STOP(0, 0);

    private final int linearSign;
    private final int angularSign;

    DriveDirection(int linearSign, int angularSign) {
        this.linearSign = linearSign;
        this.angularSign = angularSign;
    }

    public int linearSign() {
        return linearSign;
    }

    public int angularSign() {
        return angularSign;
    }

    public boolean isStop() {
        return this == STOP;
    }
}
