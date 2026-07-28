-- 사용자가 올린 대표 사진과 장치가 찍은 성장 사진이 한 테이블에 함께 산다.
-- 저장·리사이즈·URL 생성을 공유하고, 대표 사진 참조가 한 종류로 유지된다.
-- 대신 포토 로그와 타임랩스는 장치 사진만 보아야 하므로 출처를 구분해야 한다.
--
-- source_robot_id 로 판별하지 않는다. 그 FK 가 ON DELETE SET NULL 이라 로봇이 삭제되면
-- 장치 사진이 사용자 사진으로 둔갑한다.
ALTER TABLE `plant_photo`
    ADD COLUMN `source` VARCHAR(10) NOT NULL DEFAULT 'DEVICE'
        COMMENT '사진 출처. DEVICE 는 장치 촬영, USER 는 사용자 업로드'
        AFTER `source_robot_id`;

-- 기존 행은 전부 장치 촬영이므로 기본값으로 채운 뒤 기본값을 뗀다.
-- 이후 삽입은 출처를 명시해야 한다.
ALTER TABLE `plant_photo`
    ALTER COLUMN `source` DROP DEFAULT;

-- 사진 조회가 전부 출처로 걸러지므로 인덱스에 출처를 넣는다.
--
-- 새 인덱스를 먼저 만들고 옛 인덱스를 지운다. 순서를 바꾸면 실패한다.
-- fk_plant_photo_plant 가 plant_id 인덱스를 필요로 하는데 그 역할을 지금은
-- idx_plant_photo_plant_date 가 하고 있어, 대체 인덱스 없이 지우면 errno 150 이 난다.
-- 새 인덱스도 plant_id 가 맨 앞이라 그 자리를 그대로 이어받는다.
CREATE INDEX `idx_plant_photo_plant_source_date`
    ON `plant_photo` (`plant_id`, `source`, `photo_date`);
DROP INDEX `idx_plant_photo_plant_date` ON `plant_photo`;

-- 대표 사진은 사용자가 새로 올린 것일 수도, 포토 로그에서 고른 장치 사진일 수도 있다.
-- 어느 쪽이든 plant_photo 행이므로 참조가 하나다.
--
-- plant -> plant_photo -> plant 순환 참조가 생긴다. MySQL 은 허용하지만 순환 구조에서
-- cascade 가 예상대로 돌지 않을 수 있다고 경고한다. 식물 삭제는 소프트 삭제라 실제
-- DELETE 가 일어나지 않으므로 실무상 문제가 없다. V10 에서 같은 판단을 했다.
ALTER TABLE `plant`
    ADD COLUMN `representative_photo_id` CHAR(36) NULL
        COMMENT '식물 대표 사진. 지정하지 않으면 NULL 이고 대체 이미지는 앱이 정한다'
        AFTER `nickname`,
    ADD CONSTRAINT `fk_plant_representative_photo`
        FOREIGN KEY (`representative_photo_id`) REFERENCES `plant_photo` (`photo_id`)
        ON DELETE SET NULL;
