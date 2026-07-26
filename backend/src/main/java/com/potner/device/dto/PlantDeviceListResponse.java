package com.potner.device.dto;

import java.util.List;

/**
 * 식물에 연결된 장치 현황이다.
 * 아직 로봇이 배정되지 않았으면 {@code robot}은 비어 있고 {@code devices}도 빈 목록이다.
 */
public record PlantDeviceListResponse(
        String plantId,
        RobotSummaryResponse robot,
        List<IotDeviceResponse> devices
) {
    public PlantDeviceListResponse {
        devices = List.copyOf(devices);
    }

    public static PlantDeviceListResponse unassigned(String plantId) {
        return new PlantDeviceListResponse(plantId, null, List.of());
    }
}
