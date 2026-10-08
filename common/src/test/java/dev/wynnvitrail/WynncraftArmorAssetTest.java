package dev.wynnvitrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Reads the armour set out of the paths and indices the server writes it in, off-game.
 * <p>
 * <strong>The list's order is the part that cannot be recovered.</strong> The server numbers its sets
 * and hands the number over in a model's custom model data, so the twenty-three names are read
 * positionally and a name moved is a different set drawn on every mount in the world. Nothing in the
 * code can tell a moved name from a reordered one, so two of the positions are pinned here by hand
 * and the first and the last are pinned by the bounds.
 * <p>
 * The rest is string surgery with three doors into the one table, and the door that is worth reading
 * twice is the model path: the piece comes off the end before the directory comes off the front,
 * because a set is allowed a name that ends in one of the four pieces and a path is not.
 */
class WynncraftArmorAssetTest {

	/** A model path for a set in the middle of the list, which is the ordinary case. */
	private static final String IRON_CHEST = "item/wynn/armor/iron_chestplate";

	/** What a set and its piece have to be told apart from, and what they have to survive. */
	@Test
	void aSetComesOutOfAModelPathWithItsPieceAndItsDirectoryTakenOff() {
		assertEquals(Optional.of("iron"), WynncraftArmorAsset.setOfModel(IRON_CHEST),
				"a set did not come out of its model path");
		assertEquals(Optional.of("quartz"), WynncraftArmorAsset.setOfModel("item/wynn/armor/quartz_leggings"),
				"a set did not come out of its model path");
		assertEquals(Optional.of("wings"), WynncraftArmorAsset.setOfModel("item/wynn/armor/wings_boots"),
				"a one word set did not come out of its model path");

		// A bare name is a path with no directory and no piece, and it is what the server's own custom
		// model data colour carries.
		assertEquals(Optional.of("netherite"), WynncraftArmorAsset.setOfModel("netherite"),
				"a bare set name was not read");

		assertEquals(Optional.empty(), WynncraftArmorAsset.setOfModel("item/wynn/armor/tin_helmet"),
				"a set the server does not have was read as one");
		assertEquals(Optional.empty(), WynncraftArmorAsset.setOfModel(""),
				"an empty path was read as a set");
		assertEquals(Optional.empty(), WynncraftArmorAsset.setOfModel(null),
				"a null path was read as a set");
	}

	/** The prefix is what tells a Wynncraft armour model from an ordinary item's at all. */
	@Test
	void onlyAWynncraftArmorModelIsOne() {
		assertTrue(WynncraftArmorAsset.isArmorModel(IRON_CHEST), "an armour model was not recognised");
		assertFalse(WynncraftArmorAsset.isArmorModel("item/wynn/skin/hat/top_hat"),
				"a hat was taken for a piece of armour");
		assertFalse(WynncraftArmorAsset.isArmorModel("item/diamond_chestplate"),
				"an ordinary item model was taken for a piece of armour");
		assertFalse(WynncraftArmorAsset.isArmorModel(null), "a null path was taken for a piece of armour");
	}

	/**
	 * The indices, which are the server's and are one-based.
	 * <p>
	 * The first is not an armour set at all: it is the name that means the armour is hidden, and it is
	 * first because the server's own indices start at one - which is also why nought is refused rather
	 * than read as the first, since nought is what a piece of armour with no custom model data has.
	 */
	@Test
	void theServersIndicesAreOneBasedAndInTheServersOrder() {
		assertEquals(Optional.of("hidden"), WynncraftArmorAsset.setOfIndex(1),
				"the first index is not the name that means hidden");
		assertEquals(Optional.of("leather"), WynncraftArmorAsset.setOfIndex(2),
				"the second index is not the first armour set");
		assertEquals(Optional.of("iron"), WynncraftArmorAsset.setOfIndex(6),
				"a set's position in the list has moved");
		assertEquals(Optional.of("wings"), WynncraftArmorAsset.setOfIndex(23),
				"the last index is not the last set");
		assertEquals(23, WynncraftArmorAsset.setCount(), "the list is not the server's twenty-three");

		assertEquals(Optional.empty(), WynncraftArmorAsset.setOfIndex(0),
				"an index of nought was read as the first set");
		assertEquals(Optional.empty(), WynncraftArmorAsset.setOfIndex(24),
				"an index past the end was read as a set");
		assertEquals(Optional.empty(), WynncraftArmorAsset.setOfIndex(-1),
				"a negative index was read as a set");
	}

	/**
	 * The hats, which are the other thing the server puts on a player and are not armour at all.
	 * <p>
	 * A hat has no sheet of its own: it is an ordinary item worn on the head, so what has to be
	 * recognised is the model the server gave it and the two items it carries one on. Asked of a bare
	 * path so that a caller with either the item's own path or the model it points at can ask.
	 */
	@Test
	void aHatIsAModelAndATwoItemCarrier() {
		assertTrue(WynncraftArmorAsset.isHatModel("item/wynn/skin/hat/top_hat"), "a hat model was not read");
		assertFalse(WynncraftArmorAsset.isHatModel(IRON_CHEST), "a piece of armour was read as a hat");
		assertFalse(WynncraftArmorAsset.isHatModel(null), "a null path was read as a hat");

		assertTrue(WynncraftArmorAsset.isHatCarrier("diamond_pickaxe"), "the pickaxe is not a carrier");
		assertTrue(WynncraftArmorAsset.isHatCarrier("potion"), "the potion is not a carrier");
		assertFalse(WynncraftArmorAsset.isHatCarrier("iron_sword"), "an ordinary item carries a hat");
		assertFalse(WynncraftArmorAsset.isHatCarrier(null), "a null path carries a hat");
	}
}
