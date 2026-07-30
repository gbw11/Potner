package com.potner.location.domain;

/**
 * 로봇이 오가는 위치의 종류다.
 *
 * <p>넷 중 물리 장치는 {@link #WATER_STATION} 뿐이라 그것만 스테이션 코드를 갖는다. 나머지는
 * SLAM 지도 위의 좌표일 뿐이다. 이 구분이 무너지면 앱 등록 화면이 그늘 자리에도 코드를
 * 요구하게 된다.
 *
 * <p>대기 장소가 충전 독과 같은 자리인지는 아직 정해지지 않았다. 갈라지면 CHARGING 을
 * 추가한다 — enum 과 DB CHECK 를 함께 고쳐야 한다.
 */
public enum RobotLocationType {

    /** 급수 스테이션. 물리 장치라 코드가 있고, 물 부족 보고가 여기로 귀속된다. */
    WATER_STATION,

    /** 대기 장소. 햇빛이 들지 않는 자리로, 임무가 없을 때 돌아와 있는 곳이다. */
    HOME,

    /** 햇빛 자리. 누적 광량이 모자라면 여기로 보낸다. */
    SUNLIGHT,

    /** 마중 지점. 사용자를 맞으러 나가는 자리다. 트리거는 아직 기획 중이다. */
    GREETING;

    /** 물리 스테이션인지. 코드 요구 여부가 여기서 갈린다. */
    public boolean isPhysicalStation() {
        return this == WATER_STATION;
    }
}
