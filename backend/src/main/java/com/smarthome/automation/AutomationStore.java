package com.smarthome.automation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database writes made by the engine, each in its own short transaction (the engine itself runs outside any
 * transaction so command delivery is never held inside one).
 */
@Component
public class AutomationStore {

	private final AutomationRuleRepository rules;
	private final AutomationExecutionRepository executions;
	private final AutomationProperties props;

	public AutomationStore(AutomationRuleRepository rules, AutomationExecutionRepository executions, AutomationProperties props) {
		this.rules = rules;
		this.executions = executions;
		this.props = props;
	}

	/**
	 * Writes an audit entry. A skip that repeats the previous entry (same outcome and reason, recent) is folded into it
	 * instead, so a rule that stays true for hours produces one row with a repeat count, not thousands.
	 */
	@Transactional
	public void record(AutomationRule rule, ExecutionOutcome outcome, String reason, String triggerValues, String evaluation,
			UUID commandId, Instant now) {
		if (outcome.isCompactable()) {
			var last = executions.findFirstByRuleIdOrderByLastAtDesc(rule.getId());
			if (last.isPresent() && last.get().getOutcome() == outcome && Objects.equals(last.get().getReason(), reason)
					&& !last.get().getLastAt().isBefore(now.minusSeconds(props.historyCompactSeconds()))) {
				last.get().repeat(triggerValues, evaluation, now);
				return;
			}
		}
		executions.save(new AutomationExecution(rule, outcome, reason, triggerValues, evaluation, commandId, now));
		long count = executions.countByRuleId(rule.getId());
		if (count > props.historyPerRule()) {
			List<AutomationExecution> oldest = executions.findByRuleIdOrderByCreatedAtAsc(rule.getId(),
					PageRequest.of(0, (int) (count - props.historyPerRule())));
			executions.deleteAll(oldest);
		}
	}

	/**
	 * Timestamps are cut to microseconds, the precision the database stores. Without this, {@link #revertClaim} could
	 * never match the value written by {@link #claimExecution} (Java clocks have finer digits than the column keeps).
	 */
	@Transactional
	public boolean claimExecution(UUID ruleId, Instant now, int cooldownSeconds) {
		Instant at = now.truncatedTo(ChronoUnit.MICROS);
		return rules.claimExecution(ruleId, at, at.minusSeconds(cooldownSeconds)) == 1;
	}

	@Transactional
	public void revertClaim(UUID ruleId, Instant claimed, Instant previous) {
		rules.revertClaim(ruleId, claimed.truncatedTo(ChronoUnit.MICROS), previous);
	}

	@Transactional
	public boolean claimScheduledDay(UUID ruleId, LocalDate day) {
		return rules.claimScheduledDay(ruleId, day) == 1;
	}
}
