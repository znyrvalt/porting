package com.altarsmp.fabric.trial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.data.WorldRecord;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * Copper Leggings trial - the "Copper Core" hot-potato event, a port of
 * {@code com.altarsmp.events.HotPotatoEvent}.
 *
 * <p>Starting it gives a random online player a named {@code HEAVY_CORE} - the
 * Copper Infused Heavy Core, which cannot be dropped - and permanent glowing, then
 * runs for 45 minutes behind a red countdown boss bar and a sidebar objective
 * listing the ten longest hold times. Hitting the holder transfers the core
 * (banking the victim's hold time and starting the attacker's), and every 90
 * seconds the holder's coordinates are broadcast with a pling so the pack can find
 * them. Nobody may fly within 50 blocks of the holder: gliders are stopped and told
 * so. A holder who logs out passes the core to another random player, or drops it in
 * the world if nobody is left.
 *
 * <p>When the time runs out - or an admin stops it - the winner is announced with
 * their total hold time, the top ten are listed longest to shortest, the core and
 * the glowing are removed, and the boss bar and sidebar go away.
 *
 * <p>One port difference: Bukkit built a throw-away scoreboard per event and swapped
 * it onto each player, which vanilla cannot do. The port writes the same lines into
 * the server scoreboard under a dedicated {@code altarsmp_hotpotato} objective,
 * shown in the sidebar while the trial runs and removed when it ends.
 */
public final class HotPotatoEvent {

	/** The plugin's 45 minutes. */
	private static final long DURATION_MILLIS = 2700000L;
	/** How often the holder's coordinates are broadcast. */
	private static final long ANNOUNCE_INTERVAL_MILLIS = 90000L;
	/** Nobody may glide this close to the holder. */
	private static final double NO_FLY_RADIUS = 50.0D;
	private static final String OBJECTIVE_NAME = "altarsmp_hotpotato";
	private static final String POTATO_VALUE = "hot_potato";
	private static final int SIDEBAR_TOP_SCORE = 15;
	private static final int MAX_LISTED = 10;

	private final AltarSMPMod mod;
	private boolean running;
	private long endsAt;
	private long lastAnnounce;
	@Nullable
	private UUID holder;
	private final Map<UUID, Long> holdTimes = new HashMap<>();
	private final Map<UUID, Long> holdStarts = new HashMap<>();
	@Nullable
	private ServerBossEvent bar;
	@Nullable
	private Objective objective;

	public HotPotatoEvent(AltarSMPMod mod) {
		this.mod = mod;
	}

	/** {@code HotPotatoEvent#isRunning}. */
	public boolean isRunning() {
		return this.running;
	}

	/** {@code /coppertrial leggings status}. */
	public String status() {
		if (!this.running) {
			return "<gray>Copper Core Trial is not running.";
		}
		String line = "<yellow>Copper Core Trial is running! Time remaining: "
				+ formatTime(Math.max(0L, (this.endsAt - System.currentTimeMillis()) / 1000L));
		ServerPlayer current = holder();
		return current == null ? line : line + "\n<yellow>Current holder: " + current.getGameProfile().getName();
	}

