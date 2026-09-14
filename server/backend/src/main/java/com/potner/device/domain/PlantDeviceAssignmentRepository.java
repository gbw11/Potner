package com.potner.device.domain;

import com.potner.plant.domain.PlantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlantDeviceAssignmentRepository extends JpaRepository<PlantDeviceAssignment, String> {

    Optional<PlantDeviceAssignment> findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(
            String robotId
    );

    Optional<PlantDeviceAssignment> findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(
            String plantId
    );

    /**
     * 명령을 보낼 대상을 한 번에 뽑는다. 식물마다 배정과 장치를 따로 조회하면 로봇 수만큼
     * 쿼리가 나간다.
     *
     * <p>연관을 걸지 않은 엔티티끼리 식별자로 조인한다. {@code plant_device_assignment} 는
     * 식물과 로봇을 원시 식별자로만 들고 있다.
     *
     * <p>{@code d.robot.id} 다. {@code IotDevice} 는 {@code robotId} 필드가 아니라
     * {@code @ManyToOne Robot} 을 들고 있다. 파생 쿼리
     * ({@code findAllByRobotId...})는 Spring Data 가 이 경로로 풀어 주지만 직접 쓴 JPQL 에는
     * 그 변환이 없어서 {@code d.robotId} 로 쓰면 컨텍스트가 시작될 때 실패한다.
     *
     * <p>삭제된 식물을 걸러낸다. 식물 삭제는 소프트 삭제인데 {@code plant_device_assignment} 의
     * 참조가 RESTRICT 라 배정이 남아 있을 수 있다. 걸러내지 않으면 지워진 식물의 로봇에
     * 명령이 계속 나간다.
     */
    @Query("""
            select a.plantId as plantId, d.deviceUid as deviceUid
            from PlantDeviceAssignment a, IotDevice d, Plant p
            where a.robotId = d.robot.id
              and a.plantId = p.id
              and a.unassignedAt is null
              and p.status <> :excludedStatus
              and d.deviceType = :deviceType
            """)
    List<AssignedDeviceView> findActiveAssignedDevices(
            @Param("deviceType") IotDeviceType deviceType,
            @Param("excludedStatus") PlantStatus excludedStatus
    );
}
