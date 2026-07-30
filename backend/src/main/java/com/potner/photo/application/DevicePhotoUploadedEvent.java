package com.potner.photo.application;

/**
 * 장치가 올린 사진이 저장됐다는 사실을 알린다.
 *
 * <p>{@code BloomRecordedEvent} 와 같은 이유로 엔티티가 아니라 원시 값만 담는다. 수신자는
 * 트랜잭션이 커밋된 뒤 다른 스레드에서 동작하므로 영속성 컨텍스트가 이미 닫혀 있다.
 *
 * <p>이미지 바이트를 담지 않는다. 최대 10 MiB 라 비동기 큐에 그대로 실으면 대기 건수만큼 힙을
 * 차지한다. 수신자가 저장된 재생용 변형을 다시 읽는다.
 *
 * <p>사용자 업로드에는 발행하지 않는다. 대표 사진은 사용자가 고른 것이라 생장 단계를 판정할
 * 근거로 쓰기 어렵고, 하루 한 장 제약도 장치 사진에만 걸려 있다.
 */
public record DevicePhotoUploadedEvent(
        String photoId,
        String plantId
) {
}
