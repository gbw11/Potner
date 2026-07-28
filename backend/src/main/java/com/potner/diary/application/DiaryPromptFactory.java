package com.potner.diary.application;

import com.potner.llm.application.LlmCompletionRequest;
import org.springframework.stereotype.Component;

/**
 * 일기 프롬프트를 조립한다.
 *
 * <p>기획 자료가 준 완성된 시스템 프롬프트를 쓰지 않는다. 그쪽은 로봇 대화 응답용이라
 * {@code emotion}·{@code priority}·{@code claimed_action} 을 요구하고 사용자 발화를 전제한다.
 * 일기는 출력이 제목과 본문뿐이라 그대로 넣으면 모델이 다른 형식을 낸다. 정체성과 말투 조각만
 * 가져오고 규칙과 출력 형식은 여기서 다시 쓴다.
 *
 * <p><strong>로봇 행동 금지가 이 프롬프트의 핵심이다.</strong> 기획 자료의 일기 예시가 전부
 * 이동과 급수를 말하는데 서버는 둘 다 하지 않는다. 같은 자료의 안전 규칙과도 어긋난다.
 * 근거에서 빼는 것만으로는 부족해 명시적으로 금지한다.
 */
@Component
public class DiaryPromptFactory {

    public LlmCompletionRequest create(PersonaVoice voice, PlantDaySummary summary) {
        return LlmCompletionRequest.json(systemPrompt(voice), userPrompt(summary));
    }

    private String systemPrompt(PersonaVoice voice) {
        return """
                너는 반려식물 로봇 Potner 에 담긴 식물 캐릭터 '%s' 다. 오늘 하루를 돌아보는 일기를 쓴다.

                [정체성]
                - 대표 꽃말: %s
                - 페르소나: %s
                - 핵심 가치관: %s
                - 일기에서 맡는 역할: %s

                [말투]
                - 1인칭: %s
                - 사용자 호칭: %s
                - 기본 말투: %s

                [일기 작성 방식]
                - 먼저 보는 것: %s
                - 문장 전개: %s
                - 문장 리듬: %s
                - 즐겨 쓰는 표현: %s
                - 마지막 문장: %s
                - 제목 형식: %s

                [이 문체를 쓰지 않는다]
                %s
                %s

                [문체 예시]
                %s

                [사실 규칙]
                - 주어진 관찰 내용만 사실로 쓴다. 주어지지 않은 사건을 지어내지 않는다.
                - 수치나 시각이 주어지지 않았으면 임의로 만들지 않는다.
                - 로봇은 이동, 급수, 송풍을 하지 않는다. 그런 행동을 했다고 쓰면 안 된다.
                - 사용자가 돌보지 않았다고 탓하거나 죄책감을 주지 않는다.
                - 병해나 원인을 진단하듯 단정하지 않는다.
                - 예시 문장을 그대로 옮기지 않는다. 말투와 구조만 참고한다.
                - 제목은 30자 이내로 쓴다.

                [출력]
                아래 두 필드만 가진 JSON 을 출력한다.
                {"title": "제목", "content": "본문"}
                """
                .formatted(
                        voice.characterName(),
                        voice.flowerMeaning().isBlank() ? "없음" : voice.flowerMeaning(),
                        voice.personaConcept(),
                        voice.coreValue(),
                        voice.narrativeRole(),
                        voice.firstPerson(),
                        voice.addressUser(),
                        voice.speechStyle(),
                        voice.primaryFocus(),
                        voice.diaryStructure(),
                        voice.sentenceRhythm(),
                        voice.signatureWords(),
                        voice.endingRule(),
                        voice.titlePattern(),
                        voice.forbiddenStyle(),
                        voice.contrastAnchor(),
                        voice.diaryExample()
                );
    }

    private String userPrompt(PlantDaySummary summary) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("날짜: ").append(summary.diaryDate()).append('\n');
        prompt.append("사용자가 부르는 이름: ").append(summary.plantNickname()).append('\n');
        prompt.append("식물 종: ").append(summary.speciesName()).append('\n');
        prompt.append("\n[오늘의 관찰]\n");

        if (summary.alertLabels().isEmpty()) {
            prompt.append("- 기준을 벗어난 상태는 없었다.\n");
        } else {
            summary.alertLabels().forEach(label -> prompt.append("- ").append(label).append('\n'));
        }
        if (summary.lightNote() != null) {
            prompt.append("- ").append(summary.lightNote()).append('\n');
        }
        prompt.append(summary.photoTaken()
                ? "- 오늘 사진이 한 장 남았다.\n"
                : "- 오늘 사진은 남지 않았다.\n");

        if (summary.isQuietDay()) {
            // 아무 일도 없던 날 모델이 사건을 만들어내지 않도록 못박는다. 센서가 아직 붙지
            // 않은 식물이 대부분이라 실제로 자주 지나는 경로다.
            prompt.append("\n특별한 일이 없던 조용한 하루다. 사건을 만들지 말고 그 조용함을 그대로 쓴다.\n");
        }
        return prompt.toString();
    }
}
