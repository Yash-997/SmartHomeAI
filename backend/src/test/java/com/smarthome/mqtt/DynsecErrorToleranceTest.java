package com.smarthome.mqtt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Which broker error strings count as "already in the desired state". The strings are what Mosquitto 2.1.2 actually
 * returns (checked against the real broker); getting this wrong broke the second start of the backend.
 */
class DynsecErrorToleranceTest {

	@Test
	void duplicateRoleAssignmentIsTolerated() {
		// Mosquitto's answer when the client already has the role
		assertTrue(DynsecBrokerAccess.harmless("addClientRole", "Internal error"));
	}

	@Test
	void missingRoleOrClientDuringAssignmentIsARealFailure() {
		assertFalse(DynsecBrokerAccess.harmless("addClientRole", "Role not found"));
		assertFalse(DynsecBrokerAccess.harmless("addClientRole", "Client not found"));
	}

	@Test
	void alreadyExistsIsToleratedWhereCreating() {
		assertTrue(DynsecBrokerAccess.harmless("createClient", "Client already exists"));
		assertTrue(DynsecBrokerAccess.harmless("createRole", "Role already exists"));
		assertTrue(DynsecBrokerAccess.harmless("addRoleACL", "ACL with this topic already exists"));
		assertFalse(DynsecBrokerAccess.harmless("createClient", "Internal error"));
	}

	@Test
	void revokingSomethingAlreadyGoneIsSuccessButOnlyForRevocation() {
		assertTrue(DynsecBrokerAccess.harmless("disableClient", "Client not found"));
		assertTrue(DynsecBrokerAccess.harmless("deleteClient", "Client not found"));
		assertFalse(DynsecBrokerAccess.harmless("setClientPassword", "Client not found"));
		assertFalse(DynsecBrokerAccess.harmless("enableClient", "Client not found"));
	}

	@Test
	void unknownCommandsAndErrorsAreNotTolerated() {
		assertFalse(DynsecBrokerAccess.harmless("createClient", "Invalid/missing username"));
		assertFalse(DynsecBrokerAccess.harmless("somethingElse", "already exists"));
	}
}
