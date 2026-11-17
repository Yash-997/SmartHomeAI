package com.smarthome.assistant.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.smarthome.assistant.LlmClient.ToolSpec;
import com.smarthome.assistant.Text;
import com.smarthome.assistant.tools.ToolTypes.Param;
import com.smarthome.assistant.tools.ToolTypes.ToolArgumentException;
import com.smarthome.assistant.tools.ToolTypes.ToolContext;
import com.smarthome.assistant.tools.ToolTypes.ToolOutput;
import com.smarthome.assistant.tools.ToolTypes.ToolResult;

import tools.jackson.databind.ObjectMapper;

/**
 * The only functions a language model can ask the backend to run. A call is executed only if:
 * <ol>
 * <li>its name is one of the registered tools (an allow-list, not reflection);</li>
 * <li>its arguments pass {@link ToolArgs#parse} against that tool's declared parameters;</li>
 * <li>every device it names resolves to one of the caller's own devices.</li>
 * </ol>
 * Tools are read-only queries, except {@code create_report} (stores a report for the caller) and
 * {@code propose_device_command} (which sends nothing: it returns a proposal the user must confirm in the app).
 * There is no tool that runs SQL, changes settings, rules or devices.
 */
@Component
public class ToolRegistry {

	private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

	/** A registered tool. */
	public record Tool(String name, String description, List<Param> params, Runner runner) {
	}

	@FunctionalInterface
	public interface Runner {
		ToolOutput run(ToolContext ctx, ToolArgs args);
	}

	private final Map<String, Tool> tools = new LinkedHashMap<>();
	private final ObjectMapper mapper;

	public ToolRegistry(SmartHomeTools smartHome, ObjectMapper mapper) {
		this.mapper = mapper;
		smartHome.all().forEach(t -> tools.put(t.name(), t));
	}

	public List<String> names() {
		return List.copyOf(tools.keySet());
	}

	/** JSON-schema descriptions for the model. */
	public List<ToolSpec> specs() {
		List<ToolSpec> out = new ArrayList<>();
		for (Tool t : tools.values()) {
			Map<String, Object> props = new LinkedHashMap<>();
			List<String> required = new ArrayList<>();
			for (Param p : t.params()) {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("type", switch (p.type()) {
					case "integer" -> "integer";
					default -> "string";
				});
				String desc = p.description() + ("date".equals(p.type()) ? " Format YYYY-MM-DD." : "");
				s.put("description", desc);
				if (!p.allowed().isEmpty()) {
					s.put("enum", p.allowed());
				}
				if (p.min() != null) {
					s.put("minimum", p.min());
				}
				if (p.max() != null) {
					s.put("maximum", p.max());
				}
				props.put(p.name(), s);
				if (p.required()) {
					required.add(p.name());
				}
			}
			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", "object");
			schema.put("properties", props);
			schema.put("required", required);
			schema.put("additionalProperties", false);
			out.add(new ToolSpec(t.name(), t.description(), schema));
		}
		return out;
	}

	/** Runs one call. Never throws for bad input: the result carries an error message the model can read and act on. */
	public ToolResult call(ToolContext ctx, String name, String argumentsJson) {
		String args = argumentsJson == null ? "{}" : argumentsJson;
		String shownArgs = Text.oneLine(args, 300);
		Tool t = name == null ? null : tools.get(name);
		if (t == null) {
			return new ToolResult(Text.oneLine(String.valueOf(name), 40), shownArgs, false, null,
					"Unknown tool. Available tools: " + String.join(", ", tools.keySet()));
		}
		try {
			ToolArgs parsed = ToolArgs.parse(args, t.params(), mapper);
			ToolOutput out = t.runner().run(ctx, parsed);
			return new ToolResult(t.name(), shownArgs, true, out, null);
		} catch (ToolArgumentException e) {
			return new ToolResult(t.name(), shownArgs, false, null, e.getMessage());
		} catch (DataAccessException e) {
			log.error("Tool {} could not read the database: {}", t.name(), e.getClass().getSimpleName());
			return new ToolResult(t.name(), shownArgs, false, null, "The data could not be read right now (database unavailable). Do not guess.");
		} catch (RuntimeException e) {
			log.warn("Tool {} failed: {}", t.name(), e.getClass().getSimpleName());
			return new ToolResult(t.name(), shownArgs, false, null, "The tool failed unexpectedly. Do not guess the answer.");
		}
	}
}
