package com.potner;

import com.jayway.jsonpath.JsonPath;
import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.auth.domain.RefreshTokenRepository;
import com.potner.diary.application.DiaryGenerationResult;
import com.potner.diary.application.DiaryGenerationService;
import com.potner.diary.application.DiaryScheduler;
import com.potner.diary.application.DiaryService;
import com.potner.diary.application.DiaryWriteResult;
import com.potner.mqtt.application.LoggingRobotCommandPublisher;
import com.potner.mqtt.application.RobotCommandPublisher;
import com.potner.mqtt.application.SensorTelemetryMessageProcessor;
import com.potner.device.application.HeartbeatUpdateResult;
import com.potner.device.application.IotDeviceHeartbeatService;
import com.potner.mqtt.dto.SensorTelemetryMessage;
import com.potner.push.application.AlertPushListener;
import com.potner.push.application.BloomPushListener;
import com.potner.push.application.FcmTokenService;
import com.potner.push.application.LoggingPushSender;
import com.potner.push.application.PushSender;
import com.potner.push.application.PushTargetResolver;
import com.potner.push.domain.FcmToken;
import com.potner.user.domain.NotificationCategory;
import com.potner.sensor.application.SensorReadingSaveResult;
import com.potner.sensor.application.SensorReadingService;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.domain.SensorUnit;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Testcontainers
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PotnerApplicationTests {

    private static final String BASIL_ID = "20000000-0000-0000-0000-000000000104";
    private static final String TOMATO_ID = "20000000-0000-0000-0000-000000000106";
    private static final String KALANDIVA_ID = "20000000-0000-0000-0000-000000000103";
    private static final String GERMINATION_ID = "10000000-0000-0000-0000-000000000005";
    private static final String FLOWERING_ID = "10000000-0000-0000-0000-000000000004";
    private static final String FRUITING_ID = "10000000-0000-0000-0000-000000000009";
    private static final String VEGETATIVE_ID = "10000000-0000-0000-0000-000000000006";
    private static final String TEST_INACTIVE_SPECIES_ID = "90000000-0000-0000-0000-000000000001";

    /** MQTT 페이로드는 오프셋을 포함한 ISO-8601 이어야 하므로 초까지 항상 채워 UTC 로 만든다. */
    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SensorReadingService sensorReadingService;

    @Autowired
    private SensorReadingRepository sensorReadingRepository;

    @Autowired
    private IotDeviceHeartbeatService heartbeatService;

    @Autowired
    private SensorTelemetryMessageProcessor telemetryProcessor;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private com.potner.light.application.DailyLightAggregationService dailyLightAggregationService;

    @Autowired
    private com.potner.light.domain.PlantDailyLightRepository plantDailyLightRepository;

    /**
     * 주입 자체가 검증이다. 스케줄러 빈이 만들어지지 않으면 @Scheduled 의 cron·zone 이
     * 검증되지 않은 채 배포로 넘어간다.
     */
    @Autowired
    private com.potner.light.application.DailyLightScheduler dailyLightScheduler;

    @Autowired
    private DiaryService diaryService;

    @Autowired
    private DiaryGenerationService diaryGenerationService;

    /**
     * 주입 자체가 검증이다. 빈이 만들어지지 않으면 @Scheduled 의 cron·zone 이 검증되지 않은 채
     * 배포로 넘어간다. 광량 스케줄러와 같은 이유다.
     */
    @Autowired
    private DiaryScheduler diaryScheduler;

    @Autowired
    private PushTargetResolver pushTargetResolver;

    @Autowired
    private FcmTokenService fcmTokenService;

    @Autowired
    private PushSender pushSender;

    @Autowired
    private RobotCommandPublisher robotCommandPublisher;

    /**
     * 주입 자체가 검증이다. 리스너 빈이 없으면 알림이 저장돼도 발송 경로가 아예 돌지 않는다.
     */
    @Autowired
    private AlertPushListener alertPushListener;

    /** 같은 이유다. 개화를 기록해도 리스너가 없으면 푸시가 나가지 않는다. */
    @Autowired
    private BloomPushListener bloomPushListener;

    @Autowired
    private Clock clock;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM fcm_token");
        jdbcTemplate.update("DELETE FROM user_notification_setting");
        jdbcTemplate.update("DELETE FROM alert");
        jdbcTemplate.update("DELETE FROM plant_daily_light");
        jdbcTemplate.update("DELETE FROM plant_bloom");
        jdbcTemplate.update("DELETE FROM plant_diary");
        jdbcTemplate.update("DELETE FROM plant_photo");
        jdbcTemplate.update("DELETE FROM sensor_reading");
        jdbcTemplate.update("DELETE FROM plant_device_assignment");
        jdbcTemplate.update("DELETE FROM iot_device");
        jdbcTemplate.update("DELETE FROM robot");
        jdbcTemplate.update("DELETE FROM plant_growth_profile");
        jdbcTemplate.update("DELETE FROM plant");
        refreshTokenRepository.deleteAll();
        appUserRepository.deleteAll();
    }

    @AfterEach
    void removeTestReferenceData() {
        jdbcTemplate.update("DELETE FROM plant_species WHERE species_id = ?", TEST_INACTIVE_SPECIES_ID);
    }

    @Test
    void healthApiReturnsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.service").value("potner"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void actuatorHealthIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void swaggerUiAndOpenApiDocsArePublic() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Potner Backend API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists());
    }

    @Test
    void signupStoresBcryptPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson("member@example.com", "password1", "potner")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("member@example.com"))
                .andExpect(jsonPath("$.nickname").value("potner"));

        AppUser user = appUserRepository.findByEmailIgnoreCase("member@example.com").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(user.getPasswordHash())
                .startsWith("$2")
                .isNotEqualTo("password1");
    }

    @Test
    void signupRejectsDuplicateEmail() throws Exception {
        signup("member@example.com");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson("MEMBER@example.com", "password1", "another")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void signupRejectsInvalidEmailAndPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson("invalid", "weak", "potner")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void loginIssuesAccessAndRefreshTokens() throws Exception {
        signup("member@example.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("member@example.com", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void loginRejectsUnknownUserWrongPasswordAndInactiveAccount() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("missing@example.com", "password1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));

        signup("member@example.com");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("member@example.com", "wrong-password1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));

        AppUser user = appUserRepository.findByEmailIgnoreCase("member@example.com").orElseThrow();
        user.suspend();
        appUserRepository.saveAndFlush(user);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("member@example.com", "password1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
    }

    @Test
    void reissueAcceptsRefreshTokenAndRejectsAccessToken() throws Exception {
        signup("member@example.com");
        Tokens tokens = login("member@example.com");

        mockMvc.perform(post("/api/v1/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(tokens.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").value(tokens.refreshToken()));

        mockMvc.perform(post("/api/v1/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(tokens.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        signup("member@example.com");
        Tokens tokens = login("member@example.com");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(tokens.refreshToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(tokens.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"));
    }

    @Test
    void meRequiresValidAccessToken() throws Exception {
        signup("member@example.com");
        Tokens tokens = login("member@example.com");

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("member@example.com"))
                .andExpect(jsonPath("$.nickname").value("potner"));
    }

    @Test
    void plantSpeciesRequiresAccessToken() throws Exception {
        mockMvc.perform(get("/api/v1/plant-species"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));
    }

    @Test
    void activePlantSpeciesAreSortedAndInactiveSpeciesIsExcluded() throws Exception {
        jdbcTemplate.update("""
                        INSERT INTO plant_species
                            (species_id, category_id, name, scientific_name, description, active)
                        VALUES (?, '20000000-0000-0000-0000-000000000001', '비활성테스트', NULL, NULL, 0)
                        """,
                TEST_INACTIVE_SPECIES_ID);
        Tokens tokens = signupAndLogin("plant-member@example.com");

        mockMvc.perform(get("/api/v1/plant-species")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.species.length()").value(6))
                .andExpect(jsonPath("$.species[0].name").value("미니해바라기"))
                .andExpect(jsonPath("$.species[1].name").value("바질"))
                .andExpect(jsonPath("$.species[?(@.name == '바질')]").isNotEmpty())
                .andExpect(jsonPath("$.species[*].scientificName").value(containsInAnyOrder(
                        "Helianthus annuus L.",
                        "Agastache rugosa (Fisch. & C.A.Mey.) Kuntze",
                        "Kalanchoe blossfeldiana Poelln.",
                        "Ocimum basilicum L.",
                        "Catharanthus roseus (L.) G.Don",
                        "Solanum lycopersicum L."
                )))
                .andExpect(jsonPath("$.species[?(@.name == '비활성테스트')]").isEmpty());
    }

    @Test
    void supportedSpeciesAreAssignedToConcreteCategories() {
        var categoryMappings = jdbcTemplate.queryForList("""
                SELECT CONCAT(pc.name, ':', ps.name)
                FROM plant_species ps
                JOIN plant_category pc ON pc.category_id = ps.category_id
                WHERE ps.active = 1
                """, String.class);

        org.assertj.core.api.Assertions.assertThat(categoryMappings).containsExactlyInAnyOrder(
                "관상·화훼:미니해바라기",
                "관상·화훼:칼란디바",
                "관상·화훼:일일초",
                "허브:배초향",
                "허브:바질",
                "과채류:방울토마토"
        );
    }

    @Test
    void growthStagesComeOnlyFromActiveRequirementsAndAreSorted() throws Exception {
        Tokens tokens = signupAndLogin("stage-member@example.com");

        mockMvc.perform(get("/api/v1/plant-species/{speciesId}/growth-stages", BASIL_ID)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speciesId").value(BASIL_ID))
                .andExpect(jsonPath("$.speciesName").value("바질"))
                .andExpect(jsonPath("$.growthStages.length()").value(4))
                .andExpect(jsonPath("$.growthStages[0].code").value("GERMINATION"))
                .andExpect(jsonPath("$.growthStages[0].sortOrder").value(5))
                .andExpect(jsonPath("$.growthStages[1].code").value("SEEDLING"))
                .andExpect(jsonPath("$.growthStages[2].code").value("VEGETATIVE"))
                .andExpect(jsonPath("$.growthStages[3].code").value("REGROWTH"))
                .andExpect(jsonPath("$.growthStages[?(@.code == 'FLOWERING')]").isEmpty());
    }

    @Test
    void growthStagesDifferPerSpeciesSoAppMustNotHardcodeThem() throws Exception {
        Tokens tokens = signupAndLogin("stage-kalandiva@example.com");

        // 칼란디바는 삽목 번식이라 발아기가 없고, 단일식물이라 꽃눈형성기가 따로 있다.
        // 계층 조회(GET /plant-categories)가 싣는 자람 수준도 같은 쿼리·정렬을 쓴다.
        mockMvc.perform(get("/api/v1/plant-species/{speciesId}/growth-stages", KALANDIVA_ID)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speciesName").value("칼란디바"))
                .andExpect(jsonPath("$.growthStages.length()").value(4))
                .andExpect(jsonPath("$.growthStages[0].code").value("SEEDLING"))
                .andExpect(jsonPath("$.growthStages[1].code").value("VEGETATIVE"))
                .andExpect(jsonPath("$.growthStages[2].code").value("BUD_FORMATION"))
                .andExpect(jsonPath("$.growthStages[3].code").value("FLOWERING"))
                .andExpect(jsonPath("$.growthStages[?(@.code == 'GERMINATION')]").isEmpty());
    }

    @Test
    void missingPlantSpeciesReturnsNotFound() throws Exception {
        Tokens tokens = signupAndLogin("missing-species@example.com");

        mockMvc.perform(get("/api/v1/plant-species/{speciesId}/growth-stages", "missing-species")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_SPECIES_NOT_FOUND"));
    }

    @Test
    void growthRequirementReturnsSeedValuesAndNullsWithoutCalculation() throws Exception {
        Tokens tokens = signupAndLogin("requirement-member@example.com");

        mockMvc.perform(get(
                        "/api/v1/plant-species/{speciesId}/growth-stages/{lifeStageId}/requirement",
                        BASIL_ID,
                        GERMINATION_ID)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speciesName").value("바질"))
                .andExpect(jsonPath("$.lifeStageCode").value("GERMINATION"))
                .andExpect(jsonPath("$.soilMoisture.minPct").value(40.0))
                .andExpect(jsonPath("$.soilMoisture.maxPct").value(55.0))
                .andExpect(jsonPath("$.watering.triggerPct").value(40.0))
                .andExpect(jsonPath("$.watering.cycleDays").value(1.0))
                .andExpect(jsonPath("$.watering.recommendedVolumeMl").value(nullValue()))
                .andExpect(jsonPath("$.temperature.minC").value(21.0))
                .andExpect(jsonPath("$.temperature.maxC").value(29.0))
                .andExpect(jsonPath("$.humidity.minPct").value(60.0))
                .andExpect(jsonPath("$.humidity.maxPct").value(80.0))
                .andExpect(jsonPath("$.illuminance.minLux").value(nullValue()))
                .andExpect(jsonPath("$.illuminance.maxLux").value(nullValue()))
                .andExpect(jsonPath("$.illuminance.targetLux").value(10000.0))
                // V7 이 목표값 대비 비율로 허용 범위를 채웠다. 순간 조도는 여전히 비어 있다.
                .andExpect(jsonPath("$.dailyLight.minLuxHour").value(105000.0))
                .andExpect(jsonPath("$.dailyLight.maxLuxHour").value(195000.0))
                .andExpect(jsonPath("$.dailyLight.targetLuxHour").value(150000.0))
                // 앱은 lux·h 대신 이 비율을 보여준다. V7 의 0.70 / 1.30 이 그대로 드러나야 한다.
                .andExpect(jsonPath("$.dailyLight.minPctOfTarget").value(70.0))
                .andExpect(jsonPath("$.dailyLight.maxPctOfTarget").value(130.0))
                .andExpect(jsonPath("$.photoperiodHours").value(15.0))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.sourceUrl").value(nullValue()));
    }

    @Test
    void decimalWateringCycleIsPreserved() throws Exception {
        Tokens tokens = signupAndLogin("decimal-member@example.com");

        mockMvc.perform(get(
                        "/api/v1/plant-species/{speciesId}/growth-stages/{lifeStageId}/requirement",
                        TOMATO_ID,
                        FRUITING_ID)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speciesName").value("방울토마토"))
                .andExpect(jsonPath("$.lifeStageCode").value("FRUITING"))
                .andExpect(jsonPath("$.watering.cycleDays").value(0.67))
                .andExpect(jsonPath("$.watering.recommendedVolumeMl").value(nullValue()));
    }

    @Test
    void unsupportedSpeciesAndStageCombinationReturnsNotFound() throws Exception {
        Tokens tokens = signupAndLogin("unsupported-stage@example.com");

        mockMvc.perform(get(
                        "/api/v1/plant-species/{speciesId}/growth-stages/{lifeStageId}/requirement",
                        BASIL_ID,
                        FLOWERING_ID)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROWTH_REQUIREMENT_NOT_FOUND"));
    }

    @Test
    void plantManagementRequiresAccessToken() throws Exception {
        mockMvc.perform(get("/api/v1/plants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));
    }

    @Test
    void plantManagementLifecycleUsesSnapshotAndSoftDelete() throws Exception {
        Tokens tokens = signupAndLogin("plant-owner@example.com");
        String createdBody = mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlantJson(BASIL_ID, GERMINATION_ID, "내 바질")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("내 바질"))
                .andExpect(jsonPath("$.species.name").value("바질"))
                .andExpect(jsonPath("$.species.scientificName").value("Ocimum basilicum L."))
                .andExpect(jsonPath("$.category.name").value("허브"))
                .andExpect(jsonPath("$.lifeStage.code").value("GERMINATION"))
                .andExpect(jsonPath("$.growthProfile.temperature.minC").value(21.0))
                .andExpect(jsonPath("$.growthProfile.watering.recommendedVolumeMl").value(nullValue()))
                .andExpect(jsonPath("$.growthProfile.customized").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String plantId = JsonPath.read(createdBody, "$.plantId");

        String ownerId = appUserRepository.findByEmailIgnoreCase("plant-owner@example.com").orElseThrow().getId();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM plant WHERE plant_id = ?", String.class, plantId)).isEqualTo(ownerId);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plant_growth_profile WHERE plant_id = ?", Integer.class, plantId)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plants.length()").value(1))
                .andExpect(jsonPath("$.plants[0].plantId").value(plantId))
                .andExpect(jsonPath("$.plants[0].categoryName").value("허브"));

        mockMvc.perform(patch("/api/v1/plants/{plantId}/growth-profile", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"temperatureMinC":22.0,"recommendedWateringMl":120.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature.minC").value(22.0))
                .andExpect(jsonPath("$.temperature.maxC").value(29.0))
                .andExpect(jsonPath("$.watering.recommendedVolumeMl").value(120.0))
                .andExpect(jsonPath("$.customized").value(true));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT temperature_min_c FROM species_growth_requirement WHERE requirement_id = '30000000-0000-0000-0000-000000000013'",
                java.math.BigDecimal.class)).isEqualByComparingTo("21.00");

        mockMvc.perform(post("/api/v1/plants/{plantId}/growth-profile/reset", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature.minC").value(21.0))
                .andExpect(jsonPath("$.watering.recommendedVolumeMl").value(nullValue()))
                .andExpect(jsonPath("$.customized").value(false));

        mockMvc.perform(patch("/api/v1/plants/{plantId}/growth-stage", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lifeStageId":"%s"}
                                """.formatted(VEGETATIVE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifeStage.code").value("VEGETATIVE"))
                .andExpect(jsonPath("$.growthProfile.temperature.minC").value(16.0))
                .andExpect(jsonPath("$.growthProfile.temperature.maxC").value(30.0))
                .andExpect(jsonPath("$.growthProfile.customized").value(false));

        mockMvc.perform(patch("/api/v1/plants/{plantId}", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"새싹 바질"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새싹 바질"))
                .andExpect(jsonPath("$.lifeStage.code").value("VEGETATIVE"));

        mockMvc.perform(delete("/api/v1/plants/{plantId}", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/plants/{plantId}", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plants.length()").value(0));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT plant_status FROM plant WHERE plant_id = ?", String.class, plantId)).isEqualTo("DELETED");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plant_growth_profile WHERE plant_id = ?", Integer.class, plantId)).isEqualTo(1);
    }

    @Test
    void plantManagementRejectsUnsupportedReferencesAndInvalidName() throws Exception {
        Tokens tokens = signupAndLogin("invalid-plant@example.com");

        mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlantJson("missing-species", GERMINATION_ID, "식물")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_SPECIES_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlantJson(BASIL_ID, "missing-stage", "식물")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_LIFE_STAGE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlantJson(BASIL_ID, FLOWERING_ID, "식물")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROWTH_REQUIREMENT_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlantJson(BASIL_ID, GERMINATION_ID, "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void plantOwnershipIsHiddenAndGrowthProfileValidationIsApplied() throws Exception {
        Tokens owner = signupAndLogin("owner@example.com");
        String createdBody = mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlantJson(BASIL_ID, GERMINATION_ID, "소유 식물")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String plantId = JsonPath.read(createdBody, "$.plantId");
        Tokens stranger = signupAndLogin("stranger@example.com");

        mockMvc.perform(get("/api/v1/plants/{plantId}", plantId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
        mockMvc.perform(patch("/api/v1/plants/{plantId}/growth-profile", plantId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"humidityMinPct":90,"humidityMaxPct":80}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GROWTH_PROFILE"));
        mockMvc.perform(patch("/api/v1/plants/{plantId}/growth-profile", plantId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recommendedWateringMl":100}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watering.recommendedVolumeMl").value(100.0));
        mockMvc.perform(patch("/api/v1/plants/{plantId}/growth-profile", plantId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recommendedWateringMl":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watering.recommendedVolumeMl").value(nullValue()));
    }

    @Test
    void raspberryAndJetsonReadingsUseSameRobotAndPlantWithDistinctSourceDevices()
            throws Exception {
        SensorFixture fixture = createSensorFixture("sensor-member@example.com");
        List<SensorTelemetryMessage> messages = List.of(
                sensorMessage(
                        "550e8400-e29b-41d4-a716-446655440000",
                        fixture.raspberryUid(),
                        SensorType.TEMPERATURE,
                        "24.3",
                        SensorUnit.CELSIUS
                ),
                sensorMessage(
                        "550e8400-e29b-41d4-a716-446655440001",
                        fixture.raspberryUid(),
                        SensorType.HUMIDITY,
                        "58.1",
                        SensorUnit.PERCENT
                ),
                sensorMessage(
                        "550e8400-e29b-41d4-a716-446655440002",
                        fixture.raspberryUid(),
                        SensorType.ILLUMINANCE,
                        "1350",
                        SensorUnit.LUX
                ),
                sensorMessage(
                        "550e8400-e29b-41d4-a716-446655440003",
                        fixture.jetsonUid(),
                        SensorType.SOIL_MOISTURE,
                        "42.7",
                        SensorUnit.PERCENT
                )
        );

        messages.forEach(message -> org.assertj.core.api.Assertions.assertThat(
                sensorReadingService.save(message)
        ).isEqualTo(SensorReadingSaveResult.SAVED));

        org.assertj.core.api.Assertions.assertThat(sensorReadingRepository.findAll())
                .hasSize(4)
                .allSatisfy(reading -> {
                    org.assertj.core.api.Assertions.assertThat(reading.getPlantId())
                            .isEqualTo(fixture.plantId());
                    org.assertj.core.api.Assertions.assertThat(reading.getRobotId())
                            .isEqualTo(fixture.robotId());
                    org.assertj.core.api.Assertions.assertThat(reading.getQuality().name())
                            .isEqualTo("GOOD");
                    org.assertj.core.api.Assertions.assertThat(reading.getMeasuredAt())
                            .isEqualTo(LocalDateTime.parse("2026-07-22T08:10:00"));
                });
        org.assertj.core.api.Assertions.assertThat(sensorReadingRepository.findAll())
                .filteredOn(reading -> reading.getSensorType() == SensorType.SOIL_MOISTURE)
                .extracting(reading -> reading.getSourceDeviceId())
                .containsOnly(fixture.jetsonDeviceId());
        org.assertj.core.api.Assertions.assertThat(sensorReadingRepository.findAll())
                .filteredOn(reading -> reading.getSensorType() != SensorType.SOIL_MOISTURE)
                .extracting(reading -> reading.getSourceDeviceId())
                .containsOnly(fixture.raspberryDeviceId());
    }

    @Test
    void sequentialDuplicateMessageStoresOneReading() throws Exception {
        SensorFixture fixture = createSensorFixture("sequential-duplicate@example.com");
        SensorTelemetryMessage message = sensorMessage(
                "550e8400-e29b-41d4-a716-446655440010",
                fixture.raspberryUid(),
                SensorType.TEMPERATURE,
                "24.3",
                SensorUnit.CELSIUS
        );

        org.assertj.core.api.Assertions.assertThat(sensorReadingService.save(message))
                .isEqualTo(SensorReadingSaveResult.SAVED);
        org.assertj.core.api.Assertions.assertThat(sensorReadingService.save(message))
                .isEqualTo(SensorReadingSaveResult.DUPLICATE);
        org.assertj.core.api.Assertions.assertThat(
                sensorReadingRepository.countByDeviceMessageId(message.messageId().toString())
        ).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT index_name
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'sensor_reading'
                      AND non_unique = 0
                      AND index_name <> 'PRIMARY'
                    GROUP BY index_name
                    HAVING COUNT(*) = 1
                       AND MAX(column_name = 'device_message_id') = 1
                ) matching_unique_indexes
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateMessageStoresOnlyOneReading() throws Exception {
        SensorFixture fixture = createSensorFixture("concurrent-sensor@example.com");
        SensorTelemetryMessage message = sensorMessage(
                "550e8400-e29b-41d4-a716-446655440011",
                fixture.raspberryUid(),
                SensorType.HUMIDITY,
                "58.1",
                SensorUnit.PERCENT
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<SensorReadingSaveResult>> futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return sensorReadingService.save(message);
                    }),
                    executor.submit(() -> {
                        start.await();
                        return sensorReadingService.save(message);
                    })
            );
            start.countDown();

            org.assertj.core.api.Assertions.assertThat(
                    List.of(futures.get(0).get(), futures.get(1).get())
            ).containsExactlyInAnyOrder(
                    SensorReadingSaveResult.SAVED,
                    SensorReadingSaveResult.DUPLICATE
            );
        } finally {
            executor.shutdownNow();
        }

        org.assertj.core.api.Assertions.assertThat(
                sensorReadingRepository.countByDeviceMessageId(message.messageId().toString())
        ).isEqualTo(1);
    }

    @Test
    void concurrentRaspberryAndJetsonMessagesAreBothStored() throws Exception {
        SensorFixture fixture = createSensorFixture("concurrent-devices@example.com");
        SensorTelemetryMessage raspberryMessage = sensorMessage(
                "550e8400-e29b-41d4-a716-446655440012",
                fixture.raspberryUid(),
                SensorType.ILLUMINANCE,
                "1350",
                SensorUnit.LUX
        );
        SensorTelemetryMessage jetsonMessage = sensorMessage(
                "550e8400-e29b-41d4-a716-446655440013",
                fixture.jetsonUid(),
                SensorType.SOIL_MOISTURE,
                "42.7",
                SensorUnit.PERCENT
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<SensorReadingSaveResult>> futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return sensorReadingService.save(raspberryMessage);
                    }),
                    executor.submit(() -> {
                        start.await();
                        return sensorReadingService.save(jetsonMessage);
                    })
            );
            start.countDown();

            org.assertj.core.api.Assertions.assertThat(
                    List.of(futures.get(0).get(), futures.get(1).get())
            ).containsOnly(SensorReadingSaveResult.SAVED);
        } finally {
            executor.shutdownNow();
        }

        org.assertj.core.api.Assertions.assertThat(sensorReadingRepository.count()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(sensorReadingRepository.findAll())
                .extracting(
                        reading -> reading.getSourceDeviceId(),
                        reading -> reading.getSensorType()
                )
                .containsExactlyInAnyOrder(
                        tuple(fixture.raspberryDeviceId(), SensorType.ILLUMINANCE),
                        tuple(fixture.jetsonDeviceId(), SensorType.SOIL_MOISTURE)
                );
    }

    @Test
    void raspberryAndJetsonHeartbeatsUpdateIndependentDeviceStates() throws Exception {
        SensorFixture fixture = createSensorFixture("heartbeat-devices@example.com");

        org.assertj.core.api.Assertions.assertThat(
                heartbeatService.recordHeartbeat(fixture.raspberryUid())
        ).isEqualTo(HeartbeatUpdateResult.UPDATED);
        assertDeviceState(fixture.raspberryUid(), "ONLINE", true);
        assertDeviceState(fixture.jetsonUid(), "OFFLINE", false);

        LocalDateTime oldSeenAt = LocalDateTime.ofInstant(
                clock.instant().minusSeconds(10),
                ZoneOffset.UTC
        );
        jdbcTemplate.update("""
                UPDATE iot_device
                   SET last_seen_at = ?
                 WHERE device_uid = ?
                """, oldSeenAt, fixture.raspberryUid());
        org.assertj.core.api.Assertions.assertThat(
                heartbeatService.recordHeartbeat(fixture.raspberryUid())
        ).isEqualTo(HeartbeatUpdateResult.UPDATED);
        org.assertj.core.api.Assertions.assertThat(deviceLastSeenAt(fixture.raspberryUid()))
                .isAfter(oldSeenAt);

        org.assertj.core.api.Assertions.assertThat(
                heartbeatService.recordHeartbeat(fixture.jetsonUid())
        ).isEqualTo(HeartbeatUpdateResult.UPDATED);
        assertDeviceState(fixture.raspberryUid(), "ONLINE", true);
        assertDeviceState(fixture.jetsonUid(), "ONLINE", true);
    }

    @Test
    void timedOutOnlineDeviceBecomesOfflineWithoutUpdatingOtherDevices()
            throws Exception {
        SensorFixture fixture = createSensorFixture("heartbeat-timeout@example.com");
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusSeconds(90);
        jdbcTemplate.update("""
                UPDATE iot_device
                   SET connection_status = 'ONLINE',
                       last_seen_at = ?
                 WHERE device_uid = ?
                """, cutoff.minusSeconds(1), fixture.raspberryUid());
        jdbcTemplate.update("""
                UPDATE iot_device
                   SET connection_status = 'ONLINE',
                       last_seen_at = ?
                 WHERE device_uid = ?
                """, cutoff.plusSeconds(1), fixture.jetsonUid());

        org.assertj.core.api.Assertions.assertThat(
                heartbeatService.markTimedOutDevicesOffline(cutoff)
        ).isEqualTo(1);
        assertDeviceState(fixture.raspberryUid(), "OFFLINE", true);
        assertDeviceState(fixture.jetsonUid(), "ONLINE", true);

        org.assertj.core.api.Assertions.assertThat(
                heartbeatService.markTimedOutDevicesOffline(cutoff)
        ).isZero();
    }

    @Test
    void concurrentRaspberryAndJetsonHeartbeatsRemainIndependent() throws Exception {
        SensorFixture fixture = createSensorFixture("heartbeat-concurrent@example.com");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<HeartbeatUpdateResult>> futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return heartbeatService.recordHeartbeat(fixture.raspberryUid());
                    }),
                    executor.submit(() -> {
                        start.await();
                        return heartbeatService.recordHeartbeat(fixture.jetsonUid());
                    })
            );
            start.countDown();

            org.assertj.core.api.Assertions.assertThat(
                    List.of(futures.get(0).get(), futures.get(1).get())
            ).containsOnly(HeartbeatUpdateResult.UPDATED);
        } finally {
            executor.shutdownNow();
        }

        assertDeviceState(fixture.raspberryUid(), "ONLINE", true);
        assertDeviceState(fixture.jetsonUid(), "ONLINE", true);
    }

    @Test
    void unknownHeartbeatDeviceIsIgnored() {
        org.assertj.core.api.Assertions.assertThat(
                heartbeatService.recordHeartbeat("unknown-device")
        ).isEqualTo(HeartbeatUpdateResult.DEVICE_NOT_FOUND);
    }

    @Test
    void currentSensorsAreJudgedAgainstAppliedGrowthProfile() throws Exception {
        SensorFixture fixture = createSensorFixture("sensor-current@example.com");
        Tokens tokens = login("sensor-current@example.com");
        LocalDateTime fresh = nowUtc().minusMinutes(1);
        insertReading(fixture, SensorType.TEMPERATURE, "24.00", SensorUnit.CELSIUS, fresh);
        insertReading(fixture, SensorType.HUMIDITY, "85.00", SensorUnit.PERCENT, fresh);
        insertReading(fixture, SensorType.SOIL_MOISTURE, "32.50", SensorUnit.PERCENT, fresh);
        insertReading(fixture, SensorType.ILLUMINANCE, "8200", SensorUnit.LUX, fresh);

        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/current", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plantId").value(fixture.plantId()))
                .andExpect(jsonPath("$.sensors.length()").value(4))
                .andExpect(jsonPath("$.sensors[0].sensorType").value("TEMPERATURE"))
                .andExpect(jsonPath("$.sensors[0].unit").value("CELSIUS"))
                .andExpect(jsonPath("$.sensors[0].status").value("NORMAL"))
                .andExpect(jsonPath("$.sensors[1].sensorType").value("HUMIDITY"))
                .andExpect(jsonPath("$.sensors[1].status").value("HIGH"))
                .andExpect(jsonPath("$.sensors[1].thresholdMax").value(80.0))
                .andExpect(jsonPath("$.sensors[2].sensorType").value("SOIL_MOISTURE"))
                .andExpect(jsonPath("$.sensors[2].status").value("LOW"))
                .andExpect(jsonPath("$.sensors[2].value").value(32.5))
                .andExpect(jsonPath("$.sensors[2].thresholdMin").value(40.0))
                .andExpect(jsonPath("$.sensors[2].thresholdMax").value(55.0))
                // 조도는 순간값으로 판정하지 않으므로 기준값도 비어 있다.
                .andExpect(jsonPath("$.sensors[3].sensorType").value("ILLUMINANCE"))
                .andExpect(jsonPath("$.sensors[3].unit").value("LUX"))
                .andExpect(jsonPath("$.sensors[3].status").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.sensors[3].thresholdMin").value(nullValue()))
                .andExpect(jsonPath("$.sensors[3].thresholdMax").value(nullValue()));

        Tokens stranger = signupAndLogin("sensor-stranger@example.com");
        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/current", fixture.plantId())
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/current", fixture.plantId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));
    }

    @Test
    void currentSensorsReportNoDataAndStaleReadings() throws Exception {
        SensorFixture fixture = createSensorFixture("sensor-stale@example.com");
        Tokens tokens = login("sensor-stale@example.com");

        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/current", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensors.length()").value(4))
                .andExpect(jsonPath("$.sensors[0].status").value("NO_DATA"))
                .andExpect(jsonPath("$.sensors[0].value").value(nullValue()))
                .andExpect(jsonPath("$.sensors[0].measuredAt").value(nullValue()))
                // 측정값이 없어도 단위와 기준값은 채워 앱이 카드를 그릴 수 있게 한다.
                .andExpect(jsonPath("$.sensors[0].unit").value("CELSIUS"))
                .andExpect(jsonPath("$.sensors[0].thresholdMin").value(21.0));

        insertReading(
                fixture,
                SensorType.TEMPERATURE,
                "24.00",
                SensorUnit.CELSIUS,
                nowUtc().minusMinutes(60)
        );

        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/current", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensors[0].status").value("STALE"))
                .andExpect(jsonPath("$.sensors[0].value").value(24.0));
    }

    @Test
    void sensorHistoryAggregatesIntoServiceTimeZoneDayBuckets() throws Exception {
        SensorFixture fixture = createSensorFixture("sensor-history@example.com");
        Tokens tokens = login("sensor-history@example.com");
        // 한국 시간으로는 07-25 23:00, 07-26 00:30, 07-26 01:00 이다.
        // UTC 기준으로 하루를 끊으면 세 건이 모두 07-25 로 묶여 버린다.
        insertReading(fixture, SensorType.TEMPERATURE, "20.00", SensorUnit.CELSIUS,
                LocalDateTime.parse("2026-07-25T14:00:00"));
        insertReading(fixture, SensorType.TEMPERATURE, "22.00", SensorUnit.CELSIUS,
                LocalDateTime.parse("2026-07-25T15:30:00"));
        insertReading(fixture, SensorType.TEMPERATURE, "26.00", SensorUnit.CELSIUS,
                LocalDateTime.parse("2026-07-25T16:00:00"));

        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/history", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("sensorType", "TEMPERATURE")
                        .param("from", "2026-07-24T15:00:00Z")
                        .param("to", "2026-07-26T15:00:00Z")
                        .param("interval", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorType").value("TEMPERATURE"))
                .andExpect(jsonPath("$.unit").value("CELSIUS"))
                .andExpect(jsonPath("$.interval").value("DAY"))
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[0].bucketAt").value(startsWith("2026-07-24T15:00")))
                .andExpect(jsonPath("$.points[0].averageValue").value(20.0))
                .andExpect(jsonPath("$.points[0].sampleCount").value(1))
                .andExpect(jsonPath("$.points[1].bucketAt").value(startsWith("2026-07-25T15:00")))
                .andExpect(jsonPath("$.points[1].averageValue").value(24.0))
                .andExpect(jsonPath("$.points[1].minimumValue").value(22.0))
                .andExpect(jsonPath("$.points[1].maximumValue").value(26.0))
                .andExpect(jsonPath("$.points[1].sampleCount").value(2));

        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/history", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("sensorType", "TEMPERATURE")
                        .param("from", "2026-07-25T15:00:00Z")
                        .param("to", "2026-07-25T17:00:00Z")
                        .param("interval", "HOUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[0].bucketAt").value(startsWith("2026-07-25T15:00")))
                .andExpect(jsonPath("$.points[0].averageValue").value(22.0))
                .andExpect(jsonPath("$.points[1].bucketAt").value(startsWith("2026-07-25T16:00")))
                .andExpect(jsonPath("$.points[1].averageValue").value(26.0));
    }

    @Test
    void sensorHistoryRejectsInvalidRangeAndParameters() throws Exception {
        SensorFixture fixture = createSensorFixture("sensor-history-invalid@example.com");
        Tokens tokens = login("sensor-history-invalid@example.com");

        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/history", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("sensorType", "TEMPERATURE")
                        .param("from", "2026-07-26T00:00:00Z")
                        .param("to", "2026-07-26T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SENSOR_QUERY_RANGE"));

        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/history", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("sensorType", "TEMPERATURE")
                        .param("from", "2026-07-01T00:00:00Z")
                        .param("to", "2026-07-15T00:00:01Z")
                        .param("interval", "HOUR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SENSOR_QUERY_RANGE"));

        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/history", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("sensorType", "PRESSURE")
                        .param("from", "2026-07-26T00:00:00Z")
                        .param("to", "2026-07-27T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/history", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("sensorType", "TEMPERATURE")
                        .param("from", "2026-07-26T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 오프셋이 없는 시각은 UTC 로 단정하지 않고 거부한다.
        mockMvc.perform(get("/api/v1/plants/{plantId}/sensors/history", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("sensorType", "TEMPERATURE")
                        .param("from", "2026-07-26T00:00:00")
                        .param("to", "2026-07-27T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void sensorAlertFollowsTransitionRulesOverMqttPipeline() throws Exception {
        SensorFixture fixture = createSensorFixture("alert-lifecycle@example.com");
        LocalDateTime base = nowUtc().minusMinutes(2);

        // 표본 3건이 모이기 전에는 판정하지 않는다.
        publishSoilMoisture(fixture, "34.00", base);
        publishSoilMoisture(fixture, "33.00", base.plusSeconds(5));
        org.assertj.core.api.Assertions.assertThat(alertRepository.findAll()).isEmpty();

        // 중앙값 34 가 하한 40 을 밑돌아 LOW 를 생성한다.
        publishSoilMoisture(fixture, "35.00", base.plusSeconds(10));
        Alert created = alertRepository.findAll().getFirst();
        org.assertj.core.api.Assertions.assertThat(created.getMetricType())
                .isEqualTo(AlertMetricType.SOIL_MOISTURE);
        org.assertj.core.api.Assertions.assertThat(created.getDeviation())
                .isEqualTo(AlertDeviation.LOW);
        org.assertj.core.api.Assertions.assertThat(created.getThresholdMin())
                .isEqualByComparingTo("40.00");
        org.assertj.core.api.Assertions.assertThat(created.isActive()).isTrue();

        // 이상이 계속되어도 새 Alert 를 만들지 않는다.
        publishSoilMoisture(fixture, "32.00", base.plusSeconds(15));
        org.assertj.core.api.Assertions.assertThat(alertRepository.count()).isEqualTo(1);

        // 하한은 넘겼지만 복귀 기준 41.5 에 못 미치면 활성 상태를 유지한다.
        publishSoilMoisture(fixture, "41.00", base.plusSeconds(20));
        publishSoilMoisture(fixture, "40.60", base.plusSeconds(25));
        publishSoilMoisture(fixture, "41.20", base.plusSeconds(30));
        org.assertj.core.api.Assertions.assertThat(activeAlertCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(alertRepository.count()).isEqualTo(1);

        // 복귀 기준을 넘기면 해제한다.
        publishSoilMoisture(fixture, "43.00", base.plusSeconds(35));
        publishSoilMoisture(fixture, "42.00", base.plusSeconds(40));
        publishSoilMoisture(fixture, "44.00", base.plusSeconds(45));
        org.assertj.core.api.Assertions.assertThat(activeAlertCount()).isZero();
        org.assertj.core.api.Assertions.assertThat(
                alertRepository.findAll().getFirst().getResolvedAt()).isNotNull();
        // 해제되면 파생 키가 비워져 다음 Alert 를 만들 수 있다.
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert WHERE active_key IS NULL", Integer.class)).isEqualTo(1);

        // 다시 이상해지면 새 Alert 가 생긴다.
        publishSoilMoisture(fixture, "30.00", base.plusSeconds(50));
        publishSoilMoisture(fixture, "31.00", base.plusSeconds(55));
        publishSoilMoisture(fixture, "29.00", base.plusSeconds(60));
        org.assertj.core.api.Assertions.assertThat(alertRepository.count()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(activeAlertCount()).isEqualTo(1);
    }

    @Test
    void spikeAndIlluminanceDoNotCreateAlerts() throws Exception {
        SensorFixture fixture = createSensorFixture("alert-spike@example.com");
        LocalDateTime base = nowUtc().minusMinutes(2);

        // 표본 하나만 튀어도 중앙값은 정상 범위에 남는다.
        publishSoilMoisture(fixture, "45.00", base);
        publishSoilMoisture(fixture, "44.00", base.plusSeconds(5));
        publishSoilMoisture(fixture, "2.00", base.plusSeconds(10));
        org.assertj.core.api.Assertions.assertThat(alertRepository.findAll()).isEmpty();

        // 조도는 순간값으로 판정하지 않는다.
        for (int index = 0; index < 3; index++) {
            telemetryProcessor.process(
                    "potner/device/" + fixture.raspberryUid() + "/sensor/telemetry",
                    telemetryJson(
                            fixture.raspberryUid(),
                            SensorType.ILLUMINANCE,
                            "0",
                            SensorUnit.LUX,
                            base.plusSeconds(20L + index * 5)
                    )
            );
        }
        org.assertj.core.api.Assertions.assertThat(alertRepository.findAll()).isEmpty();
    }

    @Test
    void databaseRejectsSecondActiveAlertForSamePlantAndMetric() throws Exception {
        SensorFixture fixture = createSensorFixture("alert-unique@example.com");
        String userId = appUserRepository.findByEmailIgnoreCase("alert-unique@example.com")
                .orElseThrow()
                .getId();

        insertActiveAlert(userId, fixture.plantId());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> insertActiveAlert(userId, fixture.plantId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThat(activeAlertCount()).isEqualTo(1);
    }

    @Test
    void alertInboxIsFilteredAndScopedToOwner() throws Exception {
        SensorFixture fixture = createSensorFixture("alert-inbox@example.com");
        Tokens tokens = login("alert-inbox@example.com");
        String userId = appUserRepository.findByEmailIgnoreCase("alert-inbox@example.com")
                .orElseThrow()
                .getId();
        LocalDateTime base = nowUtc().minusMinutes(3);
        // 활성 Alert는 식물·지표별로 1건만 존재할 수 있으므로 지표를 나눠 만든다.
        insertAlert(userId, fixture.plantId(), "SOIL_MOISTURE", base, false, false);
        insertAlert(userId, fixture.plantId(), "TEMPERATURE", base.plusMinutes(1), false, true);
        insertAlert(userId, fixture.plantId(), "HUMIDITY", base.plusMinutes(2), true, false);

        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.size").value(20))
                // 만들어진 시각 기준 최신순이다.
                .andExpect(jsonPath("$.alerts[0].metricType").value("HUMIDITY"))
                .andExpect(jsonPath("$.alerts[0].active").value(false))
                .andExpect(jsonPath("$.alerts[1].metricType").value("TEMPERATURE"))
                .andExpect(jsonPath("$.alerts[1].read").value(true))
                .andExpect(jsonPath("$.alerts[2].metricType").value("SOIL_MOISTURE"))
                .andExpect(jsonPath("$.alerts[2].plantName").value("센서 테스트 식물"))
                .andExpect(jsonPath("$.alerts[2].deviation").value("LOW"))
                .andExpect(jsonPath("$.alerts[2].thresholdMin").value(40.0))
                .andExpect(jsonPath("$.alerts[2].active").value(true));

        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("unreadOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(2))
                .andExpect(jsonPath("$.alerts[*].metricType")
                        .value(containsInAnyOrder("HUMIDITY", "SOIL_MOISTURE")));

        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("activeOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(2))
                .andExpect(jsonPath("$.alerts[*].metricType")
                        .value(containsInAnyOrder("TEMPERATURE", "SOIL_MOISTURE")));

        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("unreadOnly", "true")
                        .param("activeOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].metricType").value("SOIL_MOISTURE"));

        // 상한을 넘긴 size 는 줄여서 적용하고 실제 값을 응답에 담는다.
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        Tokens stranger = signupAndLogin("alert-stranger@example.com");
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0));
        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));
    }

    @Test
    void alertReadProcessingIsIdempotentAndOwnershipChecked() throws Exception {
        SensorFixture fixture = createSensorFixture("alert-read@example.com");
        Tokens tokens = login("alert-read@example.com");
        String userId = appUserRepository.findByEmailIgnoreCase("alert-read@example.com")
                .orElseThrow()
                .getId();
        insertAlert(userId, fixture.plantId(), "SOIL_MOISTURE", nowUtc(), false, false);
        String alertId = jdbcTemplate.queryForObject(
                "SELECT alert_id FROM alert WHERE user_id = ?", String.class, userId);

        mockMvc.perform(patch("/api/v1/alerts/{alertId}/read", alertId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());
        LocalDateTime firstReadAt = jdbcTemplate.queryForObject(
                "SELECT read_at FROM alert WHERE alert_id = ?", LocalDateTime.class, alertId);
        org.assertj.core.api.Assertions.assertThat(firstReadAt).isNotNull();

        mockMvc.perform(patch("/api/v1/alerts/{alertId}/read", alertId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                        "SELECT read_at FROM alert WHERE alert_id = ?", LocalDateTime.class, alertId))
                .isEqualTo(firstReadAt);

        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.alerts[0].read").value(true));

        Tokens stranger = signupAndLogin("alert-read-stranger@example.com");
        mockMvc.perform(patch("/api/v1/alerts/{alertId}/read", alertId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALERT_NOT_FOUND"));
    }

    @Test
    void plantCategoryTreeGroupsSpeciesForRegistrationDropdowns() throws Exception {
        Tokens tokens = signupAndLogin("category-tree@example.com");

        mockMvc.perform(get("/api/v1/plant-categories")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                // 루트 분류(Potner 지원 식물)는 노출하지 않고 sortOrder 순으로 정렬한다.
                .andExpect(jsonPath("$.categories.length()").value(3))
                .andExpect(jsonPath("$.categories[0].name").value("관상·화훼"))
                .andExpect(jsonPath("$.categories[0].species.length()").value(3))
                .andExpect(jsonPath("$.categories[0].species[*].name")
                        .value(containsInAnyOrder("미니해바라기", "일일초", "칼란디바")))
                .andExpect(jsonPath("$.categories[1].name").value("허브"))
                .andExpect(jsonPath("$.categories[1].species[*].name")
                        .value(containsInAnyOrder("바질", "배초향")))
                .andExpect(jsonPath("$.categories[2].name").value("과채류"))
                .andExpect(jsonPath("$.categories[2].species.length()").value(1))
                .andExpect(jsonPath("$.categories[2].species[0].name").value("방울토마토"))
                // 자람 수준을 함께 실어 등록 화면이 한 번의 조회로 세 드롭다운을 채운다.
                // 과채류는 종이 하나라 인덱스가 정렬에 의존하지 않는다. sortOrder 순이어야 한다.
                .andExpect(jsonPath("$.categories[2].species[0].growthStages[*].code")
                        .value(contains(
                                "GERMINATION", "SEEDLING", "VEGETATIVE", "FLOWERING", "FRUITING")))
                // 종마다 단계가 달라 앱이 하드코딩하면 안 된다는 것이 계층 전체에서 드러나야 한다.
                // 재생장기는 바질만, 꽃눈형성기는 칼란디바만, 결실기는 방울토마토만 가진다.
                // 종 정렬 순서에 기대지 않도록 계층 전체를 펼쳐서 확인한다.
                .andExpect(jsonPath("$..growthStages..code")
                        .value(hasItems("REGROWTH", "BUD_FORMATION", "FRUITING")));

        mockMvc.perform(get("/api/v1/plant-categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));
    }

    @Test
    void growthStagesCarryDescriptionsAndVaryBySpecies() throws Exception {
        Tokens tokens = signupAndLogin("stage-description@example.com");

        // 바질은 개화기가 없고 재생장기가 있다. 잎을 먹는 허브라 개화 전에 수확하기 때문이다.
        mockMvc.perform(get("/api/v1/plant-species/{speciesId}/growth-stages", BASIL_ID)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.growthStages[*].code")
                        .value(containsInAnyOrder("GERMINATION", "SEEDLING", "VEGETATIVE", "REGROWTH")))
                .andExpect(jsonPath("$.growthStages[0].description")
                        .value("씨앗에서 싹이 트는 시기"))
                .andExpect(jsonPath("$.growthStages[3].description")
                        .value("수확한 뒤 잎과 줄기를 다시 키우는 시기"));

        // 방울토마토는 결실기가 더 있다.
        mockMvc.perform(get("/api/v1/plant-species/{speciesId}/growth-stages", TOMATO_ID)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.growthStages.length()").value(5))
                .andExpect(jsonPath("$.growthStages[*].code")
                        .value(containsInAnyOrder(
                                "GERMINATION", "SEEDLING", "VEGETATIVE", "FLOWERING", "FRUITING")));
    }

    @Test
    void adoptedDateIsStoredOnCreateAndCanBeUpdated() throws Exception {
        Tokens tokens = signupAndLogin("adopted-date@example.com");

        String created = mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"speciesId":"%s","lifeStageId":"%s","name":"내 바질",
                                 "adoptedDate":"2026-03-20"}
                                """.formatted(BASIL_ID, GERMINATION_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adoptedDate").value("2026-03-20"))
                .andReturn().getResponse().getContentAsString();
        String plantId = JsonPath.read(created, "$.plantId");

        mockMvc.perform(get("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plants[0].adoptedDate").value("2026-03-20"));

        mockMvc.perform(patch("/api/v1/plants/{plantId}", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adoptedDate":"2026-04-01"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adoptedDate").value("2026-04-01"))
                .andExpect(jsonPath("$.name").value("내 바질"));

        // 데려온 날짜를 모를 수 있으므로 필수가 아니다.
        String withoutDate = mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlantJson(BASIL_ID, GERMINATION_ID, "날짜 없는 바질")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adoptedDate").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT adopted_date FROM plant WHERE plant_id = ?",
                java.sql.Date.class,
                (String) JsonPath.read(withoutDate, "$.plantId"))).isNull();
    }

    @Test
    void nicknameCanBeChangedAndIsTrimmed() throws Exception {
        Tokens tokens = signupAndLogin("nickname-change@example.com");

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"  새 닉네임  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새 닉네임"))
                .andExpect(jsonPath("$.email").value("nickname-change@example.com"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새 닉네임"));

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void passwordChangeRevokesRefreshTokensAndSwapsCredentials() throws Exception {
        signup("password-change@example.com");
        Tokens tokens = login("password-change@example.com");

        mockMvc.perform(patch("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordJson("wrong-password1", "new-password1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));

        mockMvc.perform(patch("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordJson("password1", "password1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"));

        // 회원가입과 같은 규칙을 적용한다.
        mockMvc.perform(patch("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordJson("password1", "weak")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.newPassword").exists());

        mockMvc.perform(patch("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordJson("password1", "new-password1")))
                .andExpect(status().isNoContent());

        // 유출된 Refresh Token 을 무효화하기 위해 전량 폐기한다.
        mockMvc.perform(post("/api/v1/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(tokens.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("password-change@example.com", "password1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("password-change@example.com", "new-password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void withdrawalBlocksEveryRequestAndFurtherLogin() throws Exception {
        signup("withdraw@example.com");
        Tokens tokens = login("withdraw@example.com");

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap("""
                        SELECT account_status, withdrawn_at FROM app_user WHERE email = ?
                        """, "withdraw@example.com"))
                .containsEntry("account_status", "WITHDRAWN")
                .hasEntrySatisfying("withdrawn_at", value ->
                        org.assertj.core.api.Assertions.assertThat(value).isNotNull());

        // 인증 필터가 매 요청 계정 상태를 확인하므로 남은 Access Token 유효 기간을 기다리지 않는다.
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
        mockMvc.perform(get("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));

        mockMvc.perform(post("/api/v1/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(tokens.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("withdraw@example.com", "password1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
    }

    @Test
    void notificationSettingsFallBackToDefaultsAndAcceptPartialUpdates() throws Exception {
        Tokens tokens = signupAndLogin("notification-settings@example.com");
        String userId = appUserRepository.findByEmailIgnoreCase("notification-settings@example.com")
                .orElseThrow()
                .getId();

        // 설정을 바꾼 적이 없으면 행이 없고 기본값이 나온다.
        mockMvc.perform(get("/api/v1/users/me/notification-settings")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allEnabled").value(true))
                .andExpect(jsonPath("$.pushEnabled").value(true))
                .andExpect(jsonPath("$.plantCareEnabled").value(true))
                .andExpect(jsonPath("$.marketingEnabled").value(false));
        // 조회만으로 행을 만들지 않는다.
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notification_setting WHERE user_id = ?",
                Integer.class,
                userId)).isZero();

        // 보낸 항목만 바뀌고 나머지는 기본값을 유지한다.
        mockMvc.perform(patch("/api/v1/users/me/notification-settings")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketingEnabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingEnabled").value(true))
                .andExpect(jsonPath("$.allEnabled").value(true))
                .andExpect(jsonPath("$.pushEnabled").value(true));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notification_setting WHERE user_id = ?",
                Integer.class,
                userId)).isEqualTo(1);

        // 두 번째 수정은 기존 행을 갱신한다.
        mockMvc.perform(patch("/api/v1/users/me/notification-settings")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allEnabled":false,"pushEnabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.plantCareEnabled").value(true))
                .andExpect(jsonPath("$.marketingEnabled").value(true));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notification_setting WHERE user_id = ?",
                Integer.class,
                userId)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/users/me/notification-settings")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allEnabled").value(false))
                .andExpect(jsonPath("$.marketingEnabled").value(true));

        // 다른 사용자의 설정은 영향받지 않는다.
        Tokens other = signupAndLogin("notification-other@example.com");
        mockMvc.perform(get("/api/v1/users/me/notification-settings")
                        .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allEnabled").value(true))
                .andExpect(jsonPath("$.marketingEnabled").value(false));

        mockMvc.perform(get("/api/v1/users/me/notification-settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));
    }

    @Test
    void fcmTokenRegistrationIsIdempotentPerInstallation() throws Exception {
        Tokens tokens = signupAndLogin("fcm-upsert@example.com");
        String userId = userIdOf("fcm-upsert@example.com");
        String installationId = "fid-upsert-0001";

        registerFcmToken(tokens, installationId, "token-first", "ANDROID")
                .andExpect(status().isNoContent());
        registerFcmToken(tokens, installationId, "token-rotated", "ANDROID")
                .andExpect(status().isNoContent());

        // 토큰이 회전해도 설치 ID 가 같으면 행이 늘어나지 않는다.
        assertThat(fcmTokenCount()).isEqualTo(1);
        assertThat(fcmTokenColumn(installationId, "token")).isEqualTo("token-rotated");
        assertThat(fcmTokenColumn(installationId, "user_id")).isEqualTo(userId);
        assertThat(fcmTokenColumn(installationId, "active")).isEqualTo("1");
    }

    @Test
    void registeringSameInstallationForAnotherUserReplacesThePreviousOwner() throws Exception {
        Tokens first = signupAndLogin("fcm-owner-a@example.com");
        Tokens second = signupAndLogin("fcm-owner-b@example.com");
        String secondUserId = userIdOf("fcm-owner-b@example.com");
        String installationId = "fid-shared-device";

        registerFcmToken(first, installationId, "token-a", "ANDROID")
                .andExpect(status().isNoContent());

        // 세션이 만료된 사용자는 등록을 지우지 못한 채 로그아웃된다. 그 상태에서 다른 사용자가
        // 같은 기기로 로그인하면, 이전 사용자의 알림이 이 기기로 가지 않아야 한다.
        registerFcmToken(second, installationId, "token-b", "ANDROID")
                .andExpect(status().isNoContent());

        assertThat(fcmTokenCount()).isEqualTo(1);
        assertThat(fcmTokenColumn(installationId, "user_id")).isEqualTo(secondUserId);
        assertThat(fcmTokenColumn(installationId, "token")).isEqualTo("token-b");
    }

    @Test
    void fcmTokenUnregistrationIsIdempotentAndScopedToTheOwner() throws Exception {
        Tokens owner = signupAndLogin("fcm-delete-owner@example.com");
        Tokens stranger = signupAndLogin("fcm-delete-stranger@example.com");
        String ownerInstallationId = "fid-owner-device";

        registerFcmToken(owner, ownerInstallationId, "token-owner", "ANDROID")
                .andExpect(status().isNoContent());

        // 남의 설치 ID 로 해제를 시도해도 204 지만 그 등록은 지워지지 않는다.
        mockMvc.perform(delete("/api/v1/users/me/fcm-tokens/{installationId}", ownerInstallationId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNoContent());
        assertThat(fcmTokenCount()).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/users/me/fcm-tokens/{installationId}", ownerInstallationId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        assertThat(fcmTokenCount()).isZero();

        // 이미 지워진 뒤 재시도해도 성공이어야 앱이 로그아웃을 마칠 수 있다.
        mockMvc.perform(delete("/api/v1/users/me/fcm-tokens/{installationId}", ownerInstallationId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void fcmTokenRegistrationRejectsInvalidRequests() throws Exception {
        Tokens tokens = signupAndLogin("fcm-invalid@example.com");

        // 앱은 android/iOS 가 아니면 등록을 건너뛰므로 이 값은 도달하지 않아야 한다.
        registerFcmToken(tokens, "fid-invalid-platform", "token", "UNSUPPORTED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        registerFcmToken(tokens, "fid-blank-token", "   ", "ANDROID")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 컬럼 길이를 넘기면 저장 단계에서 500 이 되므로 그 전에 막아야 한다.
        registerFcmToken(tokens, "f".repeat(129), "token", "ANDROID")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(fcmTokenCount()).isZero();

        mockMvc.perform(put("/api/v1/users/me/fcm-tokens/{installationId}", "fid-no-auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"token\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));
    }

    @Test
    void pushTargetsFollowNotificationSettingsAndActiveFlag() throws Exception {
        Tokens tokens = signupAndLogin("fcm-target@example.com");
        String userId = userIdOf("fcm-target@example.com");
        registerFcmToken(tokens, "fid-target-device", "token-target", "ANDROID")
                .andExpect(status().isNoContent());

        assertThat(pushTargetResolver.resolve(userId, NotificationCategory.PLANT_CARE)).hasSize(1);
        // 마케팅은 기본값이 꺼짐이라 같은 토큰이라도 대상이 아니다.
        assertThat(pushTargetResolver.resolve(userId, NotificationCategory.MARKETING)).isEmpty();

        mockMvc.perform(patch("/api/v1/users/me/notification-settings")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allEnabled\":false}"))
                .andExpect(status().isOk());
        assertThat(pushTargetResolver.resolve(userId, NotificationCategory.PLANT_CARE)).isEmpty();

        mockMvc.perform(patch("/api/v1/users/me/notification-settings")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allEnabled\":true,\"marketingEnabled\":true}"))
                .andExpect(status().isOk());
        assertThat(pushTargetResolver.resolve(userId, NotificationCategory.MARKETING)).hasSize(1);

        // 무효로 판정되어 비활성된 토큰은 발송 대상에서 빠진다.
        jdbcTemplate.update("UPDATE fcm_token SET active = 0 WHERE installation_id = ?", "fid-target-device");
        assertThat(pushTargetResolver.resolve(userId, NotificationCategory.PLANT_CARE)).isEmpty();

        // 같은 기기가 다시 등록하면 활성으로 되돌아온다.
        registerFcmToken(tokens, "fid-target-device", "token-target", "ANDROID")
                .andExpect(status().isNoContent());
        assertThat(pushTargetResolver.resolve(userId, NotificationCategory.PLANT_CARE)).hasSize(1);
    }

    @Test
    void invalidTokensAreDeactivatedWithoutDeletingTheDevice() throws Exception {
        Tokens tokens = signupAndLogin("fcm-deactivate@example.com");
        String userId = userIdOf("fcm-deactivate@example.com");
        registerFcmToken(tokens, "fid-keep", "token-keep", "ANDROID")
                .andExpect(status().isNoContent());
        registerFcmToken(tokens, "fid-drop", "token-drop", "ANDROID")
                .andExpect(status().isNoContent());
        assertThat(pushTargetResolver.resolve(userId, NotificationCategory.PLANT_CARE)).hasSize(2);

        assertThat(fcmTokenService.deactivate(List.of("fid-drop"))).isEqualTo(1);

        // 행은 남기고 active 만 내린다. 같은 기기가 새 토큰으로 돌아오면 upsert 가 되살린다.
        assertThat(fcmTokenCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active FROM fcm_token WHERE installation_id = ?", Integer.class, "fid-drop"))
                .isZero();
        assertThat(pushTargetResolver.resolve(userId, NotificationCategory.PLANT_CARE))
                .extracting(FcmToken::getInstallationId)
                .containsExactly("fid-keep");

        // 빈 목록으로 부르면 쿼리를 만들지 않는다. MySQL 에서 IN () 은 문법 오류다.
        assertThat(fcmTokenService.deactivate(List.of())).isZero();
        assertThat(pushTargetResolver.resolve(userId, NotificationCategory.PLANT_CARE)).hasSize(1);
    }

    @Test
    void robotCommandPublisherFallsBackToNoOpWithoutABroker() {
        // 테스트 컨텍스트에는 MQTT 브로커가 없다. 임시 CI 컨테이너와 같은 상태이며, 여기서
        // 발행기 빈이 만들어지지 않으면 주입받는 쪽이 전부 뜨지 못한다. Firebase 키가 없을
        // 때와 같은 이유로 구현만 갈아끼워야 한다.
        assertThat(robotCommandPublisher).isInstanceOf(LoggingRobotCommandPublisher.class);
    }

    @Test
    void pushSenderFallsBackToNoOpWithoutFirebaseCredentials() {
        // 임시 CI 컨테이너에는 Firebase 키가 없다. 여기서 초기화가 예외를 던지면
        // Health Check 가 실패해 배포가 막히므로, 자격증명이 없으면 구현만 갈아끼워야 한다.
        assertThat(pushSender).isInstanceOf(LoggingPushSender.class);
    }

    @Test
    void withdrawnUserLosesRegisteredDevices() throws Exception {
        Tokens tokens = signupAndLogin("fcm-withdraw@example.com");
        registerFcmToken(tokens, "fid-withdraw-device", "token-withdraw", "ANDROID")
                .andExpect(status().isNoContent());
        assertThat(fcmTokenCount()).isEqualTo(1);

        // 탈퇴는 소프트 처리라 행이 남지만, 계정을 물리 삭제하면 토큰이 함께 지워져야 한다.
        // 생성 컬럼이 없어 CASCADE 를 쓸 수 있는 것이 이 테이블의 특징이다.
        appUserRepository.deleteAll();

        assertThat(fcmTokenCount()).isZero();
    }

    @Test
    void plantDeviceStatusIsDerivedFromChildDevices() throws Exception {
        SensorFixture fixture = createSensorFixture("device-status@example.com");
        Tokens tokens = login("device-status@example.com");

        // 두 장치가 모두 조용한 상태다. robot.connection_status 컬럼은 갱신되지 않으므로
        // 장치 상태에서 파생한 값이 응답에 담긴다.
        mockMvc.perform(get("/api/v1/plants/{plantId}/devices", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plantId").value(fixture.plantId()))
                .andExpect(jsonPath("$.robot.name").value("테스트 로봇"))
                .andExpect(jsonPath("$.robot.connectionStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.robot.lastSeenAt").value(nullValue()))
                .andExpect(jsonPath("$.robot.batteryPercent").value(nullValue()))
                .andExpect(jsonPath("$.robot.firmwareVersion").value(nullValue()))
                .andExpect(jsonPath("$.robot.assignedAt").exists())
                .andExpect(jsonPath("$.devices.length()").value(2))
                // 장치 종류 문자열 순으로 정렬한다.
                .andExpect(jsonPath("$.devices[0].deviceType").value("JETSON_ORIN"))
                .andExpect(jsonPath("$.devices[0].connectionStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.devices[1].deviceType").value("RASPBERRY_PI"))
                .andExpect(jsonPath("$.devices[1].deviceUid").value(fixture.raspberryUid()));

        // 라즈베리 하나만 응답하면 로봇에 도달할 수 있다는 뜻이므로 로봇도 ONLINE 이다.
        heartbeatService.recordHeartbeat(fixture.raspberryUid());

        mockMvc.perform(get("/api/v1/plants/{plantId}/devices", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.robot.connectionStatus").value("ONLINE"))
                .andExpect(jsonPath("$.robot.lastSeenAt").exists())
                .andExpect(jsonPath("$.devices[0].connectionStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.devices[1].connectionStatus").value("ONLINE"));
    }

    @Test
    void plantWithoutRobotReturnsEmptyDevicePayload() throws Exception {
        Tokens tokens = signupAndLogin("device-unassigned@example.com");
        String body = mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlantJson(BASIL_ID, GERMINATION_ID, "장치 없는 식물")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String plantId = JsonPath.read(body, "$.plantId");

        mockMvc.perform(get("/api/v1/plants/{plantId}/devices", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.robot").value(nullValue()))
                .andExpect(jsonPath("$.devices.length()").value(0));

        Tokens stranger = signupAndLogin("device-stranger@example.com");
        mockMvc.perform(get("/api/v1/plants/{plantId}/devices", plantId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/plants/{plantId}/devices", plantId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));
    }

    @Test
    void registrationApiCompletesTheSensorIngestPath() throws Exception {
        // 유입 경로는 device_uid → iot_device → robot → plant_device_assignment → plant 다.
        // 세 행이 모두 API 로 만들어져야 수동 INSERT 없이 측정값이 저장된다.
        Tokens tokens = signupAndLogin("robot-register@example.com");
        String plantId = createPlant(tokens, "등록 테스트 식물");
        String robotUid = "robot-register-01";
        String sensorUid = "raspberry-register-01";

        String robotBody = mockMvc.perform(post("/api/v1/robots")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"" + robotUid + "\",\"name\":\"거실 로봇\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.robot.deviceUid").value(robotUid))
                // 등록만으로는 측정값이 저장되지 않는다는 것이 응답에 드러나야 한다.
                .andExpect(jsonPath("$.robot.assignedPlantId").value(nullValue()))
                .andExpect(jsonPath("$.robot.devices.length()").value(0))
                .andExpect(jsonPath("$.robot.connectionStatus").value("OFFLINE"))
                // 업로드 토큰 원문은 이 응답에서만 볼 수 있다.
                .andExpect(jsonPath("$.uploadToken").isString())
                .andReturn().getResponse().getContentAsString();
        String robotId = JsonPath.read(robotBody, "$.robot.robotId");

        mockMvc.perform(post("/api/v1/robots/{robotId}/devices", robotId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"" + sensorUid + "\",\"deviceType\":\"RASPBERRY_PI\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceUid").value(sensorUid))
                .andExpect(jsonPath("$.connectionStatus").value("OFFLINE"));

        // 배정 전에는 담당 식물이 없어 측정값이 버려진다.
        org.assertj.core.api.Assertions.assertThat(
                sensorReadingService.save(sensorMessage(
                        UUID.randomUUID().toString(),
                        sensorUid,
                        SensorType.TEMPERATURE,
                        "24.3",
                        SensorUnit.CELSIUS))
        ).isEqualTo(SensorReadingSaveResult.PLANT_ASSIGNMENT_NOT_FOUND);

        mockMvc.perform(post("/api/v1/plants/{plantId}/assignment", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"robotId\":\"" + robotId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.robotDeviceUid").value(robotUid));

        // 배정 후에는 같은 장치의 측정값이 이 식물로 저장된다.
        org.assertj.core.api.Assertions.assertThat(
                sensorReadingService.save(sensorMessage(
                        UUID.randomUUID().toString(),
                        sensorUid,
                        SensorType.TEMPERATURE,
                        "24.3",
                        SensorUnit.CELSIUS))
        ).isEqualTo(SensorReadingSaveResult.SAVED);

        mockMvc.perform(get("/api/v1/robots")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.robots.length()").value(1))
                .andExpect(jsonPath("$.robots[0].assignedPlantId").value(plantId))
                .andExpect(jsonPath("$.robots[0].assignedPlantName").value("등록 테스트 식물"))
                .andExpect(jsonPath("$.robots[0].devices.length()").value(1));
    }

    @Test
    void unassignAllowsReassignmentAndKeepsHistory() throws Exception {
        // V10 이 컬럼 전체 UNIQUE 를 생성 컬럼 기반 활성 유일성으로 바꾼 이유가 이것이다.
        // 이전 스키마에서는 해제해도 같은 식물에 새 행을 넣을 수 없어 재배정이 불가능했다.
        Tokens tokens = signupAndLogin("robot-reassign@example.com");
        String plantId = createPlant(tokens, "재배정 테스트 식물");
        String firstRobotId = registerRobot(tokens, "robot-reassign-01", "첫 로봇");
        String secondRobotId = registerRobot(tokens, "robot-reassign-02", "둘째 로봇");

        assignRobot(tokens, plantId, firstRobotId).andExpect(status().isCreated());

        // 이미 배정된 식물과 이미 배정된 로봇은 각각 다른 코드로 막는다.
        assignRobot(tokens, plantId, secondRobotId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLANT_ALREADY_ASSIGNED"));
        String otherPlantId = createPlant(tokens, "다른 식물");
        assignRobot(tokens, otherPlantId, firstRobotId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROBOT_ALREADY_ASSIGNED"));

        mockMvc.perform(delete("/api/v1/plants/{plantId}/assignment", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());

        // 해제되면 파생 키가 NULL 이 되어 UNIQUE 대상에서 빠지므로 재배정이 된다.
        assignRobot(tokens, plantId, secondRobotId).andExpect(status().isCreated());

        // 행을 지우지 않으므로 이력이 남는다.
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plant_device_assignment WHERE plant_id = ?",
                Integer.class,
                plantId);
        org.assertj.core.api.Assertions.assertThat(historyCount).isEqualTo(2);

        mockMvc.perform(delete("/api/v1/plants/{plantId}/assignment", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/plants/{plantId}/assignment", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_ASSIGNMENT_NOT_FOUND"));
    }

    @Test
    void deviceUidIsUniqueAcrossRobotAndIotDevice() throws Exception {
        // device_uid 는 MQTT 토픽 세그먼트로 쓰이므로 두 테이블을 통틀어 겹치면
        // 어느 장치의 측정값인지 가릴 수 없다.
        Tokens tokens = signupAndLogin("robot-uid@example.com");
        String robotId = registerRobot(tokens, "robot-uid-01", "로봇");

        mockMvc.perform(post("/api/v1/robots")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"robot-uid-01\",\"name\":\"중복 로봇\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_UID_ALREADY_REGISTERED"));

        mockMvc.perform(post("/api/v1/robots/{robotId}/devices", robotId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"robot-uid-01\",\"deviceType\":\"RASPBERRY_PI\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_UID_ALREADY_REGISTERED"));

        // 토픽을 깨뜨리는 문자는 검증에서 막는다.
        mockMvc.perform(post("/api/v1/robots")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"robot/uid\",\"name\":\"슬래시\"}"))
                .andExpect(status().isBadRequest());

        Tokens stranger = signupAndLogin("robot-uid-stranger@example.com");
        mockMvc.perform(post("/api/v1/robots/{robotId}/devices", robotId)
                        .header("Authorization", "Bearer " + stranger.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"robot-uid-99\",\"deviceType\":\"RASPBERRY_PI\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROBOT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/robots"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));
    }

    @Test
    void devicePhotoUploadIsAuthenticatedByTokenAndDerivesPlantFromAssignment() throws Exception {
        Tokens tokens = signupAndLogin("photo-device@example.com");
        String plantId = createPlant(tokens, "사진 테스트 식물");
        String robotBody = mockMvc.perform(post("/api/v1/robots")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"photo-robot-01\",\"name\":\"사진 로봇\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String robotId = JsonPath.read(robotBody, "$.robot.robotId");
        String uploadToken = JsonPath.read(robotBody, "$.uploadToken");

        // 배정이 없으면 어느 식물의 사진인지 정할 수 없다.
        mockMvc.perform(multipart("/api/v1/device/photos")
                        .file(jpegPart(120, 80))
                        .header("X-Device-Token", uploadToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_ASSIGNMENT_NOT_FOUND"));

        assignRobot(tokens, plantId, robotId).andExpect(status().isCreated());

        // plantId 를 받지 않는다. 로봇의 활성 배정에서 서버가 정한다.
        mockMvc.perform(multipart("/api/v1/device/photos")
                        .file(jpegPart(120, 80))
                        .header("X-Device-Token", uploadToken)
                        .param("capturedAt", "2026-07-20T12:00:00Z"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photoDate").value("2026-07-20"))
                .andExpect(jsonPath("$.width").value(120))
                .andExpect(jsonPath("$.height").value(80))
                // 크기별 URL 세 개가 모두 채워져야 타임랩스와 그리드가 각각 알맞은 파일을 쓴다.
                // 사진은 API 와 같은 origin 에서 나가므로 서버는 상대 경로를 내려준다.
                // 앱이 자기 API base URL 에 붙이므로 서버가 도메인을 알 필요가 없다.
                .andExpect(jsonPath("$.thumbnailUrl").value(startsWith("/media/")))
                .andExpect(jsonPath("$.playbackUrl").value(containsString("/playback.jpg")))
                .andExpect(jsonPath("$.originalUrl").value(containsString("/original.jpg")));

        // 하루 한 장. 타임랩스 프레임 간격을 일정하게 유지하기 위함이다.
        // 같은 서비스 타임존 날짜(07-20 KST)에 해당하는 다른 UTC 시각을 쓴다.
        mockMvc.perform(multipart("/api/v1/device/photos")
                        .file(jpegPart(120, 80))
                        .header("X-Device-Token", uploadToken)
                        .param("capturedAt", "2026-07-20T01:00:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHOTO_ALREADY_EXISTS_FOR_DATE"));

        // 날짜 경계는 UTC 자정이 아니라 서비스 타임존 자정이다. 15:00Z 부터 다음 날이므로
        // 하루 한 장 제한에 걸리지 않는다. plant_daily_light.light_date 와 같은 규칙이다.
        mockMvc.perform(multipart("/api/v1/device/photos")
                        .file(jpegPart(120, 80))
                        .header("X-Device-Token", uploadToken)
                        .param("capturedAt", "2026-07-20T15:00:00Z"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photoDate").value("2026-07-21"));

        mockMvc.perform(multipart("/api/v1/device/photos")
                        .file(jpegPart(120, 80))
                        .header("X-Device-Token", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_DEVICE_TOKEN"));

        // 이미지로 읽히지 않으면 확장자와 무관하게 거부한다. Content-Type 은 신뢰할 수 없다.
        mockMvc.perform(multipart("/api/v1/device/photos")
                        .file(new MockMultipartFile(
                                "file", "fake.jpg", "image/jpeg", "not an image".getBytes()))
                        .header("X-Device-Token", uploadToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHOTO"));
    }

    @Test
    void diaryGenerationIsSkippedWithoutAnLlmKeyAndWritesNothing() throws Exception {
        Tokens tokens = signupAndLogin("diary-generation@example.com");
        String plantId = createPlant(tokens, "바질이");

        // 테스트 컨텍스트에는 LLM 키가 없다. 임시 CI 컨테이너와 같은 상태이며, 여기서 예외가
        // 나면 배치가 죽고 배포 이후 매일 새벽에 실패한다. 조용히 건너뛰어야 한다.
        assertThat(diaryGenerationService.generate(plantId, LocalDate.of(2026, 7, 20)))
                .isEqualTo(DiaryGenerationResult.SKIPPED_NO_CONTENT);
        assertThat(diaryCount(plantId)).isZero();

        // 배치도 같은 이유로 살아남아야 한다. 식물 수만큼 돌고 결과만 세어 돌려준다.
        assertThat(diaryScheduler.writeFor(LocalDate.of(2026, 7, 20)))
                .containsEntry(DiaryGenerationResult.SKIPPED_NO_CONTENT, 1);
    }

    @Test
    void seededPersonaExamplesDoNotClaimRobotActions() {
        // 기획 자료의 일기 예시가 전부 이동과 급수를 말하는데 서버는 둘 다 하지 않는다.
        // 그 문장을 시드에 그대로 넣으면 모델이 문체를 따라 하며 없는 행동을 지어낸다.
        List<String> examples = jdbcTemplate.queryForList(
                "SELECT diary_example FROM species_persona", String.class);

        // 지원 6종에 모두 있다. 기획 자료 v2.1 의 봉선화와 란타나는 지원 종이 아니라 뺐다.
        assertThat(examples).hasSize(6);
        assertThat(examples).allSatisfy(example ->
                assertThat(example).doesNotContain("이동했", "옮겼", "물을 받", "급수"));
    }

    @Test
    void diaryListAndDetailReuseThatDaysDevicePhoto() throws Exception {
        Tokens tokens = signupAndLogin("diary@example.com");
        String plantId = createPlant(tokens, "일기 식물");
        String robotBody = mockMvc.perform(post("/api/v1/robots")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"diary-robot-01\",\"name\":\"일기 로봇\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assignRobot(tokens, plantId, JsonPath.read(robotBody, "$.robot.robotId"))
                .andExpect(status().isCreated());
        String uploadToken = JsonPath.read(robotBody, "$.uploadToken");

        // 07-20 은 사진이 있고 07-21 은 없다. 로봇이 꺼져 있던 날을 흉내낸다.
        mockMvc.perform(multipart("/api/v1/device/photos")
                        .file(jpegPart(120, 80))
                        .header("X-Device-Token", uploadToken)
                        .param("capturedAt", "2026-07-20T01:00:00Z"))
                .andExpect(status().isCreated());

        // 작성 API 가 없다. 일기는 LLM 배치가 쓰므로 서비스를 직접 부른다.
        assertThat(diaryService.write(plantId, LocalDate.of(2026, 7, 20), "첫 잎", "잎이 하나 났어요."))
                .isEqualTo(DiaryWriteResult.WRITTEN);
        assertThat(diaryService.write(plantId, LocalDate.of(2026, 7, 21), "흐린 날", "오늘은 조용했어요."))
                .isEqualTo(DiaryWriteResult.WRITTEN);

        // 배치가 두 번 돌아도 멀쩡한 일기를 갈아치우지 않는다.
        assertThat(diaryService.write(plantId, LocalDate.of(2026, 7, 20), "덮어쓰기", "새 내용"))
                .isEqualTo(DiaryWriteResult.SKIPPED_ALREADY_WRITTEN);
        assertThat(diaryCount(plantId)).isEqualTo(2);

        // 목록은 최신순이고 본문을 담지 않는다. 사진이 없던 날은 썸네일이 null 이다.
        mockMvc.perform(get("/api/v1/plants/{plantId}/diaries", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diaries.length()").value(2))
                .andExpect(jsonPath("$.diaries[0].diaryDate").value("2026-07-21"))
                .andExpect(jsonPath("$.diaries[0].thumbnailUrl").doesNotExist())
                .andExpect(jsonPath("$.diaries[1].diaryDate").value("2026-07-20"))
                .andExpect(jsonPath("$.diaries[1].thumbnailUrl").value(startsWith("/media/")));

        String diaryId = JsonPath.read(
                mockMvc.perform(get("/api/v1/plants/{plantId}/diaries", plantId)
                                .header("Authorization", "Bearer " + tokens.accessToken())
                                .param("from", "2026-07-20")
                                .param("to", "2026-07-20"))
                        .andReturn().getResponse().getContentAsString(),
                "$.diaries[0].diaryId");

        // 상세는 본문과 그날 장치 사진을 함께 준다. 일기가 사진을 따로 갖지 않는 이유다.
        mockMvc.perform(get("/api/v1/plants/{plantId}/diaries/{diaryId}", plantId, diaryId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("잎이 하나 났어요."))
                .andExpect(jsonPath("$.photo.originalUrl").value(containsString("/original.jpg")));

        // 남의 식물 일기는 존재 여부를 숨긴다.
        Tokens other = signupAndLogin("diary-other@example.com");
        mockMvc.perform(get("/api/v1/plants/{plantId}/diaries/{diaryId}", plantId, diaryId)
                        .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
    }

    @Test
    void bloomRecordingIsScopedToOwnerAndValidatesTheDate() throws Exception {
        Tokens tokens = signupAndLogin("bloom-record@example.com");
        String plantId = createPlant(tokens, "칼란디바");
        LocalDate today = serviceToday();

        // 기본 사용은 본문 없이 누르는 것이다. 서버가 서비스 타임존 기준 오늘로 채운다.
        mockMvc.perform(post("/api/v1/plants/{plantId}/blooms", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plantId").value(plantId))
                .andExpect(jsonPath("$.plantName").value("칼란디바"))
                .andExpect(jsonPath("$.bloomDate").value(today.toString()))
                .andExpect(jsonPath("$.source").value("USER"))
                .andExpect(jsonPath("$.read").value(false))
                .andExpect(jsonPath("$.note").value(nullValue()))
                // 저장 직후 그대로 응답한다. DB 기본값에 맡기면 여기가 null 로 나간다.
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        // 며칠 지나서 기록하는 경우다.
        mockMvc.perform(post("/api/v1/plants/{plantId}/blooms", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bloomDate\":\"%s\",\"note\":\"  분홍 꽃  \"}"
                                .formatted(today.minusDays(3))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bloomDate").value(today.minusDays(3).toString()))
                .andExpect(jsonPath("$.note").value("분홍 꽃"));

        mockMvc.perform(post("/api/v1/plants/{plantId}/blooms", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bloomDate\":\"%s\"}".formatted(today.plusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/plants/{plantId}/blooms", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"%s\"}".formatted("가".repeat(201))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 남의 식물은 존재 여부를 숨긴다.
        Tokens stranger = signupAndLogin("bloom-stranger@example.com");
        mockMvc.perform(post("/api/v1/plants/{plantId}/blooms", plantId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));

        // 목록은 개화한 날 기준 최신순이고 다른 사용자에게는 보이지 않는다.
        mockMvc.perform(get("/api/v1/blooms")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blooms.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.blooms[0].bloomDate").value(today.toString()))
                .andExpect(jsonPath("$.blooms[1].bloomDate").value(today.minusDays(3).toString()));
        mockMvc.perform(get("/api/v1/blooms")
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blooms.length()").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0));

        mockMvc.perform(get("/api/v1/blooms")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
        mockMvc.perform(get("/api/v1/blooms")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/blooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_REQUIRED"));
    }

    @Test
    void bloomReadProcessingIsIdempotentAndOwnershipChecked() throws Exception {
        Tokens tokens = signupAndLogin("bloom-read@example.com");
        String plantId = createPlant(tokens, "읽음 처리 식물");
        String bloomId = recordBloom(tokens, plantId, null);

        mockMvc.perform(patch("/api/v1/blooms/{bloomId}/read", bloomId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());
        LocalDateTime firstReadAt = jdbcTemplate.queryForObject(
                "SELECT read_at FROM plant_bloom WHERE bloom_id = ?", LocalDateTime.class, bloomId);
        assertThat(firstReadAt).isNotNull();

        mockMvc.perform(patch("/api/v1/blooms/{bloomId}/read", bloomId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT read_at FROM plant_bloom WHERE bloom_id = ?", LocalDateTime.class, bloomId))
                .isEqualTo(firstReadAt);

        mockMvc.perform(get("/api/v1/blooms")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("unreadOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blooms.length()").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0));

        Tokens stranger = signupAndLogin("bloom-read-stranger@example.com");
        mockMvc.perform(patch("/api/v1/blooms/{bloomId}/read", bloomId)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BLOOM_NOT_FOUND"));
    }

    @Test
    void plantBloomStoresWhatTheAlertTableCannotAndDeletesPhysically() throws Exception {
        Tokens tokens = signupAndLogin("bloom-delete@example.com");
        String plantId = createPlant(tokens, "두 송이 식물");
        LocalDate today = serviceToday();

        // alert 는 active_key 가 (plant_id, metric_type) 라 같은 식물의 두 번째를 UNIQUE 로 막는다.
        // 개화는 하루에 두 송이가 필 수 있어 같은 날짜로도 두 건이 남아야 한다.
        String first = recordBloom(tokens, plantId, today);
        String second = recordBloom(tokens, plantId, today);
        assertThat(first).isNotEqualTo(second);
        assertThat(bloomCount(plantId)).isEqualTo(2);

        mockMvc.perform(delete("/api/v1/plants/{plantId}/blooms/{bloomId}", plantId, first)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());
        // 물리 삭제다. 잘못 누른 것을 되돌리는 용도라 흔적을 남기지 않는다.
        assertThat(bloomCount(plantId)).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/plants/{plantId}/blooms/{bloomId}", plantId, first)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BLOOM_NOT_FOUND"));

        Tokens stranger = signupAndLogin("bloom-delete-stranger@example.com");
        mockMvc.perform(delete("/api/v1/plants/{plantId}/blooms/{bloomId}", plantId, second)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
        assertThat(bloomCount(plantId)).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/plants/{plantId}", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());
        // 식물 삭제 API 는 소프트 삭제라 개화 기록이 남는다.
        assertThat(bloomCount(plantId)).isEqualTo(1);

        // 물리 삭제하면 함께 사라진다. plant_id 가 생성 컬럼의 기반이 아니어서 CASCADE 를 쓸 수
        // 있고, 같은 자리에서 alert 는 RESTRICT 라 이 삭제 자체가 막힌다.
        //
        // 사용자를 지워서 확인할 수는 없다. plant.user_id 가 RESTRICT 라 식물이 남아 있으면
        // app_user 삭제가 먼저 막힌다.
        jdbcTemplate.update("DELETE FROM plant WHERE plant_id = ?", plantId);
        assertThat(bloomCount(plantId)).isZero();
    }

    /** 서비스 타임존 기준 오늘이다. {@code potner.sensor.zone-offset} 과 같아야 한다. */
    private LocalDate serviceToday() {
        return clock.instant().atOffset(ZoneOffset.of("+09:00")).toLocalDate();
    }

    private String recordBloom(Tokens tokens, String plantId, LocalDate bloomDate) throws Exception {
        var request = post("/api/v1/plants/{plantId}/blooms", plantId)
                .header("Authorization", "Bearer " + tokens.accessToken());
        if (bloomDate != null) {
            request = request
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"bloomDate\":\"%s\"}".formatted(bloomDate));
        }
        return JsonPath.read(
                mockMvc.perform(request)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.bloomId");
    }

    private int bloomCount(String plantId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plant_bloom WHERE plant_id = ?", Integer.class, plantId);
    }

    @Test
    void representativePhotoIsSeparateFromThePhotoLog() throws Exception {
        Tokens tokens = signupAndLogin("photo-representative@example.com");
        String plantId = createPlant(tokens, "대표 사진 식물");
        String robotBody = mockMvc.perform(post("/api/v1/robots")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"repr-robot-01\",\"name\":\"대표 사진 로봇\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String robotId = JsonPath.read(robotBody, "$.robot.robotId");
        String uploadToken = JsonPath.read(robotBody, "$.uploadToken");
        assignRobot(tokens, plantId, robotId).andExpect(status().isCreated());

        String userPhotoId = JsonPath.read(
                mockMvc.perform(multipart("/api/v1/plants/{plantId}/representative-photo", plantId)
                                .file(jpegPart(60, 40))
                                .header("Authorization", "Bearer " + tokens.accessToken()))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.photoId");

        // 사용자가 오늘 대표 사진을 올렸어도 장치는 오늘 사진을 올릴 수 있다.
        // 하루 한 장 제한은 타임랩스 프레임 간격을 위한 것이라 출처가 장치인 사진에만 걸린다.
        String devicePhotoId = JsonPath.read(
                mockMvc.perform(multipart("/api/v1/device/photos")
                                .file(jpegPart(120, 80))
                                .header("X-Device-Token", uploadToken))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.photoId");

        // 포토 로그와 타임랩스는 장치 사진만 본다. 사용자 사진이 섞이면 프레임 간격이 깨진다.
        mockMvc.perform(get("/api/v1/plants/{plantId}/photos", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("from", "2026-01-01")
                        .param("to", "2036-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos.length()").value(1))
                .andExpect(jsonPath("$.photos[0].photoId").value(devicePhotoId));

        // 대표 사진은 식물 상세와 목록에 실린다. 목록 화면 썸네일이 이 값을 쓴다.
        mockMvc.perform(get("/api/v1/plants/{plantId}", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(jsonPath("$.representativePhoto.photoId").value(userPhotoId));
        mockMvc.perform(get("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(jsonPath("$.plants[0].representativePhoto.photoId").value(userPhotoId));

        // 포토 로그의 장치 사진을 대표로 고른다. 이전 대표가 사용자 사진이면 함께 지운다.
        // 포토 로그에도 안 보이고 대표도 아니게 되면 어디서도 닿을 수 없기 때문이다.
        mockMvc.perform(patch("/api/v1/plants/{plantId}/representative-photo", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoId\":\"%s\"}".formatted(devicePhotoId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoId").value(devicePhotoId));
        assertThat(photoCount(plantId, "USER")).isZero();
        assertThat(photoCount(plantId, "DEVICE")).isEqualTo(1);

        // 해제해도 장치 사진은 포토 로그에 남는다. 대표에서 내려올 뿐이다.
        mockMvc.perform(delete("/api/v1/plants/{plantId}/representative-photo", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());
        assertThat(photoCount(plantId, "DEVICE")).isEqualTo(1);
        assertThat(representativePhotoIdOf(plantId)).isNull();
        mockMvc.perform(get("/api/v1/plants/{plantId}", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(jsonPath("$.representativePhoto").doesNotExist());
    }

    @Test
    void representativePhotoCannotPointAtAnotherPlantsPhoto() throws Exception {
        Tokens owner = signupAndLogin("repr-owner@example.com");
        String ownerPlantId = createPlant(owner, "주인 식물");
        String ownerPhotoId = JsonPath.read(
                mockMvc.perform(multipart("/api/v1/plants/{plantId}/representative-photo", ownerPlantId)
                                .file(jpegPart(60, 40))
                                .header("Authorization", "Bearer " + owner.accessToken()))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.photoId");

        Tokens other = signupAndLogin("repr-other@example.com");
        String otherPlantId = createPlant(other, "남의 식물");

        // 사진 URL 에는 인증이 걸리지 않으므로 남의 사진을 대표로 걸 수 있으면 그대로 유출 경로다.
        mockMvc.perform(patch("/api/v1/plants/{plantId}/representative-photo", otherPlantId)
                        .header("Authorization", "Bearer " + other.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoId\":\"%s\"}".formatted(ownerPhotoId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PHOTO_NOT_FOUND"));

        // 남의 식물에 올리는 것도 막힌다. 존재 여부를 숨기려고 403 이 아니라 404 다.
        mockMvc.perform(multipart("/api/v1/plants/{plantId}/representative-photo", ownerPlantId)
                        .file(jpegPart(60, 40))
                        .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
    }

    @Test
    void photoQueryReturnsTimelapseOrderAndRevealsMissingDays() throws Exception {
        Tokens tokens = signupAndLogin("photo-query@example.com");
        String plantId = createPlant(tokens, "타임랩스 식물");
        String robotBody = mockMvc.perform(post("/api/v1/robots")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"photo-robot-02\",\"name\":\"사진 로봇\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assignRobot(tokens, plantId, (String) JsonPath.read(robotBody, "$.robot.robotId"))
                .andExpect(status().isCreated());
        String uploadToken = JsonPath.read(robotBody, "$.uploadToken");

        // 3일 중 이틀만 촬영한다. 22일은 로봇이 꺼져 있던 날을 흉내낸다.
        for (String captured : List.of("2026-07-21T03:00:00Z", "2026-07-23T03:00:00Z")) {
            mockMvc.perform(multipart("/api/v1/device/photos")
                            .file(jpegPart(120, 80))
                            .header("X-Device-Token", uploadToken)
                            .param("capturedAt", captured))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/plants/{plantId}/photos", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("from", "2026-07-21")
                        .param("to", "2026-07-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zoneOffset").value("+09:00"))
                .andExpect(jsonPath("$.photos.length()").value(2))
                // 오래된 것부터다. 앱이 그대로 넘기면 타임랩스가 된다.
                .andExpect(jsonPath("$.photos[0].photoDate").value("2026-07-21"))
                .andExpect(jsonPath("$.photos[1].photoDate").value("2026-07-23"))
                // 결측일을 드러낸다. 3일을 요청했지만 이틀만 찍혔다.
                .andExpect(jsonPath("$.requestedDays").value(3))
                .andExpect(jsonPath("$.capturedDays").value(2));

        mockMvc.perform(get("/api/v1/plants/{plantId}/photos", plantId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("from", "2026-07-23")
                        .param("to", "2026-07-21"))
                .andExpect(status().isBadRequest());

        Tokens stranger = signupAndLogin("photo-stranger@example.com");
        mockMvc.perform(get("/api/v1/plants/{plantId}/photos", plantId)
                        .header("Authorization", "Bearer " + stranger.accessToken())
                        .param("from", "2026-07-21")
                        .param("to", "2026-07-23"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
    }

    /** 실제 JPEG 바이트를 만든다. 서버가 ImageIO 로 읽어 크기별 축소본을 만들기 때문이다. */
    private MockMultipartFile jpegPart(int width, int height) throws Exception {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpg", out);
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", out.toByteArray());
    }

    private String createPlant(Tokens tokens, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlantJson(BASIL_ID, GERMINATION_ID, name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.plantId");
    }

    private String registerRobot(Tokens tokens, String deviceUid, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/robots")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceUid\":\"" + deviceUid + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.robot.robotId");
    }

    private ResultActions assignRobot(Tokens tokens, String plantId, String robotId) throws Exception {
        return mockMvc.perform(post("/api/v1/plants/{plantId}/assignment", plantId)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"robotId\":\"" + robotId + "\"}"));
    }

    @Test
    void dailyLightSchedulerIsRegisteredWithValidCronAndZone() {
        // @Scheduled 의 zone 은 TimeZone.getTimeZone 으로 해석되므로 '+09:00' 같은 맨 오프셋을 받지 않는다.
        // 잘못된 값이면 이 빈 생성 단계에서 컨텍스트가 실패한다.
        org.assertj.core.api.Assertions.assertThat(dailyLightScheduler).isNotNull();
    }

    @Test
    void dailyLightBandsAreFilledByMigrationAndInstantBoundsStayEmpty() {
        // 시드는 목표 광량만 채워져 있어 판정이 불가능했다. V7이 목표값 대비 비율로 범위를 채운다.
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap("""
                        SELECT daily_light_target_lux_hour, daily_light_min_lux_hour,
                               daily_light_max_lux_hour, illuminance_min_lux, illuminance_max_lux
                          FROM species_growth_requirement
                         WHERE requirement_id = '30000000-0000-0000-0000-000000000013'
                        """))
                .containsEntry("daily_light_target_lux_hour", new BigDecimal("150000.00"))
                .containsEntry("daily_light_min_lux_hour", new BigDecimal("105000.00"))
                .containsEntry("daily_light_max_lux_hour", new BigDecimal("195000.00"))
                // 순간 조도는 의도적으로 비워 둔다. 밤에는 0 lux가 정상이다.
                .containsEntry("illuminance_min_lux", null)
                .containsEntry("illuminance_max_lux", null);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM species_growth_requirement
                 WHERE daily_light_min_lux_hour IS NULL OR daily_light_max_lux_hour IS NULL
                """, Integer.class)).isZero();
    }

    @Test
    void fullDayOfIlluminanceIsIntegratedAndJudgedNormal() throws Exception {
        SensorFixture fixture = createSensorFixture("daily-light-normal@example.com");
        LocalDate lightDate = dailyLightAggregationService.today().minusDays(2);
        // 10분 간격 144개, 앞의 15시간은 10,000 lux, 나머지는 0 lux.
        insertIlluminanceDay(fixture, lightDate, 90, "10000");

        dailyLightAggregationService.aggregateAndStore(fixture.plantId(), lightDate);

        var daily = plantDailyLightRepository
                .findByPlantIdAndLightDate(fixture.plantId(), lightDate)
                .orElseThrow();
        // 10,000 lux × 15시간 = 150,000 lux·h 로 목표와 정확히 일치한다.
        org.assertj.core.api.Assertions.assertThat(daily.getAccumulatedLuxHour())
                .isEqualByComparingTo("150000.00");
        org.assertj.core.api.Assertions.assertThat(daily.getLightHours())
                .isEqualByComparingTo("15.00");
        org.assertj.core.api.Assertions.assertThat(daily.getCoveragePct())
                .isEqualByComparingTo("99.31");
        org.assertj.core.api.Assertions.assertThat(daily.getLightStatus().name()).isEqualTo("NORMAL");
        org.assertj.core.api.Assertions.assertThat(daily.getPhotoperiodStatus().name())
                .isEqualTo("NORMAL");
        org.assertj.core.api.Assertions.assertThat(alertRepository.findAll()).isEmpty();

        // 같은 날짜를 다시 집계하면 행이 늘지 않는다.
        dailyLightAggregationService.aggregateAndStore(fixture.plantId(), lightDate);
        org.assertj.core.api.Assertions.assertThat(plantDailyLightRepository.count()).isEqualTo(1);
    }

    @Test
    void tooLongLightPeriodRaisesPhotoperiodAlertWhileTotalLightStaysNormal() throws Exception {
        SensorFixture fixture = createSensorFixture("daily-light-photoperiod@example.com");
        LocalDate lightDate = dailyLightAggregationService.today().minusDays(2);
        // 7,500 lux × 20시간 = 150,000 lux·h. 총량은 정상이지만 일조가 허용 18시간을 넘는다.
        insertIlluminanceDay(fixture, lightDate, 120, "7500");

        dailyLightAggregationService.aggregateAndStore(fixture.plantId(), lightDate);

        var daily = plantDailyLightRepository
                .findByPlantIdAndLightDate(fixture.plantId(), lightDate)
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(daily.getAccumulatedLuxHour())
                .isEqualByComparingTo("150000.00");
        org.assertj.core.api.Assertions.assertThat(daily.getLightHours())
                .isEqualByComparingTo("20.00");
        org.assertj.core.api.Assertions.assertThat(daily.getLightStatus().name()).isEqualTo("NORMAL");
        org.assertj.core.api.Assertions.assertThat(daily.getPhotoperiodStatus().name())
                .isEqualTo("HIGH");

        org.assertj.core.api.Assertions.assertThat(alertRepository.findAll())
                .singleElement()
                .satisfies(alert -> {
                    org.assertj.core.api.Assertions.assertThat(alert.getMetricType())
                            .isEqualTo(AlertMetricType.PHOTOPERIOD);
                    org.assertj.core.api.Assertions.assertThat(alert.getDeviation())
                            .isEqualTo(AlertDeviation.HIGH);
                    org.assertj.core.api.Assertions.assertThat(alert.getMeasuredValue())
                            .isEqualByComparingTo("20.00");
                });
    }

    @Test
    void lowCoverageDayIsRecordedWithoutJudgementOrAlert() throws Exception {
        SensorFixture fixture = createSensorFixture("daily-light-sparse@example.com");
        LocalDate lightDate = dailyLightAggregationService.today().minusDays(2);
        LocalDateTime start = dailyLightAggregationService.startOfDayUtc(lightDate);
        for (int index = 0; index < 5; index++) {
            insertReading(
                    fixture,
                    SensorType.ILLUMINANCE,
                    "100",
                    SensorUnit.LUX,
                    start.plusSeconds(index * 600L)
            );
        }

        dailyLightAggregationService.aggregateAndStore(fixture.plantId(), lightDate);

        var daily = plantDailyLightRepository
                .findByPlantIdAndLightDate(fixture.plantId(), lightDate)
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(daily.getLightStatus().name())
                .isEqualTo("INSUFFICIENT_DATA");
        org.assertj.core.api.Assertions.assertThat(daily.getPhotoperiodStatus().name())
                .isEqualTo("INSUFFICIENT_DATA");
        org.assertj.core.api.Assertions.assertThat(alertRepository.findAll()).isEmpty();
    }

    @Test
    void dailyLightApiReturnsTodayProgressAndConfirmedHistory() throws Exception {
        SensorFixture fixture = createSensorFixture("daily-light-api@example.com");
        Tokens tokens = login("daily-light-api@example.com");
        LocalDate yesterday = dailyLightAggregationService.today().minusDays(1);
        insertIlluminanceDay(fixture, yesterday, 90, "10000");
        dailyLightAggregationService.aggregateAndStore(fixture.plantId(), yesterday);
        // 오늘은 아직 진행 중이므로 확실히 과거인 시각만 넣는다.
        // DATETIME(0) 컬럼은 소수 초를 반올림하므로 현재 시각을 그대로 쓰면 조회 상한을 넘길 수 있다.
        LocalDateTime now = nowUtc().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        insertReading(fixture, SensorType.ILLUMINANCE, "10000", SensorUnit.LUX, now.minusSeconds(1800));
        insertReading(fixture, SensorType.ILLUMINANCE, "10000", SensorUnit.LUX, now.minusSeconds(1200));
        insertReading(fixture, SensorType.ILLUMINANCE, "10000", SensorUnit.LUX, now.minusSeconds(600));

        mockMvc.perform(get("/api/v1/plants/{plantId}/daily-light", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plantId").value(fixture.plantId()))
                .andExpect(jsonPath("$.zoneOffset").value("+09:00"))
                .andExpect(jsonPath("$.today.lightDate")
                        .value(dailyLightAggregationService.today().toString()))
                .andExpect(jsonPath("$.today.targetLuxHour").value(150000.0))
                .andExpect(jsonPath("$.today.accumulatedLuxHour").value(3333.33))
                .andExpect(jsonPath("$.today.progressPct").value(2.22))
                .andExpect(jsonPath("$.today.targetPhotoperiodHours").value(15.0))
                // 확정된 이력에만 판정 결과가 담기고 오늘은 포함되지 않는다.
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].lightDate").value(yesterday.toString()))
                .andExpect(jsonPath("$.history[0].lightStatus").value("NORMAL"))
                .andExpect(jsonPath("$.history[0].photoperiodStatus").value("NORMAL"))
                .andExpect(jsonPath("$.history[0].thresholdMinLuxHour").value(105000.0));

        mockMvc.perform(get("/api/v1/plants/{plantId}/daily-light", fixture.plantId())
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .param("days", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        Tokens stranger = signupAndLogin("daily-light-stranger@example.com");
        mockMvc.perform(get("/api/v1/plants/{plantId}/daily-light", fixture.plantId())
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
    }

    /** 10분 간격 표본 144개로 하루를 채운다. 앞의 {@code lightIntervals}개만 빛이 있는 구간이다. */
    private void insertIlluminanceDay(
            SensorFixture fixture,
            LocalDate lightDate,
            int lightIntervals,
            String lightLux
    ) {
        LocalDateTime start = dailyLightAggregationService.startOfDayUtc(lightDate);
        for (int index = 0; index < 144; index++) {
            insertReading(
                    fixture,
                    SensorType.ILLUMINANCE,
                    index < lightIntervals ? lightLux : "0",
                    SensorUnit.LUX,
                    start.plusSeconds(index * 600L)
            );
        }
    }

    private void insertAlert(
            String userId,
            String plantId,
            String metricType,
            LocalDateTime createdAt,
            boolean resolved,
            boolean read
    ) {
        jdbcTemplate.update("""
                        INSERT INTO alert (
                            alert_id, user_id, plant_id, metric_type, deviation,
                            measured_value, threshold_min, threshold_max,
                            occurred_at, resolved_at, read_at, created_at
                        ) VALUES (?, ?, ?, ?, 'LOW', 34.0, 40.0, 55.0, ?, ?, ?, ?)
                        """,
                UUID.randomUUID().toString(),
                userId,
                plantId,
                metricType,
                createdAt,
                resolved ? createdAt : null,
                read ? createdAt : null,
                createdAt
        );
    }

    private void publishSoilMoisture(
            SensorFixture fixture,
            String value,
            LocalDateTime measuredAt
    ) {
        telemetryProcessor.process(
                "potner/device/" + fixture.raspberryUid() + "/sensor/telemetry",
                telemetryJson(
                        fixture.raspberryUid(),
                        SensorType.SOIL_MOISTURE,
                        value,
                        SensorUnit.PERCENT,
                        measuredAt
                )
        );
    }

    private String telemetryJson(
            String deviceUid,
            SensorType sensorType,
            String value,
            SensorUnit unit,
            LocalDateTime measuredAtUtc
    ) {
        return """
                {"messageId":"%s","deviceId":"%s","sensorType":"%s","value":%s,"unit":"%s","measuredAt":"%s"}
                """.formatted(
                UUID.randomUUID(),
                deviceUid,
                sensorType.name(),
                value,
                unit.name(),
                UTC_ISO.format(measuredAtUtc)
        );
    }

    private void insertActiveAlert(String userId, String plantId) {
        jdbcTemplate.update("""
                        INSERT INTO alert (
                            alert_id, user_id, plant_id, metric_type, deviation,
                            measured_value, threshold_min, threshold_max, occurred_at
                        ) VALUES (?, ?, ?, 'SOIL_MOISTURE', 'LOW', 34.0, 40.0, 55.0, UTC_TIMESTAMP())
                        """,
                UUID.randomUUID().toString(),
                userId,
                plantId
        );
    }

    private int activeAlertCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert WHERE resolved_at IS NULL", Integer.class);
    }

    private void insertReading(
            SensorFixture fixture,
            SensorType sensorType,
            String value,
            SensorUnit unit,
            LocalDateTime measuredAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO sensor_reading (
                            plant_id,
                            robot_id,
                            source_device_id,
                            device_message_id,
                            sensor_type,
                            measured_value,
                            unit,
                            quality,
                            measured_at,
                            received_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'GOOD', ?, ?)
                        """,
                fixture.plantId(),
                fixture.robotId(),
                fixture.raspberryDeviceId(),
                UUID.randomUUID().toString(),
                sensorType.name(),
                new BigDecimal(value),
                unit.name(),
                measuredAt,
                measuredAt
        );
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private void signup(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(email, "password1", "potner")))
                .andExpect(status().isCreated());
    }

    private Tokens login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "password1")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    private Tokens signupAndLogin(String email) throws Exception {
        signup(email);
        return login(email);
    }

    private String signupJson(String email, String password, String nickname) {
        return """
                {"email":"%s","password":"%s","nickname":"%s"}
                """.formatted(email, password, nickname);
    }

    private String loginJson(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private String changePasswordJson(String currentPassword, String newPassword) {
        return """
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(currentPassword, newPassword);
    }

    private String tokenJson(String token) {
        return """
                {"refreshToken":"%s"}
                """.formatted(token);
    }

    private String createPlantJson(String speciesId, String lifeStageId, String name) {
        return """
                {"speciesId":"%s","lifeStageId":"%s","name":"%s"}
                """.formatted(speciesId, lifeStageId, name);
    }

    private SensorFixture createSensorFixture(String email) throws Exception {
        Tokens tokens = signupAndLogin(email);
        String plantBody = mockMvc.perform(post("/api/v1/plants")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlantJson(BASIL_ID, GERMINATION_ID, "센서 테스트 식물")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String plantId = JsonPath.read(plantBody, "$.plantId");
        String userId = appUserRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        String robotId = UUID.randomUUID().toString();
        String raspberryDeviceId = UUID.randomUUID().toString();
        String jetsonDeviceId = UUID.randomUUID().toString();
        String raspberryUid = "raspberry-" + robotId.substring(0, 8);
        String jetsonUid = "jetson-" + robotId.substring(0, 8);

        jdbcTemplate.update("""
                INSERT INTO robot (
                    robot_id,
                    user_id,
                    device_uid,
                    name,
                    connection_status
                ) VALUES (?, ?, ?, ?, 'OFFLINE')
                """, robotId, userId, "legacy-" + robotId, "테스트 로봇");
        jdbcTemplate.update("""
                INSERT INTO iot_device (
                    device_id,
                    robot_id,
                    device_uid,
                    device_type,
                    connection_status
                ) VALUES
                    (?, ?, ?, 'RASPBERRY_PI', 'OFFLINE'),
                    (?, ?, ?, 'JETSON_ORIN', 'OFFLINE')
                """,
                raspberryDeviceId,
                robotId,
                raspberryUid,
                jetsonDeviceId,
                robotId,
                jetsonUid
        );
        jdbcTemplate.update("""
                INSERT INTO plant_device_assignment (
                    assignment_id,
                    plant_id,
                    robot_id,
                    assigned_at,
                    unassigned_at
                ) VALUES (?, ?, ?, UTC_TIMESTAMP(), NULL)
                """, UUID.randomUUID().toString(), plantId, robotId);

        return new SensorFixture(
                plantId,
                robotId,
                raspberryDeviceId,
                raspberryUid,
                jetsonDeviceId,
                jetsonUid
        );
    }

    private SensorTelemetryMessage sensorMessage(
            String messageId,
            String deviceUid,
            SensorType sensorType,
            String value,
            SensorUnit unit
    ) {
        return new SensorTelemetryMessage(
                UUID.fromString(messageId),
                deviceUid,
                sensorType,
                new BigDecimal(value),
                unit,
                OffsetDateTime.parse("2026-07-22T17:10:00+09:00")
        );
    }

    private void assertDeviceState(
            String deviceUid,
            String expectedStatus,
            boolean lastSeenExpected
    ) {
        java.util.Map<String, Object> device = jdbcTemplate.queryForMap("""
                SELECT connection_status, last_seen_at
                  FROM iot_device
                 WHERE device_uid = ?
                """, deviceUid);
        org.assertj.core.api.Assertions.assertThat(device.get("connection_status"))
                .isEqualTo(expectedStatus);
        if (lastSeenExpected) {
            org.assertj.core.api.Assertions.assertThat(device.get("last_seen_at")).isNotNull();
        } else {
            org.assertj.core.api.Assertions.assertThat(device.get("last_seen_at")).isNull();
        }
    }

    private LocalDateTime deviceLastSeenAt(String deviceUid) {
        return jdbcTemplate.queryForObject("""
                SELECT last_seen_at
                  FROM iot_device
                 WHERE device_uid = ?
                """, LocalDateTime.class, deviceUid);
    }

    private ResultActions registerFcmToken(
            Tokens tokens,
            String installationId,
            String token,
            String platform
    ) throws Exception {
        return mockMvc.perform(put("/api/v1/users/me/fcm-tokens/{installationId}", installationId)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","platform":"%s"}
                        """.formatted(token, platform)));
    }

    private int diaryCount(String plantId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plant_diary WHERE plant_id = ?",
                Integer.class,
                plantId
        );
    }

    private int photoCount(String plantId, String source) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plant_photo WHERE plant_id = ? AND source = ?",
                Integer.class,
                plantId,
                source
        );
    }

    private String representativePhotoIdOf(String plantId) {
        return jdbcTemplate.queryForObject(
                "SELECT representative_photo_id FROM plant WHERE plant_id = ?",
                String.class,
                plantId
        );
    }

    private int fcmTokenCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fcm_token", Integer.class);
    }

    private String fcmTokenColumn(String installationId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT `%s` FROM fcm_token WHERE installation_id = ?".formatted(column),
                String.class,
                installationId
        );
    }

    private String userIdOf(String email) {
        return appUserRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    private record Tokens(String accessToken, String refreshToken) {
    }

    private record SensorFixture(
            String plantId,
            String robotId,
            String raspberryDeviceId,
            String raspberryUid,
            String jetsonDeviceId,
            String jetsonUid
    ) {
    }
}
