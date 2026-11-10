package dev.vitrail.render.upscale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DlssPackProfileTest {

	@Test
	void readsATriggerAndTheHdrImageItNames() {
		var profile = DlssPackProfile.parse("""
				{
				  "schema_version": 1,
				  "name": "Test pack",
				  "trigger": { "type": "BEFORE", "pass": "composite7" },
				  "inputs": { "color": { "src": "colortex4" } },
				  "hdr": true,
				  "disable_pack_taa": false
				}
				""", "test.json").orElseThrow();

		assertEquals("Test pack", profile.name());
		assertEquals("composite7", profile.triggerPass());
		assertTrue(profile.before());
		assertEquals(4, profile.colorTarget());
		assertTrue(profile.hdr());
		assertTrue(profile.cameraMotion());
		assertFalse(profile.disableTaa());
	}

	@Test
	void defaultsMatchTheContractThisFeatureStartedWith() {
		var profile = DlssPackProfile.parse("""
				{ "schema_version": 1, "trigger": { "pass": "composite13" } }
				""", "test.json").orElseThrow();

		assertEquals("composite13", profile.triggerPass());
		assertEquals(0, profile.colorTarget());
		assertTrue(profile.hdr());
		assertTrue(profile.disableTaa());
	}

	@Test
	void refusesASchemaItDoesNotSpeak() {
		assertTrue(DlssPackProfile.parse("""
				{ "schema_version": 99, "trigger": { "pass": "composite13" } }
				""", "test.json").isEmpty());
	}

	@Test
	void refusesAContractWithNoTrigger() {
		assertTrue(DlssPackProfile.parse("""
				{ "schema_version": 1 }
				""", "test.json").isEmpty());
	}

	@Test
	void readsAnAfterTriggerForTheChainToResolve() {
		var profile = DlssPackProfile.parse("""
				{ "schema_version": 1, "trigger": { "type": "AFTER", "pass": "composite15" } }
				""", "test.json").orElseThrow();

		assertEquals("composite15", profile.triggerPass());
		assertFalse(profile.before());
	}

	@Test
	void refusesColourFromSomethingOtherThanAColourTarget() {
		assertTrue(DlssPackProfile.parse("""
				{ "schema_version": 1, "trigger": { "pass": "composite13" },
				  "inputs": { "color": { "src": "depthtex0" } } }
				""", "test.json").isEmpty());
	}

	@Test
	void refusesMalformedJson() {
		assertTrue(DlssPackProfile.parse("{ not json", "test.json").isEmpty());
	}
}
