package com.potner.command.domain;

/**
 * 명령을 누가 시작했는지다.
 *
 * <p>자동 급수 체인은 {@code AUTO} 명령의 회신에만 다음 단계를 잇는다. 사용자가 직접 누른
 * 명령의 회신에 서버가 멋대로 후속 명령을 붙이면, 사용자는 시키지 않은 동작을 보게 된다.
 */
public enum CommandInitiator {

    /** 앱에서 사용자가 발행했다. */
    USER,

    /** 서버 자동 케어가 발행했다. */
    AUTO
}
