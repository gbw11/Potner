package com.potner.diary.application;

import com.potner.diary.domain.PlantDiaryRepository;
import com.potner.diary.domain.SpeciesPersona;
import com.potner.diary.domain.SpeciesPersonaRepository;
import com.potner.llm.application.LlmClient;
import com.potner.llm.application.LlmCompletionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiaryGenerationServiceTest {

    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String SPECIES_ID = "20000000-0000-0000-0000-000000000104";
    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);

    @Mock
    private PlantDiaryRepository diaryRepository;

    @Mock
    private SpeciesPersonaRepository personaRepository;

    @Mock
    private PlantDaySummaryReader summaryReader;

    @Mock
    private LlmClient llmClient;

    @Mock
    private DiaryService diaryService;

    private DiaryGenerationService generationService;

    @BeforeEach
    void setUp() {
        generationService = new DiaryGenerationService(
                diaryRepository,
                personaRepository,
                summaryReader,
                new DiaryPromptFactory(),
                llmClient,
                diaryService,
                new ObjectMapper()
        );
    }

    @Test
    void writesTheDiaryFromTheModelResponse() {
        givenGeneratable();
        givenModelResponds("{\"title\":\"조용한 하루\",\"content\":\"오늘은 흙이 말랐다.\"}");
        when(diaryService.write(eq(PLANT_ID), eq(DATE), any(), any()))
                .thenReturn(DiaryWriteResult.WRITTEN);

        assertThat(generationService.generate(PLANT_ID, DATE))
                .isEqualTo(DiaryGenerationResult.WRITTEN);
        verify(diaryService).write(PLANT_ID, DATE, "조용한 하루", "오늘은 흙이 말랐다.");
    }

    @Test
    void doesNotCallTheModelWhenTheDiaryAlreadyExists() {
        when(diaryRepository.existsByPlantIdAndDiaryDate(PLANT_ID, DATE)).thenReturn(true);

        assertThat(generationService.generate(PLANT_ID, DATE))
                .isEqualTo(DiaryGenerationResult.SKIPPED_ALREADY_WRITTEN);
        // 저장 단계에서도 걸리지만 그때는 이미 돈을 쓴 뒤다.
        verify(llmClient, never()).complete(any());
    }

    @Test
    void inactivePlantIsSkipped() {
        when(diaryRepository.existsByPlantIdAndDiaryDate(PLANT_ID, DATE)).thenReturn(false);
        when(summaryReader.read(PLANT_ID, DATE)).thenReturn(Optional.empty());

        assertThat(generationService.generate(PLANT_ID, DATE))
                .isEqualTo(DiaryGenerationResult.SKIPPED_PLANT_NOT_ACTIVE);
        verify(llmClient, never()).complete(any());
    }

    @Test
    void noModelResponseMeansNoDiary() {
        givenGeneratable();
        // 키가 없거나 호출이 실패하면 빈 값이 온다. 예외가 아니라 정상 흐름이다.
        when(llmClient.complete(any())).thenReturn(Optional.empty());

        assertThat(generationService.generate(PLANT_ID, DATE))
                .isEqualTo(DiaryGenerationResult.SKIPPED_NO_CONTENT);
        verify(diaryService, never()).write(any(), any(), any(), any());
    }

    @Test
    void responseWithoutTheExpectedFieldsIsRejected() {
        givenGeneratable();
        // response_format 은 JSON 임을 보장할 뿐 어떤 키가 들어갈지는 보장하지 않는다.
        givenModelResponds("{\"message\":\"오늘은 좋았어\"}");

        assertThat(generationService.generate(PLANT_ID, DATE))
                .isEqualTo(DiaryGenerationResult.SKIPPED_NO_CONTENT);
        verify(diaryService, never()).write(any(), any(), any(), any());
    }

    @Test
    void nonJsonResponseIsRejected() {
        givenGeneratable();
        givenModelResponds("오늘은 흙이 말랐다.");

        assertThat(generationService.generate(PLANT_ID, DATE))
                .isEqualTo(DiaryGenerationResult.SKIPPED_NO_CONTENT);
    }

    @Test
    void overlongTitleIsTruncatedRatherThanDropped() {
        givenGeneratable();
        givenModelResponds("{\"title\":\"%s\",\"content\":\"본문\"}".formatted("가".repeat(150)));
        when(diaryService.write(any(), any(), any(), any())).thenReturn(DiaryWriteResult.WRITTEN);

        generationService.generate(PLANT_ID, DATE);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(diaryService).write(eq(PLANT_ID), eq(DATE), title.capture(), any());
        // 컬럼이 100자다. 버리면 그날 일기가 없어지므로 자른다.
        assertThat(title.getValue()).hasSize(100);
    }

    @Test
    void promptLimitsCareToTheGivenListAndCarriesThePersona() {
        givenGeneratable();
        givenModelResponds("{\"title\":\"t\",\"content\":\"c\"}");
        when(diaryService.write(any(), any(), any(), any())).thenReturn(DiaryWriteResult.WRITTEN);

        generationService.generate(PLANT_ID, DATE);

        ArgumentCaptor<LlmCompletionRequest> request =
                ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(llmClient).complete(request.capture());

        // 기획 자료의 일기 예시가 전부 이동·급수를 말한다. 이제 서버가 실제로 하므로 통째로
        // 막지는 않지만, 근거로 준 목록 밖의 돌봄을 지어내는 것은 계속 막아야 한다.
        assertThat(request.getValue().systemPrompt())
                .contains("목록에 없는 돌봄을 받았다고 쓰면 안 된다")
                .contains("로봇이나 기계를 주어로 삼지 않는다");
        // 페르소나가 없는 종은 기본 화법으로 간다.
        assertThat(request.getValue().systemPrompt()).contains("바질");
        assertThat(request.getValue().jsonOutput()).isTrue();
        assertThat(request.getValue().userPrompt()).contains("흙이 말랐다");
    }

    @Test
    void personaProfileFieldsReachThePromptSoSpeciesDoNotSoundAlike() {
        // 성격 설명만 주면 모델이 종을 가리지 않고 비슷한 글을 낸다. 기획 자료 v2 의 목적이
        // 같은 기록으로도 확실히 다르게 쓰이는 것이라, 구조와 어휘와 금지 문체까지 넘겨야 한다.
        givenGeneratable();
        // 목 생성과 스터빙을 thenReturn 인자 안에서 하면 Mockito 가 미완성 스터빙으로 본다.
        SpeciesPersona persona = persona();
        when(personaRepository.findById(SPECIES_ID)).thenReturn(Optional.of(persona));
        givenModelResponds("{\"title\":\"t\",\"content\":\"c\"}");
        when(diaryService.write(any(), any(), any(), any())).thenReturn(DiaryWriteResult.WRITTEN);

        generationService.generate(PLANT_ID, DATE);

        ArgumentCaptor<LlmCompletionRequest> request =
                ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(llmClient).complete(request.capture());
        String system = request.getValue().systemPrompt();

        assertThat(system)
                .contains("하루의 생활 리듬을 돌보는 보호자")
                .contains("상태 확인 → 현재의 안정 → 포근한 마무리")
                .contains("천천히, 편안하게")
                .contains("오늘의 돌봄 기록")
                // 무엇이 아닌지도 준다. 일곱 페르소나가 모두 다정하면 구별이 사라진다.
                .contains("경쟁, 승리 선언")
                .contains("일일초처럼 추억을 회상하지 않는다");
    }

    @Test
    void theNicknameIsLabelledAsThePlantsOwnNameNotTheUsers() {
        // "사용자가 부르는 이름" 이라고 적었더니 모델이 사용자의 이름으로 읽었다. 실호출에서
        // 여섯 중 둘이 틀렸다. 자기 이름으로 사용자를 부르거나("초록이, 오늘은…") 자신을
        // 제3자로 서술했다("초록이가 남긴 사진"). 사용자 호칭은 페르소나가 따로 정한다.
        givenGeneratable();
        givenModelResponds("{\"title\":\"t\",\"content\":\"c\"}");
        when(diaryService.write(any(), any(), any(), any())).thenReturn(DiaryWriteResult.WRITTEN);

        generationService.generate(PLANT_ID, DATE);

        ArgumentCaptor<LlmCompletionRequest> request =
                ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(llmClient).complete(request.capture());
        assertThat(request.getValue().userPrompt())
                .contains("내 이름: 로지")
                .doesNotContain("사용자가 부르는 이름");
        // 라벨만으로는 부족했다. 사용자를 등장시키라고 요구하는 페르소나(미니해바라기의
        // ending_rule, 칼란디바의 core_value)는 부를 이름이 없으면 애칭을 끌어다 쓴다.
        assertThat(request.getValue().systemPrompt())
                .contains("사용자를 부를 때는 '너' 만 쓴다")
                .contains("그 이름으로 사용자를 부르면 안 된다");
    }

    @Test
    void careReceivedTodayIsGivenAsItsOwnSection() {
        // 돌봄이 근거에 없으면 모델이 쓸 수 있는 것은 환경 상태뿐이라, 센서가 조용한 날 여섯
        // 페르소나가 모두 같은 하루를 각자 말투로만 반복한다.
        givenGeneratable();
        givenModelResponds("{\"title\":\"t\",\"content\":\"c\"}");
        when(diaryService.write(any(), any(), any(), any())).thenReturn(DiaryWriteResult.WRITTEN);

        generationService.generate(PLANT_ID, DATE);

        ArgumentCaptor<LlmCompletionRequest> request =
                ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(llmClient).complete(request.capture());
        assertThat(request.getValue().userPrompt())
                .contains("[오늘 받은 돌봄]")
                .contains("물을 받았다");
        // 상태와 돌봄이 한 목록에 섞이면 무엇이 원인이고 무엇이 대응인지 흐려진다.
        assertThat(request.getValue().userPrompt().indexOf("[오늘의 관찰]"))
                .isLessThan(request.getValue().userPrompt().indexOf("[오늘 받은 돌봄]"));
        // 이제 서버가 실제로 급수·송풍을 하므로 통째로 금지하지 않는다.
        assertThat(request.getValue().systemPrompt())
                .doesNotContain("로봇은 이동, 급수, 송풍을 하지 않는다")
                .contains("목록에 없는 돌봄을 받았다고 쓰면 안 된다")
                .contains("숫자와 단위를 쓰지 않는다");
        // 근거에 숫자가 없어야 모델이 옮겨 적지 못한다. 금지 문구만으로는 새어 나온다.
        assertThat(request.getValue().userPrompt()).doesNotContain("ml").doesNotContain("℃");
    }

    @Test
    void quietDayIsToldToTheModelSoItDoesNotInventEvents() {
        when(diaryRepository.existsByPlantIdAndDiaryDate(PLANT_ID, DATE)).thenReturn(false);
        when(summaryReader.read(PLANT_ID, DATE)).thenReturn(Optional.of(new PlantDaySummary(
                "로지", SPECIES_ID, "바질", DATE, List.of(), List.of(), List.of(), null, false)));
        givenModelResponds("{\"title\":\"t\",\"content\":\"c\"}");
        when(diaryService.write(any(), any(), any(), any())).thenReturn(DiaryWriteResult.WRITTEN);

        generationService.generate(PLANT_ID, DATE);

        ArgumentCaptor<LlmCompletionRequest> request =
                ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(llmClient).complete(request.capture());
        assertThat(request.getValue().userPrompt()).contains("조용한 하루");
    }

    private void givenGeneratable() {
        when(diaryRepository.existsByPlantIdAndDiaryDate(PLANT_ID, DATE)).thenReturn(false);
        when(summaryReader.read(PLANT_ID, DATE)).thenReturn(Optional.of(new PlantDaySummary(
                "로지",
                SPECIES_ID,
                "바질",
                DATE,
                List.of("흙이 말랐다"),
                List.of("물을 받았다"),
                List.of("춥지도 덥지도 않았다"),
                null,
                true
        )));
        lenient().when(personaRepository.findById(SPECIES_ID)).thenReturn(Optional.empty());
    }

    private void givenModelResponds(String content) {
        when(llmClient.complete(any())).thenReturn(Optional.of(content));
    }

    /** 시드된 바질 페르소나를 흉내낸다. 엔티티에 세터가 없어 목으로 만든다. */
    private SpeciesPersona persona() {
        SpeciesPersona persona = mock(SpeciesPersona.class);
        when(persona.getCharacterName()).thenReturn("바질");
        when(persona.getFlowerMeaning()).thenReturn("좋은 소망");
        when(persona.getPersonaConcept()).thenReturn("필요한 돌봄을 하나씩 챙기는 현실적 보호자");
        when(persona.getCoreValue()).thenReturn("규칙적인 돌봄으로 하루를 편안하게 만드는 것");
        when(persona.getFirstPerson()).thenReturn("나");
        when(persona.getAddressUser()).thenReturn("너");
        when(persona.getSpeechStyle()).thenReturn("낮고 포근한 반말.");
        when(persona.getNarrativeRole()).thenReturn("하루의 생활 리듬을 돌보는 보호자");
        when(persona.getPrimaryFocus()).thenReturn("편안함, 안정된 생활 리듬");
        when(persona.getDiaryStructure()).thenReturn("상태 확인 → 현재의 안정 → 포근한 마무리");
        when(persona.getSentenceRhythm()).thenReturn("느리고 차분한 문장 3~5개");
        when(persona.getSignatureWords()).thenReturn("천천히, 편안하게, 필요한 만큼");
        when(persona.getEndingRule()).thenReturn("마지막 문장은 모두 편안하기를 바라는 말로 끝낸다.");
        when(persona.getForbiddenStyle()).thenReturn("경쟁, 승리 선언, 극적인 비유");
        when(persona.getTitlePattern()).thenReturn("오늘의 돌봄 기록: {가장 중요한 관리}");
        when(persona.getContrastAnchor()).thenReturn("일일초처럼 추억을 회상하지 않는다.");
        when(persona.getDiaryExample()).thenReturn("천천히 지나간 하루였다.");
        return persona;
    }
}
