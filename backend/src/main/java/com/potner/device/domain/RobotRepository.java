package com.potner.device.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RobotRepository extends JpaRepository<Robot, String> {

    /** created_at 은 매핑하지 않으므로 이름순으로 정렬해 목록 순서를 고정한다. */
    List<Robot> findAllByUserIdAndReleasedAtIsNullOrderByNameAsc(String userId);

    /**
     * 타인 로봇을 조작하지 못하도록 소유자와 함께 조회한다. 해제된 로봇도 제외한다 —
     * 넘긴 기기에 장치 등록·토큰 재발급·위치 수정이 되면 안 된다.
     */
    Optional<Robot> findByIdAndUserIdAndReleasedAtIsNull(String id, String userId);

    boolean existsByDeviceUidAndReleasedAtIsNull(String deviceUid);

    /** 장치 업로드의 유일한 인증 경로다. 원문이 아니라 해시로 찾는다. */
    Optional<Robot> findByUploadTokenHashAndReleasedAtIsNull(String uploadTokenHash);
}
