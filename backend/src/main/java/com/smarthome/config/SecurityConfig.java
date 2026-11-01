package com.smarthome.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smarthome.device.DeviceAuthService;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties({ JwtProperties.class, CorsProperties.class })
public class SecurityConfig {

	public static final String ISSUER = "smarthome-backend";

	/**
	 * Device API (/api/device/**): authenticated by device credentials, never by user JWTs.
	 * Only /pair is open (its proof is the pairing code); everything else needs a valid device.
	 */
	@Bean
	@Order(1)
	SecurityFilterChain deviceFilterChain(HttpSecurity http, ObjectMapper mapper, DeviceAuthService deviceAuth)
			throws Exception {
		return http
				.securityMatcher("/api/device/**")
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(new DeviceAuthFilter(deviceAuth), AuthorizationFilter.class)
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.POST, "/api/device/pair").permitAll()
						.anyRequest().hasRole("DEVICE"))
				.exceptionHandling(e -> e
						.authenticationEntryPoint((req, res, ex) -> writeError(mapper, res,
								401, "Unauthorized", "Invalid or missing device credentials"))
						.accessDeniedHandler((req, res, ex) -> writeError(mapper, res, 403, "Forbidden", "Access denied")))
				.build();
	}

	/** User API: everything else, authenticated by user JWTs. */
	@Bean
	@Order(2)
	SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
		return http
				.csrf(AbstractHttpConfigurer::disable) // stateless API with bearer tokens, no cookies
				.cors(Customizer.withDefaults())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/api/health", "/api/auth/register", "/api/auth/login").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(o -> o
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint((req, res, ex) -> writeError(mapper, res,
								401, "Unauthorized", "Missing, invalid or expired token")))
				.exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) ->
						writeError(mapper, res, 403, "Forbidden", "Access denied")))
				.build();
	}

	private static void writeError(ObjectMapper mapper, HttpServletResponse res,
			int status, String error, String message) throws IOException {
		res.setStatus(status);
		res.setContentType(MediaType.APPLICATION_JSON_VALUE);
		res.getOutputStream().write(mapper.writeValueAsBytes(ApiError.of(status, error, message)));
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	private static SecretKey key(JwtProperties props) {
		return new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(JwtProperties props) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(key(props)));
	}

	/** Verifies the HS256 signature, expiry (exp/nbf) and issuer. */
	@Bean
	JwtDecoder jwtDecoder(JwtProperties props) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(props)).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		return decoder;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
		CorsConfiguration cfg = new CorsConfiguration();
		cfg.setAllowedOrigins(props.allowedOrigins());
		cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		cfg.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cfg);
		return source;
	}
}
