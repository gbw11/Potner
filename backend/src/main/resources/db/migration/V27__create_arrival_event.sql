-- 앱의 임시 버튼과 이후 Android Geofence가 같은 귀가 이벤트 경로를 사용한다.
-- event_id는 앱이 발급하며 API 재시도와 MQTT QoS 1 재전송을 끝까지 대조하는 키다.
CREATE TABLE IF NOT EXISTS `arrival_event` (
    `event_id`          CHAR(36)     NOT NULL,
    `visit_id`          CHAR(36)     NOT NULL,
    `user_id`           CHAR(36)     NOT NULL,
    `robot_id`          CHAR(36)     NOT NULL,
    `device_uid`        VARCHAR(100) NOT NULL,
    `event_type`        VARCHAR(20)  NOT NULL COMMENT 'APPROACH, CANCEL',
    `source`            VARCHAR(30)  NOT NULL COMMENT 'DEBUG_BUTTON, ANDROID_GEOFENCE',
    `geofence_id`       VARCHAR(50)  NULL,
    `occurred_at`       DATETIME(6)  NOT NULL COMMENT '클라이언트 이벤트 발생 시각. UTC',
    `processing_status` VARCHAR(30)  NOT NULL COMMENT 'COMMAND_PUBLISHED, OK, ERROR, BUSY, TIMED_OUT',
    `result_message_id` VARCHAR(100) NULL,
    `error_message`     VARCHAR(200) NULL,
    `reported_at`       DATETIME(6)  NULL,
    `created_at`        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_arrival_event` PRIMARY KEY (`event_id`),
    CONSTRAINT `fk_arrival_event_robot`
        FOREIGN KEY (`robot_id`) REFERENCES `robot` (`robot_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_arrival_event_type`
        CHECK (`event_type` IN ('APPROACH', 'CANCEL')),
    CONSTRAINT `ck_arrival_event_source`
        CHECK (`source` IN ('DEBUG_BUTTON', 'ANDROID_GEOFENCE')),
    CONSTRAINT `ck_arrival_event_status`
        CHECK (`processing_status` IN (
            'COMMAND_PUBLISHED', 'OK', 'ERROR', 'BUSY', 'TIMED_OUT'
        )),

    INDEX `idx_arrival_event_user_visit` (`user_id`, `visit_id`),
    INDEX `idx_arrival_event_status_created` (`processing_status`, `created_at`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='귀가 접근·취소 이벤트와 Jetson 처리 결과';
