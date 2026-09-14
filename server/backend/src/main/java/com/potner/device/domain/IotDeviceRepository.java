package com.potner.device.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IotDeviceRepository extends JpaRepository<IotDevice, String> {

    Optional<IotDevice> findByDeviceUidAndReleasedAtIsNull(String deviceUid);

    /** 장치 종류 순으로 정렬해 앱이 카드 순서를 고정할 수 있게 한다. */
    List<IotDevice> findAllByRobotIdOrderByDeviceTypeAsc(String robotId);

    /**
     * device_uid 는 robot 과 iot_device 양쪽에서 <strong>활성 행끼리</strong> 유일하다
     * ({@code active_device_uid} 생성 컬럼 UNIQUE). MQTT 토픽 세그먼트로 쓰이므로 활성이
     * 겹치면 어느 장치의 측정값인지 가릴 수 없고, 해제된 행은 이력이라 겹쳐도 된다.
     */
    boolean existsByDeviceUidAndReleasedAtIsNull(String deviceUid);

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
