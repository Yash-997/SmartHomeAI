package com.smarthome.assistant;

import java.util.List;

/** Turns text into vectors for similarity search. Vectors from different models are never compared. */
public interface EmbeddingClient {

	/** Identifies the model (and so the vector space). Stored with every chunk. */
	String modelId();

	/** True for the built-in embedder, which needs no network or key. */
	boolean local();

	/** One vector per text, in order. */
	List<float[]> embed(List<String> texts) throws LlmUnavailableException;

	default float[] embedOne(String text) throws LlmUnavailableException {
		return embed(List.of(text)).get(0);
	}
}
