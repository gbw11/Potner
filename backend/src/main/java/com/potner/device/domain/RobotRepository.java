package com.potner.device.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RobotRepository extends JpaRepository<Robot, String> {

    Optional<Robot> findByDeviceUid(String deviceUid);
}
