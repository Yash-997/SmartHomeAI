package com.smarthome.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.smarthome.assistant.AssistantProperties;
import com.smarthome.automation.AutomationProperties;
import com.smarthome.ml.MlProperties;
import com.smarthome.mqtt.MqttProperties;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({ MqttProperties.class, AutomationProperties.class, MlProperties.class, AssistantProperties.class })
public class AppConfig {

	/** Injected wherever "now" matters, so time-dependent logic is testable. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
