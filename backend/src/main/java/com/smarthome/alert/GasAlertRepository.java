package com.smarthome.alert;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GasAlertRepository extends JpaRepository<GasAlert, UUID> {

	Optional<GasAlert> findFirstByDeviceUuidAndStateOrderByStartedAtDesc(UUID deviceUuid, GasAlert.State state);

	List<GasAlert> findByOwnerUserIdOrderByStartedAtDesc(UUID ownerUserId, Pageable page);
}
