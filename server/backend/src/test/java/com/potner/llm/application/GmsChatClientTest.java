package com.potner.llm.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GmsChatClientTest {

    private static final String BASE_URL = "https://gms.example.test/v1";
    private static final String MODEL = "gpt-4.1-nano";

    private MockRestServiceServer server;
    private GmsChatClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GmsChatClient(builder.build(), MODEL, 500);
    }

    @Test
    void sendsSystemAndUserMessagesInOpenAiFormat() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.max_tokens").value(500))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("너는 바질이다."))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("오늘 일기를 써줘."))
                // JSON 을 요구하지 않은 호출에는 response_format 을 넣지 않는다.
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess(completion("오늘은 흙이 말랐다."), MediaType.APPLICATION_JSON));

        Optional<String> content = client.complete(
                LlmCompletionRequest.text("너는 바질이다.", "오늘 일기를 써줘."));

        assertThat(content).contains("오늘은 흙이 말랐다.");
        server.verify();
    }

    @Test
    void jsonModeAsksForAJsonObjectAndReturnsItVerbatim() {
        // 본문이 JSON 이므로 응답을 헬퍼로 만들지 않는다. 따옴표를 이스케이프해야 한다.
        String body = """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant",\
                "content":"{\\"title\\":\\"첫 잎\\",\\"content\\":\\"흙이 말랐다.\\"}"}}]}
                """;
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // 파싱은 호출자 몫이다. 이 층은 본문을 그대로 넘긴다.
        assertThat(client.complete(LlmCompletionRequest.json("s", "u")))
                .contains("{\"title\":\"첫 잎\",\"content\":\"흙이 말랐다.\"}");
        server.verify();
    }

    @Test
    void serverErrorBecomesEmptyInsteadOfAnException() {
        server.expect(requestTo(BASE_URL + "/chat/completions")).andRespond(withServerError());

        // 호출자가 배치라 한 건의 실패로 나머지 식물이 처리되지 않으면 안 된다.
        assertThat(client.complete(LlmCompletionRequest.text("s", "u"))).isEmpty();
    }

    @Test
    void malformedResponseBecomesEmpty() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.complete(LlmCompletionRequest.text("s", "u"))).isEmpty();
    }

    @Test
    void truncatedResponseIsRejected() {
        // 정원을 넘겨 잘린 본문은 문장이 끊기거나 JSON 이 닫히지 않는다. 저장하면 사용자가 본다.
        String truncated = """
                {"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"오늘은 흙이"}}]}
                """;
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(truncated, MediaType.APPLICATION_JSON));

        assertThat(client.complete(LlmCompletionRequest.text("s", "u"))).isEmpty();
    }

    @Test
    void blankContentBecomesEmpty() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(completion("   "), MediaType.APPLICATION_JSON));

        assertThat(client.complete(LlmCompletionRequest.text("s", "u"))).isEmpty();
    }

    @Test
    void unauthorizedBecomesEmpty() {
        // 키가 틀리면 기동은 성공하고 여기서 처음 드러난다.
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.complete(LlmCompletionRequest.text("s", "u"))).isEmpty();
    }

    private String completion(String content) {
        return """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"%s"}}]}
                """.formatted(content);
    }
}
