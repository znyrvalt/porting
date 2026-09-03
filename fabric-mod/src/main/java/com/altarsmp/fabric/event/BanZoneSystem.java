package com.altarsmp.fabric.event;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.data.WorldRecord;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * Ban Zone - port of {@code com.altarsmp.systems.BanZoneSystem}.
 *
 * <p>A last-player-standing mode. While it is active, dying does not send you back
 * to the fight: you are announced as <b>ELIMINATED</b> with a thunder crack for
 * everyone, and five ticks later you are put into spectator mode and told so. If
 * you respawn or rejoin anyway the spectator mode is re-applied (the plugin did
 * this from respawn and join listeners; here the same job is done by waiting for
 * the player to come back alive and by the join callback), so an eliminated player
 * can never get back into the game. When exactly one non-eliminated player is left
 * online, the winner is announced with the toast sound.
 *
 * <p>Deactivating puts every eliminated player back into survival and tells them
 * they are free.
 *
 * <p>The plugin kept the eliminated set in memory only, so a restart during a ban
 * zone freed everybody silently. The port stores the flag and the eliminated
 * players in {@link WorldRecord.EventState} and reloads them on start.
 */
public final class BanZoneSystem {

	/** Ticks the plugin waited before forcing spectator mode after a death. */
	private static final long SPECTATOR_DELAY = 5L;

	private final AltarSMPMod mod;
	private final Set<UUID> eliminated = new HashSet<>();
	private boolean active;

	public BanZoneSystem(AltarSMPMod mod) {
		this.mod = mod;
	}

