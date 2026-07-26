package com.potner;

import com.jayway.jsonpath.JsonPath;
import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.auth.domain.RefreshTokenRepository;
import com.potner.mqtt.application.SensorTelemetryMessageProcessor;
import com.potner.device.application.HeartbeatUpdateResult;
import com.potner.device.application.IotDeviceHeartbeatService;
import com.potner.mqtt.dto.SensorTelemetryMessage;
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
import org.springframework.test.web.servlet.MockMvc;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.assertj.core.api.Assertions.tuple;

@Testcontainers
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PotnerApplicationTests {

    private static final String BASIL_ID = "20000000-0000-0000-0000-000000000104";
    private static final String TOMATO_ID = "20000000-0000-0000-0000-000000000106";
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
    private Clock clock;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM user_notification_setting");
        jdbcTemplate.update("DELETE FROM alert");
        jdbcTemplate.update("DELETE FROM plant_daily_light");
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
