# Spring Boot 3 통합

대상은 Java 17과 Spring Boot 3입니다. Python 패키지는 다음 두 경계 중 하나로 호출합니다.

## 통합 방식 선택

| 방식 | 적합한 경우 | 비용과 주의점 |
|---|---|---|
| `ProcessBuilder`로 CLI 호출 | 저빈도 관리 작업, 오프라인 배치, 요청별 프로세스 격리가 필요한 경우 | 요청마다 Python 시작과 모델 로드가 발생합니다. stdout/stderr 동시 소비, 타임아웃과 전체 프로세스 트리 종료가 필수입니다. |
| FastAPI를 `WebClient`로 호출 | 상시 트래픽, 짧고 예측 가능한 지연시간, 수평 확장이 필요한 경우 | 별도 서비스 운영과 HTTP 타임아웃/용량 관리가 필요하지만 모델을 메모리에 재사용합니다. |

**상시 트래픽에는 FastAPI + WebClient 방식을 권장합니다.** Python 모델이 프로세스 시작 시 한 번 로드되고 `YOLO_MAX_CONCURRENCY` semaphore가 body parsing 전부터 디코딩과 CPU 추론 응답 완료까지 전체 `/predict` 요청을 제한합니다. CLI 방식은 요청마다 모델을 다시 로드하므로 동기 HTTP 요청 경로의 기본 선택으로 삼지 마십시오.

두 방식 모두 Python 응답의 `request_id`를 Spring의 trace/request ID와 함께 로그에 남기되 이미지 바이트, 원본 파일명, 절대 경로와 stderr 원문은 외부 응답이나 일반 로그에 남기지 않습니다.

## 공통 JSON DTO

Python JSON은 snake_case입니다. 아래 Java 17 records는 중첩된 성공/오류/준비 응답을 모두 표현하며 각 필드를 `@JsonProperty`로 고정합니다. 전역 Jackson naming 설정에 의존하지 않으므로 다른 Spring DTO에 영향을 주지 않습니다.

```java
package com.example.yolo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public final class YoloDtos {
    private YoloDtos() {
    }

    public record PredictionResponse(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("source_name") String sourceName,
            ImageInfo image,
            ModelInfo model,
            List<Detection> detections,
            @JsonProperty("detection_count") int detectionCount,
            @JsonProperty("inference_ms") double inferenceMs
    ) {
    }

    public record ImageInfo(int width, int height) {
    }

    public record ModelInfo(
            String weights,
            String device,
            int imgsz,
            @JsonProperty("confidence_threshold") double confidenceThreshold
    ) {
    }

    public record Detection(
            @JsonProperty("class_id") int classId,
            @JsonProperty("class_name") String className,
            double confidence,
            BoundingBox bbox
    ) {
    }

    public record BoundingBox(double x1, double y1, double x2, double y2) {
    }

    public record ErrorResponse(ErrorBody error) {
    }

    public record ErrorBody(
            String code,
            String message,
            @JsonProperty("request_id") String requestId
    ) {
    }

    public record ReadyResponse(
            boolean ready,
            String weights,
            String device,
            Map<String, String> classes
    ) {
    }

    public static PredictionResponse validatePrediction(
            PredictionResponse response
    ) {
        requireNonNull(response, "prediction");
        requireNonBlank(response.requestId(), "request_id");
        requireNonBlank(response.sourceName(), "source_name");
        requireNonNull(response.image(), "image");
        requireNonNull(response.model(), "model");
        requireNonNull(response.detections(), "detections");
        requireNonBlank(response.model().weights(), "model.weights");
        requireNonBlank(response.model().device(), "model.device");
        if (!"cpu".equals(response.model().device())) {
            throw new IllegalArgumentException("model.device must be cpu");
        }
        for (Detection detection : response.detections()) {
            requireNonNull(detection, "detection");
            requireNonBlank(detection.className(), "detection.class_name");
            requireNonNull(detection.bbox(), "detection.bbox");
        }
        if (response.detectionCount() != response.detections().size()) {
            throw new IllegalArgumentException("detection_count mismatch");
        }
        return response;
    }

    public static ErrorResponse validateError(
            ErrorResponse response,
            boolean requireRequestId
    ) {
        requireNonNull(response, "error response");
        requireNonNull(response.error(), "error");
        requireNonBlank(response.error().code(), "error.code");
        requireNonBlank(response.error().message(), "error.message");
        String requestId = response.error().requestId();
        if (requestId != null && requestId.isBlank()) {
            throw new IllegalArgumentException("error.request_id is blank");
        }
        if (requireRequestId && requestId == null) {
            throw new IllegalArgumentException("error.request_id is missing");
        }
        return response;
    }

    public static ReadyResponse validateReady(ReadyResponse response) {
        requireNonNull(response, "ready response");
        requireNonBlank(response.weights(), "ready.weights");
        requireNonBlank(response.device(), "ready.device");
        requireNonNull(response.classes(), "ready.classes");
        if (!response.ready()) {
            throw new IllegalArgumentException("ready must be true");
        }
        if (!"cpu".equals(response.device())) {
            throw new IllegalArgumentException("ready.device must be cpu");
        }
        Map<String, String> expectedClasses = Map.of(
                "0", "germination",
                "1", "vegetative",
                "2", "flowering"
        );
        if (!expectedClasses.equals(response.classes())) {
            throw new IllegalArgumentException("ready.classes mismatch");
        }
        return response;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is missing");
        }
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value;
    }
}
```

