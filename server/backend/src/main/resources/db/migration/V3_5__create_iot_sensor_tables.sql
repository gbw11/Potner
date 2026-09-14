CREATE TABLE IF NOT EXISTS `robot` (
    `robot_id`           CHAR(36)          NOT NULL,
    `user_id`            CHAR(36)          NOT NULL,
    `device_uid`         VARCHAR(100)      NOT NULL COMMENT 'Raspberry Pi/로봇 고유 식별자',
    `name`               VARCHAR(50)       NOT NULL,
    `connection_status`  VARCHAR(20)       NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE, OFFLINE, ERROR',
    `battery_percent`    TINYINT UNSIGNED  NULL,
    `last_seen_at`       DATETIME          NULL,
    `firmware_version`   VARCHAR(50)       NULL,
    `created_at`         DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_robot` PRIMARY KEY (`robot_id`),
    CONSTRAINT `uq_robot_device_uid` UNIQUE (`device_uid`),
    CONSTRAINT `fk_robot_user`
        FOREIGN KEY (`user_id`) REFERENCES `app_user` (`user_id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_robot_connection_status`
        CHECK (`connection_status` IN ('ONLINE', 'OFFLINE', 'ERROR')),
    CONSTRAINT `ck_robot_battery_percent`
        CHECK (`battery_percent` IS NULL OR `battery_percent` BETWEEN 0 AND 100),
    INDEX `idx_robot_user_status` (`user_id`, `connection_status`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Raspberry Pi 및 이동 로봇 장치';

CREATE TABLE IF NOT EXISTS `plant_device_assignment` (
    `assignment_id`  CHAR(36)  NOT NULL,
    `plant_id`       CHAR(36)  NOT NULL,
    `robot_id`       CHAR(36)  NOT NULL,
    `assigned_at`    DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `unassigned_at`  DATETIME  NULL,
    CONSTRAINT `pk_plant_device_assignment` PRIMARY KEY (`assignment_id`),
    CONSTRAINT `uq_plant_device_assignment_plant` UNIQUE (`plant_id`),
    CONSTRAINT `uq_plant_device_assignment_robot` UNIQUE (`robot_id`),
    CONSTRAINT `fk_plant_device_assignment_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_plant_device_assignment_robot`
        FOREIGN KEY (`robot_id`) REFERENCES `robot` (`robot_id`) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='식물과 담당 Raspberry Pi/로봇의 1:1 연결';

CREATE TABLE IF NOT EXISTS `sensor_reading` (
    `reading_id`         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    `plant_id`           CHAR(36)         NOT NULL,
    `robot_id`           CHAR(36)         NOT NULL,
    `device_message_id`  VARCHAR(100)     NULL COMMENT '장치 재전송 중복 방지 ID',
    `sensor_type`        VARCHAR(30)      NOT NULL COMMENT 'SOIL_MOISTURE, TEMPERATURE, HUMIDITY, ILLUMINANCE',
    `measured_value`     DECIMAL(14,4)    NOT NULL,
    `unit`               VARCHAR(20)      NOT NULL COMMENT 'PERCENT, CELSIUS, LUX',
    `quality`            VARCHAR(20)      NOT NULL DEFAULT 'GOOD' COMMENT 'GOOD, SUSPECT, BAD',
    `measured_at`        DATETIME         NOT NULL COMMENT '장치 측정 시각',
    `received_at`        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '서버 수신 시각',
    CONSTRAINT `pk_sensor_reading` PRIMARY KEY (`reading_id`),
    CONSTRAINT `uq_sensor_reading_device_message` UNIQUE (`device_message_id`),
    CONSTRAINT `fk_sensor_reading_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_sensor_reading_robot`
        FOREIGN KEY (`robot_id`) REFERENCES `robot` (`robot_id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_sensor_reading_type`
        CHECK (`sensor_type` IN ('SOIL_MOISTURE', 'TEMPERATURE', 'HUMIDITY', 'ILLUMINANCE')),
    CONSTRAINT `ck_sensor_reading_quality`
        CHECK (`quality` IN ('GOOD', 'SUSPECT', 'BAD')),
    CONSTRAINT `ck_sensor_reading_value`
        CHECK ((`sensor_type` NOT IN ('SOIL_MOISTURE', 'HUMIDITY')
                    OR `measured_value` BETWEEN 0 AND 100)
           AND (`sensor_type` <> 'ILLUMINANCE' OR `measured_value` >= 0)),
    INDEX `idx_sensor_reading_plant_type_time` (`plant_id`, `sensor_type`, `measured_at`),
    INDEX `idx_sensor_reading_robot_time` (`robot_id`, `measured_at`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='식물 센서 원본 측정값';
