package com.altarsmp.fabric.command;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/**
 * Port of {@code com.altarsmp.managers.CommandControlsManager}.
 *
 * <p>{@code /controls toggle} puts a player in <em>command mode</em>: the normal
 * F / Shift+F activation stops firing and {@code /ability1} / {@code /ability2}
 * take over. Those commands temporarily raise the bypass flag while they replay
 * the activation, which is exactly how the original avoided an infinite loop.</p>
 */
public final class CommandControlsManager {

	private final Set<UUID> commandMode = new HashSet<>();
	private final Set<UUID> bypass = new HashSet<>();

	public boolean isCommandMode(ServerPlayer player) {
		return this.commandMode.contains(player.getUUID());
	}

	/** @return the new state - {@code true} when command mode was switched on. */
	public boolean toggleCommandMode(ServerPlayer player) {
		UUID uuid = player.getUUID();
		if (this.commandMode.contains(uuid)) {
			this.commandMode.remove(uuid);
			return false;
		}
		this.commandMode.add(uuid);
		return true;
	}

	public void setCommandMode(ServerPlayer player, boolean enabled) {
		if (enabled) {
			this.commandMode.add(player.getUUID());
		} else {
			this.commandMode.remove(player.getUUID());
		}
	}

	public void setBypass(ServerPlayer player, boolean enabled) {
		if (enabled) {
			this.bypass.add(player.getUUID());
		} else {
			this.bypass.remove(player.getUUID());
		}
	}

	public boolean isBypass(ServerPlayer player) {
		return this.bypass.contains(player.getUUID());
	}

	public boolean shouldSkipDefaultActivation(ServerPlayer player) {
		return this.isCommandMode(player) && !this.isBypass(player);
	}

	public void forget(ServerPlayer player) {
		this.commandMode.remove(player.getUUID());
		this.bypass.remove(player.getUUID());
	}

	public int commandModeCount() {
		return this.commandMode.size();
	}
}
