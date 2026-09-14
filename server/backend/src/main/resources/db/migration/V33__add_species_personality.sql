-- 프로필 화면이 보여줄 성격 문장이다.
--
-- 지금까지는 persona_concept 을 그대로 내보냈다. 그 컬럼은 원래 일기 프롬프트의 역할 지시문이라
-- 직업 은유로 쓰여 있다 — "섬세한 조향사", "열혈 응원단장", "감정파 배우". 모델에게 "이렇게
-- 써라" 를 시킬 때는 역할을 주는 편이 효과적이지만, 사용자가 식물의 성격을 물었을 때 사람
-- 직업이 답으로 나오면 겉돈다. 여섯 종 중 넷이 직업이었다.
--
-- 한 컬럼을 두 용도가 나눠 쓴 것이 원인이라 컬럼을 나눈다. persona_concept 은 프롬프트 전용으로
-- 남기고 화면에는 이 컬럼만 쓴다. 일기 문체는 건드리지 않는다 — 직업 은유를 걷어내면 종별
-- 구분이 흐려지는데, 그 구분이 페르소나 표의 존재 이유다.
--
-- 문장은 꽃말과 core_value 에서 뽑았다. 새로 지어낸 성격이 아니라 같은 근거를 사람 말로 옮긴
-- 것이다.

ALTER TABLE `species_persona`
    ADD COLUMN `personality` VARCHAR(200) NOT NULL DEFAULT ''
        COMMENT '프로필에 보여줄 성격. persona_concept 과 달리 프롬프트에 쓰지 않는다'
        AFTER `flower_meaning`;

-- 미니해바라기 (꽃말: 동경)
UPDATE `species_persona`
   SET `personality` = '밝고 씩씩해요. 힘든 일이 있어도 금세 털어내고 좋아하는 쪽을 계속 바라봐요.',
       `persona_tags` = '밝음,씩씩함,회복탄력'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000101';

-- 배초향 (꽃말: 향수)
UPDATE `species_persona`
   SET `personality` = '감각이 예민하고 조용해요. 공기와 온도의 작은 변화를 먼저 알아채고 그 느낌을 오래 간직해요.',
       `persona_tags` = '섬세함,감각적,차분함'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000102';

-- 칼란디바 (꽃말: 설렘)
UPDATE `species_persona`
   SET `personality` = '감정 표현이 풍부해요. 사소한 일도 크게 느끼고 그 설렘을 숨기지 않고 드러내요.',
       `persona_tags` = '감정풍부,표현력,활기'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000103';

-- 바질 (꽃말: 좋은 소망)
UPDATE `species_persona`
   SET `personality` = '다정하고 차분해요. 서두르는 법이 없고 필요한 만큼만 챙기며 곁을 편안하게 만들어요.',
       `persona_tags` = '다정함,차분함,꾸준함'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000104';

-- 일일초 (꽃말: 즐거운 추억)
UPDATE `species_persona`
   SET `personality` = '섬세하고 다감해요. 스쳐 지나갈 순간도 마음에 오래 담아 둬요.',
       `persona_tags` = '섬세함,서정적,다정함'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000105';

-- 방울토마토 (꽃말: 완성된 아름다움)
UPDATE `species_persona`
   SET `personality` = '부지런하고 의욕이 넘쳐요. 작은 변화도 놓치지 않고 어제보다 나아지는 것을 좋아해요.',
       `persona_tags` = '부지런함,의욕적,성취지향'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000106';
