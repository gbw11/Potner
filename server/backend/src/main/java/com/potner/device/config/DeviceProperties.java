package com.potner.device.config;

import com.potner.device.domain.IotDeviceType;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 장치 정책이다. MQTT 전송 설정과 나눠 둔다.
 *
 * <p>{@code potner.mqtt.*} 는 {@code MqttConfiguration} 이 등록하는데 그 설정은
 * {@code potner.mqtt.enabled=true} 일 때만 로드된다. 브로커를 끈 환경에서도 살아 있어야 하는
 * 도메인 서비스가 그 프로퍼티를 주입받으면 컨텍스트가 아예 뜨지 않는다. 실제로 한 번 겪었다.
 *
 * <p>여기 있는 값은 어느 장치를 신뢰할지에 관한 것이고 전송 수단과 무관하다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.device")
public record DeviceProperties(

        /**
         * 배터리 잔량을 보고하도록 정한 장치 종류다.
         *
         * <p>{@code robot.battery_percent} 가 컬럼 하나라 두 장치가 보내면 서로 덮어쓴다.
         * 이동 로봇은 배터리 팩 하나로 두 보드를 돌리므로 젯슨만 보내기로 정했고 서버가 그
         * 규칙을 강제한다. 배터리 센서가 라즈베리로 옮겨가면 이미지를 다시 만들지 않고 이 값만
         * 바꾼다.
         *
         * <p>문자열이 아니라 enum 으로 받는다. 잘못된 값이면 컨텍스트가 시작될 때 실패해서 바로
         * 드러난다.
         */
        @NotNull IotDeviceType batteryReporterType
) {
}
