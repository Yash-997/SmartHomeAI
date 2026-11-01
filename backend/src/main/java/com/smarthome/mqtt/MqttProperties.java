package com.smarthome.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection settings for the Mosquitto broker. Passwords have no defaults and are read from .env. */
@ConfigurationProperties(prefix = "app.mqtt")
public record MqttProperties(
		boolean enabled,
		String host,
		int port,
		String adminUsername,
		String adminPassword,
		String backendUsername,
		String backendPassword) {

	public String brokerUri() {
		return "tcp://" + host + ":" + port;
	}
}
