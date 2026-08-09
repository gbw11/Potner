package com.potner.vision.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * 사진 생장 단계 추론 설정이다.
 *
 * <p>{@code enabled} 가 꺼져 있으면 분류기가 no-op 으로 떨어진다. LLM 과 달리 플래그를 두는
 * 이유는 자격증명이 없어서가 아니라 <strong>의존 서비스가 없을 수 있기 때문</strong>이다.
 * 추론은 별도 컨테이너라 로컬과 임시 CI 컨테이너에는 떠 있지 않다. 키의 유무로 갈리는
 * {@code potner.llm.api-key} 와는 판단 근거가 다르므로 플래그가 필요하다.
 *
 * <p>환경변수 접두어를 {@code VISION_} 으로 둔다. 추론 컨테이너 자신의 설정이
 * {@code YOLO_WEIGHTS_PATH} 처럼 {@code YOLO_} 를 쓰고 있어, 같은 접두어를 쓰면 어느 쪽 설정인지
 * 구분되지 않는다. 여기 값들은 모두 <em>부르는 쪽</em>의 설정이다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.vision")
public record VisionProperties(
        boolean enabled,

        /** 도커 네트워크 안의 추론 서비스다. 포트를 호스트로 열지 않으므로 서비스명으로 부른다. */
        @NotBlank String baseUrl,

        /** TCP 연결 수립 제한이다. 같은 네트워크 안이라 짧게 잡는다. */
        @Positive int connectTimeoutSeconds,

        /**
         * 응답 대기 제한이다.
         *
         * <p>CPU 추론이고 {@code YOLO_MAX_CONCURRENCY=1} 이라 앞선 요청이 있으면 그만큼 더
         * 기다린다. 짧게 잡으면 정상 대기를 실패로 처리한다.
         */
        @Positive int readTimeoutSeconds,

        /**
         * 추론 서비스에 넘길 신뢰도 하한이다.
         *
         * <p>추론 컨테이너 기본값 0.10 은 매우 낮아 오탐이 그대로 올라온다. 여기서 올려 잡고,
         * 올라온 검출은 {@code minConfidence} 로 한 번 더 걸러 단계 판정에 쓴다.
         */
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal requestConfidence,

        /** 추론 서비스에 넘길 입력 변 길이다. 모델 학습 크기와 같아야 판정이 흔들리지 않는다. */
        @Positive int imageSize,

        /**
         * 단계 판정에 쓸 신뢰도 하한이다. 이보다 낮은 검출은 판정하지 않고 버린다.
         *
         * <p>{@code requestConfidence} 와 따로 두는 이유는 역할이 다르기 때문이다. 그쪽은
         * "무엇을 받아올지", 이쪽은 "받아온 것 중 무엇을 믿을지"다. 오탐을 줄이려면 이 값을
         * 올린다. 실측 오탐률을 본 뒤 조정한다.
         */
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minConfidence,

        /**
         * 판정 결과로 식물의 생장 단계를 실제로 올릴지 여부다.
         *
         * <p><strong>기본값이 꺼짐이다.</strong> 켜면 사용자가 손대지 않아도 단계가 바뀌고 푸시가
         * 나간다. 오탐이 그대로 사용자에게 도달하며 되돌릴 수단이 없으므로, 실측 오탐률을 보고
         * {@code minConfidence} 를 확정한 뒤에 켠다. 꺼져 있어도 판정과 저장은 그대로 돌아가
         * 그동안 정확도를 관찰할 수 있다.
         */
        boolean autoAdvanceEnabled,

        /**
         * 개화 판정으로 개화 기록을 자동으로 남길지 여부다.
         *
         * <p>{@code autoAdvanceEnabled} 와 달리 <strong>기본값이 켜짐이다.</strong> 단계 승급은
         * 되돌릴 수단이 없지만 개화 기록은 삭제 API 가 있어 오탐을 사용자가 정리할 수 있고,
         * {@code minConfidence} 와 재개화 냉각으로 걸러진 것만 남는다.
         */
        boolean autoBloomEnabled,

        /**
         * 자동 개화 기록의 재기록 냉각 기간(일)이다.
         *
         * <p>연속 개화는 직전 판정과의 비교로 걸러지지만, 촬영 각도에 따라 판정이 개화 ↔ 영양생장
         * 사이를 오가면 그때마다 "새 개화" 가 된다. 최근 이 기간 안에 개화 기록(수동 포함)이
         * 있으면 자동 기록을 건너뛴다.
         */
        @Positive int bloomCooldownDays,

        /**
         * 발아 판정을 사용자에게 알릴지 여부다.
         *
         * <p>단계 승급으로는 알릴 수 없다. 발아기가 생장 단계의 맨 아래라 올라갈 곳이 없어
         * 판정이 정확해도 {@code NOT_PROGRESSED} 로 조용히 끝난다. 그런데 씨앗을 심고 기다리던
         * 사용자에게 싹이 텄다는 것은 개화만큼 큰 소식이다.
         *
         * <p>{@code autoBloomEnabled} 와 같은 이유로 기본값이 켜짐이다. 오탐이 나도 알림 하나이고
         * 단계 승급처럼 되돌릴 수 없는 상태 변경이 아니다.
         */
        boolean autoSproutEnabled,

        /**
         * 새싹 알림의 재알림 냉각 기간(일)이다.
         *
         * <p>발아는 생애에 한 번이므로 "이전 판정이 하나도 없을 때만" 이 가장 정확하다. 그런데
         * 그러면 판정 이력이 이미 쌓인 식물에서는 영영 울리지 않아, 기능이 도는지 확인할 방법이
         * 없다. 그래서 개화와 같은 냉각 방식으로 둔다 — 최근 이 기간 안에 확신 판정이 있으면
         * 건너뛴다.
         *
         * <p>0 이면 판정될 때마다 알린다. 시연에서 반복해 보여줄 때 쓴다.
         */
        @PositiveOrZero int sproutCooldownDays
) {
}