Jackson의 Spring 기본 `ObjectMapper`는 위 records를 역직렬화할 수 있지만, records의 reference 누락을 `null`, primitive 누락을 `0`으로 만들 수 있습니다. 두 클라이언트는 주입받은 mapper를 복사해 `FAIL_ON_MISSING_CREATOR_PROPERTIES`를 켜고, `readValue` 직후 `validatePrediction` 또는 `validateError`를 호출합니다. CLI 오류의 `request_id`는 정상적으로 null일 수 있지만 HTTP 오류에서는 필수입니다. 알 수 없는 필드를 허용할지는 호환성 정책으로 정하되 누락, 중첩 null, 검출 개수 불일치를 정상 응답으로 취급하지 마십시오.

## ProcessBuilder 통합

### 절대 경로 설정

Python 실행 파일, 핸드오프 디렉터리, 가중치 경로를 구성으로 주입하고 시작 시 절대 경로인지 검증합니다.

```java
package com.example.yolo;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("yolo.cli")
public record YoloCliProperties(
        Path python,
        Path handoffDirectory,
        Path weights,
        Duration timeout
) {
    public YoloCliProperties {
        python = requireAbsolute(python, "python");
        handoffDirectory = requireAbsolute(handoffDirectory, "handoff-directory");
        weights = requireAbsolute(weights, "weights");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private static Path requireAbsolute(Path value, String name) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be an absolute path");
        }
        return value.toAbsolutePath().normalize();
    }
}
```

```yaml
yolo:
  cli:
    python: /srv/yolo-cpu-handoff/.venv/bin/python
    handoff-directory: /srv/yolo-cpu-handoff
    weights: /srv/yolo-cpu-handoff/models/best.pt
    timeout: 45s
```

Spring Boot 시작 시 `@EnableConfigurationProperties(YoloCliProperties.class)`로 등록합니다. 상대 경로, 사용자가 입력한 경로, PATH에서 우연히 선택되는 `python`을 허용하지 마십시오. 배포 전 `models/SHA256SUMS`로 가중치를 검증합니다.

### 안전한 프로세스 실행

핵심 규칙은 다음과 같습니다.

1. 셸을 사용하지 않고 `List<String> command`의 각 인자를 분리합니다. 파일명이나 옵션을 명령 문자열에 이어 붙이지 않습니다.
2. 프로세스를 시작한 직후 stdout과 stderr를 동시에 별도 작업으로 비웁니다. 한 스트림의 OS 파이프가 가득 차 `waitFor`와 서로 기다리는 교착을 방지합니다.
3. 한 absolute deadline을 프로세스 종료와 두 drain이 함께 사용합니다. 프로세스가 실행되는 동안 root와 관찰한 descendants를 별도 집합에 보존하고, 모든 종료 경로의 `finally`에서 parent 생존 여부와 무관하게 정리합니다.
4. stdout/stderr는 UTF-8로 해석하고 Python에도 UTF-8 환경을 지정합니다.
5. 업로드, 동시 프로세스, drain thread와 보관할 출력 바이트에 명시적 상한을 둡니다.
6. 모든 성공/실패/타임아웃 경로에서 프로세스 스트림, future, semaphore permit과 임시 파일을 `finally`로 정리합니다.

다음 예제는 `MultipartFile`을 임시 파일로 옮긴 뒤 CLI의 JSON stdout을 파싱합니다. 임시 파일명은 원본 파일명 대신 생성되므로 CLI 응답의 `source_name`이 업무 키가 되어서는 안 됩니다.

Spring의 선행 multipart 제한도 같은 파일 상한을 사용합니다. `max-request-size`는 multipart framing 여유만 더 주며, Java bounded copy가 파일 본문의 정확한 10 MiB 상한을 다시 집행합니다.

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10485760B
      max-request-size: 11534336B
