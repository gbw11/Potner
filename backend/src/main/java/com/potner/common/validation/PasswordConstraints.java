package com.potner.common.validation;

/**
 * 비밀번호 정책이다.
 *
 * <p>회원가입과 비밀번호 변경이 같은 규칙을 쓰도록 한 곳에 둔다.
 * {@code auth}와 {@code user} 양쪽에서 참조하므로 패키지 순환을 만들지 않는 자리에 둔다.
 */
public final class PasswordConstraints {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;

    /** 영문과 숫자를 각각 하나 이상 포함하고 공백이 없어야 한다. */
    public static final String PATTERN = "^(?=.*[A-Za-z])(?=.*\\d)\\S+$";

    public static final String LENGTH_MESSAGE = "비밀번호는 8자 이상 72자 이하여야 합니다.";
    public static final String PATTERN_MESSAGE = "비밀번호는 영문과 숫자를 포함하고 공백이 없어야 합니다.";

    private PasswordConstraints() {
    }
}
