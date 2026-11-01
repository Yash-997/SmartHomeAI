package com.smarthome;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.smarthome.mqtt.BrokerAccess;
import com.smarthome.mqtt.CommandPublisher;

/** Stands in for the broker in tests: remembers every command "published" and can simulate an outage. */
@Component
@Primary
public class RecordingCommandPublisher implements CommandPublisher {

	public record Published(String deviceId, String json) {
	}

	private final List<Published> published = new CopyOnWriteArrayList<>();
	private volatile boolean down;

	@Override
	public Link link() {
		return down ? Link.DISCONNECTED : Link.CONNECTED;
	}

	@Override
	public void publish(String deviceId, String json) {
		if (down) {
			throw new BrokerAccess.BrokerUnavailableException("broker is down (test)");
		}
		published.add(new Published(deviceId, json));
	}

	public List<Published> published() {
		return List.copyOf(published);
	}

	public List<Published> publishedTo(String deviceId) {
		return published.stream().filter(p -> p.deviceId().equals(deviceId)).toList();
	}

	public void setDown(boolean down) {
		this.down = down;
	}
}
