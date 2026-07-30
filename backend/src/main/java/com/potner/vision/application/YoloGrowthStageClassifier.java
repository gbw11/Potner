package com.potner.vision.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.potner.vision.domain.DetectedGrowthStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 추론 서비스({@code yolo} 컨테이너)의 {@code POST /predict} 를 부른다.
 *
 * <p>계약은 {@code yolo_cpu_handoff/SPRING_BOOT_INTEGRATION.md} 다. 문서는 WebClient 예제를 주지만
 * {@code RestClient} 로 옮겼다. 이 애플리케이션은 MVC 이고 호출자가 블로킹 스레드 하나라
 * webflux 를 새로 넣을 이유가 없다. {@code GmsChatClient} 도 같은 이유로 RestClient 를 쓴다.
 *
 * <p><strong>성공은 HTTP 200 과 성공 본문의 조합만 인정한다.</strong> 문서의 오류 매트릭스에 없는
 * 상태·코드 조합, 200 인데 형식이 어긋난 본문, {@code detection_count} 와 실제 검출 수의 불일치는
 * 모두 프로토콜 위반으로 보고 빈 값을 돌려준다. 뭉개서 "외부 서버 오류" 하나로 만들지 않는 이유는
 * 모델을 바꿨을 때 생기는 형식 불일치와 정상적인 추론 실패를 로그에서 구분해야 하기 때문이다.
 *
 * <p>이미지 바이트, 원본 파일명, stderr 원문은 로그에 남기지 않는다. 남기는 것은 추론 서비스가 준
 * {@code request_id} 와 안정 오류 코드뿐이며, 그 두 값이 저쪽 로그와 대조할 유일한 열쇠다.
 */
public class YoloGrowthStageClassifier implements GrowthStageClassifier {

    private static final Logger log = LoggerFactory.getLogger(YoloGrowthStageClassifier.class);

    private static final String PREDICT_PATH = "/predict";
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String REQUIRED_DEVICE = "cpu";

    /** 문서의 오류 매트릭스다. 여기 없는 상태·코드 조합은 프로토콜 위반이다. */
    private static final Map<Integer, Set<String>> ALLOWED_ERROR_CODES = Map.of(
            400, Set.of("INVALID_REQUEST", "INVALID_IMAGE"),
            404, Set.of("INVALID_REQUEST"),
            413, Set.of("PAYLOAD_TOO_LARGE"),
            500, Set.of("INFERENCE_FAILED"),
            503, Set.of("MODEL_NOT_READY")
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final BigDecimal requestConfidence;
    private final int imageSize;

    public YoloGrowthStageClassifier(
            RestClient restClient,
            ObjectMapper objectMapper,
            BigDecimal requestConfidence,
            int imageSize
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.requestConfidence = requestConfidence;
        this.imageSize = imageSize;
    }

    @Override
    public Optional<GrowthStageClassification> classify(byte[] imageBytes, String imageName) {
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(PREDICT_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipartBody(imageBytes, imageName))
                    .retrieve()
                    // 기본 오류 변환을 끈다. 상태별 오류 코드를 직접 읽어야 하고, 예외를 밖으로
                    // 내지 않는 것이 이 클래스의 계약이다.
                    .onStatus(HttpStatusCode::isError, (request, ignored) -> {
                    })
                    .toEntity(String.class);
        } catch (RestClientException exception) {
            // 연결 실패와 타임아웃이 여기로 온다. 재시도하지 않는다. CPU 추론이라 무조건 재시도는
            // 부하와 중복 작업만 늘린다.
            log.warn("Growth stage inference call failed: imageName={}", imageName, exception);
            return Optional.empty();
        }

        String headerRequestId = response.getHeaders().getFirst(REQUEST_ID_HEADER);
        String body = response.getBody();
        int status = response.getStatusCode().value();

        if (status != 200) {
            logError(status, body, headerRequestId, imageName);
            return Optional.empty();
        }
        return parsePrediction(body, headerRequestId, imageName);
    }

    private MultiValueMap<String, Object> multipartBody(byte[] imageBytes, String imageName) {
        // getFilename 을 덮어써야 multipart 의 filename 이 채워진다. 비어 있으면 추론 서비스가
        // 파일 파트로 인식하지 못해 INVALID_REQUEST 가 온다.
        ByteArrayResource image = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return imageName;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", image);
        body.add("conf", requestConfidence.toPlainString());
        body.add("imgsz", Integer.toString(imageSize));
        return body;
    }

    private Optional<GrowthStageClassification> parsePrediction(
            String body,
            String headerRequestId,
            String imageName
    ) {
        PredictionResponse prediction;
        try {
            prediction = objectMapper.readValue(body, PredictionResponse.class);
        } catch (JacksonException exception) {
            log.warn("Growth stage inference returned unreadable body: imageName={}", imageName, exception);
            return Optional.empty();
        }

        String violation = validate(prediction, headerRequestId);
        if (violation != null) {
            log.warn(
                    "Growth stage inference violated the response contract: imageName={}, requestId={}, reason={}",
                    imageName,
                    headerRequestId,
                    violation
            );
            return Optional.empty();
        }

        return Optional.of(toClassification(prediction));
    }

