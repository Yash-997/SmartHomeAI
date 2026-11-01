package com.smarthome.device;

/** What a device last said about itself over MQTT. Self-reported: only believed while recent (see DeviceConnection). */
public enum ReportedState {
	ONLINE,
	OFFLINE
}
