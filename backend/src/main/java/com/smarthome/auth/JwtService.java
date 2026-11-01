package com.smarthome.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.smarthome.config.JwtProperties;
import com.smarthome.config.SecurityConfig;
import com.smarthome.user.User;

@Service
public class JwtService {

	private final JwtEncoder encoder;
	private final Duration lifetime;

	public JwtService(JwtEncoder encoder, JwtProperties props) {
		this.encoder = encoder;
		this.lifetime = Duration.ofMinutes(props.expirationMinutes());
	}

	public long lifetimeSeconds() {
		return lifetime.toSeconds();
	}

	/** Issues an HS256 token whose subject is the user's stable UUID. */
	public String issue(User user) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(SecurityConfig.ISSUER)
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(now.plus(lifetime))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