```

예제는 `MAX_CONCURRENT_PROCESSES=1` semaphore를 사용하고 drain executor를 정확히 `1 × 2` thread로 고정합니다. permit을 얻기 전에는 프로세스를 시작하지 않으며 모든 경로에서 반환합니다. `MultipartFile.getSize()`의 빠른 거부 뒤에도 입력을 `MAX_UPLOAD_BYTES + 1`까지만 읽어 실제 크기를 재검증하므로 임시 파일에는 10 MiB를 넘겨 쓰지 않습니다. 이 입력 거부는 `@ControllerAdvice`에서 외부 `413 PAYLOAD_TOO_LARGE`로 변환하십시오.

```java
package com.example.yolo;

import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.example.yolo.YoloDtos;
import com.example.yolo.YoloDtos.ErrorResponse;
import com.example.yolo.YoloDtos.PredictionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public final class YoloCliClient {
    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    private static final int MAX_CONCURRENT_PROCESSES = 1;
    private static final int MAX_STDOUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 64 * 1024;
    private static final long OBSERVE_INTERVAL_NANOS =
            TimeUnit.MILLISECONDS.toNanos(25);
    private final YoloCliProperties properties;
    private final ObjectMapper objectMapper;
    private final Semaphore processSlots =
            new Semaphore(MAX_CONCURRENT_PROCESSES, true);
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(
            MAX_CONCURRENT_PROCESSES * 2,
            runnable -> {
                Thread thread = new Thread(runnable, "yolo-cli-stream");
                thread.setDaemon(true);
                return thread;
            }
    );

    public YoloCliClient(
            YoloCliProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper.copy().enable(
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES
        );
    }

    public PredictionResponse predict(
            MultipartFile image,
            double confidence,
            int imageSize
    ) {
        long deadlineNanos = deadlineAfter(properties.timeout());
        boolean slotAcquired = false;
        Path tempImage = null;
        Process process = null;
        Set<ProcessHandle> trackedHandles = ConcurrentHashMap.newKeySet();
        CompletableFuture<StreamCapture> stdoutFuture = null;
        CompletableFuture<StreamCapture> stderrFuture = null;
        try {
            slotAcquired = processSlots.tryAcquire(
                    remainingNanos(deadlineNanos),
                    TimeUnit.NANOSECONDS
            );
            if (!slotAcquired) {
                throw timeoutFailure("waiting for a YOLO CLI slot");
            }
            if (image.getSize() > MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException(
                        "image exceeds the 10 MiB upload limit"
                );
            }

            tempImage = Files.createTempFile("yolo-upload-", ".img");
            try (InputStream input = image.getInputStream()) {
                copyBounded(input, tempImage);
            }
            if (remainingNanos(deadlineNanos) == 0) {
                throw timeoutFailure("preparing the YOLO CLI request");
            }

            List<String> command = List.of(
                    properties.python().toString(),
                    "-m", "app.cli",
                    "predict", tempImage.toString(),
                    "--weights", properties.weights().toString(),
                    "--conf", Double.toString(confidence),
                    "--imgsz", Integer.toString(imageSize)
            );
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(properties.handoffDirectory().toFile())
                    .redirectErrorStream(false);
            processBuilder.environment().put("PYTHONUTF8", "1");
            processBuilder.environment().put("PYTHONIOENCODING", "UTF-8");
            process = processBuilder.start();
            trackedHandles.add(process.toHandle());
            observeDescendants(process, trackedHandles);

            Process running = process;
            stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readUtf8(
                            running.getInputStream(),
                            MAX_STDOUT_BYTES
                    ),
                    ioExecutor
            );
            stderrFuture = CompletableFuture.supplyAsync(
                    () -> readUtf8(
                            running.getErrorStream(),
                            MAX_STDERR_BYTES
                    ),
                    ioExecutor
            );

            waitForProcess(process, trackedHandles, deadlineNanos);
            CompletableFuture.allOf(stdoutFuture, stderrFuture).get(
                    remainingNanos(deadlineNanos),
                    TimeUnit.NANOSECONDS
            );
            StreamCapture stdout = stdoutFuture.getNow(null);
            StreamCapture stderr = stderrFuture.getNow(null);
            if (stdout == null || stderr == null) {
                throw protocolFailure("YOLO CLI drain completed without output");
            }
            if (stdout.truncated()) {
                throw protocolFailure(
                        "YOLO CLI stdout exceeded the capture limit"
                );
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return parsePrediction(stdout.text());
            }

            ErrorResponse error = parseError(stdout.text());
            CliExit mappedExit = CliExit.fromPredict(
                    exitCode,
                    error.error().code()
            );
            if (mappedExit == CliExit.PROTOCOL_FAILURE) {
                throw protocolFailure(
                        "YOLO CLI exit/code mismatch (success/error body mismatch)"
                );
            }
            throw new YoloCliException(
                    mappedExit,
                    error,
                    "YOLO CLI failed; stderr bytes=" + stderr.totalBytes()
            );
        } catch (TimeoutException timeout) {
            throw timeoutFailure("running or draining the YOLO CLI", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new YoloCliException(
                    CliExit.INTERRUPTED,
                    null,
                    "YOLO CLI interrupted",
                    interrupted
            );
        } catch (IOException | ExecutionException failure) {
            throw new YoloCliException(
                    CliExit.PROTOCOL_FAILURE,
                    null,
                    "YOLO CLI transport/protocol failure",
                    failure
            );
        } finally {
            cleanupProcess(
                    process,
                    trackedHandles,
                    stdoutFuture,
                    stderrFuture,
                    deadlineNanos
            );
            if (tempImage != null) {
                try {
                    Files.deleteIfExists(tempImage);
                } catch (IOException cleanupFailure) {
                    // 경로는 로그하지 말고 cleanup 실패 카운터만 증가시킨다.
                }
            }
            if (slotAcquired) {
                processSlots.release();
            }
        }
    }

    private PredictionResponse parsePrediction(String stdout) {
        try {
            return YoloDtos.validatePrediction(
                    objectMapper.readValue(stdout, PredictionResponse.class)
            );
        } catch (JsonProcessingException | IllegalArgumentException invalidJson) {
            throw new YoloCliException(
                    CliExit.PROTOCOL_FAILURE,
                    null,
                    "YOLO CLI returned invalid prediction JSON",
                    invalidJson
            );
        }
    }

    private ErrorResponse parseError(String stdout) {
        try {
            return YoloDtos.validateError(
                    objectMapper.readValue(stdout, ErrorResponse.class),
                    false
            );
        } catch (JsonProcessingException | IllegalArgumentException invalidJson) {
            throw new YoloCliException(
                    CliExit.PROTOCOL_FAILURE,
                    null,
                    "YOLO CLI returned invalid error JSON",
                    invalidJson
            );
        }
    }

    private static void copyBounded(InputStream input, Path target)
            throws IOException {
        byte[] buffer = new byte[8192];
        long totalBytes = 0;
        long readLimit = MAX_UPLOAD_BYTES + 1;
        try (OutputStream output = Files.newOutputStream(
                target,
                WRITE,
                TRUNCATE_EXISTING
        )) {
            while (totalBytes < readLimit) {
                int requested = (int) Math.min(
                        buffer.length,
                        readLimit - totalBytes
                );
                int count = input.read(buffer, 0, requested);
                if (count == -1) {
                    return;
                }
                totalBytes += count;
                if (totalBytes > MAX_UPLOAD_BYTES) {
                    throw new IllegalArgumentException(
                            "image exceeds the 10 MiB upload limit"
                    );
                }
                output.write(buffer, 0, count);
            }
        }
    }

    private static long deadlineAfter(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    private static void waitForProcess(
            Process process,
            Set<ProcessHandle> trackedHandles,
            long deadlineNanos
    ) throws InterruptedException, TimeoutException {
        while (process.isAlive()) {
            observeDescendants(process, trackedHandles);
            long remaining = remainingNanos(deadlineNanos);
            if (remaining == 0) {
                throw new TimeoutException("YOLO CLI process timed out");
            }
            long waitNanos = Math.min(remaining, OBSERVE_INTERVAL_NANOS);
            process.waitFor(waitNanos, TimeUnit.NANOSECONDS);
        }
        observeDescendants(process, trackedHandles);
    }

    private static void observeDescendants(
            Process process,
            Set<ProcessHandle> trackedHandles
    ) {
        try {
            trackedHandles.add(process.toHandle());
            try (var descendants = process.descendants()) {
                descendants.forEach(trackedHandles::add);
            }
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // Platform/process permissions can make tree observation partial.
        }
    }

    private static StreamCapture readUtf8(InputStream stream, int maxBytes) {
        ByteArrayOutputStream retained = new ByteArrayOutputStream(maxBytes);
        byte[] buffer = new byte[8192];
        long totalBytes = 0;
        try (stream) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                totalBytes += count;
                int remaining = maxBytes - retained.size();
                if (remaining > 0) {
                    retained.write(buffer, 0, Math.min(count, remaining));
                }
            }
            return new StreamCapture(
                    retained.toString(StandardCharsets.UTF_8),
                    totalBytes,
                    totalBytes > maxBytes
            );
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot drain YOLO CLI stream", failure);
        }
    }

    private record StreamCapture(
            String text,
            long totalBytes,
            boolean truncated
    ) {
    }

    private static void cleanupProcess(
            Process process,
            Set<ProcessHandle> trackedHandles,
            CompletableFuture<?> stdoutFuture,
            CompletableFuture<?> stderrFuture,
            long deadlineNanos
    ) {
        if (process != null) {
            observeDescendants(process, trackedHandles);
        }
        closeProcessStreams(process);
        trackedHandles.stream()
                .filter(ProcessHandle::isAlive)
                .forEach(handle -> destroyQuietly(handle, false));
        try {
            joinDrainsWithinDeadline(
                    stdoutFuture,
                    stderrFuture,
                    deadlineNanos
            );
            waitForTrackedHandles(trackedHandles, deadlineNanos);
        } finally {
            if (stdoutFuture != null) {
                stdoutFuture.cancel(true);
            }
            if (stderrFuture != null) {
                stderrFuture.cancel(true);
            }
            trackedHandles.stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(handle -> destroyQuietly(handle, true));
        }
    }

    private static void joinDrainsWithinDeadline(
            CompletableFuture<?> stdoutFuture,
            CompletableFuture<?> stderrFuture,
            long deadlineNanos
    ) {
        long remaining = remainingNanos(deadlineNanos);
        if (remaining == 0 || stdoutFuture == null || stderrFuture == null) {
            return;
        }
        try {
            CompletableFuture.allOf(stdoutFuture, stderrFuture).get(
                    remaining,
                    TimeUnit.NANOSECONDS
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // The original request deadline remains the only wait budget.
        }
    }

    private static void waitForTrackedHandles(
            Set<ProcessHandle> trackedHandles,
            long deadlineNanos
    ) {
        CompletableFuture<?>[] exits = trackedHandles.stream()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::onExit)
                .toArray(CompletableFuture<?>[]::new);
        long remaining = remainingNanos(deadlineNanos);
        if (remaining == 0 || exits.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(exits).get(
                    remaining,
                    TimeUnit.NANOSECONDS
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Force-kill below does not add another blocking wait.
        }
    }

    private static void destroyQuietly(
            ProcessHandle handle,
            boolean forcibly
    ) {
        try {
            if (forcibly) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // Container/cgroup or Job Object remains the hard isolation boundary.
        }
    }

    private static void closeProcessStreams(Process process) {
        if (process == null) {
            return;
        }
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Cleanup is best effort.
        }
    }

    private static YoloCliException protocolFailure(String message) {
        return new YoloCliException(
                CliExit.PROTOCOL_FAILURE,
                null,
                message
        );
    }

    private static YoloCliException timeoutFailure(String action) {
        return new YoloCliException(
                CliExit.TIMEOUT,
                null,
                "YOLO CLI timed out while " + action
        );
    }

    private static YoloCliException timeoutFailure(
            String action,
            TimeoutException cause
    ) {
        return new YoloCliException(
                CliExit.TIMEOUT,
                null,
                "YOLO CLI timed out while " + action,
                cause
        );
    }

    @PreDestroy
    void close() {
        ioExecutor.shutdownNow();
    }
}
```

`ProcessHandle.descendants()`는 부모 프로세스가 먼저 종료하기 전에 관찰한 handle만 보존할 수 있습니다. 매우 짧게 실행된 뒤 re-parent/daemonize되는 자식, 다른 사용자·namespace의 프로세스, 일부 플랫폼 권한 모델에서는 완전한 process-tree 보장을 제공하지 않습니다. 이 코드는 root가 살아 있는 동안 25 ms 간격으로 관찰하고 이미 본 handle은 parent 종료 뒤에도 정리하지만, 운영에서는 컨테이너/cgroup 또는 Windows Job Object 같은 플랫폼 격리와 함께 사용해야 합니다. Python CLI는 daemon을 만들지 않는다는 전제가 필요합니다. cleanup은 새 deadline이나 추가 grace duration을 만들지 않습니다. original `deadlineNanos`의 남은 값만 drain join과 정상 종료 대기에 쓰고, 이미 소진됐으면 즉시 future를 cancel하고 관찰한 live handle을 강제 종료하며 추가로 기다리지 않습니다.

CLI `predict`의 허용 matrix는 다음뿐입니다. 종료 `0`은 성공 DTO만 허용합니다. 아래 조합 이외의 종료 코드/오류 코드, 종료 `0`의 오류 DTO, nonzero 종료의 성공 DTO는 모두 success/error body mismatch를 포함한 `PROTOCOL_FAILURE`입니다.

| CLI predict 종료 코드 | 허용 오류 코드 |
|---:|---|
| `2` | `INVALID_REQUEST` |
| `3` | `MODEL_NOT_READY` |
| `4` | `INVALID_IMAGE` |
| `4` | `PAYLOAD_TOO_LARGE` |
| `5` | `INFERENCE_FAILED` |

```java
package com.example.yolo;

