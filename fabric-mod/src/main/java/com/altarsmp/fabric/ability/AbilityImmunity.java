package com.altarsmp.fabric.ability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerBossEvent;

import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.util.Messaging;

/**
 * Port of {@code a/l.java} (the plugin's {@code AbilityImmunityManager}).
 *
 * <p>Two independent gates, both driven by {@code ability-immunity.*} config:</p>
 * <ul>
 *   <li><b>victim immunity</b> - after being hit by an ability a player cannot be
 *       hit by another one for {@code victim-duration} seconds, and gets the
 *       blue "Ability Immunity" boss bar draining over that window;</li>
 *   <li><b>attacker cooldown</b> - after landing an ability the attacker cannot
 *       land another one for {@code attacker-duration} seconds.</li>
 * </ul>
 */
public final class AbilityImmunity {

	private final AltarConfig config;
	private final Map<UUID, Long> victimUntil = new HashMap<>();
	private final Map<UUID, ServerBossEvent> bars = new HashMap<>();
	private final Map<UUID, Long> barTotal = new HashMap<>();
	private final Map<UUID, Long> attackerUntil = new HashMap<>();

	public AbilityImmunity(AltarConfig config) {
		this.config = config;
	}

	private boolean victimEnabled() {
		return this.config.getBoolean("ability-immunity.victim-immunity", true);
	}

	private long victimMillis() {
		return this.config.getInt("ability-immunity.victim-duration", 2) * 1000L;
	}

	private boolean attackerEnabled() {
		return this.config.getBoolean("ability-immunity.attacker-cooldown", true);
	}

	private long attackerMillis() {
		return this.config.getInt("ability-immunity.attacker-duration", 2) * 1000L;
	}

	public boolean isVictimImmune(ServerPlayer player) {
		Long until = this.victimUntil.get(player.getUUID());
		if (until == null) {
			return false;
		}
		if (System.currentTimeMillis() >= until) {
			this.victimUntil.remove(player.getUUID());
			return false;
		}
		return true;
	}

	/** Grants victim immunity and shows the draining boss bar (a/l.java#b). */
	public void grantVictimImmunity(ServerPlayer player) {
		UUID uuid = player.getUUID();
		long millis = this.victimMillis();
		this.victimUntil.put(uuid, System.currentTimeMillis() + millis);
		ServerBossEvent existing = this.bars.remove(uuid);
		if (existing != null) {
			existing.removeAllPlayers();
		}
		ServerBossEvent bar = Messaging.bossBar("<aqua>Ability Immunity", BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
		bar.addPlayer(player);
		bar.setProgress(1.0F);
		this.bars.put(uuid, bar);
		this.barTotal.put(uuid, millis);
	}

	public boolean isAttackerOnCooldown(ServerPlayer player) {
		Long until = this.attackerUntil.get(player.getUUID());
		if (until == null) {
			return false;
		}
		if (System.currentTimeMillis() >= until) {
			this.attackerUntil.remove(player.getUUID());
			return false;
		}
		return true;
	}

	public void startAttackerCooldown(ServerPlayer player) {
		this.attackerUntil.put(player.getUUID(), System.currentTimeMillis() + this.attackerMillis());
	}

	/**
	 * The gate every ability calls before hurting a player (a/l.java#a).
	 * Returns {@code false} when the hit must not happen, and - when it does
	 * happen - arms both gates as a side effect, exactly like the original.
	 */
	public boolean allowAbilityHit(@Nullable ServerPlayer attacker, ServerPlayer victim) {
		if (this.victimEnabled() && this.isVictimImmune(victim)) {
			return false;
		}
		if (this.attackerEnabled() && attacker != null && this.isAttackerOnCooldown(attacker)) {
			return false;
		}
		if (this.victimEnabled()) {
			this.grantVictimImmunity(victim);
		}
		if (this.attackerEnabled() && attacker != null) {
			this.startAttackerCooldown(attacker);
		}
		return true;
	}

	/** Environmental/ownerless ability damage (a/l.java#e). */
	public boolean allowEnvironmentalHit(ServerPlayer victim) {
		return this.allowAbilityHit(null, victim);
	}

	public void clear(ServerPlayer player) {
		UUID uuid = player.getUUID();
		this.victimUntil.remove(uuid);
		this.attackerUntil.remove(uuid);
		this.barTotal.remove(uuid);
		ServerBossEvent bar = this.bars.remove(uuid);
		if (bar != null) {
			bar.removeAllPlayers();
		}
	}

	public void clearAll() {
		for (ServerBossEvent bar : this.bars.values()) {
			bar.removeAllPlayers();
		}
		this.bars.clear();
		this.barTotal.clear();
		this.victimUntil.clear();
		this.attackerUntil.clear();
	}

	/**
	 * Drains the immunity boss bars. The original used a Bukkit task ticking every
	 * 2 ticks; the same cadence is kept so the bar empties at the same rate.
	 */
	public void tick(MinecraftServer server) {
		if (this.bars.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		java.util.Iterator<Map.Entry<UUID, ServerBossEvent>> it = this.bars.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, ServerBossEvent> entry = it.next();
			UUID uuid = entry.getKey();
			Long until = this.victimUntil.get(uuid);
			Long total = this.barTotal.get(uuid);
			if (until == null || total == null || total <= 0L) {
				it.remove();
				this.barTotal.remove(uuid);
				entry.getValue().removeAllPlayers();
				continue;
			}
			long remaining = until - now;
			if (remaining <= 0L) {
				it.remove();
				this.barTotal.remove(uuid);
				this.victimUntil.remove(uuid);
				entry.getValue().removeAllPlayers();
				continue;
			}
			float progress = (float) Math.max(0.0D, Math.min(1.0D, (double) remaining / (double) total));
			entry.getValue().setProgress(progress);
		}
	}

	public int trackedBars() {
		return this.bars.size();
	}

}
