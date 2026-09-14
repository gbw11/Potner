package com.potner.diary.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 일기 배치는 광량 집계나 MQTT 와 무관하게 돌아야 하므로 스케줄링을 여기서도 켠다.
 *
 * <p>{@code @EnableScheduling} 은 여러 번 붙어도 무해하다. 다른 모듈의 설정에 얹혀 가면
 * 그쪽을 끄는 순간 이쪽도 조용히 멈춘다. 광량 집계가 MQTT 설정에 얹혀 있다가 같은 이유로
 * 분리됐다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiaryProperties.class)
@EnableScheduling
public class DiaryConfiguration {
}
