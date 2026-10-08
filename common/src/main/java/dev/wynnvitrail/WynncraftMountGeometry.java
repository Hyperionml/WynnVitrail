package dev.wynnvitrail;

/**
 * Where a Wynncraft mount's armour belongs, read out of the geometry the server sent rather than out
 * of anything the game knows.
 * <p>
 * <strong>A mounted player on Wynncraft is a second body, and the game has no idea it is
 * there.</strong> The server does not move the player's own model onto the mount: it spawns a small
 * army of quads that happen to spell a player, one limb at a time, and the client draws them as
 * ordinary geometry. The armour the player is wearing is drawn by the client, from the player's own
 * equipment, onto the mount's position - so it lands wherever the player's body would have been,
 * which on a mount is the wrong place entirely, and it is drawn at the wrong size for the limb it
 * ends up over.
 * <p>
 * <strong>The one thing the server does tell is a height, and it is a packed number.</strong> Every
 * vertex of every one of those quads carries a Y that is not a height at all above a threshold: it
 * is {@code 1024} plus a counter in mixed radix, and the counter says which limb the vertex belongs
 * to, whether the body is a Steve or an Alex, and how far the limb has faded. Everything this class
 * does follows from unpacking it, and the unpacking is why this is a class of arithmetic with no
 * game type in it: {@code WynncraftMountArmorOverlay.iris$decodeFakePlayerLimbIndex} does the same
 * three divisions in WynnIris, and the four radixes below are the ones it does them in.
 * <p>
 * <strong>The same encoding is already read once in this fork, by the shader.</strong>
 * {@link WynncraftEmote}'s decode walks the same number in GLSL for the pose a player strikes, under
 * its own four constants with the same values. They are not shared - one is Java and one is text a
 * pack compiles - so a reader changing one has to change the other, and the two files name each
 * other for that reason.
 * <p>
 * <strong>What the number is spent on is a stand-off and a width.</strong> The armour the client
 * draws sits on the skin, and a plate stands off it by an amount that differs by layer and by
 * whether the limb is a leg; and a slim arm is narrower than the plate the client draws for it, so
 * the plate is pushed out sideways on the side the quads agree is the outside. Both are decided
 * here, which is what makes the overlay land on the mount rather than beside it.
 */
public final class WynncraftMountGeometry {

	/**
	 * The height at and above which a vertex belongs to a body the server placed rather than to one
	 * the game drew.
	 * <p>
	 * A world is not a thousand blocks tall and no model reaches it, so nothing a player or a mob
	 * carries can be mistaken for one of these. Below it the number is the height it looks like, and
	 * every ordinary model lives there.
	 */
	public static final float FAKE_PLAYER_Y = 1024.0F;

	/**
	 * The four radixes the counter above the threshold is written in, in the order the divisions
	 * undo them.
	 * <p>
	 * The first is the position the server encodes, which is why the metadata starts two of them
	 * above the threshold: {@code 2 * 512} is the whole of what the number is offset by, and the
	 * two steps are the two halves of the encoding's own header rather than a constant anybody
	 * chose.
	 */
	public static final int Y_RADIX = 512;
	public static final int STEVE_ALEX_RADIX = 2;
	public static final int LIMB_FADE_RADIX = 3;
	public static final int LIMB_INDEX_RADIX = 6;

	/** The limbs, by the index the counter carries. Two of the six names are unused by the server. */
	public static final int HEAD = 0;
	public static final int LEFT_ARM = 2;
	public static final int RIGHT_ARM = 3;
	public static final int LEFT_LEG = 4;
	public static final int RIGHT_LEG = 5;

	/** How far a slim arm's plate is pushed sideways, where the quads agree which side is out. */
	public static final float SLIM_ARM_WIDTH = 0.5F;

	private WynncraftMountGeometry() {
	}

	/**
	 * Whether a vertex, taken through the pose it is drawn under, belongs to one of these bodies.
	 *
	 * @param transformedY the height a vertex lands at once the pose has been applied, which is the
	 *                     only place the threshold means anything: the encoded number is a position
	 *                     the server wrote and the pose is what turns it into one
	 */
	public static boolean isFakePlayerVertex(float transformedY) {
		return transformedY >= FAKE_PLAYER_Y;
	}

