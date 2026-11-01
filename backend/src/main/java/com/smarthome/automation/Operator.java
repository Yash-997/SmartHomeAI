package com.smarthome.automation;

import java.util.Arrays;
import java.util.Optional;

/** Comparison operators, written as symbols in JSON. */
public enum Operator {
	GT(">"), GTE(">="), LT("<"), LTE("<="), EQ("=="), NEQ("!=");

	private final String symbol;

	Operator(String symbol) {
		this.symbol = symbol;
	}

	public String symbol() { return symbol; }

	public static Optional<Operator> fromSymbol(String symbol) {
		return Arrays.stream(values()).filter(o -> o.symbol.equals(symbol)).findFirst();
	}

	/** Booleans (motion) can only be compared for equality. */
	public boolean isEquality() {
		return this == EQ || this == NEQ;
	}
}
