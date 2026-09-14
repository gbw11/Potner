package com.potner.diary.application;

import com.potner.diary.domain.SpeciesPersona;

/**
 * 프롬프트 조립에 필요한 페르소나 정보다.
 *
 * <p>말투 한 줄만으로는 부족하다. 기획 자료(v2)의 목적이 "같은 센서 기록을 받아도 종마다 확실히
 * 다르게 쓰이는 것" 인데, 성격 설명만 주면 모델이 전부 비슷한 밝고 다정한 글을 낸다. 관찰 초점,
 * 문장 구조, 대표 어휘, 금지 문체, 결말 규칙까지 넘겨야 서정형과 보고서형이 갈린다.
 *
 * <p>엔티티를 그대로 쓰지 않는 이유는 페르소나가 없는 종에도 기본값을 줘야 하기 때문이다.
 * 저장되지 않는 기본 화법을 엔티티로 만들면 영속성 없는 인스턴스가 돌아다닌다.
 */
public record PersonaVoice(
        String characterName,
        String flowerMeaning,
        String personaConcept,
        String coreValue,
        String firstPerson,
        String addressUser,
        String speechStyle,
        String narrativeRole,
        String primaryFocus,
        String diaryStructure,
        String sentenceRhythm,
        String signatureWords,
        String endingRule,
        String forbiddenStyle,
        String titlePattern,
        String contrastAnchor,
        String diaryExample
) {

    public static PersonaVoice from(SpeciesPersona persona) {
        return new PersonaVoice(
                persona.getCharacterName(),
                persona.getFlowerMeaning(),
                persona.getPersonaConcept(),
                persona.getCoreValue(),
                persona.getFirstPerson(),
                persona.getAddressUser(),
                persona.getSpeechStyle(),
                persona.getNarrativeRole(),
                persona.getPrimaryFocus(),
                persona.getDiaryStructure(),
                persona.getSentenceRhythm(),
                persona.getSignatureWords(),
                persona.getEndingRule(),
                persona.getForbiddenStyle(),
                persona.getTitlePattern(),
                persona.getContrastAnchor(),
                persona.getDiaryExample()
        );
    }

    /**
     * 페르소나가 정해지지 않은 종이 쓰는 기본 화법이다.
     *
     * <p>지금은 지원 6종에 모두 페르소나가 있어 쓰이지 않는다. 그래도 남겨 두는 이유는 종이
     * 먼저 늘고 페르소나가 나중에 오는 구간이 생기기 때문이다. 그때 그 종만 일기가 사라지면
     * 사용자에게는 기능이 고장 난 것으로 보인다. 중립적인 말투로라도 쓴다.
     *
     * <p>종 이름을 그대로 캐릭터 이름으로 쓴다. 기획이 설정을 주면 시드에 행을 넣는 것만으로
     * 이 기본값을 벗어난다.
     */
    public static PersonaVoice defaultVoice(String speciesName) {
        return new PersonaVoice(
                speciesName,
                "",
                "사용자 곁에서 조용히 자라는 식물",
                "하루하루의 변화를 솔직하게 전하는 것",
                "나",
                "너",
                "담백하고 편안한 반말. 과장하지 않고 사실을 그대로 말한다.",
                "그날의 상태를 담담히 적는 기록자",
                "상태 변화, 하루의 분위기",
                "오늘의 상태 → 느낀 점 → 담담한 마무리",
                "차분한 문장 3~4개",
                "오늘은, 그대로, 지나갔다",
                "마지막 문장은 내일에 대한 담담한 기대로 끝낸다.",
                "과한 감탄, 점수 매기기, 극적인 비유",
                "오늘의 기록: {핵심 사건}",
                "특정 성격을 강하게 드러내지 않는다. 사실과 담담한 감상만 쓴다.",
                "오늘은 큰 변화 없이 조용히 지나갔다. 상태는 대체로 안정적이었다. 내일도 이런 하루면 좋겠다."
        );
    }
}
