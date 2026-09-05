package com.altarsmp.fabric.util;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerBossEvent;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * Chat / action-bar / title / boss-bar delivery.
 *
 * <p>The original plugin broadcasts with Bukkit's {@code broadcastMessage},
 * {@code sendActionBar}, {@code sendTitle} and {@code BossBar}.  These helpers
 * are the Fabric equivalents and are used by every announcement in the port so
 * that wording, colour and delivery channel stay identical.</p>
 */
public final class Messaging {

	private Messaging() {
	}

	public static Component msg(String markup) {
		return TextFx.parse(markup);
	}

	public static void send(ServerPlayer player, String markup) {
		if (player != null) {
			player.displayClientMessage(TextFx.parse(markup), false);
		}
	}

	public static void send(ServerPlayer player, Component component) {
		if (player != null && component != null) {
			player.displayClientMessage(component, false);
		}
	}

	public static void actionBar(ServerPlayer player, String markup) {
		if (player != null) {
			player.displayClientMessage(TextFx.parse(markup), true);
		}
	}

	public static void actionBar(ServerPlayer player, Component component) {
		if (player != null && component != null) {
			player.displayClientMessage(component, true);
		}
	}

	public static void title(ServerPlayer player, String titleMarkup, String subtitleMarkup, int fadeIn, int stay, int fadeOut) {
		if (player == null) {
			return;
		}
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
		player.connection.send(new ClientboundSetSubtitleTextPacket(TextFx.parse(subtitleMarkup == null ? "" : subtitleMarkup)));
		player.connection.send(new ClientboundSetTitleTextPacket(TextFx.parse(titleMarkup == null ? "" : titleMarkup)));
	}

	public static void broadcast(MinecraftServer server, String markup) {
		if (server == null) {
			return;
		}
		Component component = TextFx.parse(markup);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.displayClientMessage(component, false);
		}
		AltarSMPMod.LOGGER.info("[broadcast] {}", TextFx.strip(markup));
	}

	public static void broadcast(MinecraftServer server, Component component) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.displayClientMessage(component, false);
		}
	}

	public static void broadcastOps(MinecraftServer server, String markup) {
		if (server == null) {
			return;
		}
		Component component = TextFx.parse(markup);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.hasPermissions(2)) {
				player.displayClientMessage(component, false);
			}
		}
	}

	public static void send(List<ServerPlayer> players, String markup) {
		Component component = TextFx.parse(markup);
		for (ServerPlayer player : players) {
			player.displayClientMessage(component, false);
		}
	}

	/** Creates a boss bar with the given name/colour/overlay. */
	public static ServerBossEvent bossBar(String markup, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
		return new ServerBossEvent(TextFx.parse(markup), color == null ? BossEvent.BossBarColor.WHITE : color,
				overlay == null ? BossEvent.BossBarOverlay.PROGRESS : overlay);
	}

	public static void showBossBar(ServerBossEvent bar, ServerPlayer player) {
		if (bar != null && player != null) {
			bar.addPlayer(player);
		}
	}

	public static void hideBossBar(ServerBossEvent bar, ServerPlayer player) {
		if (bar != null && player != null) {
			bar.removePlayer(player);
		}
	}

	@Nullable
	public static ChatFormatting colorOf(String name) {
		if (name == null) {
			return null;
		}
		try {
			return ChatFormatting.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
