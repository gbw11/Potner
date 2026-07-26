CREATE TABLE IF NOT EXISTS `plant_daily_light` (
    `daily_light_id`           CHAR(36)      NOT NULL,
    `plant_id`                 CHAR(36)      NOT NULL,
    `light_date`               DATE          NOT NULL COMMENT '서비스 타임존 기준 날짜',
    `accumulated_lux_hour`     DECIMAL(14,2) NOT NULL COMMENT '하루 누적 광량',
    `light_hours`              DECIMAL(5,2)  NOT NULL COMMENT '임계 조도 이상을 받은 실측 시간',
    `coverage_pct`             DECIMAL(5,2)  NOT NULL COMMENT '집계 구간이 하루를 덮은 비율',
    `sample_count`             INT UNSIGNED  NOT NULL,
    `target_lux_hour`          DECIMAL(14,2) NOT NULL COMMENT '판정 당시 목표 광량 스냅샷',
    `target_photoperiod_hours` DECIMAL(4,2)  NOT NULL COMMENT '판정 당시 목표 일조 시간 스냅샷',
    `threshold_min_lux_hour`   DECIMAL(14,2) NULL COMMENT '기준이 비어 있으면 광량 판정을 하지 않는다',
    `threshold_max_lux_hour`   DECIMAL(14,2) NULL,
    `light_status`             VARCHAR(20)   NOT NULL
        COMMENT 'LOW, NORMAL, HIGH, INSUFFICIENT_DATA, NOT_APPLICABLE',
    `photoperiod_status`       VARCHAR(20)   NOT NULL
        COMMENT 'LOW, NORMAL, HIGH, INSUFFICIENT_DATA, NOT_APPLICABLE',
    `computed_at`              DATETIME      NOT NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT `pk_plant_daily_light` PRIMARY KEY (`daily_light_id`),
    CONSTRAINT `uq_plant_daily_light_date` UNIQUE (`plant_id`, `light_date`),
    CONSTRAINT `fk_plant_daily_light_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_plant_daily_light_light_status`
        CHECK (`light_status` IN (
            'LOW', 'NORMAL', 'HIGH', 'INSUFFICIENT_DATA', 'NOT_APPLICABLE')),
    CONSTRAINT `ck_plant_daily_light_photoperiod_status`
        CHECK (`photoperiod_status` IN (
            'LOW', 'NORMAL', 'HIGH', 'INSUFFICIENT_DATA', 'NOT_APPLICABLE')),
    CONSTRAINT `ck_plant_daily_light_coverage`
        CHECK (`coverage_pct` BETWEEN 0 AND 100),
    CONSTRAINT `ck_plant_daily_light_threshold_order`
        CHECK ((`threshold_min_lux_hour` IS NULL AND `threshold_max_lux_hour` IS NULL)
            OR (`threshold_min_lux_hour` IS NOT NULL AND `threshold_max_lux_hour` IS NOT NULL
                AND `threshold_min_lux_hour` <= `threshold_max_lux_hour`)),
    INDEX `idx_plant_daily_light_date` (`light_date`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='식물별 일일 누적 광량 및 일조 시간 판정 결과';

-- 시드 기준정보는 목표 광량만 채워져 있고 허용 범위가 비어 있어 판정을 할 수 없었다.
-- 목표값 대비 비율로 출발점을 만든다. 종·단계별 조정이 필요해지면 이 값을 갱신하면 된다.
-- revision은 올리지 않는다. 이미 만들어진 프로필도 아래에서 같이 채워 값이 어긋나지 않는다.
UPDATE `species_growth_requirement`
SET `daily_light_min_lux_hour` = ROUND(`daily_light_target_lux_hour` * 0.70, 2),
    `daily_light_max_lux_hour` = ROUND(`daily_light_target_lux_hour` * 1.30, 2)
WHERE `daily_light_min_lux_hour` IS NULL
  AND `daily_light_max_lux_hour` IS NULL;

UPDATE `plant_growth_profile`
SET `daily_light_min_lux_hour` = ROUND(`daily_light_target_lux_hour` * 0.70, 2),
    `daily_light_max_lux_hour` = ROUND(`daily_light_target_lux_hour` * 1.30, 2)
WHERE `daily_light_min_lux_hour` IS NULL
  AND `daily_light_max_lux_hour` IS NULL;

-- 순간 조도(illuminance_min/max_lux)는 의도적으로 비워 둔다.
-- 밤에는 0 lux가 정상이므로 순간값으로는 판정하지 않는다.
