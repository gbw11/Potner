CREATE TABLE IF NOT EXISTS `plant_category` (
    `category_id`        CHAR(36)         NOT NULL,
    `parent_category_id` CHAR(36)         NULL,
    `name`               VARCHAR(100)     NOT NULL,
    `level`              TINYINT UNSIGNED NOT NULL,
    `sort_order`         INT              NOT NULL DEFAULT 0,
    `active`             TINYINT(1)       NOT NULL DEFAULT 1,
    CONSTRAINT `pk_plant_category` PRIMARY KEY (`category_id`),
    CONSTRAINT `fk_plant_category_parent`
        FOREIGN KEY (`parent_category_id`) REFERENCES `plant_category` (`category_id`) ON DELETE SET NULL,
    INDEX `idx_plant_category_parent` (`parent_category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='식물 카테고리';

CREATE TABLE IF NOT EXISTS `plant_species` (
    `species_id`      CHAR(36)     NOT NULL,
    `category_id`     CHAR(36)     NOT NULL,
    `name`            VARCHAR(100) NOT NULL COMMENT '식물 종명',
    `scientific_name` VARCHAR(150) NULL,
    `description`     TEXT         NULL,
    `active`          TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_plant_species` PRIMARY KEY (`species_id`),
    CONSTRAINT `uq_plant_species_category_name` UNIQUE (`category_id`, `name`),
    CONSTRAINT `fk_plant_species_category`
        FOREIGN KEY (`category_id`) REFERENCES `plant_category` (`category_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='식물 종';

CREATE TABLE IF NOT EXISTS `plant_life_stage` (
    `life_stage_id` CHAR(36)    NOT NULL,
    `code`          VARCHAR(30) NOT NULL,
    `name`          VARCHAR(50) NOT NULL,
    `sort_order`    INT         NOT NULL DEFAULT 0,
    `active`        TINYINT(1)  NOT NULL DEFAULT 1,
    CONSTRAINT `pk_plant_life_stage` PRIMARY KEY (`life_stage_id`),
    CONSTRAINT `uq_plant_life_stage_code` UNIQUE (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='식물 생장 단계';

CREATE TABLE IF NOT EXISTS `species_growth_requirement` (
    `requirement_id`              CHAR(36)      NOT NULL,
    `species_id`                  CHAR(36)      NOT NULL,
    `life_stage_id`               CHAR(36)      NOT NULL,
    `soil_moisture_min_pct`       DECIMAL(5,2)  NOT NULL,
    `soil_moisture_max_pct`       DECIMAL(5,2)  NOT NULL,
    `temperature_min_c`           DECIMAL(5,2)  NOT NULL,
    `temperature_max_c`           DECIMAL(5,2)  NOT NULL,
    `humidity_min_pct`            DECIMAL(5,2)  NOT NULL,
    `humidity_max_pct`            DECIMAL(5,2)  NOT NULL,
    `illuminance_min_lux`         DECIMAL(12,2) NULL,
    `illuminance_max_lux`         DECIMAL(12,2) NULL,
    `illuminance_target_lux`      DECIMAL(12,2) NOT NULL,
    `photoperiod_hours`           DECIMAL(4,2)  NOT NULL,
    `daily_light_min_lux_hour`    DECIMAL(14,2) NULL,
    `daily_light_max_lux_hour`    DECIMAL(14,2) NULL,
    `daily_light_target_lux_hour` DECIMAL(14,2) NOT NULL,
    `watering_cycle_days`         DECIMAL(5,2)  NULL,
    `watering_trigger_pct`        DECIMAL(5,2)  NULL,
    `recommended_watering_ml`     DECIMAL(10,2) NULL,
    `revision`                    INT UNSIGNED   NOT NULL DEFAULT 1,
    `source_name`                 VARCHAR(255)   NULL,
    `source_url`                  VARCHAR(500)   NULL,
    `active`                      TINYINT(1)     NOT NULL DEFAULT 1,
    `created_at`                  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_species_growth_requirement` PRIMARY KEY (`requirement_id`),
    CONSTRAINT `uq_species_growth_requirement_species_stage` UNIQUE (`species_id`, `life_stage_id`),
    CONSTRAINT `fk_species_growth_requirement_species`
        FOREIGN KEY (`species_id`) REFERENCES `plant_species` (`species_id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_species_growth_requirement_stage`
        FOREIGN KEY (`life_stage_id`) REFERENCES `plant_life_stage` (`life_stage_id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_species_req_soil_moisture`
        CHECK (`soil_moisture_min_pct` BETWEEN 0 AND 100
           AND `soil_moisture_max_pct` BETWEEN 0 AND 100
           AND `soil_moisture_min_pct` <= `soil_moisture_max_pct`),
    CONSTRAINT `ck_species_req_temperature`
        CHECK (`temperature_min_c` >= -30
           AND `temperature_max_c` <= 80
           AND `temperature_min_c` <= `temperature_max_c`),
    CONSTRAINT `ck_species_req_humidity`
        CHECK (`humidity_min_pct` BETWEEN 0 AND 100
           AND `humidity_max_pct` BETWEEN 0 AND 100
           AND `humidity_min_pct` <= `humidity_max_pct`),
    CONSTRAINT `ck_species_req_illuminance`
        CHECK (`illuminance_target_lux` >= 0 AND (
            (`illuminance_min_lux` IS NULL AND `illuminance_max_lux` IS NULL)
            OR (`illuminance_min_lux` IS NOT NULL AND `illuminance_max_lux` IS NOT NULL
                AND `illuminance_min_lux` >= 0
                AND `illuminance_min_lux` <= `illuminance_target_lux`
                AND `illuminance_target_lux` <= `illuminance_max_lux`))),
    CONSTRAINT `ck_species_req_photoperiod`
        CHECK (`photoperiod_hours` > 0 AND `photoperiod_hours` <= 24),
    CONSTRAINT `ck_species_req_daily_light`
        CHECK (`daily_light_target_lux_hour` >= 0 AND (
            (`daily_light_min_lux_hour` IS NULL AND `daily_light_max_lux_hour` IS NULL)
            OR (`daily_light_min_lux_hour` IS NOT NULL AND `daily_light_max_lux_hour` IS NOT NULL
                AND `daily_light_min_lux_hour` >= 0
                AND `daily_light_min_lux_hour` <= `daily_light_target_lux_hour`
                AND `daily_light_target_lux_hour` <= `daily_light_max_lux_hour`))),
    CONSTRAINT `ck_species_req_watering_cycle`
        CHECK (`watering_cycle_days` IS NULL OR `watering_cycle_days` > 0),
    CONSTRAINT `ck_species_req_watering_trigger`
        CHECK (`watering_trigger_pct` IS NULL OR `watering_trigger_pct` BETWEEN 0 AND 100),
    CONSTRAINT `ck_species_req_watering_amount`
        CHECK (`recommended_watering_ml` IS NULL OR `recommended_watering_ml` > 0),
    CONSTRAINT `ck_species_req_revision` CHECK (`revision` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='식물 종 및 생장 단계별 기본 생육 기준';

INSERT INTO `plant_life_stage`
    (`life_stage_id`, `code`, `name`, `sort_order`, `active`)
VALUES
    ('10000000-0000-0000-0000-000000000005', 'GERMINATION', '발아기', 5, 1),
    ('10000000-0000-0000-0000-000000000001', 'SEEDLING', '유묘기', 10, 1),
    ('10000000-0000-0000-0000-000000000002', 'GROWTH', '성장기', 30, 1),
    ('10000000-0000-0000-0000-000000000006', 'VEGETATIVE', '영양생장기', 40, 1),
    ('10000000-0000-0000-0000-000000000008', 'REGROWTH', '재생장기', 45, 1),
    ('10000000-0000-0000-0000-000000000003', 'MATURE', '성숙기', 50, 1),
    ('10000000-0000-0000-0000-000000000007', 'BUD_FORMATION', '꽃눈형성기', 55, 1),
    ('10000000-0000-0000-0000-000000000004', 'FLOWERING', '개화기', 60, 1),
    ('10000000-0000-0000-0000-000000000009', 'FRUITING', '결실기', 70, 1)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`), `sort_order` = VALUES(`sort_order`), `active` = VALUES(`active`);

INSERT INTO `plant_category`
    (`category_id`, `parent_category_id`, `name`, `level`, `sort_order`, `active`)
VALUES
    ('20000000-0000-0000-0000-000000000001', NULL, 'Potner 지원 식물', 1, 10, 1),
    ('20000000-0000-0000-0000-000000000011', '20000000-0000-0000-0000-000000000001', '관상·화훼', 2, 10, 1),
    ('20000000-0000-0000-0000-000000000012', '20000000-0000-0000-0000-000000000001', '허브', 2, 20, 1),
    ('20000000-0000-0000-0000-000000000013', '20000000-0000-0000-0000-000000000001', '과채류', 2, 30, 1)
ON DUPLICATE KEY UPDATE
    `parent_category_id` = VALUES(`parent_category_id`), `name` = VALUES(`name`), `level` = VALUES(`level`),
    `sort_order` = VALUES(`sort_order`), `active` = VALUES(`active`);

INSERT INTO `plant_species`
    (`species_id`, `category_id`, `name`, `scientific_name`, `description`, `active`)
VALUES
    ('20000000-0000-0000-0000-000000000101', '20000000-0000-0000-0000-000000000011', '미니해바라기', 'Helianthus annuus L.', '해바라기의 왜성 원예 품종군으로, 비교적 작은 화분에서도 기를 수 있는 한해살이 관상식물입니다.', 1),
    ('20000000-0000-0000-0000-000000000102', '20000000-0000-0000-0000-000000000012', '배초향', 'Agastache rugosa (Fisch. & C.A.Mey.) Kuntze', '꿀풀과의 여러해살이 방향성 식물로, 잎과 꽃에서 특유의 향이 나며 허브와 관상용으로 재배합니다.', 1),
    ('20000000-0000-0000-0000-000000000103', '20000000-0000-0000-0000-000000000011', '칼란디바', 'Kalanchoe blossfeldiana Poelln.', '칼랑코에 블로스펠디아나의 겹꽃 원예 품종군으로, 다육질 잎과 오래 지속되는 꽃이 특징입니다.', 1),
    ('20000000-0000-0000-0000-000000000104', '20000000-0000-0000-0000-000000000012', '바질', 'Ocimum basilicum L.', '꿀풀과의 향기로운 한해살이 허브로, 잎을 식용과 향신료 용도로 널리 이용합니다.', 1),
    ('20000000-0000-0000-0000-000000000105', '20000000-0000-0000-0000-000000000011', '일일초', 'Catharanthus roseus (L.) G.Don', '마다가스카르 원산의 관상식물로, 따뜻하고 햇빛이 충분한 환경에서 꽃을 오래 피웁니다.', 1),
    ('20000000-0000-0000-0000-000000000106', '20000000-0000-0000-0000-000000000013', '방울토마토', 'Solanum lycopersicum L.', '작은 열매가 송이로 달리는 토마토 원예 품종군으로, 충분한 광량과 지지대가 필요한 식용 작물입니다.', 1)
ON DUPLICATE KEY UPDATE
    `category_id` = VALUES(`category_id`), `name` = VALUES(`name`),
    `scientific_name` = VALUES(`scientific_name`), `description` = VALUES(`description`),
    `active` = VALUES(`active`);

-- 아래 값은 potner_schema_v3_seeded.sql에 이미 반영된 온도·발아기 습도 보정 결과다.
INSERT INTO `species_growth_requirement` (
    `requirement_id`, `species_id`, `life_stage_id`,
    `soil_moisture_min_pct`, `soil_moisture_max_pct`,
    `temperature_min_c`, `temperature_max_c`, `humidity_min_pct`, `humidity_max_pct`,
    `illuminance_min_lux`, `illuminance_max_lux`, `illuminance_target_lux`,
    `photoperiod_hours`, `daily_light_min_lux_hour`, `daily_light_max_lux_hour`,
    `daily_light_target_lux_hour`, `watering_cycle_days`, `watering_trigger_pct`,
    `recommended_watering_ml`, `revision`, `source_name`, `source_url`, `active`
)
VALUES
    ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000101', '10000000-0000-0000-0000-000000000005', 40.00, 55.00, 19.00, 27.00, 60.00, 80.00, NULL, NULL, 12000.00, 15.00, NULL, NULL, 180000.00, 1.00, 40.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000101', '10000000-0000-0000-0000-000000000001', 35.00, 48.00, 15.00, 26.00, 60.00, 70.00, NULL, NULL, 21000.00, 15.00, NULL, NULL, 315000.00, 1.50, 33.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000101', '10000000-0000-0000-0000-000000000006', 30.00, 45.00, 15.00, 29.00, 50.00, 65.00, NULL, NULL, 33500.00, 14.00, NULL, NULL, 469000.00, 3.00, 30.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000101', '10000000-0000-0000-0000-000000000004', 35.00, 48.00, 14.00, 27.00, 45.00, 60.00, NULL, NULL, 40500.00, 13.00, NULL, NULL, 526500.00, 2.00, 33.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000102', '10000000-0000-0000-0000-000000000005', 40.00, 55.00, 18.00, 26.00, 60.00, 80.00, NULL, NULL, 10000.00, 15.00, NULL, NULL, 150000.00, 1.00, 40.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000102', '10000000-0000-0000-0000-000000000001', 35.00, 48.00, 14.00, 27.00, 60.00, 70.00, NULL, NULL, 17500.00, 15.00, NULL, NULL, 262500.00, 2.00, 33.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000102', '10000000-0000-0000-0000-000000000006', 25.00, 40.00, 14.00, 29.00, 45.00, 65.00, NULL, NULL, 29500.00, 14.00, NULL, NULL, 413000.00, 4.50, 25.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000102', '10000000-0000-0000-0000-000000000004', 22.00, 38.00, 14.00, 30.00, 45.00, 60.00, NULL, NULL, 37000.00, 13.50, NULL, NULL, 499500.00, 5.00, 23.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000009', '20000000-0000-0000-0000-000000000103', '10000000-0000-0000-0000-000000000001', 30.00, 45.00, 16.00, 23.00, 65.00, 80.00, NULL, NULL, 15000.00, 14.00, NULL, NULL, 210000.00, 3.00, 29.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000010', '20000000-0000-0000-0000-000000000103', '10000000-0000-0000-0000-000000000006', 25.00, 40.00, 16.00, 24.00, 50.00, 70.00, NULL, NULL, 24500.00, 16.00, NULL, NULL, 392000.00, 6.00, 24.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000011', '20000000-0000-0000-0000-000000000103', '10000000-0000-0000-0000-000000000007', 22.00, 35.00, 16.00, 22.00, 50.00, 65.00, NULL, NULL, 24500.00, 9.50, NULL, NULL, 232750.00, 6.00, 22.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000012', '20000000-0000-0000-0000-000000000103', '10000000-0000-0000-0000-000000000004', 20.00, 35.00, 12.00, 23.00, 45.00, 65.00, NULL, NULL, 20500.00, 11.00, NULL, NULL, 225500.00, 7.50, 21.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000013', '20000000-0000-0000-0000-000000000104', '10000000-0000-0000-0000-000000000005', 40.00, 55.00, 21.00, 29.00, 60.00, 80.00, NULL, NULL, 10000.00, 15.00, NULL, NULL, 150000.00, 1.00, 40.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000014', '20000000-0000-0000-0000-000000000104', '10000000-0000-0000-0000-000000000001', 35.00, 50.00, 16.00, 26.00, 60.00, 75.00, NULL, NULL, 16000.00, 15.00, NULL, NULL, 240000.00, 1.50, 34.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000015', '20000000-0000-0000-0000-000000000104', '10000000-0000-0000-0000-000000000006', 35.00, 50.00, 16.00, 30.00, 50.00, 70.00, NULL, NULL, 24500.00, 15.00, NULL, NULL, 367500.00, 2.00, 33.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000016', '20000000-0000-0000-0000-000000000104', '10000000-0000-0000-0000-000000000008', 38.00, 52.00, 16.00, 29.00, 55.00, 70.00, NULL, NULL, 27500.00, 15.00, NULL, NULL, 412500.00, 2.00, 35.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000017', '20000000-0000-0000-0000-000000000105', '10000000-0000-0000-0000-000000000005', 38.00, 52.00, 22.00, 29.00, 60.00, 80.00, NULL, NULL, 10000.00, 15.00, NULL, NULL, 150000.00, 1.00, 39.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000018', '20000000-0000-0000-0000-000000000105', '10000000-0000-0000-0000-000000000001', 30.00, 45.00, 18.00, 29.00, 60.00, 70.00, NULL, NULL, 17500.00, 15.00, NULL, NULL, 262500.00, 2.00, 29.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000019', '20000000-0000-0000-0000-000000000105', '10000000-0000-0000-0000-000000000006', 25.00, 40.00, 17.00, 31.00, 45.00, 65.00, NULL, NULL, 29500.00, 14.00, NULL, NULL, 413000.00, 3.50, 25.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000020', '20000000-0000-0000-0000-000000000105', '10000000-0000-0000-0000-000000000004', 25.00, 38.00, 16.00, 32.00, 45.00, 60.00, NULL, NULL, 35000.00, 13.50, NULL, NULL, 472500.00, 3.00, 24.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000021', '20000000-0000-0000-0000-000000000106', '10000000-0000-0000-0000-000000000005', 42.00, 55.00, 22.00, 31.00, 60.00, 80.00, NULL, NULL, 12000.00, 15.00, NULL, NULL, 180000.00, 1.00, 41.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000022', '20000000-0000-0000-0000-000000000106', '10000000-0000-0000-0000-000000000001', 35.00, 50.00, 16.00, 27.00, 60.00, 70.00, NULL, NULL, 18500.00, 17.00, NULL, NULL, 314500.00, 1.50, 34.00, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000023', '20000000-0000-0000-0000-000000000106', '10000000-0000-0000-0000-000000000006', 35.00, 50.00, 15.00, 28.00, 55.00, 70.00, NULL, NULL, 29500.00, 15.00, NULL, NULL, 442500.00, 1.50, 33.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000024', '20000000-0000-0000-0000-000000000106', '10000000-0000-0000-0000-000000000004', 38.00, 55.00, 15.00, 28.00, 60.00, 70.00, NULL, NULL, 39000.00, 15.00, NULL, NULL, 585000.00, 1.50, 36.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1),
    ('30000000-0000-0000-0000-000000000025', '20000000-0000-0000-0000-000000000106', '10000000-0000-0000-0000-000000000009', 38.00, 55.00, 15.00, 27.00, 55.00, 70.00, NULL, NULL, 35000.00, 14.00, NULL, NULL, 490000.00, 0.67, 36.50, NULL, 2, '식물_6종_생장단계별_권장환경.csv - 사용자 조정 규칙 적용', NULL, 1)
ON DUPLICATE KEY UPDATE
    `soil_moisture_min_pct` = VALUES(`soil_moisture_min_pct`),
    `soil_moisture_max_pct` = VALUES(`soil_moisture_max_pct`),
    `temperature_min_c` = VALUES(`temperature_min_c`),
    `temperature_max_c` = VALUES(`temperature_max_c`),
    `humidity_min_pct` = VALUES(`humidity_min_pct`),
    `humidity_max_pct` = VALUES(`humidity_max_pct`),
    `illuminance_min_lux` = VALUES(`illuminance_min_lux`),
    `illuminance_max_lux` = VALUES(`illuminance_max_lux`),
    `illuminance_target_lux` = VALUES(`illuminance_target_lux`),
    `photoperiod_hours` = VALUES(`photoperiod_hours`),
    `daily_light_min_lux_hour` = VALUES(`daily_light_min_lux_hour`),
    `daily_light_max_lux_hour` = VALUES(`daily_light_max_lux_hour`),
    `daily_light_target_lux_hour` = VALUES(`daily_light_target_lux_hour`),
    `watering_cycle_days` = VALUES(`watering_cycle_days`),
    `watering_trigger_pct` = VALUES(`watering_trigger_pct`),
    `recommended_watering_ml` = VALUES(`recommended_watering_ml`),
    `revision` = VALUES(`revision`),
    `source_name` = VALUES(`source_name`),
    `source_url` = VALUES(`source_url`),
    `active` = VALUES(`active`);
