package com.smarthome.security;

/** HOME = nothing is watched. AWAY and NIGHT watch for motion with their own settings. */
public enum SecurityMode {
	HOME, AWAY, NIGHT;

	public boolean armed() {
		return this != HOME;
	}
}