import java.util.Map;
import java.util.Set;

public enum CliExit {
    SUCCESS,
    INVALID_REQUEST,
    MODEL_NOT_READY,
    INVALID_INPUT,
    INFERENCE_FAILED,
    TIMEOUT,
    INTERRUPTED,
    PROTOCOL_FAILURE;

    private static final Map<Integer, Set<String>> PREDICT_ERROR_CODES =
            Map.of(
                    2, Set.of("INVALID_REQUEST"),
                    3, Set.of("MODEL_NOT_READY"),
                    4, Set.of("INVALID_IMAGE", "PAYLOAD_TOO_LARGE"),
                    5, Set.of("INFERENCE_FAILED")
            );

    public static CliExit fromPredict(int exitCode, String code) {
        Set<String> allowedCodes = PREDICT_ERROR_CODES.get(exitCode);
        if (allowedCodes == null || !allowedCodes.contains(code)) {
            return PROTOCOL_FAILURE;
        }
        return switch (exitCode) {
            case 2 -> INVALID_REQUEST;
            case 3 -> MODEL_NOT_READY;
            case 4 -> INVALID_INPUT;
            case 5 -> INFERENCE_FAILED;
            default -> PROTOCOL_FAILURE;
        };
    }
}
```

```java
package com.example.yolo;

