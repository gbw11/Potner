ALTER TABLE `plant`
    ADD COLUMN `adopted_date` DATE NULL
        COMMENT '사용자가 식물을 데려온 날짜. 앱 등록일(created_at)과 다르다'
        AFTER `nickname`;

ALTER TABLE `plant_life_stage`
    ADD COLUMN `description` VARCHAR(255) NULL
        COMMENT '단계 이름만으로는 무엇을 고를지 알기 어려우므로 선택을 돕는 설명'
        AFTER `name`;

UPDATE `plant_life_stage` SET `description` = '씨앗에서 싹이 트는 시기'
 WHERE `code` = 'GERMINATION';
UPDATE `plant_life_stage` SET `description` = '어린 잎이 나고 뿌리가 자리를 잡는 시기'
 WHERE `code` = 'SEEDLING';
UPDATE `plant_life_stage` SET `description` = '전체적으로 크기가 늘어나는 시기'
 WHERE `code` = 'GROWTH';
UPDATE `plant_life_stage` SET `description` = '잎과 줄기가 왕성하게 자라는 시기'
 WHERE `code` = 'VEGETATIVE';
UPDATE `plant_life_stage` SET `description` = '수확한 뒤 잎과 줄기를 다시 키우는 시기'
 WHERE `code` = 'REGROWTH';
UPDATE `plant_life_stage` SET `description` = '생장이 완성되어 형태가 안정된 시기'
 WHERE `code` = 'MATURE';
UPDATE `plant_life_stage` SET `description` = '꽃봉오리가 만들어지는 시기. 낮이 짧아야 꽃눈이 생기는 식물도 있다'
 WHERE `code` = 'BUD_FORMATION';
UPDATE `plant_life_stage` SET `description` = '꽃이 피는 시기'
 WHERE `code` = 'FLOWERING';
UPDATE `plant_life_stage` SET `description` = '열매가 달리고 익는 시기'
 WHERE `code` = 'FRUITING';
