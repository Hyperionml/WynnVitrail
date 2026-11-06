package dev.wynnvitrail;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WynncraftTransitionTest {
	@Test
	void consumesTheLatestValidSignalOnceEvenWhenDrawingIsSkipped() {
		WynncraftTransition.note(1, 0.25F, 0);
		WynncraftTransition.note(19, 0.5F, 0xff00ff);
		WynncraftTransition.note(20, 1.0F, 0);
		assertEquals(new WynncraftTransition.Detection(19, 0.5F, 0xff00ff), WynncraftTransition.consume());
		assertNull(WynncraftTransition.consume());
	}
}
