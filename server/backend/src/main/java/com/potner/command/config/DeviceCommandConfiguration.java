package com.potner.command.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DeviceCommandProperties.class, DriveProperties.class})
public class DeviceCommandConfiguration {
}
