package com.smarthome;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.smarthome.mqtt.BrokerAccess;

/**
 * Stands in for the real broker in tests: remembers which devices currently have a broker account and can be told
 * to fail like an unreachable broker. Imported explicitly by the tests that need it.
 */
@Component
@Primary
public class RecordingBrokerAccess implements BrokerAccess {

	private final Map<String, String> accounts = new ConcurrentHashMap<>();
	private volatile boolean down;

	@Override
	public void provisionDevice(String deviceId, String credential) {
		failIfDown();
		accounts.put(deviceId, credential);
	}

	@Override
	public void revokeDevice(String deviceId) {
		failIfDown();
		accounts.remove(deviceId);
	}

	private void failIfDown() {
		if (down) {
			throw new BrokerUnavailableException("broker is down (test)");
		}
	}

	public boolean hasAccount(String deviceId) {
		return accounts.containsKey(deviceId);
	}

	public String credentialOf(String deviceId) {
		return accounts.get(deviceId);
	}

	public Set<String> accountIds() {
		return Set.copyOf(accounts.keySet());
	}

	public void setDown(boolean down) {
		this.down = down;
	}
}
