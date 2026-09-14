SET @single_message_unique_index = (
    SELECT `index_name`
    FROM `information_schema`.`statistics`
    WHERE `table_schema` = DATABASE()
      AND `table_name` = 'sensor_reading'
      AND `non_unique` = 0
      AND `index_name` <> 'PRIMARY'
    GROUP BY `index_name`
    HAVING COUNT(*) = 1
       AND MAX(`column_name` = 'device_message_id') = 1
    LIMIT 1
);

SET @drop_single_message_unique_sql = IF(
    @single_message_unique_index IS NULL,
    'SELECT 1',
    CONCAT(
        'ALTER TABLE `sensor_reading` DROP INDEX `',
        REPLACE(@single_message_unique_index, '`', '``'),
        '`'
    )
);

PREPARE drop_single_message_unique_statement FROM @drop_single_message_unique_sql;
EXECUTE drop_single_message_unique_statement;
DEALLOCATE PREPARE drop_single_message_unique_statement;

SET @message_type_unique_exists = (
    SELECT COUNT(*)
    FROM (
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
    ) AS `matching_unique_indexes`
);

SET @add_message_type_unique_sql = IF(
    @message_type_unique_exists > 0,
    'SELECT 1',
    'ALTER TABLE `sensor_reading`
        ADD CONSTRAINT `uq_sensor_reading_device_message_type`
        UNIQUE (`device_message_id`, `sensor_type`)'
);

PREPARE add_message_type_unique_statement FROM @add_message_type_unique_sql;
EXECUTE add_message_type_unique_statement;
DEALLOCATE PREPARE add_message_type_unique_statement;