	/**
	 * Which limb a vertex belongs to, or minus one where it belongs to none of them.
	 * <p>
	 * The three divisions above the first take the counter apart: the position, then whether the body
	 * is a Steve or an Alex, then how far the limb has faded. What is left is the limb, and the
	 * modulus is what makes it a limb rather than a number that grows: the counter is written in
	 * mixed radix, so the limb is the top digit of it and everything under that digit is somebody
	 * else's business. A body whose limb digit runs past the five names wraps, which is the server's
	 * encoding and not an error to report.
	 * <p>
	 * The first division is an integer division of a float that has had its fraction thrown away, and
	 * that is deliberate and is WynnIris's: the server writes the height as a whole number and the
	 * pose scales it, so the fraction carries the pose rather than the counter.
	 *
	 * @param transformedY the height a vertex lands at once the pose has been applied
	 * @return the limb's index, or minus one where this vertex is not one of these bodies
	 */
	public static int limbIndex(float transformedY) {
		if (transformedY < 2.0F * Y_RADIX) {
			return -1;
		}

		int counter = (int) transformedY - 2 * Y_RADIX;

		return counter / Y_RADIX / STEVE_ALEX_RADIX / LIMB_FADE_RADIX % LIMB_INDEX_RADIX;
	}

	/**
	 * The armour layers, which stand off the skin by different amounts.
	 * <p>
	 * Three of them and not four: the helmet is drawn as a scaled model rather than as a plate, and
	 * what it needs is a scale rather than an offset.
	 */
	public enum Layer {

		/** The chestplate and the helmet, which sit closest to the body and have to clear the legs. */
		OUTER(0.75F, 0.65F),

		/** The boots, which are drawn over everything the other two put on and stand off the most. */
		BOOTS(1.25F, 1.25F),

		/**
		 * The leggings, which are drawn first and stand off barely at all.
		 * <p>
		 * The body figure is for the part of a legging that is not a leg, and it is the smallest of
		 * the six because a legging is the layer closest to the skin.
		 */
		LEGGINGS(0.25F, 0.15F);

		private final float bodyExpand;
		private final float legExpand;

		Layer(float bodyExpand, float legExpand) {
			this.bodyExpand = bodyExpand;
			this.legExpand = legExpand;
		}

		/**
		 * How far this layer's plate stands off the limb it lies over.
		 *
		 * @param limbIndex the limb the plate is on, as {@link #limbIndex} answers it
		 */
		public float expand(int limbIndex) {
			return limbIndex == LEFT_LEG || limbIndex == RIGHT_LEG ? this.legExpand : this.bodyExpand;
		}

		/**
		 * Whether this layer is the one whose plates have to be widened for a slim arm.
		 * <p>
		 * The outer layer alone, and the reason is the order the client draws them in: a slim arm is
		 * narrower than the plate the client builds for it, so a plate built for a wide arm floats
		 * beside a slim one - and the outer layer is the one whose plate is the arm's own silhouette.
		 * The other two are drawn inside it and would be pushed out through it.
		 */
		public boolean widensSlimArms() {
			return this == OUTER;
		}
	}

	/**
	 * How far one limb's quads reach sideways, which is what tells a slim arm from a wide one.
	 * <p>
	 * Measured rather than asked for, because the game does not say: the client draws the armour from
	 * the player's own equipment and a slim skin is a property of the skin, which the server's quads
	 * do not name. What they do carry is their own positions, and a limb whose quads span two blocks
	 * of x is a wide one.
	 */
	public static final class LimbBounds {

		private float minX = Float.POSITIVE_INFINITY;
		private float maxX = Float.NEGATIVE_INFINITY;

		/** Takes one vertex's x into the span. */
		public void include(float x) {
			this.minX = Math.min(this.minX, x);
			this.maxX = Math.max(this.maxX, x);
		}

		/** Whether anything was taken at all, and whether it was more than one point. */
		public boolean hasWidth() {
			return this.maxX > this.minX;
		}

		/** The middle of the span, which is the line the two sides of a limb are on either side of. */
		public float centreX() {
			return (this.minX + this.maxX) * 0.5F;
		}
	}

	/**
	 * How far a vertex of a slim arm's plate is pushed sideways, which is nought on every other
	 * limb.
	 * <p>
	 * The side is taken from the vertex's own x against the limb's middle rather than from the face
	 * it lies on: a quad of a plate is one face and a plate has four of them, so the face's normal
	 * would push all four the same way and take the plate off the arm instead of widening it.
	 *
	 * @param x      the vertex's own x, before any stand-off
	 * @param bounds the limb's span, or null where this limb was not measured
	 * @return the distance to add to x, which may be negative
	 */
	public static float slimArmPush(float x, LimbBounds bounds) {
		if (bounds == null || !bounds.hasWidth()) {
			return 0.0F;
		}

		return Math.signum(x - bounds.centreX()) * SLIM_ARM_WIDTH;
	}
}
