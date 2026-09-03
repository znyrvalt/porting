package com.altarsmp.fabric.event;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Team;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.data.WorldRecord;
import com.altarsmp.fabric.faction.FactionManager;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * Blood Moon - port of {@code com.altarsmp.managers.BloodMoonManager}.
 *
 * <p>Starting one is far more than "set the time to midnight", which is what the
 * plugin did <em>plus</em> everything below, and the port keeps every part:
 * <ol>
 *   <li>the pale system is woken up if it has not been already
 *       ({@link FactionManager#activatePaleSystem()}), which converts everyone
 *       standing on moss at that moment and broadcasts the awakening;</li>
 *   <li>every overworld is pushed to tick 13000 - full night;</li>
 *   <li>every online player gets a dark-red bold <b>BLOOD MOON</b> title (20/80/30
 *       fade), the chat line, a wither spawn sound and an ender dragon growl;</li>
 *   <li>a yellow {@code bloodmoon_pale} scoreboard team is created and every pale
 *       player is put in it, so the infected are visibly revealed for the rest of
 *       the night;</li>
 *   <li>each second vampires get Speed III and Strength II and pales get Resistance
 *       I (hidden particles, 40-tick duration so they lapse the moment the moon
 *       ends), and the team membership is re-synced to catch newly turned players;</li>
 *   <li>when the overworld clock reaches 23000 - or is somehow before 13000 - the
 *       moon ends: buffs are stripped, the team is unregistered, and everyone is
 *       told the night has returned to normal with a beacon deactivate sound.</li>
 * </ol>
 *
 * <p>The plugin kept all of that in a {@code BukkitRunnable} and lost it on
 * restart. Here the state lives in {@link WorldRecord.BloodMoon}, so a restart in
 * the middle of a blood moon resumes it (see {@link #resumeIfActive()}) and
 * {@link #shutdown()} leaves the flag set on purpose.
 *
 * <p>One deliberate difference: the plugin coloured pale names yellow twice - once
 * through {@code VampireManager#updatePlayerNameColor}'s display name and once
 * through this team. A Fabric player can only be in one team, so
 * {@link FactionManager} now leaves pale players alone while the moon is up and
 * this class owns their yellow.
 */
public final class BloodMoonManager {

	/** The plugin's team name, kept so existing scoreboards and maps still read it. */
	public static final String PALE_TEAM = "bloodmoon_pale";
	/** Tick of the day the moon forces ({@code World#setTime(13000)}). */
	private static final long NIGHT_TIME = 13000L;
	/** Tick of the day the moon ends at ({@code time >= 23000 || time < 13000}). */
	private static final long DAWN_TIME = 23000L;
	/** Buff duration in ticks - 2 seconds, re-applied every second. */
	private static final int BUFF_DURATION = 40;
	/** The plugin's task period. */
	private static final long TASK_PERIOD = 20L;
	private static final int TITLE_FADE_IN = 20;
	private static final int TITLE_STAY = 80;
	private static final int TITLE_FADE_OUT = 30;

	private final AltarSMPMod mod;
	private boolean active;
	private long tickCount;

	public BloodMoonManager(AltarSMPMod mod) {
		this.mod = mod;
	}

	/** {@code BloodMoonManager#isBloodMoonActive}. */
	public boolean isActive() {
		return this.active;
	}

	/**
	 * Nothing to subscribe to: the plugin's only listener-free work ran on its
	 * repeating task, which is {@link #tick} here, and the {@code /bloodmoon} command
	 * is registered by {@code command.CommandRegistrar}.
	 */
	public void registerEventHooks() {
		// Intentionally empty - see the class javadoc and #tick.
	}

	/** Reads the persisted flag; the visuals come back in {@link #resumeIfActive()}. */
	public void loadFromStore() {
		WorldRecord.BloodMoon record = this.mod.store().world().bloodMoon();
		this.active = record.active();
	}

	/**
	 * Server-started hook: a blood moon that was running when the server went down
	 * comes back with its team and its announcement, and the tick loop ends it at
	 * dawn as usual. If the world clock already passed dawn while the server was off
	 * the very first tick winds it down cleanly.
	 */
	public void resumeIfActive() {
		if (!this.active) {
			return;
		}
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		AltarSMPMod.LOGGER.info("[AltarSMP] resuming an active Blood Moon (started at tick {}, {} activation(s) so far)",
				this.mod.store().world().bloodMoon().startedAt(),
				this.mod.store().world().bloodMoon().activations());
		createPaleTeam(server);
		syncPaleTeam(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.title(player, "<dark_red><bold>BLOOD MOON", "<red>The blood moon still hangs in the sky...",
					TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
			Fx.soundTo(player, "ENTITY_WITHER_SPAWN", 0.8F, 0.5F);
		}
	}

	/** {@code BloodMoonManager#shutdown} - stops the loop but keeps the flag stored. */
	public void shutdown() {
		if (!this.active) {
			return;
		}
		this.active = false;
		MinecraftServer server = this.mod.server();
		if (server != null) {
			removePaleTeam(server);
		}
		// The record stays active on purpose: resumeIfActive() picks it up next boot.
		this.mod.store().markDirty();
	}

	/**
	 * {@code /bloodmoon} - the plugin's {@code onCommand}. Called by the command
	 * registrar, which has already checked the permission.
	 *
	 * @return the markup line the command should show the sender
	 */
	public String start() {
		if (!this.mod.config().getBoolean("curses.enabled", true)) {
			return "<red>The faction system (curses) is disabled in config. Blood Moon requires it.";
		}
		if (this.active) {
			return "<red>Blood Moon is already active! It will end at dawn.";
		}
		activate();
		return "<dark_red>Blood Moon has been <bold>activated</bold>! It will end at dawn.";
	}

	/** {@code BloodMoonManager#a()} - the whole activation sequence. */
	private void activate() {
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		this.active = true;
		WorldRecord.BloodMoon record = this.mod.store().world().bloodMoon();
		record.active(true);
		record.startedAt(server.getTickCount());
		record.activations(record.activations() + 1);

		if (!this.mod.factions().isPaleActivated()) {
			this.mod.factions().activatePaleSystem();
		}

		for (ServerLevel level : overworlds(server)) {
			record.savedDayTime(level.getDayTime());
			level.setDayTime(NIGHT_TIME);
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.title(player, "<dark_red><bold>BLOOD MOON", "<red>The blood moon rises...",
					TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
			Messaging.send(player,
					"<dark_red><bold>BLOOD MOON</bold> <red>has risen! Vampires grow stronger and pale rots are revealed.");
			Fx.soundTo(player, "ENTITY_WITHER_SPAWN", 0.8F, 0.5F);
			Fx.soundTo(player, "ENTITY_ENDER_DRAGON_GROWL", 0.6F, 0.5F);
		}

		createPaleTeam(server);
		syncPaleTeam(server);
		this.mod.store().markDirty();
	}

	/** {@code BloodMoonManager}'s repeating task body. */
	public void tick(MinecraftServer server) {
		if (!this.active) {
			return;
		}
		this.tickCount++;
		if (this.tickCount % TASK_PERIOD != 0L) {
			return;
		}
		for (ServerLevel level : overworlds(server)) {
			long time = level.getDayTime();
			if (time >= DAWN_TIME || time < NIGHT_TIME) {
				end(server);
				return;
			}
			break;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (FactionManager.isVampire(player)) {
				Effects.apply(player, "SPEED", BUFF_DURATION, 2, false, false, true);
				Effects.apply(player, "STRENGTH", BUFF_DURATION, 1, false, false, true);
			}
			if (FactionManager.isPale(player)) {
				Effects.apply(player, "RESISTANCE", BUFF_DURATION, 0, false, false, true);
			}
		}
		syncPaleTeam(server);
	}

	/** {@code BloodMoonManager#b()} - dawn. */
	private void end(MinecraftServer server) {
		this.active = false;
		WorldRecord.BloodMoon record = this.mod.store().world().bloodMoon();
		record.active(false);
		record.endsAt(server.getTickCount());

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (FactionManager.isVampire(player)) {
				Effects.remove(player, "SPEED");
				Effects.remove(player, "STRENGTH");
			}
			if (FactionManager.isPale(player)) {
				Effects.remove(player, "RESISTANCE");
			}
		}
		removePaleTeam(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.send(player, "<gray>The Blood Moon has faded. The night returns to normal.");
			Fx.soundTo(player, "BLOCK_BEACON_DEACTIVATE", 0.8F, 1.0F);
		}
		this.mod.store().markDirty();
	}

	// ------------------------------------------------------------- pale team

	/** Bukkit's {@code Environment.NORMAL} is the overworld. */
	private static List<ServerLevel> overworlds(MinecraftServer server) {
		return server.getAllLevels().stream()
				.filter(level -> level.dimension().equals(Level.OVERWORLD))
				.toList();
	}

	/** {@code BloodMoonManager#c()}. */
	private void createPaleTeam(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(PALE_TEAM);
		if (team == null) {
			team = scoreboard.addPlayerTeam(PALE_TEAM);
		}
		team.setColor(ChatFormatting.YELLOW);
		team.setPlayerPrefix(Component.empty());
		team.setPlayerSuffix(Component.empty());
		team.setAllowFriendlyFire(true);
		team.setCanSeeFriendlyInvisibles(true);
		team.setNameTagVisibility(Team.Visibility.ALWAYS);
		syncPaleTeam(server);
	}

	/** {@code BloodMoonManager#d()} - pales in, everyone else out. */
	private void syncPaleTeam(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(PALE_TEAM);
		if (team == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			String name = player.getGameProfile().getName();
			boolean pale = FactionManager.isPale(player);
			boolean member = team.getPlayers().contains(name);
			if (pale && !member) {
				scoreboard.addPlayerToTeam(name, team);
			} else if (!pale && member) {
				scoreboard.removePlayerFromTeam(name, team);
			}
		}
	}

	/** {@code BloodMoonManager#e()}. */
	private void removePaleTeam(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(PALE_TEAM);
		if (team != null) {
			scoreboard.removePlayerTeam(team);
		}
	}

	/** Diagnostics for {@code /altarsmp status}. */
	public String describe() {
		WorldRecord.BloodMoon record = this.mod.store().world().bloodMoon();
		if (!this.active) {
			return "inactive (" + record.activations() + " activation(s) recorded)";
		}
		return "active since tick " + record.startedAt();
	}
}
