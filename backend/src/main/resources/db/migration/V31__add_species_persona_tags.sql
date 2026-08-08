-- 프로필 화면이 해시태그로 보여줄 짧은 키워드다.
--
-- 기존 컬럼을 그대로 쓸 수 없어 따로 둔다. persona_concept·narrative_role·core_value 는 모두
-- 문장이라("어떤 하루든 다시 일어설 이유를 찾아 외치는 열혈 응원단장") 해시태그로 쓰면 줄이
-- 넘친다. 앱에서 문장을 잘라 키워드를 만들면 종마다 다른 자리에서 잘려 결과를 예측할 수 없다.
--
-- 값은 새로 지어내지 않고 같은 행의 persona_concept·narrative_role·core_value 에서 뽑았다.
-- 꽃말은 flower_meaning 이 이미 갖고 있어 여기 넣지 않는다. 앱이 두 곳을 합쳐 보여주므로
-- 중복하면 '#동경 #동경' 이 된다.
--
-- 쉼표로 구분한다. 같은 표의 signature_words 가 이미 쓰는 방식이라 파싱 규칙을 하나로 둔다.
ALTER TABLE `species_persona`
    ADD COLUMN `persona_tags` VARCHAR(200) NOT NULL DEFAULT ''
        COMMENT '프로필 해시태그용 짧은 키워드. 쉼표 구분' AFTER `persona_concept`;

-- 미니해바라기: '열혈 응원단장'(persona_concept), '위기를 반전'(narrative_role),
--               '끝까지 밝은 방향'(core_value)
UPDATE `species_persona` SET `persona_tags` = '열혈응원단장,긍정,회복탄력'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000101';

-- 배초향: '섬세한 조향사'(persona_concept), '분위기를 향으로 배합'(narrative_role),
--         '감각을 조화롭게 정돈'(core_value)
UPDATE `species_persona` SET `persona_tags` = '섬세한조향사,감각적,차분함'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000102';

-- 칼란디바: '감정파 배우'(persona_concept), '하루를 공연으로 재구성'(narrative_role)
--           설렘은 꽃말이라 빼고 그 성질을 '드라마틱'·'표현력'으로 옮겼다.
UPDATE `species_persona` SET `persona_tags` = '감정파배우,드라마틱,표현력'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000103';

-- 바질: '현실적 보호자'(persona_concept), '생활 리듬을 돌보는'(narrative_role),
--       '규칙적인 돌봄'(core_value)
UPDATE `species_persona` SET `persona_tags` = '현실적보호자,편안함,규칙적돌봄'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000104';

-- 일일초: '감성적인 기록가'(persona_concept), '장면을 기억으로 보존'(narrative_role)
UPDATE `species_persona` SET `persona_tags` = '감성기록가,서정적,장면수집'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000105';

-- 방울토마토: '야심찬 성장 플레이어'(persona_concept), '성장 기록을 갱신'(narrative_role),
--             '다음 목표에 도전'(core_value)
UPDATE `species_persona` SET `persona_tags` = '성장플레이어,도전적,기록갱신'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000106';
