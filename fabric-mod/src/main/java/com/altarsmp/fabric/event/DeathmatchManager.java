package com.altarsmp.fabric.event;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.data.WorldRecord;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TickScheduler;

/**
 * Deathmatch - port of {@code com.altarsmp.sessions.Deathmatch} and its
 * {@code /deathmatch <start|stop|status>} command.
 *
 * <p>Starting a deathmatch does four real things to the world the sender is
 * standing in:
 * <ul>
 *   <li>the world border starts shrinking from its current size to 200 blocks over
 *       the full hour ({@code WorldBorder#setSize(200, 3600)} in Bukkit terms);</li>
 *   <li>every player's waypoint transmit and receive ranges are dropped to zero,
 *       which is how the locator bar gets disabled, and are put back to 60,000,000
 *       when the match ends;</li>
 *   <li>a red progress boss bar counting the hour down is shown to everybody online
 *       and to anyone who joins mid-match;</li>
 *   <li>the start and end are broadcast and sound with a wither spawn and a toast.</li>
 * </ul>
 *
 * <p>When the hour runs out the match ends on its own: the boss bar goes away, the
 * border lerps back to the size it had before over five seconds and the locator
 * bars come back.
 *
 * <p>Upstream kept none of this across a restart, so a server reboot during a
 * deathmatch left a 200-block border and disabled locators behind forever. The port
 * stores the running flag, both timestamps, the dimension and the original border
 * size in {@link WorldRecord.EventState} and restores the whole picture on start.
 */
public final class DeathmatchManager {

	/** The plugin's hard-coded hour. */
	private static final long DURATION_MILLIS = 3600000L;
	/** The plugin's target border size. */
	private static final double TARGET_BORDER = 200.0D;
	/** Seconds the border takes to shrink, and to come back (5s). */
	private static final long SHRINK_MILLIS = 3600L * 1000L;
	private static final long RESTORE_MILLIS = 5L * 1000L;
	/** Bukkit's default waypoint range. */
	private static final double LOCATOR_RANGE = 6.0E7D;
	private static final long TASK_PERIOD = 20L;

	private final AltarSMPMod mod;
	private boolean running;
	@Nullable
	private ServerBossEvent bar;
	@Nullable
	private TickScheduler.Task task;
	private long endsAt;
	private double originalBorder = -1.0D;
	@Nullable
	private ResourceKey<Level> arena;

	public DeathmatchManager(AltarSMPMod mod) {
		this.mod = mod;
	}

