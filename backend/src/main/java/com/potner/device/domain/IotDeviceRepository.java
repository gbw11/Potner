package com.potner.device.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IotDeviceRepository extends JpaRepository<IotDevice, String> {

    Optional<IotDevice> findByDeviceUid(String deviceUid);

    /** 장치 종류 순으로 정렬해 앱이 카드 순서를 고정할 수 있게 한다. */
    List<IotDevice> findAllByRobotIdOrderByDeviceTypeAsc(String robotId);

    /**
     * device_uid 는 robot 과 iot_device 양쪽에서 전역 UNIQUE 다. MQTT 토픽 세그먼트로 쓰이므로
     * 두 테이블을 통틀어 겹치면 어느 장치의 측정값인지 가릴 수 없다.
     */
    boolean existsByDeviceUid(String deviceUid);

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