	/** {@code HotPotatoEvent#start}. */
	public void start() {
		if (this.running) {
			return;
		}
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		this.running = true;
		this.endsAt = System.currentTimeMillis() + DURATION_MILLIS;
		this.holdTimes.clear();
		this.holdStarts.clear();
		this.holder = null;
		this.lastAnnounce = 0L;

		this.bar = Messaging.bossBar("<red><bold>COPPER CORE TRIAL</bold> <gray>- <yellow>45:00",
				BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
		this.bar.setProgress(1.0F);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.showBossBar(this.bar, player);
		}
		setupScoreboard(server);
		spawnHotPotato(server);

		Messaging.broadcast(server, "<red><bold>===============================");
		Messaging.broadcast(server, "<gold><bold>COPPER CORE TRIAL HAS STARTED!");
		Messaging.broadcast(server, "<gray>A Copper Infused Heavy Core has spawned!");
		Messaging.broadcast(server, "<gray>Hit the holder to transfer the Copper Core!");
		Messaging.broadcast(server, "<gray>Winner: Longest total hold time!");
		Messaging.broadcast(server, "<gray>Duration: 45 minutes");
		Messaging.broadcast(server, "<red><bold>===============================");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Fx.soundTo(player, "ENTITY_WITHER_SPAWN", 1.0F, 1.5F);
		}
	}

	/** {@code HotPotatoEvent#stop} - also the natural end when the timer runs out. */
	public void stop() {
		if (!this.running) {
			return;
		}
		this.running = false;
		MinecraftServer server = this.mod.server();
		bankHoldTime(this.holder);

		UUID winner = null;
		long best = 0L;
		for (Map.Entry<UUID, Long> entry : this.holdTimes.entrySet()) {
			if (entry.getValue() > best) {
				best = entry.getValue();
				winner = entry.getKey();
			}
		}
		if (server == null) {
			return;
		}
		Messaging.broadcast(server, "<red><bold>===============================");
		Messaging.broadcast(server, "<gold><bold>COPPER CORE TRIAL HAS ENDED!");
		if (winner != null) {
			Messaging.broadcast(server, "<green><bold>WINNER: " + nameOf(server, winner) + " (held for "
					+ formatTime(best / 1000L) + ")");
		}
		Messaging.broadcast(server, "");
		Messaging.broadcast(server, "<yellow>Hold Times (longest to shortest):");
		List<Map.Entry<UUID, Long>> ranked = new ArrayList<>(this.holdTimes.entrySet());
		ranked.sort(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()));
		int place = 1;
		for (Map.Entry<UUID, Long> entry : ranked) {
			Messaging.broadcast(server, "<gray>" + place + ". " + nameOf(server, entry.getKey()) + ": <white>"
					+ formatTime(entry.getValue() / 1000L));
			if (++place > MAX_LISTED) {
				break;
			}
		}
		Messaging.broadcast(server, "<red><bold>===============================");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Fx.soundTo(player, "UI_TOAST_CHALLENGE_COMPLETE", 1.0F, 0.8F);
		}

		ServerPlayer current = holder();
		if (current != null) {
			removePotatoFromPlayer(current);
		}
		if (this.bar != null) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Messaging.hideBossBar(this.bar, player);
			}
			this.bar = null;
		}
		teardownScoreboard(server);
		this.holder = null;
	}

	/** Shutdown: the trial ends properly rather than vanishing with the process. */
	public void stopForShutdown() {
		if (this.running) {
			stop();
		}
	}

	// ------------------------------------------------------------- persistence

	/**
	 * Writes the live trial into its world record. {@code CopperTrialService} calls
	 * this on every autosave and at shutdown, so a restart in the middle of the 45
	 * minutes picks the trial back up instead of silently dropping it - the Bukkit
	 * plugin kept this in memory only and lost an in-flight trial on every restart.
	 */
	public void writeTo(WorldRecord.TrialState state) {
		state.active(this.running);
		state.endsAt(this.endsAt);
		state.holder(this.holder == null ? "" : this.holder.toString());
		state.data().put("lastAnnounce", Long.toString(this.lastAnnounce));
		state.numbers().clear();
		for (Map.Entry<UUID, Long> entry : this.holdTimes.entrySet()) {
			state.numbers().put(entry.getKey().toString(), entry.getValue());
		}
	}

	/** {@code CopperTrialService#resumeActiveTrials} - rebuilds an interrupted trial. */
	public boolean restoreFrom(WorldRecord.TrialState state) {
		if (this.running || !state.active()) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (state.endsAt() <= now) {
			return false;
		}
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return false;
		}
		this.running = true;
		this.endsAt = state.endsAt();
		this.lastAnnounce = now;
		this.holdTimes.clear();
		for (Map.Entry<String, Long> entry : state.numbers().entrySet()) {
			UUID uuid = parseUuid(entry.getKey());
			if (uuid != null) {
				this.holdTimes.put(uuid, entry.getValue());
			}
		}
		this.holder = parseUuid(state.holder());

		this.bar = Messaging.bossBar("<red><bold>COPPER CORE TRIAL</bold> <gray>- <yellow>"
						+ formatTime(Math.max(0L, (this.endsAt - now) / 1000L)),
				BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
		this.bar.setProgress((float) (this.endsAt - now) / (float) DURATION_MILLIS);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.showBossBar(this.bar, player);
		}
		setupScoreboard(server);

		ServerPlayer current = holder();
		if (current == null) {
			spawnHotPotato(server);
		} else {
			// The core survives the restart inside the holder's own inventory, so only
			// hand out a fresh one if it is genuinely gone.
			this.holdStarts.put(current.getUUID(), now);
			if (!carriesPotato(current)) {
				ItemStack potato = createHotPotato();
				if (!current.getInventory().add(potato)) {
					current.drop(potato, false);
				}
			}
			Effects.apply(current, "GLOWING", Integer.MAX_VALUE, 0, false, false, false);
		}
		Messaging.broadcast(server, "<gray>The Copper Core Trial resumed after a server restart.");
		return true;
	}

	private static boolean carriesPotato(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (isHotPotato(player.getInventory().getItem(slot))) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	private static UUID parseUuid(String raw) {
		if (raw == null || raw.isEmpty()) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException ex) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] Copper Core Trial record holds an unreadable player id '{}'", raw);
			return null;
		}
	}

	/**
	 * The plugin's repeating task, called from {@code CopperTrialService#tick}:
	 * countdown, sidebar refresh, the 90-second location broadcast and the no-fly
	 * rule around the holder.
	 */
	public void tick(MinecraftServer server) {
		if (!this.running) {
			return;
		}
		long now = System.currentTimeMillis();
		long remaining = this.endsAt - now;
		if (remaining <= 0L) {
			stop();
			return;
		}
		if (this.bar != null) {
			float progress = (float) remaining / (float) DURATION_MILLIS;
			this.bar.setProgress(Math.max(0.0F, Math.min(1.0F, progress)));
			this.bar.setName(Messaging.msg("<red><bold>COPPER CORE TRIAL</bold> <gray>- <yellow>"
					+ formatTime(remaining / 1000L)));
		}
		updateScoreboard(server);

		ServerPlayer current = holder();
		if (current == null) {
			return;
		}
		if (now - this.lastAnnounce >= ANNOUNCE_INTERVAL_MILLIS) {
			this.lastAnnounce = now;
			net.minecraft.core.BlockPos at = current.blockPosition();
			Messaging.broadcast(server, "<red><bold>COPPER CORE LOCATION: <yellow>"
					+ current.getGameProfile().getName() + " <gray>at <white>" + at.getX() + ", " + at.getY() + ", "
					+ at.getZ());
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Fx.soundTo(player, "BLOCK_NOTE_BLOCK_PLING", 1.0F, 1.0F);
			}
		}
		ServerLevel level = current.serverLevel();
		for (ServerPlayer player : level.players()) {
			if (player.position().distanceTo(current.position()) <= NO_FLY_RADIUS && player.isFallFlying()) {
				player.stopFallFlying();
				Messaging.send(player, "<red>No flying near the Copper Core!");
			}
		}
	}

	/** {@code HotPotatoEvent#onPlayerHit} - hitting the holder takes the core. */
	public void onPlayerHit(ServerPlayer attacker, LivingEntity victim) {
		if (!this.running || !(victim instanceof ServerPlayer target) || !target.getUUID().equals(this.holder)) {
			return;
		}
		removePotatoFromPlayer(target);
		givePotatoToPlayer(attacker);
		Messaging.send(attacker, "<red>You took the Copper Core from " + target.getGameProfile().getName() + "!");
		Messaging.send(target, "<green>You passed the Copper Core to " + attacker.getGameProfile().getName() + "!");
	}

	/** {@code HotPotatoEvent#onPlayerDrop} - the core cannot be thrown away. */
	public boolean blockDrop(ServerPlayer player, ItemStack dropped) {
		if (!this.running || !isHotPotato(dropped)) {
			return false;
		}
		Messaging.send(player, "<red>You cannot drop the Copper Core!");
		return true;
	}

	/** {@code HotPotatoEvent#onPlayerQuit}. */
	public void onPlayerQuit(ServerPlayer leaving) {
		if (!this.running || !leaving.getUUID().equals(this.holder)) {
			return;
		}
		MinecraftServer server = this.mod.server();
		removePotatoFromPlayer(leaving);
		if (server == null) {
			return;
		}
		Messaging.broadcast(server, "<red>" + leaving.getGameProfile().getName()
				+ " left while holding the Copper Core!");
		List<ServerPlayer> others = new ArrayList<>(server.getPlayerList().getPlayers());
		others.removeIf(player -> player.getUUID().equals(leaving.getUUID()));
		if (!others.isEmpty()) {
			givePotatoToPlayer(others.get(server.getRandom().nextInt(others.size())));
		} else {
			this.holder = null;
			ServerLevel level = leaving.serverLevel();
			level.spawnAtLocation(leaving.blockPosition(), createHotPotato());
		}
	}

	/** Whether this stack is the trial's core - used by the weapon-protection layer. */
	public static boolean isHotPotato(@Nullable ItemStack stack) {
		return stack != null && POTATO_VALUE.equals(Identity.legacyString(stack, Identity.KEY_HOT_POTATO, ""));
	}

	// ------------------------------------------------------------------ potato

	private void spawnHotPotato(MinecraftServer server) {
		List<ServerPlayer> online = server.getPlayerList().getPlayers();
		if (online.isEmpty()) {
			return;
		}
		givePotatoToPlayer(online.get(server.getRandom().nextInt(online.size())));
	}

	/** {@code HotPotatoEvent#createHotPotato} - the named heavy core with its lore. */
	private ItemStack createHotPotato() {
		ItemStack stack = new ItemStack(Items.HEAVY_CORE);
		stack.set(DataComponents.CUSTOM_NAME,
				Messaging.msg("<red><bold>Copper Infused Heavy Core").copy()
						.withStyle(style -> style.withItalic(false)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Messaging.msg("<gray>The Copper Core!"),
				Messaging.msg(""),
				Messaging.msg("<yellow>Hit another player to transfer!"),
				Messaging.msg("<red>Cannot be dropped!"),
				Messaging.msg(""),
				Messaging.msg("<gold>Holder has permanent glowing"))));
		// Bukkit stored this in the item's PersistentDataContainer under
		// NamespacedKey(plugin, "hot_potato"); Identity keeps the same marker.
		stack = stack.copy();
		Identity.putLegacy(stack, Identity.KEY_HOT_POTATO, POTATO_VALUE);
		return stack;
	}

	/** {@code HotPotatoEvent#givePotatoToPlayer}. */
	private void givePotatoToPlayer(ServerPlayer player) {
		this.holdStarts.put(player.getUUID(), System.currentTimeMillis());
		this.holder = player.getUUID();
		ItemStack potato = createHotPotato();
		if (!player.getInventory().add(potato)) {
			player.drop(potato, false);
		}
		// Bukkit: PotionEffect(GLOWING, Integer.MAX_VALUE, 0, false, false).
		Effects.apply(player, "GLOWING", Integer.MAX_VALUE, 0, false, false, false);
		Messaging.send(player, "<red><bold>You now hold the COPPER CORE!");
		Fx.soundTo(player, "ENTITY_BLAZE_HURT", 1.0F, 0.5F);
		MinecraftServer server = this.mod.server();
		if (server != null) {
			Messaging.broadcast(server,
					"<red>" + player.getGameProfile().getName() + " <gold>now holds the Copper Core!");
		}
	}

	/** {@code HotPotatoEvent#removePotatoFromPlayer}. */
	private void removePotatoFromPlayer(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (isHotPotato(stack)) {
				stack.setCount(0);
				player.getInventory().setItem(slot, ItemStack.EMPTY);
			}
		}
		Effects.remove(player, "GLOWING");
		bankHoldTime(player.getUUID());
	}

	private void bankHoldTime(@Nullable UUID uuid) {
		if (uuid == null) {
			return;
		}
		Long started = this.holdStarts.remove(uuid);
		if (started != null) {
			this.holdTimes.merge(uuid, System.currentTimeMillis() - started, Long::sum);
		}
	}

	@Nullable
	private ServerPlayer holder() {
		MinecraftServer server = this.mod.server();
		return this.holder == null || server == null ? null : server.getPlayerList().getPlayer(this.holder);
	}

	private static String nameOf(MinecraftServer server, UUID uuid) {
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		return player == null ? "Unknown" : player.getGameProfile().getName();
	}

	// --------------------------------------------------------------- scoreboard

	private void setupScoreboard(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();
		this.objective = scoreboard.getObjective(OBJECTIVE_NAME);
		if (this.objective == null) {
			this.objective = scoreboard.addObjective(OBJECTIVE_NAME, ObjectiveCriteria.DUMMY,
					Messaging.msg("<gold><bold>COPPER CORE TRIAL"), ObjectiveCriteria.RenderType.INTEGER, false, null);
		}
		scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, this.objective);
		updateScoreboard(server);
	}

	private void teardownScoreboard(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();
		if (this.objective != null) {
			scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, null);
			scoreboard.removeObjective(this.objective);
			this.objective = null;
		}
	}

	/** {@code HotPotatoEvent#updateScoreboard} - holder line plus the top ten. */
	private void updateScoreboard(MinecraftServer server) {
		if (this.objective == null) {
			return;
		}
		Scoreboard scoreboard = server.getScoreboard();
		Map<UUID, Long> totals = new HashMap<>(this.holdTimes);
		Long started = this.holder == null ? null : this.holdStarts.get(this.holder);
		if (this.holder != null && started != null) {
			totals.merge(this.holder, System.currentTimeMillis() - started, Long::sum);
		}
		List<Map.Entry<UUID, Long>> ranked = new ArrayList<>(totals.entrySet());
		ranked.sort(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()));

		int score = SIDEBAR_TOP_SCORE;
		setLine(scoreboard, "----------------", score--);
		ServerPlayer current = holder();
		if (current != null) {
			setLine(scoreboard, "<gold>Holder: <red>" + current.getGameProfile().getName(), score--);
			setLine(scoreboard, "---------------", score--);
		}
		int place = 1;
		for (Map.Entry<UUID, Long> entry : ranked) {
			if (place > MAX_LISTED) {
				break;
			}
			String name = nameOf(server, entry.getKey());
			if (name.length() > 10) {
				name = name.substring(0, 10);
			}
			String colour = place == 1 ? "<gold>" : place == 2 ? "<gray>" : place == 3 ? "<red>" : "<white>";
			setLine(scoreboard, colour + place + ". <yellow>" + name + " <white>"
					+ formatTime(entry.getValue() / 1000L), score--);
			place++;
		}
		setLine(scoreboard, "-----------------", score);
	}

	/**
	 * Sidebar lines are fake entries: the plugin used {@code ChatColor} strings as
	 * score names, which is the same trick through {@code ScoreHolder}.
	 */
	private void setLine(Scoreboard scoreboard, String markup, int score) {
		if (this.objective == null) {
			return;
		}
		String text = com.altarsmp.fabric.util.TextFx.strip(markup);
		ScoreHolder holder = ScoreHolder.fromStringOnlyForParsing(text);
		ScoreAccess access = scoreboard.getOrCreatePlayerScore(holder, this.objective);
		access.set(score);
	}

	/** {@code HotPotatoEvent#formatTime}. */
	static String formatTime(long seconds) {
		return String.format("%d:%02d", seconds / 60L, seconds % 60L);
	}
}
