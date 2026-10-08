package dev.wynnvitrail;

import java.util.List;
import java.util.Optional;

/**
 * Which armour sheet a Wynncraft armour model names, read out of the model's own path.
 * <p>
 * <strong>Wynncraft enumerates its armour sets and the game does not know that.</strong> The game
 * resolves a piece of armour to an equipment asset through a component the server sets, and for
 * Wynncraft's own sets that component names a sheet the pack ships - so what an overlay needs is not
 * the item but the NAME of the set, and the only place it is written is the model the item points
 * at. The server writes it there as {@code item/wynn/armor/<set>_<piece>}, which is a path this class
 * reads apart.
 * <p>
 * <strong>The set names are a list the server owns and this has to keep.</strong> Twenty-three of
 * them, and a set the server adds is an armour piece this does not recognise - which is a piece of
 * armour drawn as the game would have drawn it, so the failure is a plate in the wrong place rather
 * than a crash. That is the right way round for a list that cannot be asked for itself: nothing here
 * can enumerate a server's armour, and a name that is wrong is a picture where a name that is missing
 * is nothing at all.
 * <p>
 * <strong>The same set name is read out of three places and they have to agree.</strong> A model
 * path, an item's custom model data colour's string, and an index into the list above - the last
 * being the server's own fallback for a set whose model it did not name. All three end in a name from
 * the one list, so there is one table and three doors.
 */
public final class WynncraftArmorAsset {

	/**
	 * The prefix a Wynncraft armour model carries, which is what tells one of them from an ordinary
	 * item model at all.
	 */
	private static final String ARMOR_MODEL_PREFIX = "item/wynn/armor/";

	/** And the prefix a Wynncraft hat model carries, which is a cosmetic drawn on a player's head. */
	private static final String HAT_MODEL_PREFIX = "item/wynn/skin/hat/";

	/** The four pieces a set's sheet is split into, which a model name ends with. */
	private static final List<String> PIECES = List.of("_helmet", "_chestplate", "_leggings", "_boots");

	/**
	 * The two ordinary items the server carries its hats on.
	 * <p>
	 * A pickaxe and a potion, which are what an item has to BE for the server to be able to give a
	 * player a hat at all - so a model that names a hat is one of these two wearing one, and an item
	 * that is one of these two with no model named may be wearing one.
	 */
	private static final List<String> HAT_CARRIERS = List.of("diamond_pickaxe", "potion");

	/**
	 * The sets, in the order the server's own indices are in.
	 * <p>
	 * The order is load-bearing and is not alphabetical: the server numbers its sets and hands the
	 * number over in a model's custom model data, so a name moved in this list is a different armour
	 * set drawn on every mount in the world. The first is not an armour set at all - it is the name
	 * that means the armour is hidden, and it is first because the server's indices start at one.
	 */
	private static final List<String> SETS = List.of("hidden", "leather", "tan", "chainmail", "copper",
			"iron", "gold", "diamond", "titanium", "netherite", "pale_leather", "pale_chainmail",
			"pale_copper", "pale_iron", "pale_gold", "pale_diamond", "pale_titanium", "pale_netherite",
			"shaman", "infernal", "phantom", "quartz", "wings");

	private WynncraftArmorAsset() {
	}

	/** Whether a model path is one of Wynncraft's armour models at all. */
	public static boolean isArmorModel(String path) {
		return path != null && path.startsWith(ARMOR_MODEL_PREFIX);
	}

	/**
	 * The set a Wynncraft armour model path names, or empty where it names none.
	 * <p>
	 * The piece is taken off the end first and the directory off the front second, in that order
	 * because a set is allowed to have a name that ends in one of the four and a directory that does
	 * not - and because the last slash is the only one that tells a directory from a name once a set
	 * has one of either.
	 *
	 * @param path a model path, with or without its namespace already taken off
	 * @return the set's name, which is one of the names above
	 */
	public static Optional<String> setOfModel(String path) {
		if (path == null || path.isBlank()) {
			return Optional.empty();
		}

		String name = path;
		int slash = name.lastIndexOf('/');
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}

		for (String piece : PIECES) {
			if (name.endsWith(piece)) {
				name = name.substring(0, name.length() - piece.length());
				break;
			}
		}

		return SETS.contains(name) ? Optional.of(name) : Optional.empty();
	}

	/**
	 * The set the server's own index names, or empty where the index names none.
	 * <p>
	 * One-based, because the server's indices are and because nought means "no custom model data at
	 * all" rather than "the first set". A negative or fractional value is the caller's to refuse;
	 * what reaches here is a whole number it has already floored.
	 *
	 * @param index a one-based index into the server's set list
	 */
	public static Optional<String> setOfIndex(int index) {
		if (index < 1 || index > SETS.size()) {
			return Optional.empty();
		}

		return Optional.of(SETS.get(index - 1));
	}

	/** Whether a model path is one of the server's hat models, which are cosmetics rather than armour. */
	public static boolean isHatModel(String path) {
		return path != null && path.startsWith(HAT_MODEL_PREFIX);
	}

	/**
	 * Whether an item is one the server carries a hat on.
	 * <p>
	 * Asked of the path alone, so that the caller may pass either an item's own registry path or the
	 * model it points at: the server uses one of these two items either way, and a caller that has
	 * only one of the two in hand should not have to guess which.
	 */
	public static boolean isHatCarrier(String path) {
		return path != null && HAT_CARRIERS.contains(path);
	}

	/** How many sets there are, which is what a caller bounding an index needs. */
	public static int setCount() {
		return SETS.size();
	}
}
