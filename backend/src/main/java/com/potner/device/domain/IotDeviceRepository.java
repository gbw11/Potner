package com.potner.device.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IotDeviceRepository extends JpaRepository<IotDevice, String> {

    Optional<IotDevice> findByDeviceUid(String deviceUid);
}
