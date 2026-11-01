package com.smarthome.command;

import java.util.List;
import java.util.Optional;

/**
 * The only commands the system will ever send. This list is the single source of truth: the API validates against
 * it, the UI renders its controls from it (via DeviceResponse.outputs) and the firmware has its own matching copy.
 *
 * <p>Safety rule: the gas warning LED can be forced ON (to identify the device) or returned to AUTO, but there is
 * deliberately no way to force it OFF. The firmware also refuses it and always lights the LED on a gas warning.
 */
public final class CommandActions {

	public static final String SET_PIR_LED = "set_pir_led";
	public static final String SET_GAS_LED = "set_gas_led";

	public record Output(String action, String label, List<String> values) {
	}

	public static final List<Output> OUTPUTS = List.of(
			new Output(SET_PIR_LED, "PIR indicator LED", List.of("auto", "on", "off")),
			new Output(SET_GAS_LED, "Gas warning LED", List.of("auto", "on")));

	private CommandActions() {
	}

	public static Optional<Output> find(String action) {
		return OUTPUTS.stream().filter(o -> o.action().equals(action)).findFirst();
	}
}
