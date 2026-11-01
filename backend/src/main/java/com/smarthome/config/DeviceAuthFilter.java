package com.smarthome.config;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.smarthome.device.DeviceAuthService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates device requests from the X-Device-Id and X-Device-Key headers. If they are missing or wrong
 * it simply leaves the request unauthenticated; the security chain then answers 401.
 * Created inside the device security chain only (not a Spring bean) so it never runs for user endpoints.
 */
public class DeviceAuthFilter extends OncePerRequestFilter {

	static final String ID_HEADER = "X-Device-Id";
	static final String KEY_HEADER = "X-Device-Key";

	private final DeviceAuthService auth;

	public DeviceAuthFilter(DeviceAuthService auth) {
		this.auth = auth;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws ServletException, IOException {
		String id = req.getHeader(ID_HEADER);
		String key = req.getHeader(KEY_HEADER);
		if (id != null && key != null && !id.isBlank() && !key.isBlank()) {
			auth.authenticate(id, key).ifPresent(d ->
					SecurityContextHolder.getContext().setAuthentication(new DeviceAuthentication(d.getDeviceId())));
		}
		chain.doFilter(req, res);
	}
}
