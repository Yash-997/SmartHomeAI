package com.smarthome.device;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticates a device by its hardware id plus the credential issued at pairing. Independent of user JWTs. */
@Service
public class DeviceAuthService {

	private final DeviceRepository devices;

	public DeviceAuthService(DeviceRepository devices) {
		this.devices = devices;
	}

	/** Empty when the device is unknown, disowned, revoked, or the credential is wrong. */
	@Transactional(readOnly = true)
	public Optional<Device> authenticate(String deviceId, String credential) {
		return devices.findByDeviceId(deviceId.trim().toLowerCase())
				.filter(d -> d.getStatus() != DeviceStatus.DISOWNED && d.getCredentialHash() != null)
				.filter(d -> Secrets.hashesEqual(d.getCredentialHash(), Secrets.sha256Hex(credential)));
	}
}
