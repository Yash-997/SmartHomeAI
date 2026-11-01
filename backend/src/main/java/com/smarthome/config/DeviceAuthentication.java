package com.smarthome.config;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authentication for an ESP32 device. A different type from the user's JwtAuthenticationToken on purpose. */
public class DeviceAuthentication extends AbstractAuthenticationToken {

	private final String deviceId;

	public DeviceAuthentication(String deviceId) {
		super(List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
		this.deviceId = deviceId;
		setAuthenticated(true);
	}

	public String getDeviceId() {
		return deviceId;
	}

	@Override
	public Object getPrincipal() {
		return deviceId;
	}

	@Override
	public Object getCredentials() {
		return null; // never keep the credential around
	}
}
