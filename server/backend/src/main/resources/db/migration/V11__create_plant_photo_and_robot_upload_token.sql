-- 라즈베리가 찍은 사진을 서버가 받아 EC2 디스크에 저장하고 경로를 여기 남긴다.
-- 화면 네 곳이 이 테이블 하나를 쓴다: 포토 로그 그리드, 포토 상세, 성장 비교, 타임랩스.

-- 장치는 사용자 JWT 를 가질 수 없으므로 업로드 전용 토큰을 쓴다.
-- Refresh Token 과 같은 이유로 원문을 저장하지 않고 SHA-256 해시만 남긴다.
-- 원문은 로봇 등록 응답에서 한 번만 노출하며 사용자가 라즈베리 설정에 넣는다.
ALTER TABLE `robot`
    ADD COLUMN `upload_token_hash` CHAR(64) NULL
        COMMENT '업로드 토큰의 SHA-256 해시. 원문은 저장하지 않는다'
        AFTER `device_uid`,
    ADD CONSTRAINT `uq_robot_upload_token_hash` UNIQUE (`upload_token_hash`);

CREATE TABLE IF NOT EXISTS `plant_photo` (
    `photo_id`        CHAR(36)      NOT NULL,
    `plant_id`        CHAR(36)      NOT NULL,
    `source_robot_id` CHAR(36)      NULL COMMENT '장치가 올린 사진의 출처. 앱 업로드면 NULL',
    `photo_date`      DATE          NOT NULL COMMENT '서비스 타임존 기준 촬영 날짜',
    `captured_at`     DATETIME      NOT NULL COMMENT '촬영 시각. UTC',

    -- URL 이 아니라 상대 경로를 저장한다. 도메인과 스킴이 환경마다 다르고 나중에 바뀔 수 있으므로
    -- 조회 시 base-url 프로퍼티와 합쳐 URL 을 만든다. 경로에 UUID 두 개가 들어가 추측할 수 없다.
    `original_path`   VARCHAR(500)  NOT NULL,
    `playback_path`   VARCHAR(500)  NOT NULL COMMENT '타임랩스 재생용 축소본',
    `thumbnail_path`  VARCHAR(500)  NOT NULL COMMENT '포토 로그 그리드용',

    `width`           INT UNSIGNED  NOT NULL COMMENT '원본 가로 픽셀',
    `height`          INT UNSIGNED  NOT NULL COMMENT '원본 세로 픽셀',
    `byte_size`       INT UNSIGNED  NOT NULL COMMENT '원본 바이트 수',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT `pk_plant_photo` PRIMARY KEY (`photo_id`),
    CONSTRAINT `fk_plant_photo_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE CASCADE,
    -- 로봇이 사라져도 사진은 남아야 하므로 출처만 비운다.
    CONSTRAINT `fk_plant_photo_robot`
        FOREIGN KEY (`source_robot_id`) REFERENCES `robot` (`robot_id`) ON DELETE SET NULL,
    CONSTRAINT `ck_plant_photo_size`
        CHECK (`width` > 0 AND `height` > 0 AND `byte_size` > 0),

    -- 기간 조회(포토 로그, 타임랩스)가 인덱스를 타게 한다.
    INDEX `idx_plant_photo_plant_date` (`plant_id`, `photo_date`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='식물 성장 사진';
