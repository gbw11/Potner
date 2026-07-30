-- 로봇이 오가는 지도 위 위치다. 급수 스테이션, 대기 장소(그늘), 햇빛 자리, 마중 지점.
--
-- 이름이 station 이 아니라 location 인 이유: 넷 중 물리 장치는 급수 스테이션뿐이다.
-- 나머지 셋은 SLAM 지도 위의 좌표일 뿐 하드웨어가 없다. station 으로 부르면 그늘 자리에도
-- 스테이션 코드를 요구하는 모양이 된다.
--
-- 좌표는 로봇(젯슨)이 SLAM 으로 만든 지도의 map 프레임 기준이다(m, rad). 지도를 다시 그리면
-- 원점이 바뀌어 저장된 좌표가 전부 무효가 된다 — 그때는 좌표를 다시 넣어야 하며, 이는 스키마가
-- 아니라 운영 절차의 문제라 여기서 다루지 않는다.
--
-- 좌표가 NULL 인 것은 정상이다. 등록(코드 입력)과 좌표 입력은 다른 사람이 다른 시점에 한다 —
-- 코드는 사용자가 앱에서, 좌표는 설치자가 RViz 에서 읽어 관리 API 로 넣는다. 로봇 쪽 규약
-- "전부 0 이면 미설정" 대신 서버는 NULL 로 미설정을 표현한다. 0,0,0 은 지도 원점이라는 실제
-- 좌표라서 미설정과 겹치면 안 된다.
CREATE TABLE IF NOT EXISTS `robot_location` (
    `location_id`   CHAR(36)     NOT NULL,

    `robot_id`      CHAR(36)     NOT NULL,

    -- WATER_STATION 급수 스테이션 / HOME 대기 장소(그늘) / SUNLIGHT 햇빛 자리 / GREETING 마중 지점.
    -- 종류가 늘면(충전 분리, 송풍 등) CHECK 를 고치는 마이그레이션이 필요하다. 값을 자유롭게
    -- 두는 것보다 오타가 걸리는 편이 낫다고 보았다.
    `location_type` VARCHAR(20)  NOT NULL,

    -- 물리 스테이션만 갖는다. 사용자가 스테이션 스티커에서 읽어 앱에 입력하는 값이다.
    -- 전역 UNIQUE 다 — 한 번 등록된 코드는 다른 사용자가 쓸 수 없다. device_uid 와 같은 방침.
    `station_code`  VARCHAR(50)  NULL,

    -- map 프레임 좌표(m). DECIMAL(8,3) 이면 ±99999.999m 로 실내 지도에 충분하다.
    `pose_x`        DECIMAL(8,3) NULL,
    `pose_y`        DECIMAL(8,3) NULL,
    -- 방향(rad, -pi ~ pi). 도킹 접근 방향이 있어 좌표만으로는 부족하다.
    `pose_yaw`      DECIMAL(6,4) NULL,

    -- 물 부족 보고다. WATER_STATION 만 의미가 있다. 갱신 경로는 다음 작업(스테이션 상태 수신)
    -- 에서 붙지만 컬럼을 지금 만들어 두면 그 작업에 마이그레이션이 필요 없다.
    `water_low`     TINYINT(1)   NOT NULL DEFAULT 0,
    `water_low_at`  DATETIME     NULL COMMENT '마지막 물 부족 보고 시각',

    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT `pk_robot_location` PRIMARY KEY (`location_id`),

    -- 생성 컬럼이 없어 CASCADE 를 쓸 수 있다. 로봇이 지워지면 그 위치도 의미가 없다.
    CONSTRAINT `fk_robot_location_robot`
        FOREIGN KEY (`robot_id`) REFERENCES `robot` (`robot_id`) ON DELETE CASCADE,

    CONSTRAINT `ck_robot_location_type`
        CHECK (`location_type` IN ('WATER_STATION', 'HOME', 'SUNLIGHT', 'GREETING')),

    -- 코드는 물리 스테이션에만, 물리 스테이션에는 반드시 있다. 어긋난 조합이 저장되면
    -- 앱 등록 화면과 서버 상태가 다른 이야기를 하게 된다.
    CONSTRAINT `ck_robot_location_station_code`
        CHECK ((`location_type` = 'WATER_STATION' AND `station_code` IS NOT NULL)
            OR (`location_type` <> 'WATER_STATION' AND `station_code` IS NULL)),

    -- 좌표는 셋이 함께 있거나 함께 없다. x 만 있는 좌표로는 로봇을 보낼 수 없다.
    CONSTRAINT `ck_robot_location_pose`
        CHECK ((`pose_x` IS NULL AND `pose_y` IS NULL AND `pose_yaw` IS NULL)
            OR (`pose_x` IS NOT NULL AND `pose_y` IS NOT NULL AND `pose_yaw` IS NOT NULL
                AND `pose_yaw` BETWEEN -3.1416 AND 3.1416)),

    -- 로봇 하나에 종류별 위치는 하나다. 급수 스테이션 두 개를 등록할 화면도 명령도 없다.
    CONSTRAINT `uq_robot_location_robot_type` UNIQUE (`robot_id`, `location_type`),
    CONSTRAINT `uq_robot_location_station_code` UNIQUE (`station_code`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='로봇이 오가는 지도 위 위치(스테이션·대기·햇빛·마중)';
