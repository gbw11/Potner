package com.potner.diary.application;

import com.potner.diary.domain.PlantDiaryRepository;
import com.potner.diary.domain.SpeciesPersonaRepository;
import com.potner.llm.application.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 하루치 일기를 한 편 만든다.
 *
 * <p>사용자 요청이 아니라 배치가 부른다. 그래서 소유권을 확인하지 않고, 실패를 예외가 아니라
 * 결과로 돌려준다.
 */
@Service
public class DiaryGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DiaryGenerationService.class);

    /** {@code plant_diary.title} 컬럼 길이다. 넘치면 저장 단계에서 잘려 배치가 실패한다. */
    private static final int MAX_TITLE_LENGTH = 100;

    /** 본문은 TEXT 라 한계가 멀지만, 모델이 폭주했을 때 그대로 담지 않도록 상한을 둔다. */
    private static final int MAX_CONTENT_LENGTH = 2000;

    private final PlantDiaryRepository diaryRepository;
    private final SpeciesPersonaRepository personaRepository;
    private final PlantDaySummaryReader summaryReader;
    private final DiaryPromptFactory promptFactory;
    private final LlmClient llmClient;
    private final DiaryService diaryService;
    private final ObjectMapper objectMapper;

    public DiaryGenerationService(
            PlantDiaryRepository diaryRepository,
            SpeciesPersonaRepository personaRepository,
            PlantDaySummaryReader summaryReader,
            DiaryPromptFactory promptFactory,
            LlmClient llmClient,
            DiaryService diaryService,
            ObjectMapper objectMapper
    ) {
        this.diaryRepository = diaryRepository;
        this.personaRepository = personaRepository;
        this.summaryReader = summaryReader;
        this.promptFactory = promptFactory;
        this.llmClient = llmClient;
        this.diaryService = diaryService;
        this.objectMapper = objectMapper;
    }

    /**
     * 그날 일기가 없으면 한 편 만들어 저장한다.
     *
     * <p>트랜잭션을 걸지 않는다. 모델 호출이 수 초 걸리는데 그동안 커넥션을 붙들고 있으면
     * 식물 수만큼 풀이 잠긴다. 저장은 {@link DiaryService#write} 가 자기 트랜잭션에서 한다.
     */
    public DiaryGenerationResult generate(String plantId, LocalDate diaryDate) {
        // 모델을 부르기 전에 확인한다. 어차피 저장 단계에서 걸리지만 그때는 이미 돈을 썼다.
        if (diaryRepository.existsByPlantIdAndDiaryDate(plantId, diaryDate)) {
            return DiaryGenerationResult.SKIPPED_ALREADY_WRITTEN;
        }

        // 읽기는 여기서 끝난다. 이후 모델 호출이 몇 초 걸리는데 그동안 커넥션을 붙들지 않는다.
        PlantDaySummary summary = summaryReader.read(plantId, diaryDate).orElse(null);
        if (summary == null) {
            return DiaryGenerationResult.SKIPPED_PLANT_NOT_ACTIVE;
        }

        // 페르소나는 연관이 없는 표라 트랜잭션 밖에서 읽어도 안전하다.
        PersonaVoice voice = personaRepository.findById(summary.speciesId())
                .map(PersonaVoice::from)
                .orElseGet(() -> PersonaVoice.defaultVoice(summary.speciesName()));

        Optional<String> generated = llmClient.complete(promptFactory.create(voice, summary));
        if (generated.isEmpty()) {
            return DiaryGenerationResult.SKIPPED_NO_CONTENT;
        }

        Optional<WrittenDiary> parsed = parse(generated.get(), plantId, diaryDate);
        if (parsed.isEmpty()) {
            return DiaryGenerationResult.SKIPPED_NO_CONTENT;
        }

        WrittenDiary diary = parsed.get();
        return diaryService.write(plantId, diaryDate, diary.title(), diary.content())
                == DiaryWriteResult.WRITTEN
                ? DiaryGenerationResult.WRITTEN
                : DiaryGenerationResult.SKIPPED_ALREADY_WRITTEN;
    }

    /**
     * 모델 응답에서 제목과 본문을 꺼낸다.
     *
     * <p>{@code response_format} 으로 JSON 을 강제해도 필드 이름까지 보장되지는 않는다.
     * 모델은 형식만 맞출 뿐 어떤 키를 넣을지는 프롬프트를 따르므로 여기서 다시 확인한다.
     */
    private Optional<WrittenDiary> parse(String raw, String plantId, LocalDate diaryDate) {
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (JacksonException exception) {
            log.warn("Diary response was not JSON: plantId={}, date={}", plantId, diaryDate);
            return Optional.empty();
        }

        String title = node.path("title").asText("").trim();
        String content = node.path("content").asText("").trim();
        if (title.isEmpty() || content.isEmpty()) {
            log.warn(
                    "Diary response was missing title or content: plantId={}, date={}",
                    plantId,
                    diaryDate
            );
            return Optional.empty();
        }
        return Optional.of(new WrittenDiary(
                truncate(title, MAX_TITLE_LENGTH),
                truncate(content, MAX_CONTENT_LENGTH)
        ));
    }

    /** 길이를 넘겨도 버리지 않는다. 일기가 없는 것보다 조금 잘린 일기가 낫다. */
    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record WrittenDiary(String title, String content) {
    }
}
