package com.smarthome.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used when MQTT is switched off (MQTT_ENABLED=false). Devices can pair but cannot connect to any broker. */
@Component
@ConditionalOnProperty(name = "app.mqtt.enabled", havingValue = "false")
public class NoopBrokerAccess implements BrokerAccess {

	private static final Logger log = LoggerFactory.getLogger(NoopBrokerAccess.class);

	@Override
	public void provisionDevice(String deviceId, String credential) {
		log.warn("MQTT disabled: device {} was paired but no broker account was created", deviceId);
	}

	@Override
	public void revokeDevice(String deviceId) {
		log.debug("MQTT disabled: nothing to revoke for device {}", deviceId);
	}
}
