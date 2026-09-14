package com.potner.vision.application;

import com.potner.vision.domain.DetectedGrowthStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YoloGrowthStageClassifierTest {

    private static final String BASE_URL = "http://yolo.example.test:8000";
    private static final String REQUEST_ID = "req-0001";
    private static final String PHOTO_ID = "11111111-1111-1111-1111-111111111111";
    private static final byte[] IMAGE = "not-a-real-jpeg".getBytes();

    private MockRestServiceServer server;
    private YoloGrowthStageClassifier classifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        classifier = new YoloGrowthStageClassifier(
                builder.build(),
                new ObjectMapper(),
                new BigDecimal("0.25"),
                640
        );
    }

    @Test
    void picksTheHighestConfidenceDetection() {
        // 한 사진에 여러 단계가 잡힐 수 있다. 화분 하나에 식물 하나이므로 최고 신뢰도만 쓴다.
        String body = """
                {"request_id":"%s","source_name":"%s",
                 "image":{"width":1280,"height":960},
                 "model":{"weights":"/app/models/best.pt","device":"cpu","imgsz":640,
                          "confidence_threshold":0.25},
                 "detections":[
                   {"class_id":0,"class_name":"germination","confidence":0.41,
                    "bbox":{"x1":0,"y1":0,"x2":10,"y2":10}},
                   {"class_id":1,"class_name":"vegetative","confidence":0.88,
                    "bbox":{"x1":0,"y1":0,"x2":20,"y2":20}}],
                 "detection_count":2,"inference_ms":812.5}
                """.formatted(REQUEST_ID, PHOTO_ID);
        expectPredict(body);

        Optional<GrowthStageClassification> result = classifier.classify(IMAGE, PHOTO_ID);

        assertThat(result).isPresent();
        GrowthStageClassification classification = result.orElseThrow();
        assertThat(classification.stage()).contains(DetectedGrowthStage.VEGETATIVE);
        assertThat(classification.confidence()).contains(new BigDecimal("0.8800"));
        assertThat(classification.detectionCount()).isEqualTo(2);
        assertThat(classification.modelWeights()).isEqualTo("/app/models/best.pt");
        assertThat(classification.requestId()).isEqualTo(REQUEST_ID);
        server.verify();
    }

    @Test
    void reportsUndeterminedWhenOnlyUnknownClassesArrive() {
        // 모델이 클래스를 늘리면 서버가 모르는 이름이 온다. 검출 수는 남겨서 "판정할 것이 없었다"
        // 와 "클래스 목록이 어긋났다" 를 구분할 수 있게 한다.
        expectPredict("""
                {"request_id":"%s","source_name":"%s",
                 "image":{"width":640,"height":640},
                 "model":{"weights":"/app/models/best.pt","device":"cpu","imgsz":640,
                          "confidence_threshold":0.25},
                 "detections":[{"class_id":9,"class_name":"fruiting","confidence":0.95,
                                "bbox":{"x1":0,"y1":0,"x2":10,"y2":10}}],
                 "detection_count":1,"inference_ms":700.0}
                """.formatted(REQUEST_ID, PHOTO_ID));

        GrowthStageClassification classification = classifier.classify(IMAGE, PHOTO_ID).orElseThrow();

        assertThat(classification.isDetermined()).isFalse();
        assertThat(classification.detectionCount()).isEqualTo(1);
    }

    @Test
    void rejectsAResponseWhoseRequestIdDoesNotMatchTheHeader() {
        // 헤더와 본문이 다르면 응답이 섞였다는 뜻이라 판정을 믿을 수 없다.
        server.expect(requestTo(BASE_URL + "/predict"))
                .andRespond(withSuccess("""
                        {"request_id":"other-id","source_name":"%s",
                         "image":{"width":640,"height":640},
                         "model":{"weights":"w","device":"cpu","imgsz":640,
                                  "confidence_threshold":0.25},
                         "detections":[],"detection_count":0,"inference_ms":1.0}
                        """.formatted(PHOTO_ID), MediaType.APPLICATION_JSON)
                        .header("X-Request-ID", REQUEST_ID));

        assertThat(classifier.classify(IMAGE, PHOTO_ID)).isEmpty();
    }

    @Test
    void rejectsAResponseWhoseDetectionCountDisagreesWithTheDetections() {
        expectPredict("""
                {"request_id":"%s","source_name":"%s",
                 "image":{"width":640,"height":640},
                 "model":{"weights":"w","device":"cpu","imgsz":640,"confidence_threshold":0.25},
                 "detections":[{"class_id":1,"class_name":"vegetative","confidence":0.9,
                                "bbox":{"x1":0,"y1":0,"x2":10,"y2":10}}],
                 "detection_count":5,"inference_ms":1.0}
                """.formatted(REQUEST_ID, PHOTO_ID));

        assertThat(classifier.classify(IMAGE, PHOTO_ID)).isEmpty();
    }

    @Test
    void rejectsAResponseThatWasNotInferredOnCpu() {
        // 이 배포는 CPU 전용이다. GPU 로 돌았다면 우리가 아는 그 서비스가 아니다.
        expectPredict("""
                {"request_id":"%s","source_name":"%s",
                 "image":{"width":640,"height":640},
                 "model":{"weights":"w","device":"cuda","imgsz":640,"confidence_threshold":0.25},
                 "detections":[],"detection_count":0,"inference_ms":1.0}
                """.formatted(REQUEST_ID, PHOTO_ID));

        assertThat(classifier.classify(IMAGE, PHOTO_ID)).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheModelIsNotReady() {
        // 배포 직후 모델 적재 중에 오는 정상적인 실패다. 예외를 밖으로 내지 않는다.
        server.expect(requestTo(BASE_URL + "/predict"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                        .body("""
                                {"error":{"code":"MODEL_NOT_READY","message":"loading",
                                          "request_id":"%s"}}
                                """.formatted(REQUEST_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-ID", REQUEST_ID));

        assertThat(classifier.classify(IMAGE, PHOTO_ID)).isEmpty();
        server.verify();
    }

    private void expectPredict(String responseBody) {
        server.expect(requestTo(BASE_URL + "/predict"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON)
                        .header("X-Request-ID", REQUEST_ID));
    }
}
