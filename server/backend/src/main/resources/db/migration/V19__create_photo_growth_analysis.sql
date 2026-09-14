-- 장치가 올린 사진 한 장에 대한 생장 단계 판정 결과다.
--
-- `plant.life_stage_id` 만 갱신하고 끝내지 않는 이유가 세 가지다.
--   1. 자동 승급이 기본값으로 꺼져 있다. 켜기 전에 실측 오탐률을 봐야 하는데, 판정을 남기지
--      않으면 볼 근거가 없다.
--   2. 승급은 단계가 진행됐을 때만 일어난다. 같은 단계로 판정된 사진과 판정하지 못한 사진은
--      `plant` 에 아무 흔적을 남기지 않아 모델이 무엇을 보고 있었는지 알 수 없다.
--   3. 모델을 바꾸면 같은 사진의 판정이 달라진다. `model_weights` 를 함께 남겨야 이전 판정과
--      비교할 수 있다.
--
-- 사진 하나에 판정 하나다. `photo_id` 를 그대로 PK 로 쓰므로 같은 사진을 두 번 분석해도
-- 행이 늘지 않는다. 재분석은 UPSERT 로 덮어쓴다.
CREATE TABLE IF NOT EXISTS `photo_growth_analysis` (
    `photo_id`            CHAR(36)      NOT NULL,

    -- `plant_bloom.user_id` 와 같은 비정규화다. 식물 단위로 판정 이력을 훑을 때
    -- plant_photo 를 조인하지 않고 끝내려고 둔다.
    `plant_id`            CHAR(36)      NOT NULL COMMENT '식물 단위 조회를 단일 테이블로 처리하기 위한 비정규화',

    -- NULL 이 정상이다. 검출이 없거나, 모델이 서버가 모르는 클래스만 준 경우다.
    -- 그때도 `detection_count` 는 0 이 아닐 수 있어 "추론은 됐지만 판정할 것이 없었다" 와
    -- "모델과 서버의 클래스 목록이 어긋났다" 를 구분할 수 있다.
    `detected_stage`      VARCHAR(20)   NULL COMMENT 'GERMINATION, VEGETATIVE, FLOWERING. 판정 불가면 NULL',

    -- 0~1. `detected_stage` 가 NULL 이면 함께 NULL 이다.
    `confidence`          DECIMAL(5,4)  NULL,

    -- 판정에 쓰이지 않은 검출까지 센다. 위 주석의 구분 근거다.
    `detection_count`     INT UNSIGNED  NOT NULL DEFAULT 0,

    -- 판정 당시 가중치 파일. 모델 교체 전후를 구분하는 유일한 단서다.
    `model_weights`       VARCHAR(255)  NOT NULL,

    `inference_ms`        DECIMAL(10,2) NOT NULL COMMENT 'CPU 추론이라 느려지는 것을 관찰할 근거',

    -- 추론 서비스가 부여한 식별자. 그쪽 로그와 대조할 유일한 열쇠다.
    `request_id`          VARCHAR(64)   NOT NULL,

    -- 이 판정으로 실제 단계가 올라갔는지. 자동 승급이 꺼져 있으면 항상 0 이다.
    -- 판정과 승급을 한 컬럼으로 합치면 "판정은 됐는데 안 올렸다" 를 표현할 수 없다.
    `life_stage_advanced` TINYINT(1)    NOT NULL DEFAULT 0,

    `analyzed_at`         DATETIME      NOT NULL,
    `created_at`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT `pk_photo_growth_analysis` PRIMARY KEY (`photo_id`),

    -- 생성 컬럼이 없어 CASCADE 를 쓸 수 있다. 사진이 지워지면 그 판정도 의미가 없다.
    CONSTRAINT `fk_photo_growth_analysis_photo`
        FOREIGN KEY (`photo_id`) REFERENCES `plant_photo` (`photo_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_photo_growth_analysis_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_photo_growth_analysis_stage`
        CHECK (`detected_stage` IS NULL
           OR `detected_stage` IN ('GERMINATION', 'VEGETATIVE', 'FLOWERING')),
    -- 단계가 있으면 신뢰도도 있어야 한다. 한쪽만 있으면 판정을 신뢰할 수 없다.
    CONSTRAINT `ck_photo_growth_analysis_confidence`
        CHECK ((`detected_stage` IS NULL AND `confidence` IS NULL)
           OR (`detected_stage` IS NOT NULL AND `confidence` IS NOT NULL
               AND `confidence` BETWEEN 0 AND 1)),

    -- 식물별 판정 이력을 최근순으로 훑는 조회가 탄다.
    INDEX `idx_photo_growth_analysis_plant` (`plant_id`, `analyzed_at` DESC)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='사진 기반 생장 단계 판정 결과';
