CREATE TABLE IF NOT EXISTS `plant` (
    `plant_id`          CHAR(36)     NOT NULL,
    `user_id`           CHAR(36)     NOT NULL,
    `species_id`        CHAR(36)     NOT NULL,
    `life_stage_id`     CHAR(36)     NOT NULL,
    `nickname`          VARCHAR(50)  NOT NULL,
    `profile_image_url` VARCHAR(500) NULL,
    `personality`       JSON         NULL COMMENT 'LLM 일기 말투 등에 사용할 성격 정보',
    `plant_status`      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, ARCHIVED, DELETED',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`        DATETIME     NULL,
    CONSTRAINT `pk_plant` PRIMARY KEY (`plant_id`),
    CONSTRAINT `fk_plant_user`
        FOREIGN KEY (`user_id`) REFERENCES `app_user` (`user_id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_plant_species`
        FOREIGN KEY (`species_id`) REFERENCES `plant_species` (`species_id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_plant_life_stage`
        FOREIGN KEY (`life_stage_id`) REFERENCES `plant_life_stage` (`life_stage_id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_plant_status`
        CHECK (`plant_status` IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    INDEX `idx_plant_user_status` (`user_id`, `plant_status`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='사용자 반려식물';

CREATE TABLE IF NOT EXISTS `plant_growth_profile` (
    `plant_id`                       CHAR(36)       NOT NULL,
    `source_requirement_id`          CHAR(36)       NOT NULL COMMENT '복사 원본 기본 기준',
    `source_revision`                INT UNSIGNED   NOT NULL COMMENT '복사 당시 기본 기준 개정 번호',
    `soil_moisture_min_pct`          DECIMAL(5,2)   NOT NULL,
    `soil_moisture_max_pct`          DECIMAL(5,2)   NOT NULL,
    `temperature_min_c`              DECIMAL(5,2)   NOT NULL,
    `temperature_max_c`              DECIMAL(5,2)   NOT NULL,
    `humidity_min_pct`               DECIMAL(5,2)   NOT NULL,
    `humidity_max_pct`               DECIMAL(5,2)   NOT NULL,
    `illuminance_min_lux`            DECIMAL(12,2)  NULL,
    `illuminance_max_lux`            DECIMAL(12,2)  NULL,
    `illuminance_target_lux`         DECIMAL(12,2)  NOT NULL,
    `photoperiod_hours`              DECIMAL(4,2)   NOT NULL,
    `daily_light_min_lux_hour`       DECIMAL(14,2)  NULL,
    `daily_light_max_lux_hour`       DECIMAL(14,2)  NULL,
    `daily_light_target_lux_hour`    DECIMAL(14,2)  NOT NULL,
    `watering_cycle_days`            DECIMAL(5,2)   NULL,
    `watering_trigger_pct`           DECIMAL(5,2)   NULL,
    `recommended_watering_ml`        DECIMAL(10,2)  NULL,
    `custom_override`                TINYINT(1)      NOT NULL DEFAULT 0,
    `customized_at`                  DATETIME        NULL,
    `created_at`                     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_plant_growth_profile` PRIMARY KEY (`plant_id`),
    CONSTRAINT `fk_plant_growth_profile_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_plant_growth_profile_requirement`
        FOREIGN KEY (`source_requirement_id`) REFERENCES `species_growth_requirement` (`requirement_id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_plant_profile_soil_moisture`
        CHECK (`soil_moisture_min_pct` BETWEEN 0 AND 100
           AND `soil_moisture_max_pct` BETWEEN 0 AND 100
           AND `soil_moisture_min_pct` <= `soil_moisture_max_pct`),
    CONSTRAINT `ck_plant_profile_temperature`
        CHECK (`temperature_min_c` >= -30
           AND `temperature_max_c` <= 80
           AND `temperature_min_c` <= `temperature_max_c`),
    CONSTRAINT `ck_plant_profile_humidity`
        CHECK (`humidity_min_pct` BETWEEN 0 AND 100
           AND `humidity_max_pct` BETWEEN 0 AND 100
           AND `humidity_min_pct` <= `humidity_max_pct`),
    CONSTRAINT `ck_plant_profile_illuminance`
        CHECK (`illuminance_target_lux` >= 0 AND (
            (`illuminance_min_lux` IS NULL AND `illuminance_max_lux` IS NULL)
            OR (`illuminance_min_lux` IS NOT NULL AND `illuminance_max_lux` IS NOT NULL
                AND `illuminance_min_lux` >= 0
                AND `illuminance_min_lux` <= `illuminance_target_lux`
                AND `illuminance_target_lux` <= `illuminance_max_lux`))),
    CONSTRAINT `ck_plant_profile_photoperiod`
        CHECK (`photoperiod_hours` > 0 AND `photoperiod_hours` <= 24),
    CONSTRAINT `ck_plant_profile_daily_light`
        CHECK (`daily_light_target_lux_hour` >= 0 AND (
            (`daily_light_min_lux_hour` IS NULL AND `daily_light_max_lux_hour` IS NULL)
            OR (`daily_light_min_lux_hour` IS NOT NULL AND `daily_light_max_lux_hour` IS NOT NULL
                AND `daily_light_min_lux_hour` >= 0
                AND `daily_light_min_lux_hour` <= `daily_light_target_lux_hour`
                AND `daily_light_target_lux_hour` <= `daily_light_max_lux_hour`))),
    CONSTRAINT `ck_plant_profile_watering_cycle`
        CHECK (`watering_cycle_days` IS NULL OR `watering_cycle_days` > 0),
    CONSTRAINT `ck_plant_profile_watering_trigger`
        CHECK (`watering_trigger_pct` IS NULL OR `watering_trigger_pct` BETWEEN 0 AND 100),
    CONSTRAINT `ck_plant_profile_watering_amount`
        CHECK (`recommended_watering_ml` IS NULL OR `recommended_watering_ml` > 0)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='식물별 실제 적용 생육 기준. 사용자 수정값 포함';
