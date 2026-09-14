package com.potner.plant.dto;

import com.potner.diary.domain.SpeciesPersona;

import java.util.Arrays;
import java.util.List;

/**
 * 식물 프로필이 보여줄 성격이다.
 *
 * <p>일기 페르소나 표에서 화면에 쓸 것만 골라 담는다. 전부 내보내지 않는 이유는 나머지가
 * 프롬프트 조립용 지시문이기 때문이다. {@code forbidden_style}("경쟁, 승리 선언") 이나
 * {@code diary_structure}("상태 확인 → 현재의 안정 → 포근한 마무리") 는 모델에게 주는 규칙이라
 * 사용자에게는 뜻이 통하지 않는다.
 *
 * <p>{@code persona_concept} 은 내보내지 않는다. 그 컬럼은 모델에게 주는 역할 지시문이라
 * 직업 은유("섬세한 조향사", "감정파 배우")로 쓰여 있고, 성격을 물은 사용자에게 사람 직업이
 * 답으로 나오면 겉돈다. 화면에는 그 용도로 따로 둔 {@code personality} 를 쓴다.
 *
 * @param tags 해시태그용 키워드. 꽃말은 여기 없고 {@code flowerMeaning} 에 따로 있다.
 *             앱이 둘을 합쳐 보여주므로 섞으면 같은 말이 두 번 나온다.
 */
public record SpeciesPersonaResponse(
        String characterName,
        String flowerMeaning,
        List<String> tags,
        /** 성격. 꽃말과 핵심 가치관을 사람 말로 옮긴 것이다. */
        String personality,
        /** 무엇을 가장 중요하게 여기는가. 설명 두 번째 줄에 쓴다. */
        String coreValue
) {

    public static SpeciesPersonaResponse from(SpeciesPersona persona) {
        return new SpeciesPersonaResponse(
                persona.getCharacterName(),
                persona.getFlowerMeaning(),
                splitTags(persona.getPersonaTags()),
                persona.getPersonality(),
                persona.getCoreValue()
        );
    }

    /**
     * 쉼표로 나눈다. 빈 칸은 버린다.
     *
     * <p>컬럼 기본값이 빈 문자열이라 그냥 나누면 빈 태그 하나가 남는다. 종이 늘고 태그가
     * 아직 안 들어온 구간에서 앱에 '#' 만 찍힌 칩이 뜬다.
     */
    private static List<String> splitTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawTags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }
}
