package com.smarthome.mqtt;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used when MQTT is switched off: commands cannot be delivered, and say so. */
@Component
@ConditionalOnProperty(name = "app.mqtt.enabled", havingValue = "false")
public class NoopCommandPublisher implements CommandPublisher {

	@Override
	public Link link() {
		return Link.DISABLED;
	}

	@Override
	public void publish(String deviceId, String json) {
		throw new BrokerAccess.BrokerUnavailableException("MQTT is disabled (MQTT_ENABLED=false)");
	}
}
