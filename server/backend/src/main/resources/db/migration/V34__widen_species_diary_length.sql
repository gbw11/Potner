-- 일기 분량을 늘린다.
--
-- 실호출로 확인해 보니 모델이 sentence_rhythm 의 문장 수를 정확히 지켰다. 6종이 지시 범위
-- 안에서 3~6문장을 썼고, 결과가 106~262자로 갈렸다. 짧은 쪽은 앱의 일기 상세 화면에서
-- 본문 아래가 크게 비어 보인다.
--
-- 문장 수만 올리면 물 탄 글이 된다. 근거로 주는 사실이 네 개뿐이라 문장 하나당 사실 하나로
-- 이미 맞아떨어지는데, 여기서 문장만 늘리면 같은 말을 돌려 쓰거나 지어낸다. 그래서 같은
-- 배포에 온도·습도·조도의 그날 폭을 근거에 추가했다(PlantDaySummaryReader.describeRanges).
--
-- 종별 리듬 특성은 유지한다. 짧고 빠른 종은 문장이 더 많아야 같은 분량이 나오고, 문장이 긴
-- 종은 적어도 채워진다. 분량 자체는 프롬프트의 "본문은 180~250자" 가 잡는다.

-- 짧고 빠른 문장이라 같은 분량에 더 많이 필요하다.
UPDATE `species_persona` SET `sentence_rhythm` = '짧고 빠른 문장 7~9개, 감탄문 2~3개 허용'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000101';

-- 중간 길이 문장에 감각 비유가 붙어 한 문장이 길다.
UPDATE `species_persona` SET `sentence_rhythm` = '차분한 중간 길이 문장 6~8개, 감각 비유는 문장마다 하나 이하'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000102';

-- 이미 가장 길게 쓰던 종이다. 상한만 낮춰 다른 종과 분량을 맞춘다.
UPDATE `species_persona` SET `sentence_rhythm` = '리듬감 있는 문장 5~7개, 감탄문과 비유 적극 사용'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000103';

UPDATE `species_persona` SET `sentence_rhythm` = '느리고 차분한 문장 6~8개'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000104';

UPDATE `species_persona` SET `sentence_rhythm` = '중간 길이의 부드러운 문장 6~8개'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000105';

UPDATE `species_persona` SET `sentence_rhythm` = '빠르고 선명한 문장 7~9개, 항목식 표현 일부 허용'
 WHERE `species_id` = '20000000-0000-0000-0000-000000000106';
