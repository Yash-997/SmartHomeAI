package com.smarthome.security;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SecuritySettingsRepository extends JpaRepository<SecuritySettings, UUID> {

	List<SecuritySettings> findByModeNot(SecurityMode mode);
}
