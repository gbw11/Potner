-- 개화 기록이다. 사용자가 꽃이 핀 것을 보고 남기거나, 나중에 장치가 판정해 남긴다.
--
-- `alert` 를 재사용하지 않는 이유가 세 가지다.
--   1. `alert.active_key` 가 (plant_id, metric_type) 로 활성 1건만 허용한다. 개화를 지표로
--      끼워 넣으면 같은 식물의 두 번째 꽃이 UNIQUE 에 막힌다.
--   2. `deviation`, `measured_value`, `threshold_min`, `threshold_max` 가 전부 NOT NULL 이다.
--      개화에는 방향도 임계값도 없어 의미 없는 값을 채워야 한다.
--   3. 화면(_18)이 '이상 알림 / 개화 알림 / 알림 이력' 을 이미 나눠 보여준다. 한 테이블로
--      합쳐도 조회할 때 다시 갈라야 한다.
CREATE TABLE IF NOT EXISTS `plant_bloom` (
    `bloom_id`   CHAR(36)     NOT NULL,

    -- `alert.user_id` 와 같은 비정규화다. 개화 목록은 식물이 아니라 사용자 단위로 조회하므로
    -- plant 를 조인하지 않고 끝내려고 둔다.
    `user_id`    CHAR(36)     NOT NULL COMMENT '사용자 단위 조회를 단일 테이블로 처리하기 위한 비정규화',
    `plant_id`   CHAR(36)     NOT NULL,

    -- 서비스 타임존 기준 날짜다. plant_photo.photo_date, plant_diary.diary_date 와 같은
    -- 규칙이어야 개화한 날의 사진과 일기가 어긋나지 않는다.
    `bloom_date` DATE         NOT NULL,

    `note`       VARCHAR(200) NULL COMMENT '사용자가 남긴 한 줄 메모',

    -- 지금은 USER 만 들어온다. 장치가 개화를 판정하게 되면 DEVICE 가 붙는데, 그때 사용자가
    -- 직접 남긴 기록과 구분할 수단이 없으면 중복을 정리할 수 없다.
    `source`     VARCHAR(10)  NOT NULL COMMENT 'USER, DEVICE',

    `read_at`    DATETIME     NULL COMMENT '사용자 확인 시각',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT `pk_plant_bloom` PRIMARY KEY (`bloom_id`),

    -- 생성 컬럼이 없어 CASCADE 를 쓸 수 있다. alert 가 RESTRICT 인 것은 plant_id 가
    -- active_key 의 기반 컬럼이기 때문이고, 여기는 해당하지 않는다.
    CONSTRAINT `fk_plant_bloom_user`
        FOREIGN KEY (`user_id`) REFERENCES `app_user` (`user_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_plant_bloom_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_plant_bloom_source`
        CHECK (`source` IN ('USER', 'DEVICE')),

    -- 하루에 두 송이가 필 수 있으므로 (plant_id, bloom_date) 를 UNIQUE 로 묶지 않는다.
    -- 같은 날 두 번 눌러 생긴 중복은 사용자가 삭제로 정리한다.
    INDEX `idx_plant_bloom_user_date` (`user_id`, `bloom_date` DESC),
    -- 첫 개화 여부 판정과 식물 단위 삭제가 탄다.
    INDEX `idx_plant_bloom_plant_date` (`plant_id`, `bloom_date`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='식물 개화 기록';
