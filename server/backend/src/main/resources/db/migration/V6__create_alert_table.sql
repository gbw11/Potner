CREATE TABLE IF NOT EXISTS `alert` (
    `alert_id`       CHAR(36)      NOT NULL,
    `user_id`        CHAR(36)      NOT NULL COMMENT '알림 목록 조회를 단일 테이블로 처리하기 위한 비정규화',
    `plant_id`       CHAR(36)      NOT NULL,
    `metric_type`    VARCHAR(30)   NOT NULL
        COMMENT 'TEMPERATURE, HUMIDITY, SOIL_MOISTURE, DAILY_LIGHT, PHOTOPERIOD',
    `deviation`      VARCHAR(10)   NOT NULL COMMENT 'LOW, HIGH. 기준을 벗어난 방향',
    `measured_value` DECIMAL(14,4) NOT NULL COMMENT '판정 근거가 된 최근 측정값의 중앙값',
    `threshold_min`  DECIMAL(14,4) NOT NULL COMMENT '판정 당시 적용 기준 하한',
    `threshold_max`  DECIMAL(14,4) NOT NULL COMMENT '판정 당시 적용 기준 상한',
    `occurred_at`    DATETIME      NOT NULL COMMENT '판정 근거가 된 최신 측정 시각',
    `resolved_at`    DATETIME      NULL COMMENT '정상 복귀 시각. NULL이면 활성',
    `read_at`        DATETIME      NULL COMMENT '사용자 확인 시각',
    `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- MySQL은 부분 UNIQUE 인덱스를 지원하지 않는다.
    -- 해제되면 NULL이 되어 UNIQUE 대상에서 빠지므로 식물·지표별로 활성 Alert가 최대 1건만 존재한다.
    `active_key`     VARCHAR(80)   GENERATED ALWAYS AS (
        IF(`resolved_at` IS NULL, CONCAT(`plant_id`, ':', `metric_type`), NULL)
    ) STORED COMMENT '활성 Alert 유일성 보장용 파생 키',

    CONSTRAINT `pk_alert` PRIMARY KEY (`alert_id`),
    CONSTRAINT `uq_alert_active` UNIQUE (`active_key`),
    CONSTRAINT `fk_alert_user`
        FOREIGN KEY (`user_id`) REFERENCES `app_user` (`user_id`) ON DELETE CASCADE,
    -- MySQL은 STORED 생성 컬럼의 기반 컬럼에 CASCADE 참조 동작을 허용하지 않는다.
    -- plant_id는 active_key의 기반 컬럼이므로 RESTRICT를 쓴다.
    -- 식물 삭제는 plant_status를 DELETED로 바꾸는 소프트 삭제라 실제 동작 차이는 없다.
    CONSTRAINT `fk_alert_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_alert_metric_type`
        CHECK (`metric_type` IN (
            'TEMPERATURE', 'HUMIDITY', 'SOIL_MOISTURE', 'DAILY_LIGHT', 'PHOTOPERIOD')),
    CONSTRAINT `ck_alert_deviation`
        CHECK (`deviation` IN ('LOW', 'HIGH')),
    CONSTRAINT `ck_alert_threshold_order`
        CHECK (`threshold_min` <= `threshold_max`),
    INDEX `idx_alert_user_created` (`user_id`, `created_at`),
    INDEX `idx_alert_plant_metric` (`plant_id`, `metric_type`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='센서 이상 상태 알림';
