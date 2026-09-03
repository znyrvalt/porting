package com.altarsmp.fabric.ability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.entity.player.Player;

/**
 * Port of {@code com.altarsmp.utils.AbilityTracker}: the short "recently did X"
 * windows anti-cheat/other systems consult, plus the morph flags used by the
 * vampire/pale transformation abilities (there is no LibsDisguises here, so the
 * morph state is what the port keeps authoritative).
 */
public final class AbilityTracker {

	private static final Map<UUID, Long> ABILITY_USE = new HashMap<>();
	private static final Map<UUID, Long> DASH = new HashMap<>();
	private static final Map<UUID, Long> LEAP = new HashMap<>();
	private static final Map<UUID, Boolean> MORPHED = new HashMap<>();
	private static final Map<UUID, Boolean> MORPH_FLIGHT = new HashMap<>();

	private static final long ABILITY_WINDOW_MS = 5000L;
	private static final long DASH_WINDOW_MS = 3000L;
	private static final long LEAP_WINDOW_MS = 4000L;

	private AbilityTracker() {
	}

	public static void recordAbilityUse(Player player) {
		ABILITY_USE.put(player.getUUID(), System.currentTimeMillis());
	}

	public static void recordDash(Player player) {
		DASH.put(player.getUUID(), System.currentTimeMillis());
		ABILITY_USE.put(player.getUUID(), System.currentTimeMillis());
	}

	public static void recordLeap(Player player) {
		LEAP.put(player.getUUID(), System.currentTimeMillis());
		ABILITY_USE.put(player.getUUID(), System.currentTimeMillis());
	}

	public static void setMorphed(Player player, boolean morphed) {
		MORPHED.put(player.getUUID(), morphed);
		if (!morphed) {
			MORPH_FLIGHT.remove(player.getUUID());
		}
	}

	public static void setMorphFlight(Player player, boolean flight) {
		MORPH_FLIGHT.put(player.getUUID(), flight);
	}

	public static boolean recentlyUsedAbility(Player player) {
		Long at = ABILITY_USE.get(player.getUUID());
		return at != null && System.currentTimeMillis() - at < ABILITY_WINDOW_MS;
	}

	public static boolean recentlyDashed(Player player) {
		Long at = DASH.get(player.getUUID());
		return at != null && System.currentTimeMillis() - at < DASH_WINDOW_MS;
	}

	public static boolean recentlyLeaped(Player player) {
		Long at = LEAP.get(player.getUUID());
		return at != null && System.currentTimeMillis() - at < LEAP_WINDOW_MS;
	}

	public static boolean isMorphed(Player player) {
		return MORPHED.getOrDefault(player.getUUID(), false);
	}

	public static boolean hasMorphFlight(Player player) {
		return MORPH_FLIGHT.getOrDefault(player.getUUID(), false);
	}

	public static boolean shouldExemptFromFlyDetection(Player player) {
		return (isMorphed(player) && hasMorphFlight(player)) || recentlyUsedAbility(player);
	}

	public static void clearPlayer(Player player) {
		UUID uuid = player.getUUID();
		ABILITY_USE.remove(uuid);
		DASH.remove(uuid);
		LEAP.remove(uuid);
		MORPHED.remove(uuid);
		MORPH_FLIGHT.remove(uuid);
	}
}
