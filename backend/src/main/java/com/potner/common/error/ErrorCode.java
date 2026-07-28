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
    INVALID_DEVICE_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid device token", "유효하지 않은 장치 토큰입니다."),
    INVALID_PHOTO(HttpStatus.BAD_REQUEST, "Invalid photo", "이미지 파일이 아닙니다."),
    PHOTO_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Photo too large", "허용된 크기를 넘는 이미지입니다."),
    PHOTO_ALREADY_EXISTS_FOR_DATE(HttpStatus.CONFLICT, "Photo already exists for date", "그 날짜의 사진이 이미 있습니다."),
    PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "Photo not found", "사진을 찾을 수 없습니다."),
    DIARY_NOT_FOUND(HttpStatus.NOT_FOUND, "Diary not found", "일기를 찾을 수 없습니다."),
    INVALID_SENSOR_QUERY_RANGE(HttpStatus.BAD_REQUEST, "Invalid sensor query range", "센서 조회 기간이 유효하지 않습니다."),
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
