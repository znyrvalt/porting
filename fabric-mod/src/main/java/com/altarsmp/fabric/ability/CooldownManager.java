package com.altarsmp.fabric.ability;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Port of {@code com.altarsmp.utils.CooldownManager} - ability cooldowns keyed by
 * {@code "<weapon>.<ability>"} per player, measured in wall-clock milliseconds
 * exactly like the original (so a 60 second cooldown stays 60 seconds even if
 * the server ticks slowly).
 */
public final class CooldownManager {

	private final Map<String, Map<UUID, Long>> ends = new HashMap<>();

	public void setCooldown(ServerPlayer player, String key, long millis) {
		this.ends.computeIfAbsent(key, k -> new HashMap<>()).put(player.getUUID(), System.currentTimeMillis() + millis);
	}

	public void setCooldownSeconds(ServerPlayer player, String key, int seconds) {
		this.setCooldown(player, key, seconds * 1000L);
	}

	public boolean isOnCooldown(ServerPlayer player, String key) {
		Map<UUID, Long> map = this.ends.get(key);
		if (map == null) {
			return false;
		}
		Long until = map.get(player.getUUID());
		if (until == null) {
			return false;
		}
		if (System.currentTimeMillis() >= until) {
			map.remove(player.getUUID());
			return false;
		}
		return true;
	}

	public long getRemainingCooldown(ServerPlayer player, String key) {
		Map<UUID, Long> map = this.ends.get(key);
		if (map == null) {
			return 0L;
		}
		Long until = map.get(player.getUUID());
		if (until == null) {
			return 0L;
		}
		return Math.max(0L, until - System.currentTimeMillis());
	}

	public String getRemainingCooldownFormatted(ServerPlayer player, String key) {
		long millis = this.getRemainingCooldown(player, key);
		if (millis <= 0L) {
			return "0s";
		}
		long seconds = millis / 1000L;
		long minutes = seconds / 60L;
		seconds %= 60L;
		return minutes > 0L ? minutes + "m " + seconds + "s" : seconds + "s";
	}

	public int remainingSeconds(ServerPlayer player, String key) {
		return (int) Math.ceil(this.getRemainingCooldown(player, key) / 1000.0D);
	}

	public void clearCooldown(ServerPlayer player, String key) {
		Map<UUID, Long> map = this.ends.get(key);
		if (map != null) {
			map.remove(player.getUUID());
		}
	}

	public void clearAllCooldowns(ServerPlayer player) {
		for (Map<UUID, Long> map : this.ends.values()) {
			map.remove(player.getUUID());
		}
	}

	public void clearKey(String key) {
		this.ends.remove(key);
	}

	/** Every key that currently holds an entry for this player. */
	public Set<String> activeKeys(UUID player) {
		Set<String> keys = new LinkedHashSet<>();
		long now = System.currentTimeMillis();
		for (Map.Entry<String, Map<UUID, Long>> entry : this.ends.entrySet()) {
			Long until = entry.getValue().get(player);
			if (until != null && until > now) {
				keys.add(entry.getKey());
			}
		}
		return keys;
	}

	public int size() {
		int total = 0;
		for (Map<UUID, Long> map : this.ends.values()) {
			total += map.size();
		}
		return total;
	}

	/**
	 * Drops expired entries and forgets players who have left. The original kept
	 * them forever; pruning is the only difference and it is invisible to
	 * gameplay because expired cooldowns already read as "ready".
	 */
	public void tick(MinecraftServer server) {
		if (this.ends.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		Set<UUID> online = new LinkedHashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			online.add(player.getUUID());
		}
		Iterator<Map.Entry<String, Map<UUID, Long>>> outer = this.ends.entrySet().iterator();
		while (outer.hasNext()) {
			Map<UUID, Long> map = outer.next().getValue();
			map.values().removeIf(until -> until <= now);
			map.keySet().removeIf(uuid -> !online.contains(uuid));
			if (map.isEmpty()) {
				outer.remove();
			}
		}
	}
}
