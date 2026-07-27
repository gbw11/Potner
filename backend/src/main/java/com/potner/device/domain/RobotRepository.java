package com.potner.device.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RobotRepository extends JpaRepository<Robot, String> {

    /** created_at 은 매핑하지 않으므로 이름순으로 정렬해 목록 순서를 고정한다. */
    List<Robot> findAllByUserIdOrderByNameAsc(String userId);

    /** 타인 로봇을 조작하지 못하도록 소유자와 함께 조회한다. */
    Optional<Robot> findByIdAndUserId(String id, String userId);

    boolean existsByDeviceUid(String deviceUid);
}
