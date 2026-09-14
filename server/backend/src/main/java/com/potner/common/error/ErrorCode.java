package com.potner.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email already exists", "이미 사용 중인 이메일입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found", "사용자를 찾을 수 없습니다."),
    PASSWORD_MISMATCH(HttpStatus.UNAUTHORIZED, "Password mismatch", "비밀번호가 일치하지 않습니다."),
    PASSWORD_UNCHANGED(HttpStatus.BAD_REQUEST, "Password unchanged", "새 비밀번호가 현재 비밀번호와 같습니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "Login failed", "이메일 또는 비밀번호를 확인해 주세요."),
    ACCOUNT_INACTIVE(HttpStatus.FORBIDDEN, "Inactive account", "사용할 수 없는 계정입니다."),
    ACCESS_TOKEN_REQUIRED(HttpStatus.UNAUTHORIZED, "Access token required", "Access Token이 필요합니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid access token", "유효하지 않은 Access Token입니다."),
    EXPIRED_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "Expired access token", "만료된 Access Token입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token", "유효하지 않은 Refresh Token입니다."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "Refresh token not found", "Refresh Token을 찾을 수 없습니다."),
    REFRESH_TOKEN_MISMATCH(HttpStatus.UNAUTHORIZED, "Refresh token mismatch", "Refresh Token이 일치하지 않습니다."),
    REVOKED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Revoked refresh token", "폐기된 Refresh Token입니다."),
    EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Expired refresh token", "만료된 Refresh Token입니다."),
    PLANT_SPECIES_NOT_FOUND(HttpStatus.NOT_FOUND, "Plant species not found", "식물 종을 찾을 수 없습니다."),
    PLANT_LIFE_STAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "Plant life stage not found", "생장 단계를 찾을 수 없습니다."),
    GROWTH_REQUIREMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Growth requirement not found", "해당 식물 종에서 지원하는 생장 단계가 아닙니다."),
    PLANT_NOT_FOUND(HttpStatus.NOT_FOUND, "Plant not found", "식물을 찾을 수 없습니다."),
    PLANT_GROWTH_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "Plant growth profile not found", "식물 생육 프로필을 찾을 수 없습니다."),
    INVALID_PLANT_NAME(HttpStatus.BAD_REQUEST, "Invalid plant name", "식물 이름이 유효하지 않습니다."),
    INVALID_GROWTH_PROFILE(HttpStatus.BAD_REQUEST, "Invalid growth profile", "생육 기준값이 유효하지 않습니다."),
    ALERT_NOT_FOUND(HttpStatus.NOT_FOUND, "Alert not found", "알림을 찾을 수 없습니다."),
    ROBOT_NOT_FOUND(HttpStatus.NOT_FOUND, "Robot not found", "로봇을 찾을 수 없습니다."),
    DEVICE_UID_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Device uid already registered", "이미 등록된 장치 식별자입니다."),
    PLANT_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "Plant already assigned", "이미 로봇이 배정된 식물입니다."),
    ROBOT_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "Robot already assigned", "이미 다른 식물에 배정된 로봇입니다."),
    PLANT_ASSIGNMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Plant assignment not found", "식물에 배정된 로봇이 없습니다."),
    COMMAND_DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "Command device not found", "명령을 받을 장치가 로봇에 등록되어 있지 않습니다."),
    NAVIGATE_DESTINATION_REQUIRED(HttpStatus.BAD_REQUEST, "Navigate destination required", "이동 명령에는 목적지가 필요합니다."),
    LOCATION_POSE_NOT_CONFIGURED(HttpStatus.BAD_REQUEST, "Location pose not configured", "목적지의 지도 좌표가 아직 입력되지 않았습니다."),
    DEVICE_COMMAND_ALREADY_PENDING(HttpStatus.CONFLICT, "Device command already pending", "같은 종류의 명령이 아직 수행 중입니다."),
    ARRIVAL_EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Arrival event not found", "귀가 이벤트를 찾을 수 없습니다."),
    ARRIVAL_VISIT_NOT_FOUND(HttpStatus.CONFLICT, "Arrival visit not found", "취소할 귀가 방문을 찾을 수 없습니다."),
    ARRIVAL_MULTIPLE_ROBOTS(HttpStatus.CONFLICT, "Multiple arrival robots", "귀가 로봇이 한 대보다 많아 자동으로 선택할 수 없습니다."),
    STALE_EVENT(HttpStatus.BAD_REQUEST, "Stale event", "유효시간이 지난 귀가 이벤트입니다."),
    INVALID_EVENT_TIME(HttpStatus.BAD_REQUEST, "Invalid event time", "귀가 이벤트 시각이 현재보다 지나치게 미래입니다."),
    WATERING_AMOUNT_NOT_CONFIGURED(HttpStatus.BAD_REQUEST, "Watering amount not configured", "케어 설정에 급수량이 없습니다. 물의 양을 먼저 설정해 주세요."),
    CARE_RUN_DISABLED(HttpStatus.CONFLICT, "Care run disabled", "이 자동 케어가 꺼져 있어 시작할 수 없습니다."),
    CARE_RUN_ROBOT_BUSY(HttpStatus.CONFLICT, "Care run robot busy", "로봇이 다른 작업을 수행 중입니다. 끝난 뒤에 다시 시도해 주세요."),
    INVALID_DEVICE_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid device token", "유효하지 않은 장치 토큰입니다."),
    INVALID_PHOTO(HttpStatus.BAD_REQUEST, "Invalid photo", "이미지 파일이 아닙니다."),
    PHOTO_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Photo too large", "허용된 크기를 넘는 이미지입니다."),
    PHOTO_ALREADY_EXISTS_FOR_DATE(HttpStatus.CONFLICT, "Photo already exists for date", "그 날짜의 사진이 이미 있습니다."),
    PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "Photo not found", "사진을 찾을 수 없습니다."),
    DIARY_NOT_FOUND(HttpStatus.NOT_FOUND, "Diary not found", "일기를 찾을 수 없습니다."),
    BLOOM_NOT_FOUND(HttpStatus.NOT_FOUND, "Bloom not found", "개화 기록을 찾을 수 없습니다."),
    ROBOT_LOCATION_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Robot location already registered", "이미 등록된 종류의 위치입니다."),
    ROBOT_LOCATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Robot location not found", "등록되지 않은 위치입니다."),
    STATION_CODE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Station code already registered", "이미 등록된 스테이션 코드입니다."),
    INVALID_STATION_CODE(HttpStatus.BAD_REQUEST, "Invalid station code", "스테이션 코드는 급수 스테이션에만, 급수 스테이션에는 반드시 있어야 합니다."),
    INVALID_SENSOR_QUERY_RANGE(HttpStatus.BAD_REQUEST, "Invalid sensor query range", "센서 조회 기간이 유효하지 않습니다."),
    // 사람이 버튼을 눌러 부른 발송에만 쓴다. 이벤트로 나가는 알림은 대상이 없으면 조용히
    // 넘어가는 것이 맞다 - 사용자가 껐다는 뜻이지 요청이 실패한 것은 아니다.
    PUSH_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "Push target not found", "알림을 받을 기기가 없습니다. 알림 수신 설정과 기기 등록을 확인해 주세요."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request", "요청값이 유효하지 않습니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied", "접근 권한이 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String title;
    private final String detail;

    ErrorCode(HttpStatus status, String title, String detail) {
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }
}