    /** @return 위반 사유. 계약을 지켰으면 {@code null} */
    private String validate(PredictionResponse prediction, String headerRequestId) {
        if (prediction == null) {
            return "empty body";
        }
        if (isBlank(prediction.requestId())) {
            return "request_id is missing";
        }
        if (headerRequestId == null || !headerRequestId.equals(prediction.requestId())) {
            // 헤더와 본문이 다르면 응답이 섞였거나 프록시가 끼어든 것이다. 판정을 믿을 수 없다.
            return "X-Request-ID mismatch";
        }
        if (prediction.model() == null || isBlank(prediction.model().weights())) {
            return "model.weights is missing";
        }
        if (!REQUIRED_DEVICE.equals(prediction.model().device())) {
            // GPU 로 돌면 판정이 달라질 수 있고, 애초에 이 배포는 CPU 전용이다.
            return "model.device is not " + REQUIRED_DEVICE;
        }
        if (prediction.detections() == null) {
            return "detections is missing";
        }
        if (prediction.detectionCount() != prediction.detections().size()) {
            return "detection_count mismatch";
        }
        for (Detection detection : prediction.detections()) {
            if (detection == null || isBlank(detection.className())) {
                return "detection.class_name is missing";
            }
        }
        return null;
    }

    /**
     * 가장 신뢰도가 높은 검출 하나로 단계를 정한다.
     *
     * <p>사진 한 장에 여러 개체가 잡히거나 같은 개체가 여러 단계로 잡힐 수 있다. 화분 하나에
     * 식물 하나라는 전제이므로 최고 신뢰도 하나만 쓴다. 모델이 모르는 클래스는 여기서 걸러지지만
     * {@code detectionCount} 는 전체를 세므로, 판정이 비었는데 검출 수가 0 이 아니면 모델과 서버의
     * 클래스 목록이 어긋났다는 신호가 된다.
     */
    private GrowthStageClassification toClassification(PredictionResponse prediction) {
        Optional<Detection> best = prediction.detections().stream()
                .filter(detection -> DetectedGrowthStage
                        .fromModelClassName(detection.className())
                        .isPresent())
                .max(Comparator.comparingDouble(Detection::confidence));

        if (best.isEmpty()) {
            return GrowthStageClassification.undetermined(
                    prediction.detectionCount(),
                    prediction.model().weights(),
                    prediction.inferenceMs(),
                    prediction.requestId()
            );
        }

        Detection detection = best.get();
        return new GrowthStageClassification(
                DetectedGrowthStage.fromModelClassName(detection.className()),
                Optional.of(toConfidence(detection.confidence())),
                prediction.detectionCount(),
                prediction.model().weights(),
                prediction.inferenceMs(),
                prediction.requestId()
        );
    }

    /** 저장 컬럼이 {@code decimal(5,4)} 다. 반올림을 여기서 해 두면 저장 계층이 값을 바꾸지 않는다. */
    private BigDecimal toConfidence(double confidence) {
        return BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 오류 응답을 남긴다.
     *
     * <p>매트릭스에 있는 조합과 없는 조합을 구분해 남긴다. 전자는 추론 서비스가 정상적으로 보고한
     * 실패(모델 미준비, 손상된 이미지 등)이고 후자는 양쪽 계약이 어긋났다는 뜻이라 대응이 다르다.
     */
    private void logError(int status, String body, String headerRequestId, String imageName) {
        ErrorResponse error = null;
        try {
            if (body != null && !body.isBlank()) {
                error = objectMapper.readValue(body, ErrorResponse.class);
            }
        } catch (JacksonException ignored) {
            // 오류 본문마저 읽히지 않으면 아래에서 계약 위반으로 남는다.
        }

        String code = error != null && error.error() != null ? error.error().code() : null;
        Set<String> allowed = ALLOWED_ERROR_CODES.get(status);
        if (code != null && allowed != null && allowed.contains(code)) {
            log.warn(
                    "Growth stage inference reported a failure: imageName={}, status={}, code={}, requestId={}",
                    imageName,
                    status,
                    code,
                    headerRequestId
            );
            return;
        }
        log.warn(
                "Growth stage inference returned an undocumented failure: imageName={}, status={}, code={}, requestId={}",
                imageName,
                status,
                code,
                headerRequestId
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 응답 중 필요한 것만 받는다. 모델이 필드를 늘려도 깨지지 않도록 나머지는 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PredictionResponse(
            @JsonProperty("request_id") String requestId,
            ModelInfo model,
            List<Detection> detections,
            @JsonProperty("detection_count") int detectionCount,
            @JsonProperty("inference_ms") double inferenceMs
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelInfo(String weights, String device) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Detection(
            @JsonProperty("class_name") String className,
            double confidence
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorResponse(ErrorBody error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorBody(
            String code,
            @JsonProperty("request_id") String requestId
    ) {
    }
}
