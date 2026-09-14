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
 * <p><strong>돌봄은 근거로 준 것만 쓰게 한다.</strong> 예전에는 이동·급수·송풍을 통째로
 * 금지했다. 서버가 그 셋을 하지 않던 때의 규칙인데, 지금은 자동 케어가 실제로 돌고 이력도
 * 남으므로 금지가 아니라 목록으로 준다. 대신 목록 밖의 돌봄을 지어내는 것은 계속 막는다 —
 * 기획 자료의 일기 예시가 전부 그런 행동을 말하고 있어 모델이 따라 하기 쉽다.
 *
 * <p>주어도 못박는다. 일기를 쓰는 주체가 식물이라 "로봇이 물을 줬다" 는 시점이 어긋난다.
 *
 * <p>사용자 이름이 없다는 사실도 명시한다. 미니해바라기와 칼란디바는 페르소나가 사용자를
 * 문장에 등장시키라고 요구하는데(각각 {@code ending_rule}, {@code core_value}) 프롬프트에
 * 있는 고유명사는 식물 애칭뿐이다. 말하지 않으면 모델이 그 빈자리를 애칭으로 메워, 자기
 * 이름으로 사용자를 부르거나 자신을 제3자로 서술한다. 실호출에서 두 종 모두 그렇게 나왔다.
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
                - 사용자를 부를 때는 '%s' 만 쓴다. 사용자의 이름은 주어지지 않는다.
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
                - 숫자와 단위를 쓰지 않는다. 몇 밀리리터인지 몇 도인지는 적지 않고 느낌으로 적는다.
                  일기 화면이 그 수치를 따로 보여주므로 본문까지 숫자를 담을 이유가 없다.
                - 오늘 받은 돌봄은 주어진 목록이 전부다. 목록에 없는 돌봄을 받았다고 쓰면 안 된다.
                - 돌봄은 내가 겪은 일로 쓴다. 로봇이나 기계를 주어로 삼지 않는다.
                - 내 이름은 나를 가리킨다. 그 이름으로 사용자를 부르면 안 된다.
                - 사용자가 돌보지 않았다고 탓하거나 죄책감을 주지 않는다.
                - 병해나 원인을 진단하듯 단정하지 않는다.
                - 예시 문장을 그대로 옮기지 않는다. 말투와 구조만 참고한다.
                - 제목은 30자 이내로 쓴다.
                - 본문은 180~250자로 쓴다. 주어진 사실이 모자라면 늘리지 말고 짧게 끝낸다.

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
                        // 바로 위 줄에 한 번 더 박는다. 응원 구호처럼 사용자를 부르는 문장을
                        // 요구하는 페르소나는 호칭을 알려주는 것만으로는 이름을 끌어다 쓴다.
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
        // "사용자가 부르는 이름" 이라고 적었더니 모델이 사용자의 이름으로 읽었다. 자기 이름으로
        // 사용자를 부르거나("초록이, 오늘은…") 자신을 제3자로 서술했다("초록이가 남긴 사진").
        // 누구의 이름인지를 라벨에서 못박는다. 사용자를 어떻게 부를지는 페르소나가 정한다.
        prompt.append("내 이름: ").append(summary.plantNickname())
                .append(" (사용자가 나를 이렇게 부른다)").append('\n');
        prompt.append("식물 종: ").append(summary.speciesName()).append('\n');
        prompt.append("\n[오늘의 관찰]\n");

        if (summary.alertLabels().isEmpty()) {
            prompt.append("- 기준을 벗어난 상태는 없었다.\n");
        } else {
            summary.alertLabels().forEach(label -> prompt.append("- ").append(label).append('\n'));
        }
        summary.rangeLabels().forEach(label -> prompt.append("- ").append(label).append('\n'));
        if (summary.lightNote() != null) {
            prompt.append("- ").append(summary.lightNote()).append('\n');
        }
        prompt.append(summary.photoTaken()
                ? "- 오늘 사진이 한 장 남았다.\n"
                : "- 오늘 사진은 남지 않았다.\n");

        // 돌봄을 관찰과 나눠 적는다. 한 목록에 섞으면 "흙이 말랐다" 와 "물을 받았다" 가 같은
        // 층으로 읽혀 무엇이 상태이고 무엇이 그에 대한 대응인지 흐려진다.
        prompt.append("\n[오늘 받은 돌봄]\n");
        if (summary.careLabels().isEmpty()) {
            prompt.append("- 오늘은 받은 돌봄이 없다.\n");
        } else {
            summary.careLabels().forEach(label -> prompt.append("- ").append(label).append('\n'));
        }

        if (summary.isQuietDay()) {
            // 아무 일도 없던 날 모델이 사건을 만들어내지 않도록 못박는다. 센서가 아직 붙지
            // 않은 식물이 대부분이라 실제로 자주 지나는 경로다.
            prompt.append("\n특별한 일이 없던 조용한 하루다. 사건을 만들지 말고 그 조용함을 그대로 쓴다.\n");
        }
        return prompt.toString();
    }
}
