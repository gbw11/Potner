package com.potner.location.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RobotLocationRepository extends JpaRepository<RobotLocation, String> {

    List<RobotLocation> findAllByRobotIdOrderByLocationTypeAsc(String robotId);

    Optional<RobotLocation> findByRobotIdAndLocationType(String robotId, RobotLocationType type);

    boolean existsByRobotIdAndLocationType(String robotId, RobotLocationType type);

    /** 코드는 전역 UNIQUE 다. 한 번 등록된 코드는 다른 사용자가 쓸 수 없다. */
    boolean existsByStationCode(String stationCode);
}
