package com.potner;

import com.jayway.jsonpath.JsonPath;
import com.potner.auth.domain.RefreshTokenRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;

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
    private static final String TEST_INACTIVE_SPECIES_ID = "90000000-0000-0000-0000-000000000001";

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

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
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
                .andExpect(jsonPath("$.dailyLight.minLuxHour").value(nullValue()))
                .andExpect(jsonPath("$.dailyLight.maxLuxHour").value(nullValue()))
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

    private record Tokens(String accessToken, String refreshToken) {
    }
}
