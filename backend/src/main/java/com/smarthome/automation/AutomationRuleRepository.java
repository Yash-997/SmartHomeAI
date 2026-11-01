package com.smarthome.automation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutomationRuleRepository extends JpaRepository<AutomationRule, UUID> {

	/** Ownership is part of the lookup: another user's rule is simply "not found". */
	Optional<AutomationRule> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

	List<AutomationRule> findByOwnerUserIdOrderByCreatedAtAsc(UUID ownerUserId);

	long countByOwnerUserId(UUID ownerUserId);

	List<AutomationRule> findByDeviceUuidAndTriggerTypeAndEnabledTrue(UUID deviceUuid, TriggerType type);

	List<AutomationRule> findByTriggerTypeAndEnabledTrue(TriggerType type);

	/**
	 * Atomically claims the right to run a rule now: succeeds (1) only if it has never run or its cooldown has passed.
	 * Two concurrent evaluations can never both win, so a rule cannot fire twice inside its cooldown.
	 */
	@Modifying
	@Query("update AutomationRule r set r.lastExecutedAt = :now "
			+ "where r.id = :id and (r.lastExecutedAt is null or r.lastExecutedAt <= :threshold)")
	int claimExecution(@Param("id") UUID id, @Param("now") Instant now, @Param("threshold") Instant threshold);

	/** Gives the cooldown back when a claimed run did not actually happen (device offline, broker down, ...). */
	@Modifying
	@Query("update AutomationRule r set r.lastExecutedAt = :previous where r.id = :id and r.lastExecutedAt = :claimed")
	int revertClaim(@Param("id") UUID id, @Param("claimed") Instant claimed, @Param("previous") Instant previous);

	/** A scheduled rule may handle each local day only once. */
	@Modifying
	@Query("update AutomationRule r set r.lastScheduledOn = :day "
			+ "where r.id = :id and (r.lastScheduledOn is null or r.lastScheduledOn < :day)")
	int claimScheduledDay(@Param("id") UUID id, @Param("day") LocalDate day);

	@Modifying
	@Query("delete from AutomationRule r where r.deviceUuid = :device or r.targetDeviceUuid = :device")
	int deleteAllReferencing(@Param("device") UUID device);
}
