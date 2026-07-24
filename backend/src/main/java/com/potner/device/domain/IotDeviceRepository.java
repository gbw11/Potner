package com.potner.device.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IotDeviceRepository extends JpaRepository<IotDevice, String> {

    Optional<IotDevice> findByDeviceUid(String deviceUid);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE IotDevice device
               SET device.connectionStatus = :offlineStatus
             WHERE device.connectionStatus = :onlineStatus
               AND device.lastSeenAt < :cutoff
            """)
    int markOnlineDevicesOfflineBefore(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("onlineStatus") IotDeviceConnectionStatus onlineStatus,
            @Param("offlineStatus") IotDeviceConnectionStatus offlineStatus
    );
}
