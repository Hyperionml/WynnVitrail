package dev.wynnvitrail.ambience;

import dev.vitrail.HostReport;
import dev.vitrail.Vitrail;
import dev.vitrail.render.PackChain;
import dev.vitrail.render.PackChoice;
import dev.wynnvitrail.WynnOption;
import dev.wynnvitrail.WynncraftSettings;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;

public final class WynnAmbience {
	private static AmbiencePackManager.ResolvedProfile active;
	private static AmbiencePackManager.ResolvedProfile restore;
	private static AmbiencePackManager.ResolvedProfile previous;
	private static final ArrayDeque<AmbiencePackManager.ResolvedProfile> queue = new ArrayDeque<>();
	private static final Set<String> warmed = new HashSet<>();
	private static final Set<String> triedWarm = new HashSet<>();
	private static boolean restoreOnJoin;
	private static String failedProfile;
	private static long retryAfter;
	private static long nextSwitch;
	private static String pending;
	private static long pendingSince;
	private static long nextCheck;
	private static long nextAuto;
	private static long deadline;
	private static boolean warming;
	private static boolean switching;
	private static int warmTotal;
	private static int warmCompleted;
	private static int warmFailed;
	private static String warmTarget;

	private WynnAmbience() {}

	public static AmbiencePackManager.ResolvedProfile active() { return active; }
	public static boolean switching() { return switching; }
	public static boolean warming() { return warming; }

	public static void leaveWorld() {
		queue.clear();
		warmed.clear();
		triedWarm.clear();
		restoreOnJoin |= active != null;
		warming = false;
		restore = null;
		previous = null;
		active = null;
		failedProfile = null;
		nextSwitch = 0;
		pending = null;
		nextAuto = 0;
	}

	public static void warm(boolean auto) {
		if (warming || Minecraft.getInstance().level == null || !PackChain.drawingPack()) return;
		var profiles = AmbiencePackManager.getInstance().resolveProfiles(WynnOption.selectedPack());
		for (var p : profiles) {
			if ((!auto || !triedWarm.contains(p.key())) && !warmed.contains(p.key()) && (active == null || !p.key().equals(active.key()))) queue.add(p);
		}
		if (queue.isEmpty()) return;
		restore = active;
		warming = true;
		warmTotal = queue.size();
		warmCompleted = 0;
		warmFailed = 0;
		warmTarget = null;
		deadline = System.nanoTime() + 120_000_000_000L;
		var client = Minecraft.getInstance();
		client.gui.setScreen(new AmbienceWarmupScreen(client.gui.screen()));
	}

	static String warmProgress() {
		return warmCompleted + " / " + warmTotal + "   Failed: " + warmFailed;
	}

	public static void cancelWarmup() {
		if (!warming) return;
		queue.clear();
		warming = false;
		if (!Objects.equals(key(active), key(restore))) load(restore);
		restore = null;
	}

	private static String key(AmbiencePackManager.ResolvedProfile p) { return p == null ? null : p.key(); }

	public static void tick() {
		long now = System.nanoTime();
		if (now < nextCheck || HostReport.otherBackend()) return;
		nextCheck = now + 200_000_000L;
		var client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return;
		if (restoreOnJoin) {
			restoreOnJoin = false;
			load(null);
		}
		if (warming) {
			if (PackChoice.noPackWanted() || now > deadline) {
				cancelWarmup();
				return;
			}
			if (PackChain.compiling()) return;
			if (warmTarget != null) {
				if (active != null && active.key().equals(warmTarget) && PackChain.drawingPack()) warmed.add(warmTarget);
				else warmFailed++;
				warmCompleted++;
			}
			if (queue.isEmpty()) cancelWarmup();
			else {
				var next = queue.removeFirst();
				warmTarget = next.key();
				triedWarm.add(next.key());
				load(next);
				deadline = now + 120_000_000_000L;
			}
			return;
		}

		boolean enabled = WynnOption.AMBIENCE.enabled() && WynncraftSettings.effects() && !PackChoice.noPackWanted();
		if (active != null && PackChoice.lastError().isPresent()) {
			failedProfile = active.key();
			retryAfter = now + 30_000_000_000L;
			active = null;
			load(previous);
		}
		if (enabled && WynnOption.WARM_CACHE.enabled() && client.gui.screen() == null && now >= nextAuto) {
			nextAuto = now + 10_000_000_000L;
			warm(true);
			if (warming) return;
		}
		var next = enabled ? AmbiencePackManager.getInstance().resolveCurrentProfile(client, WynnOption.selectedPack()).orElse(null) : null;
		String key = key(next);
		if (!Objects.equals(key,pending)) {
			pending = key;
			pendingSince = now;
		}
		if (Objects.equals(key, key(active)) || now < nextSwitch || now - pendingSince < 500_000_000L) return;
		if (key != null && key.equals(failedProfile) && now < retryAfter) return;
		load(next);
		nextSwitch = System.nanoTime() + 1_500_000_000L;
	}

	private static void load(AmbiencePackManager.ResolvedProfile profile) {
		switching = true;
		long started = System.nanoTime();
		previous = active;
		try {
			// The editor mutates the manager's profile in place. Compare against what the pipeline loaded.
			active = profile == null ? null : new AmbiencePackManager.ResolvedProfile(profile.loadedPack(),
					profile.profileId(), profile.profile().copy(), profile.resolvedShaderPack(), profile.regionId());
			var game = Vitrail.platform().gameDirectory();
			PackChoice.reload(game);
			if (profile != null && PackChoice.lastError().isPresent()) {
				failedProfile = profile.key();
				retryAfter = System.nanoTime() + 30_000_000_000L;
				Vitrail.logger().warn("Ambience profile {} failed to load. Restoring previous settings.", profile.key());
				active = previous;
				previous = null;
				PackChoice.reload(game);
				if (active != null && PackChoice.lastError().isPresent()) {
					active = null;
					PackChoice.reload(game);
				}
			} else if (profile != null && profile.key().equals(failedProfile)) {
				failedProfile = null;
			}
		} finally {
			switching = false;
			if (WynnOption.DEBUG.enabled()) {
				dev.wynnvitrail.WynncraftDebugLog.info("ambience-switch",
						"Ambience switch requested={} active={} warmup={} elapsedMs={}",
						key(profile), key(active), warming, (System.nanoTime() - started) / 1_000_000L);
			}
		}
	}
}
