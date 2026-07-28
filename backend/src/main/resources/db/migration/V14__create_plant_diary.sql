-- 성장 일기다. 사용자가 쓰는 것이 아니라 매일 정해진 시각에 LLM 이 그날의 센서·알림·사진을
-- 근거로 한 편씩 쓴다. 이 마이그레이션은 저장 자리만 만들고 생성은 다음 작업에서 붙인다.
--
-- 디자인에서 기분(mood)이 사라졌고 제목이 생겼다. 목록은 날짜와 제목만 보여주고
-- 본문은 상세에서 읽는 2단 구조다.
CREATE TABLE IF NOT EXISTS `plant_diary` (
    `diary_id`   CHAR(36)     NOT NULL,
    `plant_id`   CHAR(36)     NOT NULL,

    -- 서비스 타임존 기준 날짜다. plant_photo.photo_date 와 같은 규칙이어야 일기와 그날 사진이
    -- 어긋나지 않는다. UTC 날짜를 쓰면 아침 8시(KST) 사진이 전날로 밀린다.
    `diary_date` DATE         NOT NULL,

    `title`      VARCHAR(100) NOT NULL,
    `content`    TEXT         NOT NULL,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT `pk_plant_diary` PRIMARY KEY (`diary_id`),

    -- 하루 한 편이다. 날짜가 곧 일기의 신분이라 달력과 사진 재사용이 단순해진다.
    -- 배치가 두 번 돌아도 이 제약이 중복을 막는다.
    --
    -- plant_id 가 맨 앞이라 기간 조회도 이 인덱스를 탄다. 별도 인덱스를 두지 않는다.
    CONSTRAINT `uq_plant_diary_plant_date` UNIQUE (`plant_id`, `diary_date`),

    -- 생성 컬럼이 없어 CASCADE 를 쓸 수 있다. 식물을 물리 삭제하면 일기도 함께 사라진다.
    CONSTRAINT `fk_plant_diary_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='식물 성장 일기';