	/**
	 * The plugin used join and respawn listeners; the join callback is registered
	 * here and the respawn case is handled by {@link #forceSpectator} waiting for the
	 * player to be alive again, which needs no callback of its own.
	 */
	public void registerEventHooks() {
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			if (this.active && this.eliminated.contains(player.getUUID())) {
				forceSpectator(player, "<red>You are eliminated. Spectator mode only.");
			}
		});
	}

	/** Reloads the persisted state; a restart mid-ban-zone keeps everyone eliminated. */
	public void loadFromStore() {
		WorldRecord.EventState record = this.mod.store().world().banZone();
		this.active = record.active();
		this.eliminated.clear();
		for (String key : record.data().keySet()) {
			try {
				this.eliminated.add(UUID.fromString(key));
			} catch (IllegalArgumentException e) {
				AltarSMPMod.LOGGER.warn("[AltarSMP] ignoring unreadable ban-zone entry '{}'", key);
			}
		}
		if (this.active) {
			AltarSMPMod.LOGGER.info("[AltarSMP] resuming an active ban zone with {} eliminated player(s)",
					this.eliminated.size());
		}
	}

	private void persist() {
		WorldRecord.EventState record = this.mod.store().world().banZone();
		record.active(this.active);
		record.data().clear();
		MinecraftServer server = this.mod.server();
		for (UUID uuid : this.eliminated) {
			ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(uuid);
			record.data().put(uuid.toString(), player == null ? "" : player.getGameProfile().getName());
		}
		this.mod.store().markDirty();
	}

	/** {@code BanZoneSystem#activate}. */
	public void activate() {
		if (this.active) {
			return;
		}
		this.active = true;
		this.eliminated.clear();
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		Messaging.broadcast(server, "<red><bold>===============================");
		Messaging.broadcast(server, "<dark_red><bold>BAN ZONE ACTIVATED!");
		Messaging.broadcast(server, "<gray>Death = spectator mode. No respawning.");
		Messaging.broadcast(server, "<gray>Last player standing wins!");
		Messaging.broadcast(server, "<red><bold>===============================");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Fx.soundTo(player, "ENTITY_WITHER_SPAWN", 1.0F, 0.5F);
		}
		persist();
	}

	/** {@code BanZoneSystem#deactivate}. */
	public void deactivate() {
		if (!this.active) {
			return;
		}
		this.active = false;
		MinecraftServer server = this.mod.server();
		if (server != null) {
			for (UUID uuid : this.eliminated) {
				ServerPlayer player = server.getPlayerList().getPlayer(uuid);
				if (player != null) {
					player.setGameMode(GameType.SURVIVAL);
					Messaging.send(player, "<green>Ban zone has been deactivated. You are free!");
				}
			}
			Messaging.broadcast(server, "<red><bold>===============================");
			Messaging.broadcast(server, "<green><bold>BAN ZONE DEACTIVATED!");
			Messaging.broadcast(server, "<red><bold>===============================");
		}
		this.eliminated.clear();
		persist();
	}

	/**
	 * Shutdown: the loop stops but the stored state stays, so a restart in the middle
	 * of a ban zone resumes it instead of silently freeing everybody.
	 */
	public void deactivateForShutdown() {
		persist();
	}

	/** {@code BanZoneSystem#onPlayerDeath}. */
	public void onDeath(LivingEntity victim, ServerLevel level, DamageSource source, @Nullable ServerPlayer killer) {
		if (!this.active || !(victim instanceof ServerPlayer dead) || this.eliminated.contains(dead.getUUID())) {
			return;
		}
		this.eliminated.add(dead.getUUID());
		MinecraftServer server = this.mod.server();
		if (server != null) {
			Messaging.broadcast(server, "<dark_red><bold>ELIMINATED: <yellow>" + dead.getGameProfile().getName()
					+ " <gray>has been eliminated!");
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Fx.soundTo(player, "ENTITY_LIGHTNING_BOLT_THUNDER", 0.8F, 0.5F);
			}
		}
		forceSpectator(dead, "<red>You have been eliminated. You are now a spectator.");
		persist();
		checkForWinner();
	}

	/**
	 * The plugin's two delayed spectator tasks. A dead player is not yet respawned
	 * five ticks later, so this keeps re-arming until the player is alive again and
	 * only then switches the game mode - which is what the respawn listener was for.
	 */
	private void forceSpectator(ServerPlayer player, String message) {
		this.mod.scheduler().later(() -> {
			if (!this.active) {
				return;
			}
			MinecraftServer server = this.mod.server();
			ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(player.getUUID());
			if (online == null) {
				return;
			}
			if (!online.isAlive()) {
				forceSpectator(online, message);
				return;
			}
			if (online.gameMode() != GameType.SPECTATOR) {
				online.setGameMode(GameType.SPECTATOR);
				Messaging.send(online, message);
			}
		}, SPECTATOR_DELAY);
	}

	/** {@code BanZoneSystem#checkForWinner}. */
	private void checkForWinner() {
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		List<ServerPlayer> standing = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!this.eliminated.contains(player.getUUID())) {
				standing.add(player);
			}
		}
		if (standing.size() != 1) {
			return;
		}
		ServerPlayer winner = standing.get(0);
		Messaging.broadcast(server, "<gold><bold>===============================");
		Messaging.broadcast(server, "<green><bold>WINNER: <gold>" + winner.getGameProfile().getName());
		Messaging.broadcast(server, "<gray>The last player standing!");
		Messaging.broadcast(server, "<gold><bold>===============================");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Fx.soundTo(player, "UI_TOAST_CHALLENGE_COMPLETE", 1.0F, 1.0F);
		}
		WorldRecord.EventState record = this.mod.store().world().banZone();
		record.owner(winner.getGameProfile().getName());
		record.data().put("winner", winner.getGameProfile().getName());
		this.mod.store().markDirty();
	}

	public boolean isActive() {
		return this.active;
	}

	/** {@code BanZoneSystem#isEliminated}. */
	public boolean isEliminated(ServerPlayer player) {
		return this.eliminated.contains(player.getUUID());
	}

	/** {@code BanZoneSystem#getEliminated}. */
	public Set<UUID> getEliminated() {
		return this.eliminated;
	}

	/** Diagnostics for {@code /altarsmp status}. */
	public String describe() {
		return this.active
				? "active, " + this.eliminated.size() + " eliminated"
				: "inactive (" + this.eliminated.size() + " eliminated record kept)";
	}
}