import com.example.yolo.YoloDtos.ErrorResponse;

public final class YoloCliException extends RuntimeException {
    private final CliExit exit;
    private final ErrorResponse response;

    public YoloCliException(
            CliExit exit,
            ErrorResponse response,
            String message
    ) {
        super(message);
        this.exit = exit;
        this.response = response;
    }

    public YoloCliException(
            CliExit exit,
            ErrorResponse response,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.exit = exit;
        this.response = response;
    }

    public CliExit exit() {
        return exit;
    }

    public ErrorResponse response() {
        return response;
    }
}
```

각 public type은 표시된 것처럼 자기 파일에 둡니다. 종료 코드 `4`는 오류 JSON의 `code`를 추가로 읽어 `INVALID_IMAGE`와 `PAYLOAD_TOO_LARGE`를 구분합니다. stderr는 크기 제한을 두고 내부 진단으로만 취급하며 클라이언트에 그대로 반환하지 않습니다.

## WebClient 통합

Spring Boot에 `spring-boot-starter-webflux`를 추가합니다. MVC 애플리케이션에서도 HTTP 클라이언트로 `WebClient`를 사용할 수 있습니다.

### 연결, 응답, 버퍼 제한

연결 타임아웃은 TCP 연결 수립 제한이고, 응답 타임아웃은 서버가 응답을 내기까지의 제한입니다. Reactor의 응답 타임아웃 외에도 체인 전체에 `timeout`을 적용합니다. 최대 버퍼는 **응답 본문** 상한입니다. 파일 업로드 상한은 Spring multipart 설정과 Python의 `YOLO_MAX_UPLOAD_MIB`를 같은 값으로 맞추고, Spring 전체 request 상한은 파일 상한에 Python과 같은 multipart framing 여유 1 MiB만 더합니다.

```java
package com.example.yolo;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class YoloWebClientConfiguration {
    @Bean
    WebClient yoloWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(35));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs()
                        .maxInMemorySize(2 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .baseUrl("http://yolo:8000")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
```

운영에서는 base URL과 세 타임아웃을 `@ConfigurationProperties`로 외부화하십시오. 재시도는 연결 실패처럼 요청이 서버에 도달하지 않았음이 분명한 경우에만 제한적으로 사용합니다. 추론 타임아웃에 대한 무조건 재시도는 CPU 부하와 중복 작업을 늘립니다.

### multipart 요청과 상태별 오류 파싱

아래 예제는 `ByteArrayResource`의 `getFilename()`을 재정의해 multipart filename을 보존합니다. 성공과 오류 본문을 같은 `ObjectMapper`와 DTO로 파싱하고 HTTP 상태와 안정 오류 코드를 함께 보존합니다.

```java
package com.example.yolo;

import com.example.yolo.YoloDtos;
import com.example.yolo.YoloDtos.ErrorResponse;
import com.example.yolo.YoloDtos.PredictionResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public final class YoloHttpClient {
    private static final Map<Integer, Set<String>> HTTP_ERROR_CODES =
            Map.of(
                    400, Set.of("INVALID_REQUEST", "INVALID_IMAGE"),
                    404, Set.of("INVALID_REQUEST"),
                    413, Set.of("PAYLOAD_TOO_LARGE"),
                    503, Set.of("MODEL_NOT_READY"),
                    500, Set.of("INFERENCE_FAILED")
            );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Duration totalTimeout = Duration.ofSeconds(40);

    public YoloHttpClient(WebClient yoloWebClient, ObjectMapper objectMapper) {
        this.webClient = yoloWebClient;
        this.objectMapper = objectMapper.copy().enable(
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES
        );
    }

    public Mono<PredictionResponse> predict(
            byte[] imageBytes,
            String safeFilename,
            double confidence,
            int imageSize
    ) {
        ByteArrayResource image = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return safeFilename;
            }
        };

        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("file", image)
                .filename(safeFilename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        multipart.part("conf", Double.toString(confidence));
        multipart.part("imgsz", Integer.toString(imageSize));

        return webClient.post()
                .uri("/predict")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart.build()))
                .exchangeToMono(response ->
                        response.bodyToMono(byte[].class)
                                .defaultIfEmpty(new byte[0])
                                .flatMap(body -> parseResponse(
                                        response.statusCode(),
                                        body,
                                        response.headers().asHttpHeaders()
                                                .getFirst("X-Request-ID")
                                ))
                )
                .timeout(totalTimeout);
    }

    private Mono<PredictionResponse> parseResponse(
            HttpStatusCode status,
            byte[] body,
            String headerRequestId
    ) {
        try {
            if (status.value() == 200) {
                PredictionResponse prediction = YoloDtos.validatePrediction(
                        objectMapper.readValue(body, PredictionResponse.class)
                );
                requireMatchingRequestId(
                        headerRequestId,
                        prediction.requestId()
                );
                return Mono.just(prediction);
            }
            if (status.is2xxSuccessful()) {
                throw new IllegalArgumentException(
                        "unexpected successful HTTP status"
                );
            }
            ErrorResponse error = YoloDtos.validateError(
                    objectMapper.readValue(body, ErrorResponse.class),
                    true
            );
            validateHttpError(status.value(), error.error().code());
            requireMatchingRequestId(
                    headerRequestId,
                    error.error().requestId()
            );
            return Mono.error(new YoloHttpException(status.value(), error));
        } catch (IOException | IllegalArgumentException invalidJson) {
            return Mono.error(new YoloProtocolException(
                    "YOLO service returned an invalid protocol response",
                    invalidJson
            ));
        }
    }

    private static void validateHttpError(int status, String code) {
        Set<String> allowedCodes = HTTP_ERROR_CODES.get(status);
        if (allowedCodes == null || !allowedCodes.contains(code)) {
            throw new IllegalArgumentException(
                    "HTTP status/error code mismatch"
            );
        }
    }

    private static void requireMatchingRequestId(
            String headerRequestId,
            String bodyRequestId
    ) {
        if (headerRequestId == null || !headerRequestId.equals(bodyRequestId)) {
            throw new IllegalArgumentException("X-Request-ID mismatch");
        }
    }
}
```

```java
package com.example.yolo;

