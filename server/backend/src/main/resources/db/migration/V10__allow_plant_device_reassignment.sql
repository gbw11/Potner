-- plant_device_assignment 은 plant_id 와 robot_id 에 컬럼 전체 UNIQUE 가 걸려 있어
-- 식물당 행이 영구히 하나였다. unassigned_at 을 채워 해제해도 같은 식물에 새 행을 넣을 수 없어
-- 재배정이 불가능했다. 반면 조회 코드는 이미 이력을 전제한다.
--   findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc
-- 제약과 코드의 전제가 어긋나 있었다.
--
-- alert 의 활성 유일성과 같은 방식으로 "활성 배정만 유일"을 표현한다.
-- 해제되면 파생 키가 NULL 이 되어 UNIQUE 대상에서 빠지므로 재배정이 되고 이력도 남는다.

-- 외래키가 UNIQUE 인덱스를 참조하므로 먼저 떼어낸다.
ALTER TABLE `plant_device_assignment`
    DROP FOREIGN KEY `fk_plant_device_assignment_plant`;

ALTER TABLE `plant_device_assignment`
    DROP FOREIGN KEY `fk_plant_device_assignment_robot`;

ALTER TABLE `plant_device_assignment`
    DROP INDEX `uq_plant_device_assignment_plant`;

ALTER TABLE `plant_device_assignment`
    DROP INDEX `uq_plant_device_assignment_robot`;

ALTER TABLE `plant_device_assignment`
    ADD COLUMN `active_plant_key` VARCHAR(36) GENERATED ALWAYS AS (
        IF(`unassigned_at` IS NULL, `plant_id`, NULL)
    ) STORED COMMENT '활성 배정 유일성 보장용 파생 키',
    ADD COLUMN `active_robot_key` VARCHAR(36) GENERATED ALWAYS AS (
        IF(`unassigned_at` IS NULL, `robot_id`, NULL)
    ) STORED COMMENT '활성 배정 유일성 보장용 파생 키';

ALTER TABLE `plant_device_assignment`
    ADD CONSTRAINT `uq_plant_device_assignment_active_plant` UNIQUE (`active_plant_key`),
    ADD CONSTRAINT `uq_plant_device_assignment_active_robot` UNIQUE (`active_robot_key`);

-- 이력이 쌓이기 시작하므로 활성 배정 조회가 인덱스를 타게 한다.
-- 외래키보다 먼저 만든다. 순서가 반대면 MySQL 이 외래키용 인덱스를 따로 만들어 중복된다.
ALTER TABLE `plant_device_assignment`
    ADD INDEX `idx_plant_device_assignment_plant_assigned` (`plant_id`, `assigned_at`),
    ADD INDEX `idx_plant_device_assignment_robot_assigned` (`robot_id`, `assigned_at`);

-- MySQL 은 STORED 생성 컬럼의 기반 컬럼에 CASCADE 참조 동작을 허용하지 않는다.
-- plant_id 와 robot_id 가 파생 키의 기반 컬럼이 되었으므로 RESTRICT 로 바꾼다.
-- alert 가 같은 이유로 RESTRICT 이며, 식물 삭제는 소프트 삭제라 동작 차이는 없다.
-- 로봇은 배정 이력이 남아 있는 동안 물리 삭제할 수 없다. 배정 해제만 지원한다.
ALTER TABLE `plant_device_assignment`
    ADD CONSTRAINT `fk_plant_device_assignment_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE RESTRICT,
    ADD CONSTRAINT `fk_plant_device_assignment_robot`
        FOREIGN KEY (`robot_id`) REFERENCES `robot` (`robot_id`) ON DELETE RESTRICT;
