package com.smarthome.assistant.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smarthome.assistant.tools.ToolTypes.Param;
import com.smarthome.assistant.tools.ToolTypes.ToolArgumentException;

import tools.jackson.databind.json.JsonMapper;

class ToolArgsTest {

	private final UUID kitchen = UUID.randomUUID();
	private final UUID bedroom = UUID.randomUUID();

	private Map<UUID, String> devices(String... names) {
		Map<UUID, String> m = new LinkedHashMap<>();
		for (String n : names) {
			m.put(UUID.randomUUID(), n);
		}
		return m;
	}

	@Test
	void devicesResolveOnlyAmongTheCallersOwn() {
		Map<UUID, String> mine = new LinkedHashMap<>(Map.of(kitchen, "Kitchen", bedroom, "Bedroom"));
		assertEquals(kitchen, ToolArgs.resolveDevice(mine, "the kitchen"));
		assertEquals(bedroom, ToolArgs.resolveDevice(mine, bedroom.toString()));
		// someone else's device id is refused, never looked up
		assertThrows(ToolArgumentException.class, () -> ToolArgs.resolveDevice(mine, UUID.randomUUID().toString()));
		assertThrows(ToolArgumentException.class, () -> ToolArgs.resolveDevice(mine, "garage"));
	}

	@Test
	void twoDevicesWithTheSameNameAreAmbiguous() {
		ToolArgumentException e = assertThrows(ToolArgumentException.class, () -> ToolArgs.resolveDevice(devices("Kitchen", "kitchen"), "Kitchen"));
		assertTrue(e.getMessage().contains("More than one"), e.getMessage());
	}

	@Test
	void veryShortDeviceNamesDoNotMatchInsideOtherWords() {
		// "Bathroom" contains "a"; a device called "A" must not be picked for it
		Map<UUID, String> m = devices("A", "Bathroom");
		UUID bath = m.entrySet().stream().filter(x -> x.getValue().equals("Bathroom")).findFirst().orElseThrow().getKey();
		assertEquals(bath, ToolArgs.resolveDevice(m, "bathroom area"));
	}

	@Test
	void argumentsAreValidatedStrictly() {
		var mapper = JsonMapper.builder().build();
		List<Param> params = List.of(Param.choice("period", "p", List.of("today", "yesterday"), false), Param.date("from", "f"));
		assertThrows(ToolArgumentException.class, () -> ToolArgs.parse("{\"sql\":\"select 1\"}", params, mapper));
		assertThrows(ToolArgumentException.class, () -> ToolArgs.parse("{\"period\":\"forever\"}", params, mapper));
		assertThrows(ToolArgumentException.class, () -> ToolArgs.parse("{\"from\":\"31/01/2026\"}", params, mapper));
		assertThrows(ToolArgumentException.class, () -> ToolArgs.parse("[1,2]", params, mapper));
		assertEquals("today", ToolArgs.parse("{\"period\":\"TODAY\"}", params, mapper).string("period"));
	}
}
