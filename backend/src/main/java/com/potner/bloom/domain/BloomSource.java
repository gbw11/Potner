package com.potner.bloom.domain;

/**
 * 개화 기록을 누가 남겼는지다.
 *
 * <p>구분이 없으면 같은 개화가 두 건으로 남았을 때 어느 쪽을 지워야 하는지 알 수 없다.
 */
public enum BloomSource {

    /** 사용자가 앱에서 직접 남겼다. */
    USER,

    /** 사진 생장 단계 판정이 개화를 감지해 남겼다. {@code BloomService#recordFromDevice} 가 만든다. */
    DEVICE
}
