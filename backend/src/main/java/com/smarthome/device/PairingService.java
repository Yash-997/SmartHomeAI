package com.smarthome.device;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.auth.ConflictException;
import com.smarthome.device.DeviceDtos.DevicePairResponse;
import com.smarthome.device.DeviceDtos.PairingCodeResponse;
import com.smarthome.device.DeviceExceptions.InvalidPairingCodeException;
import com.smarthome.mqtt.BrokerAccess;

@Service
public class PairingService {

	private final PairingCodeRepository codes;
	private final DeviceRepository devices;
	private final BrokerAccess broker;
	private final Duration ttl;

	public PairingService(PairingCodeRepository codes, DeviceRepository devices, BrokerAccess broker,
			@Value("${app.pairing.code-ttl-minutes:10}") long ttlMinutes) {
		this.codes = codes;
		this.devices = devices;
		this.broker = broker;
		this.ttl = Duration.ofMinutes(ttlMinutes);
	}

	/** Step 1 (signed-in user): creates a code bound to {@code userId}. Replaces the user's unused codes. */
	@Transactional
	public PairingCodeResponse createCode(UUID userId, String deviceName) {
		Instant now = Instant.now();
		codes.deleteByUserIdAndUsedAtIsNull(userId);
		String code = Secrets.newPairingCode();
		PairingCode entity = new PairingCode(userId, Secrets.sha256Hex(code), deviceName.trim(), now, now.plus(ttl));
		codes.saveAndFlush(entity);
		return new PairingCodeResponse(Secrets.formatCode(code), entity.getDeviceName(), entity.getExpiresAt(), ttl.toSeconds());
	}

	/**
	 * Step 2 (the device): redeems a code. The owner is whoever created the code, never anything the
	 * caller supplies. Any failure after the code is consumed rolls the consumption back, so a
	 * conflict does not burn a legitimate user's code.
	 */
	@Transactional
	public DevicePairResponse redeem(String rawCode, String rawDeviceId) {
		Instant now = Instant.now();
		String hash = Secrets.sha256Hex(Secrets.normalizeCode(rawCode));
		if (codes.consume(hash, now) != 1) {
			throw new InvalidPairingCodeException();
		}
		PairingCode code = codes.findByCodeHash(hash).orElseThrow(InvalidPairingCodeException::new);

		String hardwareId = rawDeviceId.trim().toLowerCase();
		String credential = Secrets.newCredential();
		String credentialHash = Secrets.sha256Hex(credential);

		Device device = devices.findByDeviceId(hardwareId).orElse(null);
		if (device == null) {
			device = new Device(hardwareId, code.getUserId(), code.getDeviceName(), now, credentialHash);
		} else if (device.getStatus() == DeviceStatus.DISOWNED) {
			device.pair(code.getUserId(), code.getDeviceName(), now, credentialHash);
		} else {
			throw new ConflictException("This device is already paired. Its owner must disown it first.");
		}
		try {
			devices.saveAndFlush(device);
		} catch (DataIntegrityViolationException e) {
			// Two pairings for the same new device raced; the unique constraint picked one.
			throw new ConflictException("This device is already paired. Its owner must disown it first.");
		}
		// Give the device its broker account last: if the broker is down this throws, everything above (including
		// consuming the code) rolls back, and the user can simply try again with the same code.
		broker.provisionDevice(device.getDeviceId(), credential);
		return new DevicePairResponse(device.getDeviceId(), device.getName(), credential);
	}
}