import com.example.yolo.YoloDtos.ErrorResponse;

public final class YoloHttpException extends RuntimeException {
    private final int httpStatus;
    private final ErrorResponse response;

    public YoloHttpException(int httpStatus, ErrorResponse response) {
        super(response.error().code() + " from YOLO service");
        this.httpStatus = httpStatus;
        this.response = response;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public ErrorResponse response() {
        return response;
    }
}
```

```java
package com.example.yolo;

public final class YoloProtocolException extends RuntimeException {
    public YoloProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

각 public type은 표시된 것처럼 자기 파일에 둡니다. 성공은 정확히 HTTP `200`과 성공 DTO 조합만 허용합니다. 오류의 엄격한 matrix는 다음과 같고, 그 밖의 상태/코드나 success/error body mismatch는 `YoloProtocolException`입니다.

| HTTP 상태 | 허용 오류 코드 |
|---:|---|
| `400` | `INVALID_REQUEST` |
| `400` | `INVALID_IMAGE` |
| `404` | `INVALID_REQUEST` |
| `413` | `PAYLOAD_TOO_LARGE` |
| `503` | `MODEL_NOT_READY` |
| `500` | `INFERENCE_FAILED` |

`400`, `413`, `503`, `500`을 단일 “외부 서버 오류”로 뭉개지 말고 검증을 통과한 `ErrorResponse.error.code`를 업무 오류로 변환하십시오. `X-Request-ID` 헤더와 본문의 `request_id`가 다르면 프로토콜 오류로 기록합니다.

`ByteArrayResource`는 전체 업로드를 JVM heap에 보관하므로 동시 요청 수만큼 메모리를 사용합니다. 기본 10 MiB처럼 제한된 파일에는 단순하지만, 파일이 커지거나 동시성이 높으면 디스크 임시 파일과 `FileSystemResource`로 스트리밍하고 성공·오류·취소 모두에서 임시 파일을 정리하십시오. `maxInMemorySize`는 업로드 크기 제한이 아니므로 Spring의 `spring.servlet.multipart.max-file-size` 또는 WebFlux multipart 제한도 별도로 설정해야 합니다.

### `/ready` 호출

같은 client가 준비 상태를 엄격한 DTO로 파싱하도록 다음 method와 parser를
`YoloHttpClient`에 추가합니다. 성공한 `/ready` 본문에는 `request_id`가
없지만 모든 오류 본문에는 있으므로 오류 경로에서는 header/body ID를
계속 비교합니다.

```java
import com.example.yolo.YoloDtos.ReadyResponse;

public Mono<ReadyResponse> ready() {
    return webClient.get()
            .uri("/ready")
            .exchangeToMono(response ->
                    response.bodyToMono(byte[].class)
                            .defaultIfEmpty(new byte[0])
                            .flatMap(body -> parseReadyResponse(
                                    response.statusCode(),
                                    body,
                                    response.headers().asHttpHeaders()
                                            .getFirst("X-Request-ID")
                            ))
            )
            .timeout(totalTimeout);
}

private Mono<ReadyResponse> parseReadyResponse(
        HttpStatusCode status,
        byte[] body,
        String headerRequestId
) {
    try {
        if (status.value() == 200) {
            ReadyResponse ready = YoloDtos.validateReady(
                    objectMapper.readValue(body, ReadyResponse.class)
            );
            return Mono.just(ready);
        }
        if (status.is2xxSuccessful()) {
            throw new IllegalArgumentException(
                    "unexpected successful readiness HTTP status"
            );
        }
        ErrorResponse error = YoloDtos.validateError(
                objectMapper.readValue(body, ErrorResponse.class),
                true
        );
        validateHttpError(status.value(), error.error().code());
        requireMatchingRequestId(
                headerRequestId,
                error.error().requestId()
        );
        return Mono.error(new YoloHttpException(status.value(), error));
    } catch (IOException | IllegalArgumentException invalidJson) {
        return Mono.error(new YoloProtocolException(
                "YOLO service returned an invalid readiness response",
                invalidJson
        ));
    }
}
```

위 두 method는 앞의 `YoloHttpClient` class 안에 둡니다. import도 같은 파일의
기존 import 목록에 합칩니다.

### Spring Actuator readiness 연결

`spring-boot-starter-actuator`를 추가하고 다음 health contributor를 별도
파일로 둡니다. 오류 exception/message를 actuator detail로 복사하지 않고
의존 서비스 이름만 노출합니다.

```java
package com.example.yolo;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("yoloReadiness")
public final class YoloReadinessHealthIndicator
        implements ReactiveHealthIndicator {
    private final YoloHttpClient yoloHttpClient;

    public YoloReadinessHealthIndicator(YoloHttpClient yoloHttpClient) {
        this.yoloHttpClient = yoloHttpClient;
    }

    @Override
    public Mono<Health> health() {
        Health down = Health.down()
                .withDetail("dependency", "yolo")
                .build();
        return yoloHttpClient.ready()
                .map(ready -> Health.up()
                        .withDetail("dependency", "yolo")
                        .withDetail("weights", ready.weights())
                        .withDetail("device", ready.device())
                        .build())
                .onErrorReturn(down);
    }
}
```

readiness group에 contributor를 포함합니다.
아래 중첩 YAML은 `management.endpoint.health.probes.enabled: true`와
readiness group의 `yoloReadiness` 포함 설정을 표현합니다.

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,yoloReadiness
```

이 작업 환경에는 Maven/Gradle과 Java 17이 없으므로 위 Java 17 컴파일 검증은 보류
상태입니다. 구조 테스트는 method, DTO validation, Actuator 연결을 검사하지만
실제 Spring Boot dependency resolution과 compile을 대체하지 않습니다.

## 경계별 권장 매핑

| Python 결과 | Spring 처리 |
|---|---|
| HTTP `400` / `INVALID_REQUEST` | 클라이언트 옵션 검증 오류 |
| HTTP `400` / `INVALID_IMAGE` | 지원하지 않거나 손상된 이미지 |
| HTTP `413` / `PAYLOAD_TOO_LARGE` | 업로드 크기 오류 |
| HTTP `503` / `MODEL_NOT_READY` | 준비되지 않은 의존 서비스; readiness/배포 상태 확인 |
| HTTP `500` / `INFERENCE_FAILED` | 추론 내부 실패; 제한적 장애 응답 |
| 연결/전체 타임아웃 | 의존 서비스 타임아웃; 무조건 재시도 금지 |
| JSON 역직렬화 실패 | 버전 또는 프로토콜 불일치 |

Spring이 외부로 반환하는 오류에는 Python의 안정 코드와 `request_id`만 포함하고 내부 경로, traceback, stderr, 원본 이미지나 모델 진단을 포함하지 마십시오.
