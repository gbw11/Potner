package com.potner.arrival.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArrivalProperties.class)
public class ArrivalConfiguration {
}
