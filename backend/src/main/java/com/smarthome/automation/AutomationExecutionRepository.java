package com.smarthome.automation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutomationExecutionRepository extends JpaRepository<AutomationExecution, UUID> {

	Optional<AutomationExecution> findFirstByRuleIdOrderByLastAtDesc(UUID ruleId);

	List<AutomationExecution> findByRuleIdOrderByLastAtDesc(UUID ruleId, Pageable page);

	List<AutomationExecution> findByOwnerUserIdOrderByLastAtDesc(UUID ownerUserId, Pageable page);

	long countByRuleId(UUID ruleId);

	/** Used for the per-device rate limit: automation commands actually issued to this device recently. */
	long countByDeviceUuidAndOutcomeAndCreatedAtAfter(UUID deviceUuid, ExecutionOutcome outcome, Instant after);

	/** Oldest entries first, for pruning. */
	List<AutomationExecution> findByRuleIdOrderByCreatedAtAsc(UUID ruleId, Pageable page);

	@Modifying
	@Query("delete from AutomationExecution e where e.deviceUuid = :device")
	int deleteAllForDevice(@Param("device") UUID device);
}
