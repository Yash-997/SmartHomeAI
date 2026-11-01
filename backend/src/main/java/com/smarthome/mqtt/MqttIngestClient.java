package com.smarthome.mqtt;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.smarthome.command.CommandIngestService;
import com.smarthome.telemetry.TelemetryIngestService;

import jakarta.annotation.PreDestroy;

/**
 * Receives device telemetry/status from Mosquitto and hands it to {@link TelemetryIngestService}.
 *
 * <ul>
 *   <li>Starts in the background, so the API works even if the broker is down at boot; it keeps retrying.</li>
 *   <li>Uses a persistent session + QoS 1, so messages sent while the backend restarts are delivered afterwards.
 *       This can redeliver messages, which the ingest service de-duplicates.</li>
 *   <li>Reconnects automatically and re-subscribes after every (re)connect.</li>
 *   <li>Never lets an exception escape the callback: Paho would drop the connection.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.mqtt.enabled", havingValue = "true")
public class MqttIngestClient implements MqttCallbackExtended, CommandPublisher {

	private static final Logger log = LoggerFactory.getLogger(MqttIngestClient.class);
	private static final String CLIENT_ID = "smarthome-backend-ingest";
	private static final String[] TOPICS = {
			"home/devices/+/telemetry", "home/devices/+/status", "home/devices/+/acks", "home/devices/+/state" };
	private static final int RETRY_SECONDS = 5;

	private final MqttProperties props;
	private final DynsecBrokerAccess admin;
	private final TelemetryIngestService ingest;
	private final CommandIngestService commandIngest;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "mqtt-connect");
		t.setDaemon(true);
		return t;
	});
	private volatile MqttClient client;
	private volatile boolean stopping;

	public MqttIngestClient(MqttProperties props, DynsecBrokerAccess admin, TelemetryIngestService ingest,
			CommandIngestService commandIngest) {
		this.props = props;
		this.admin = admin;
		this.ingest = ingest;
		this.commandIngest = commandIngest;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		scheduler.execute(this::connectLoop);
	}

	private void connectLoop() {
		if (stopping) {
			return;
		}
		try {
			admin.reconcile();
			connect();
			log.info("MQTT ingest connected to {}", props.brokerUri());
		} catch (Exception e) {
			log.warn("MQTT not ready ({}); retrying in {}s", e.getMessage(), RETRY_SECONDS);
			scheduler.schedule(this::connectLoop, RETRY_SECONDS, TimeUnit.SECONDS);
		}
	}

	private void connect() throws MqttException {
		MqttClient c = new MqttClient(props.brokerUri(), CLIENT_ID, new MemoryPersistence());
		c.setCallback(this);
		c.setTimeToWait(5000);   // synchronous publishes give up after 5 s instead of hanging
		MqttConnectOptions opts = new MqttConnectOptions();
		opts.setUserName(props.backendUsername());
		opts.setPassword(props.backendPassword().toCharArray());
		opts.setCleanSession(false);
		opts.setAutomaticReconnect(true);
		opts.setKeepAliveInterval(30);
		opts.setConnectionTimeout(5);
		this.client = c;
		try {
			c.connect(opts); // connectComplete() subscribes
		} catch (MqttException e) {
			try {
				c.close();
			} catch (MqttException ignored) {
				// already failed; the retry creates a fresh client
			}
			throw e;
		}
	}

	@Override
	public void connectComplete(boolean reconnect, String serverURI) {
		try {
			int[] qos = { 1, 1, 1, 1 };
			client.subscribe(TOPICS, qos);
			if (reconnect) {
				log.info("MQTT ingest reconnected");
			}
		} catch (MqttException e) {
			log.error("MQTT subscribe failed: {}", e.getMessage());
		}
	}

	@Override
	public void connectionLost(Throwable cause) {
		log.warn("MQTT connection lost ({}); reconnecting automatically", cause == null ? "unknown" : cause.getMessage());
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) {
		try {
			boolean fromDeviceReplies = topic.endsWith("/acks") || topic.endsWith("/state");
			Object result = fromDeviceReplies
					? commandIngest.handle(topic, message.getPayload(), message.isRetained())
					: ingest.handle(topic, message.getPayload(), message.isRetained());
			log.debug("MQTT {} -> {}", topic, result);
		} catch (Exception e) {
			// Includes database errors. Dropping one message is better than killing the consumer.
			log.error("Failed to process MQTT message on '{}': {}", topic, e.getClass().getSimpleName());
		}
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
		// publishes are synchronous (see publish()), nothing to do here
	}

	/**
	 * Sends a command to one device. QoS 1 and NOT retained, so a device that reconnects later is never handed an
	 * old command. The broker's ACL only lets the backend account publish to command topics.
	 */
	@Override
	public Link link() {
		MqttClient c = client;
		return c != null && c.isConnected() ? Link.CONNECTED : Link.DISCONNECTED;
	}

	@Override
	public void publish(String deviceId, String json) {
		MqttClient c = client;
		if (c == null || !c.isConnected()) {
			throw new BrokerAccess.BrokerUnavailableException("The backend is not connected to the message broker");
		}
		try {
			MqttMessage msg = new MqttMessage(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			msg.setQos(1);
			msg.setRetained(false);
			c.publish("home/devices/" + deviceId + "/commands", msg);
		} catch (MqttException e) {
			throw new BrokerAccess.BrokerUnavailableException("Could not publish the command: " + e.getMessage(), e);
		}
	}

	@PreDestroy
	void stop() {
		stopping = true;
		scheduler.shutdownNow();
		MqttClient c = client;
		if (c != null) {
			try {
				if (c.isConnected()) {
					c.disconnect(1000);
				}
				c.close();
			} catch (MqttException ignored) {
				// shutting down
			}
		}
	}
}
