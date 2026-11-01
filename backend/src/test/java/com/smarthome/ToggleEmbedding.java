package com.smarthome;

import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.smarthome.assistant.EmbeddingClient;
import com.smarthome.assistant.LlmUnavailableException;
import com.smarthome.assistant.LlmUnavailableException.Reason;
import com.smarthome.assistant.LocalHashEmbedding;

/** The built-in embedder, except that a test can make it fail like an unreachable embedding service. */
@Component
@Primary
public class ToggleEmbedding implements EmbeddingClient {

	private final LocalHashEmbedding real = new LocalHashEmbedding();
	private volatile boolean down;

	public void setDown(boolean down) {
		this.down = down;
	}

	@Override
	public String modelId() {
		return real.modelId();
	}

	@Override
	public boolean local() {
		return real.local();
	}

	@Override
	public List<float[]> embed(List<String> texts) throws LlmUnavailableException {
		if (down) {
			throw new LlmUnavailableException(Reason.NETWORK, "The embedding service could not be reached.");
		}
		return real.embed(texts);
	}
}
