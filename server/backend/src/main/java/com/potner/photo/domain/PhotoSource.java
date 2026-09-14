package com.potner.photo.domain;

/**
 * 사진이 어디서 왔는지다.
 *
 * <p>포토 로그와 타임랩스는 {@link #DEVICE} 만 보여준다. 사용자가 올린 사진은 촬영 간격이
 * 일정하지 않아 타임랩스 프레임이 될 수 없고, 하루 한 장 제한도 그래서 장치에만 적용한다.
 *
 * <p>{@code source_robot_id} 가 비었는지로 판별하지 않는 이유는 그 외래키가
 * {@code ON DELETE SET NULL} 이기 때문이다. 로봇을 지우면 장치 사진이 사용자 사진으로 보인다.
 */
public enum PhotoSource {

    /** 라즈베리가 하루 한 장 촬영해 업로드한 성장 사진. */
    DEVICE,

    /** 사용자가 앱에서 올린 사진. 현재는 대표 사진 용도뿐이다. */
    USER
}
