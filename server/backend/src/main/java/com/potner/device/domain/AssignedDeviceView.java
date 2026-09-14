package com.potner.device.domain;

/**
 * 활성 배정이 있는 식물과 그 로봇에 달린 특정 종류 장치의 식별자 쌍이다.
 *
 * <p>주기 작업이 명령을 보낼 대상을 한 번에 뽑을 때 쓴다. 식물마다 배정과 장치를 따로 조회하면
 * 로봇 수만큼 쿼리가 나간다.
 */
public interface AssignedDeviceView {

    String getPlantId();

    String getDeviceUid();
}