	/** Boss bar for late joiners, and the locator disable - the plugin's join listener. */
	public void registerEventHooks() {
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!this.running) {
				return;
			}
			ServerPlayer player = handler.player;
			if (this.bar != null) {
				Messaging.showBossBar(this.bar, player);
			}
			disableLocators(player);
		});
	}

	/** Reads the persisted match so a restart in the middle of one resumes it. */
	public void loadFromStore() {
		WorldRecord.EventState record = this.mod.store().world().deathmatch();
		this.running = record.active();
		this.endsAt = record.endsAt();
		this.originalBorder = record.numbers().getOrDefault("border", -1L);
		String dimension = record.data().get("world");
		this.arena = dimension == null || dimension.isBlank() ? null : parseDimension(dimension);
		if (this.running) {
			AltarSMPMod.LOGGER.info("[AltarSMP] resuming deathmatch in {} ending at {} ms (border was {})",
					dimension, this.endsAt, this.originalBorder);
		}
	}

	private static ResourceKey<Level> parseDimension(String id) {
		net.minecraft.resources.Identifier identifier = net.minecraft.resources.Identifier.parse(id);
		return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, identifier);
	}

	/**
	 * Server-started work: the boss bar and the shrinking task are per-process
	 * objects, so they are rebuilt here rather than in {@link #loadFromStore}. If the
	 * hour already ran out while the server was down the match is ended right away.
	 */
	public void resumeIfActive() {
		if (!this.running) {
			return;
		}
		MinecraftServer server = this.mod.server();
		ServerLevel level = arenaLevel(server);
		if (level == null) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] deathmatch world '{}' is not loaded; ending the match", this.arena);
			this.running = false;
			persist();
			return;
		}
		if (System.currentTimeMillis() >= this.endsAt) {
			onEnd(server, level);
			return;
		}
		createBar(server);
		startTask();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			disableLocators(player);
		}
	}

	/** Called from {@code AltarSMPMod} on shutdown; the state is kept for the next boot. */
	public void stopForShutdown() {
		if (this.task != null) {
			this.task.cancel();
			this.task = null;
		}
		persist();
	}

	/** {@code /deathmatch start} - the sender's world becomes the arena. */
	public String start(ServerPlayer initiator) {
		if (this.running) {
			return "<red>Deathmatch is already running!";
		}
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return "<red>The server is not ready.";
		}
		ServerLevel level = initiator.serverLevel();
		this.running = true;
		this.arena = level.dimension();
		long now = System.currentTimeMillis();
		this.endsAt = now + DURATION_MILLIS;

		WorldBorder border = level.getWorldBorder();
		this.originalBorder = border.getSize();
		border.lerpSizeBetween(this.originalBorder, TARGET_BORDER, SHRINK_MILLIS, level.getGameTime());

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			disableLocators(player);
		}
		createBar(server);
		startTask();

		Messaging.broadcast(server, "<red><bold>===============================");
		Messaging.broadcast(server, "<dark_red><bold>DEATHMATCH HAS STARTED!");
		Messaging.broadcast(server, "<gray>World border is shrinking!");
		Messaging.broadcast(server, "<gray>Locator bars are <red>DISABLED<gray>.");
		Messaging.broadcast(server, "<red><bold>===============================");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Fx.soundTo(player, "ENTITY_WITHER_SPAWN", 1.0F, 0.8F);
		}
		persist();
		return "<green>Deathmatch started!";
	}

	/** {@code /deathmatch stop}. */
	public String stop() {
		if (!this.running) {
			return "<red>No deathmatch is running.";
		}
		MinecraftServer server = this.mod.server();
		ServerLevel level = arenaLevel(server);
		if (this.task != null) {
			this.task.cancel();
			this.task = null;
		}
		finish(server, level);
		return "<red>Deathmatch stopped!";
	}

	/** {@code /deathmatch status}. */
	public String status() {
		if (!this.running) {
			return "<gray>No deathmatch is running.";
		}
		long left = Math.max(0L, (this.endsAt - System.currentTimeMillis()) / 1000L);
		return "<yellow>Deathmatch is running! <gray>" + formatTime(left) + " left";
	}

	/** {@code Deathmatch#isRunning}. */
	public boolean isRunning() {
		return this.running;
	}

	/** The plugin's repeating task body: count down, then end. */
	private void onTaskTick() {
		MinecraftServer server = this.mod.server();
		if (!this.running || server == null) {
			if (this.task != null) {
				this.task.cancel();
				this.task = null;
			}
			return;
		}
		long remaining = this.endsAt - System.currentTimeMillis();
		if (remaining <= 0L) {
			onEnd(server, arenaLevel(server));
			return;
		}
		if (this.bar != null) {
			float progress = (float) remaining / (float) DURATION_MILLIS;
			this.bar.setProgress(Math.max(0.0F, Math.min(1.0F, progress)));
			this.bar.setName(Messaging.msg("<red>DEATHMATCH <gray>- <yellow>" + formatTime(remaining / 1000L)));
		}
	}

	/** {@code Deathmatch#onEnd} - the hour ran out. */
	private void onEnd(MinecraftServer server, @Nullable ServerLevel level) {
		if (this.task != null) {
			this.task.cancel();
			this.task = null;
		}
		this.running = false;
		finish(server, level);
	}

	/** The shared teardown used by {@code stop()} and {@code onEnd()}. */
	private void finish(@Nullable MinecraftServer server, @Nullable ServerLevel level) {
		if (server != null) {
			if (this.bar != null) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					Messaging.hideBossBar(this.bar, player);
				}
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				enableLocators(player);
			}
		}
		this.bar = null;
		if (level != null && this.originalBorder > 0.0D) {
			level.getWorldBorder().lerpSizeBetween(level.getWorldBorder().getSize(), this.originalBorder,
					RESTORE_MILLIS, level.getGameTime());
		}
		this.running = false;
		if (server != null) {
			Messaging.broadcast(server, "<red><bold>===============================");
			Messaging.broadcast(server, "<dark_red><bold>DEATHMATCH HAS ENDED!");
			Messaging.broadcast(server, "<red><bold>===============================");
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Fx.soundTo(player, "UI_TOAST_CHALLENGE_COMPLETE", 1.0F, 0.8F);
			}
		}
		persist();
	}

	private void createBar(MinecraftServer server) {
		this.bar = Messaging.bossBar("<red>DEATHMATCH <gray>- <yellow>" + formatTime(DURATION_MILLIS / 1000L),
				BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
		this.bar.setProgress(1.0F);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.showBossBar(this.bar, player);
		}
	}

	private void startTask() {
		this.task = this.mod.scheduler().timer(this::onTaskTick, TASK_PERIOD, TASK_PERIOD);
	}

	/** {@code Deathmatch#disableLocators} - the waypoint attributes behind the locator bar. */
	private static void disableLocators(ServerPlayer player) {
		setLocatorRange(player, Attributes.WAYPOINT_TRANSMIT_RANGE, 0.0D);
		setLocatorRange(player, Attributes.WAYPOINT_RECEIVE_RANGE, 0.0D);
	}

	/** {@code Deathmatch#enableLocators}. */
	private static void enableLocators(ServerPlayer player) {
		setLocatorRange(player, Attributes.WAYPOINT_TRANSMIT_RANGE, LOCATOR_RANGE);
		setLocatorRange(player, Attributes.WAYPOINT_RECEIVE_RANGE, LOCATOR_RANGE);
	}

	private static void setLocatorRange(ServerPlayer player,
			net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance != null) {
			instance.setBaseValue(value);
		}
	}

	@Nullable
	private ServerLevel arenaLevel(@Nullable MinecraftServer server) {
		if (server == null || this.arena == null) {
			return null;
		}
		return server.getLevel(this.arena);
	}

	private void persist() {
		WorldRecord.EventState record = this.mod.store().world().deathmatch();
		record.active(this.running);
		record.endsAt(this.endsAt);
		record.startedAt(this.endsAt - DURATION_MILLIS);
		record.data().put("world", this.arena == null ? "" : this.arena.location().toString());
		record.numbers().put("border", (long) this.originalBorder);
		this.mod.store().markDirty();
	}

	/** {@code Deathmatch#formatTime}. */
	static String formatTime(long seconds) {
		long hours = seconds / 3600L;
		long minutes = seconds % 3600L / 60L;
		long rest = seconds % 60L;
		return hours > 0L ? String.format("%d:%02d:%02d", hours, minutes, rest)
				: String.format("%d:%02d", minutes, rest);
	}
}
