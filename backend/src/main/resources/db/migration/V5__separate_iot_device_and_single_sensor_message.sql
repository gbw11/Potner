CREATE TABLE IF NOT EXISTS `iot_device` (
    `device_id`           CHAR(36)      NOT NULL,
    `robot_id`            CHAR(36)      NOT NULL,
    `device_uid`          VARCHAR(100)  NOT NULL COMMENT 'MQTT topic/payload deviceId',
    `device_type`         VARCHAR(30)   NOT NULL COMMENT 'RASPBERRY_PI, JETSON_ORIN',
    `connection_status`   VARCHAR(20)   NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE, OFFLINE, ERROR',
    `last_seen_at`        DATETIME      NULL,
    `created_at`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_iot_device` PRIMARY KEY (`device_id`),
    CONSTRAINT `uq_iot_device_device_uid` UNIQUE (`device_uid`),
    CONSTRAINT `fk_iot_device_robot`
        FOREIGN KEY (`robot_id`) REFERENCES `robot` (`robot_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_iot_device_type`
        CHECK (`device_type` IN ('RASPBERRY_PI', 'JETSON_ORIN')),
    CONSTRAINT `ck_iot_device_connection_status`
        CHECK (`connection_status` IN ('ONLINE', 'OFFLINE', 'ERROR')),
    INDEX `idx_iot_device_robot_type` (`robot_id`, `device_type`),
    INDEX `idx_iot_device_status_seen` (`connection_status`, `last_seen_at`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Potner Robot 내부 MQTT 통신 장치';

INSERT IGNORE INTO `iot_device` (
    `device_id`,
    `robot_id`,
    `device_uid`,
    `device_type`,
    `connection_status`,
    `last_seen_at`,
    `created_at`,
    `updated_at`
)
SELECT
    UUID(),
    `robot_id`,
    `device_uid`,
    'RASPBERRY_PI',
    `connection_status`,
    `last_seen_at`,
    `created_at`,
    `updated_at`
FROM `robot`;

ALTER TABLE `sensor_reading`
    ADD COLUMN `source_device_id` CHAR(36) NULL AFTER `robot_id`;

UPDATE `sensor_reading` AS `sr`
JOIN `iot_device` AS `device`
  ON `device`.`robot_id` = `sr`.`robot_id`
 AND `device`.`device_type` = 'RASPBERRY_PI'
SET `sr`.`source_device_id` = `device`.`device_id`
WHERE `sr`.`source_device_id` IS NULL;

UPDATE `sensor_reading`
SET `device_message_id` = CONCAT(
    'legacy-',
    SHA2(CONCAT('null:', `reading_id`), 256)
)
WHERE `device_message_id` IS NULL;

UPDATE `sensor_reading` AS `sr`
JOIN (
    SELECT
        `reading_id`,
        ROW_NUMBER() OVER (
            PARTITION BY `device_message_id`
            ORDER BY `reading_id`
        ) AS `duplicate_order`
    FROM `sensor_reading`
) AS `ranked`
  ON `ranked`.`reading_id` = `sr`.`reading_id`
SET `sr`.`device_message_id` = CONCAT(
    'legacy-',
    SHA2(
        CONCAT(
            `sr`.`device_message_id`,
            ':',
            `sr`.`sensor_type`,
            ':',
            `sr`.`reading_id`
        ),
        256
    )
)
WHERE `ranked`.`duplicate_order` > 1;

ALTER TABLE `sensor_reading`
    MODIFY COLUMN `device_message_id` VARCHAR(100) NOT NULL,
    MODIFY COLUMN `source_device_id` CHAR(36) NOT NULL,
    ADD CONSTRAINT `fk_sensor_reading_source_device`
        FOREIGN KEY (`source_device_id`) REFERENCES `iot_device` (`device_id`) ON DELETE RESTRICT,
    ADD INDEX `idx_sensor_reading_source_device_time` (`source_device_id`, `measured_at`);

SET @message_type_unique_index = (
    SELECT `index_name`
    FROM `information_schema`.`statistics`
    WHERE `table_schema` = DATABASE()
      AND `table_name` = 'sensor_reading'
      AND `non_unique` = 0
      AND `index_name` <> 'PRIMARY'
    GROUP BY `index_name`
    HAVING COUNT(*) = 2
       AND SUM(`column_name` = 'device_message_id') = 1
       AND SUM(`column_name` = 'sensor_type') = 1
    LIMIT 1
);

SET @drop_message_type_unique_sql = IF(
    @message_type_unique_index IS NULL,
    'SELECT 1',
    CONCAT(
        'ALTER TABLE `sensor_reading` DROP INDEX `',
        REPLACE(@message_type_unique_index, '`', '``'),
        '`'
    )
);

PREPARE drop_message_type_unique_statement FROM @drop_message_type_unique_sql;
EXECUTE drop_message_type_unique_statement;
DEALLOCATE PREPARE drop_message_type_unique_statement;

SET @single_message_unique_exists = (
    SELECT COUNT(*)
    FROM (
        SELECT `index_name`
        FROM `information_schema`.`statistics`
        WHERE `table_schema` = DATABASE()
          AND `table_name` = 'sensor_reading'
          AND `non_unique` = 0
          AND `index_name` <> 'PRIMARY'
        GROUP BY `index_name`
        HAVING COUNT(*) = 1
           AND MAX(`column_name` = 'device_message_id') = 1
    ) AS `matching_unique_indexes`
);

SET @add_single_message_unique_sql = IF(
    @single_message_unique_exists > 0,
    'SELECT 1',
    'ALTER TABLE `sensor_reading`
        ADD CONSTRAINT `uq_sensor_reading_device_message`
        UNIQUE (`device_message_id`)'
);

PREPARE add_single_message_unique_statement FROM @add_single_message_unique_sql;
EXECUTE add_single_message_unique_statement;
DEALLOCATE PREPARE add_single_message_unique_statement;
