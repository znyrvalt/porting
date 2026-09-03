package com.altarsmp.fabric.ability;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerBossEvent;

import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TextFx;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * The per-ability cooldown boss bar every weapon in the plugin showed.
 *
 * <p>{@code BoneBladeWeapon#startCooldownBar} and its copies in the other weapon
 * classes created a boss bar with a label ("Skeletal Leap", "Bone Cage", ...) and
 * drained it over the cooldown length. That code was duplicated ~20 times; the
 * port keeps the behaviour in one place. Bars are keyed by
 * {@code <uuid>:<ability key>} so a player can watch two cooldowns at once.</p>
 */
public final class CooldownBars {

	private record Bar(ServerBossEvent bar, long endsAt, long total) {
	}

	private static final Map<UUID, Map<String, Bar>> BARS = new HashMap<>();
	private static boolean registered;

	private CooldownBars() {
	}

	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = System.currentTimeMillis();
			Iterator<Map.Entry<UUID, Map<String, Bar>>> players = BARS.entrySet().iterator();
			while (players.hasNext()) {
				Map<String, Bar> bars = players.next().getValue();
				Iterator<Map.Entry<String, Bar>> it = bars.entrySet().iterator();
				while (it.hasNext()) {
					Bar bar = it.next().getValue();
					long remaining = bar.endsAt() - now;
					if (remaining <= 0L) {
						bar.bar().removeAllPlayers();
						it.remove();
						continue;
					}
					bar.bar().setProgress((float) Math.max(0.0D, Math.min(1.0D, (double) remaining / (double) bar.total())));
				}
				if (bars.isEmpty()) {
					players.remove();
				}
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// Drop bars whose owner has gone offline.
			java.util.Set<UUID> online = new java.util.HashSet<>();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				online.add(player.getUUID());
			}
			BARS.keySet().removeIf(uuid -> {
				if (online.contains(uuid)) {
					return false;
				}
				Map<String, Bar> bars = BARS.get(uuid);
				if (bars != null) {
					bars.values().forEach(b -> b.bar().removeAllPlayers());
				}
				return true;
			});
		});
	}

	/** Bukkit {@code BarColor} -&gt; vanilla boss bar colour. */
	public static BossEvent.BossBarColor color(String bukkitColor) {
		if (bukkitColor == null) {
			return BossEvent.BossBarColor.YELLOW;
		}
		return switch (bukkitColor.toUpperCase(java.util.Locale.ROOT)) {
			case "BLUE" -> BossEvent.BossBarColor.BLUE;
			case "RED" -> BossEvent.BossBarColor.RED;
			case "GREEN" -> BossEvent.BossBarColor.GREEN;
			case "PINK" -> BossEvent.BossBarColor.PINK;
			case "PURPLE" -> BossEvent.BossBarColor.PURPLE;
			case "WHITE" -> BossEvent.BossBarColor.WHITE;
			default -> BossEvent.BossBarColor.YELLOW;
		};
	}

	public static void show(ServerPlayer player, String key, String label, BossEvent.BossBarColor color, int seconds) {
		show(player, key, label, color, seconds * 1000L);
	}

	public static void show(ServerPlayer player, String key, String label, BossEvent.BossBarColor color, long millis) {
		if (millis <= 0L) {
			return;
		}
		Map<String, Bar> bars = BARS.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
		Bar existing = bars.remove(key);
		if (existing != null) {
			existing.bar().removeAllPlayers();
		}
		ServerBossEvent bar = Messaging.bossBar("<yellow>" + TextFx.strip(label), color, BossEvent.BossBarOverlay.PROGRESS);
		bar.addPlayer(player);
		bar.setProgress(1.0F);
		bars.put(key, new Bar(bar, System.currentTimeMillis() + millis, millis));
	}

	public static void hide(ServerPlayer player, String key) {
		Map<String, Bar> bars = BARS.get(player.getUUID());
		if (bars == null) {
			return;
		}
		Bar bar = bars.remove(key);
		if (bar != null) {
			bar.bar().removeAllPlayers();
		}
		if (bars.isEmpty()) {
			BARS.remove(player.getUUID());
		}
	}

	public static void clear(ServerPlayer player) {
		Map<String, Bar> bars = BARS.remove(player.getUUID());
		if (bars != null) {
			bars.values().forEach(b -> b.bar().removeAllPlayers());
		}
	}

	public static int activeBars() {
		int total = 0;
		for (Map<String, Bar> bars : BARS.values()) {
			total += bars.size();
		}
		return total;
	}
}
