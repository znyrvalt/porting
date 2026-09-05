package com.altarsmp.fabric.trial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.Item;
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
import com.altarsmp.fabric.data.WorldRecord;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * Copper Boots trial - the Bingo event, a port of
 * {@code com.altarsmp.events.BingoEvent}.
 *
 * <p>Starting it hands every online player a glowing <b>Bingo Book</b> (which
 * cannot be dropped, and which evicts the least valuable inventory item if there is
 * no room, telling the player what was dropped), puts up a yellow one-hour
 * countdown boss bar and a sidebar of the ten highest task counts, and opens a
 * 54-slot task board where each of the 40 tasks is a concrete block showing what to
 * collect. Clicking a task consumes the items from the player's inventory - only
 * after the full amount has been counted, so a failed attempt costs nothing - marks
 * it green, plays a level-up, broadcasts the completion and reopens the board.
 *
 * <p>Right-clicking the book reopens the board. At 45 minutes the sidebar is
 * withdrawn with the plugin's "GO BLIND!" warning, and when the hour ends the
 * winner is announced with their task count, the top ten are listed, and the books
 * are removed from every inventory.
 *
 * <p>The task list is the plugin's own 40 entries, verbatim, including the amounts.
 */
public final class BingoEvent {

	/** The plugin's hour. */
	private static final long DURATION_MILLIS = 3600000L;
	/** After this long the sidebar is hidden ("GO BLIND!"). */
	private static final long BLIND_AFTER_MILLIS = 2700000L;
	private static final String OBJECTIVE_NAME = "altarsmp_bingo";
	private static final String BOOK_VALUE = "1";
	/** World-record key prefix for a player's completed task list. */
	private static final String TASK_PREFIX = "task:";
	private static final int SIDEBAR_TOP_SCORE = 15;
	private static final int MAX_LISTED = 10;

	/** {@code BingoEvent#l} - the 40 tasks, ids and amounts included. */
	static final Task[] TASKS = {
			new Task(1, "8 Diamonds", Items.DIAMOND, 8),
			new Task(2, "16 Gold Ingots", Items.GOLD_INGOT, 16),
			new Task(3, "32 Iron Ingots", Items.IRON_INGOT, 32),
			new Task(4, "64 Coal", Items.COAL, 64),
			new Task(5, "16 Lapis Lazuli", Items.LAPIS_LAZULI, 16),
			new Task(6, "8 Emeralds", Items.EMERALD, 8),
			new Task(7, "16 Redstone", Items.REDSTONE, 16),
			new Task(8, "4 Ancient Debris", Items.ANCIENT_DEBRIS, 4),
			new Task(9, "1 Netherite Ingot", Items.NETHERITE_INGOT, 1),
			new Task(10, "8 Blaze Rods", Items.BLAZE_ROD, 8),
			new Task(11, "16 Ender Pearls", Items.ENDER_PEARL, 16),
			new Task(12, "4 Ghast Tears", Items.GHAST_TEAR, 4),
			new Task(13, "8 Magma Cream", Items.MAGMA_CREAM, 8),
			new Task(14, "16 Slime Balls", Items.SLIME_BALL, 16),
			new Task(15, "4 Wither Skeleton Skulls", Items.WITHER_SKELETON_SKULL, 4),
			new Task(16, "1 Beacon", Items.BEACON, 1),
			new Task(17, "16 Quartz Blocks", Items.QUARTZ_BLOCK, 16),
			new Task(18, "8 End Crystals", Items.END_CRYSTAL, 8),
			new Task(19, "16 Phantom Membrane", Items.PHANTOM_MEMBRANE, 16),
			new Task(20, "8 Nautilus Shells", Items.NAUTILUS_SHELL, 8),
			new Task(21, "4 Heart of the Sea", Items.HEART_OF_THE_SEA, 4),
			new Task(22, "32 Amethyst Shards", Items.AMETHYST_SHARD, 32),
			new Task(23, "16 Copper Ingots", Items.COPPER_INGOT, 16),
			new Task(24, "8 Echo Shards", Items.ECHO_SHARD, 8),
			new Task(25, "4 Disc Fragments", Items.DISC_FRAGMENT_5, 4),
			new Task(26, "16 Glow Ink Sacs", Items.GLOW_INK_SAC, 16),
			new Task(27, "8 Honey Bottles", Items.HONEY_BOTTLE, 8),
			new Task(28, "16 Honeycomb", Items.HONEYCOMB, 16),
			new Task(29, "4 Totem of Undying", Items.TOTEM_OF_UNDYING, 4),
			new Task(30, "8 Golden Apples", Items.GOLDEN_APPLE, 8),
			new Task(31, "1 Enchanted Golden Apple", Items.ENCHANTED_GOLDEN_APPLE, 1),
			new Task(32, "4 Nether Stars", Items.NETHER_STAR, 4),
			new Task(33, "16 Prismarine Shards", Items.PRISMARINE_SHARD, 16),
			new Task(34, "8 Prismarine Crystals", Items.PRISMARINE_CRYSTALS, 8),
			new Task(35, "4 Tridents", Items.TRIDENT, 4),
			new Task(36, "8 Saddles", Items.SADDLE, 8),
			new Task(37, "4 Name Tags", Items.NAME_TAG, 4),
			new Task(38, "8 Brewing Stands", Items.BREWING_STAND, 8),
			new Task(39, "32 Glowstone", Items.GLOWSTONE, 32),
			new Task(40, "16 Sea Lanterns", Items.SEA_LANTERN, 16),
	};

	/** {@code BingoEvent.a}. */
	record Task(int id, String label, Item item, int amount) {
	}

	private final AltarSMPMod mod;
	private boolean running;
	private long startedAt;
	private long endsAt;
	@Nullable
	private ServerBossEvent bar;
	@Nullable
	private Objective objective;
	private boolean blinded;
	/** {@code BingoEvent#k} - completed task indices per player. */
	private final Map<UUID, Set<Integer>> completed = new HashMap<>();

	public BingoEvent(AltarSMPMod mod) {
		this.mod = mod;
	}

	public boolean isRunning() {
		return this.running;
	}

	/** {@code /bingo status} and {@code /coppertrial boots status}. */
	public String status() {
		if (!this.running) {
			return "<gray>Bingo is not running.";
		}
		return "<yellow>Bingo is running! Time remaining: "
				+ formatTime(Math.max(0L, (this.endsAt - System.currentTimeMillis()) / 1000L));
	}

	/** {@code BingoEvent#start}. */
	public void start() {
		if (this.running) {
			return;
		}
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		this.running = true;
		this.startedAt = System.currentTimeMillis();
		this.endsAt = this.startedAt + DURATION_MILLIS;
		this.blinded = false;
		this.completed.clear();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			this.completed.put(player.getUUID(), new HashSet<>());
		}

		this.bar = Messaging.bossBar("<gold><bold>BINGO EVENT</bold> <gray>- <yellow>1:00:00",
				BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
		this.bar.setProgress(1.0F);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.showBossBar(this.bar, player);
		}
		setupScoreboard(server);

		Messaging.broadcast(server, "<gold><bold>===============================");
		Messaging.broadcast(server, "<yellow><bold>BINGO EVENT HAS STARTED!");
		Messaging.broadcast(server, "<gray>Complete tasks to earn points!");
		Messaging.broadcast(server, "<gray>Right-click the <gold>Bingo Book<gray> to view tasks!");
		Messaging.broadcast(server, "<gray>Duration: 1 hour");
		Messaging.broadcast(server, "<gold><bold>===============================");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Fx.soundTo(player, "UI_TOAST_CHALLENGE_COMPLETE", 1.0F, 1.0F);
		}
		giveBingoBooks(server);
	}

	/** {@code BingoEvent#stop} - also the natural end of the hour. */
	public void stop() {
		if (!this.running) {
			return;
		}
		this.running = false;
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		UUID winner = null;
		int best = 0;
		for (Map.Entry<UUID, Set<Integer>> entry : this.completed.entrySet()) {
			if (entry.getValue().size() > best) {
				best = entry.getValue().size();
				winner = entry.getKey();
			}
		}
		Messaging.broadcast(server, "<gold><bold>===============================");
		Messaging.broadcast(server, "<yellow><bold>BINGO EVENT HAS ENDED!");
		if (winner != null) {
			Messaging.broadcast(server,
					"<green><bold>WINNER: " + nameOf(server, winner) + " with " + best + " tasks!");
		}
		Messaging.broadcast(server, "");
		Messaging.broadcast(server, "<yellow>Final Results:");
		List<Map.Entry<UUID, Set<Integer>>> ranked = new ArrayList<>(this.completed.entrySet());
		ranked.sort(Comparator.comparingInt((Map.Entry<UUID, Set<Integer>> entry) -> entry.getValue().size())
				.reversed());
		int place = 1;
		for (Map.Entry<UUID, Set<Integer>> entry : ranked) {
			Messaging.broadcast(server, "<gray>" + place + ". " + nameOf(server, entry.getKey()) + ": <white>"
					+ entry.getValue().size() + " tasks");
			if (++place > MAX_LISTED) {
				break;
			}
		}
		Messaging.broadcast(server, "<gold><bold>===============================");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Fx.soundTo(player, "UI_TOAST_CHALLENGE_COMPLETE", 1.0F, 0.8F);
		}
		if (this.bar != null) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Messaging.hideBossBar(this.bar, player);
			}
			this.bar = null;
		}
		teardownScoreboard(server);
		removeBingoBooks(server);
	}

	/** Shutdown: end the trial properly rather than losing it with the process. */
	public void stopForShutdown() {
		if (this.running) {
			stop();
		}
	}

	// ------------------------------------------------------------- persistence

	/**
	 * Writes the live trial - including every player's completed task list - into its
	 * world record. The Bukkit plugin held all of this in a {@code HashMap} and lost an
	 * hour-long bingo on every restart; the port resumes it instead.
	 */
	public void writeTo(WorldRecord.TrialState state) {
		state.active(this.running);
		state.startedAt(this.startedAt);
		state.endsAt(this.endsAt);
		state.data().put("blinded", this.blinded ? "1" : "0");
		state.data().keySet().removeIf(key -> key.startsWith(TASK_PREFIX));
		state.playerScores().clear();
		for (Map.Entry<UUID, Set<Integer>> entry : this.completed.entrySet()) {
			StringBuilder joined = new StringBuilder();
			for (int index : new TreeSet<>(entry.getValue())) {
				if (joined.length() > 0) {
					joined.append(',');
				}
				joined.append(index);
			}
			state.data().put(TASK_PREFIX + entry.getKey(), joined.toString());
			state.playerScores().put(entry.getKey().toString(), (long) entry.getValue().size());
		}
	}

	/** {@code CopperTrialService#resumeActiveTrials} - rebuilds an interrupted bingo. */
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
		this.startedAt = state.startedAt();
		this.endsAt = state.endsAt();
		this.blinded = "1".equals(state.data().getOrDefault("blinded", "0"));
		this.completed.clear();
		for (Map.Entry<String, String> entry : state.data().entrySet()) {
			if (!entry.getKey().startsWith(TASK_PREFIX)) {
				continue;
			}
			UUID uuid;
			try {
				uuid = UUID.fromString(entry.getKey().substring(TASK_PREFIX.length()));
			} catch (IllegalArgumentException ex) {
				AltarSMPMod.LOGGER.warn("[AltarSMP] Bingo record holds an unreadable player id '{}'", entry.getKey());
				continue;
			}
			Set<Integer> done = new HashSet<>();
			for (String part : entry.getValue().split(",")) {
				if (part.isEmpty()) {
					continue;
				}
				try {
					int index = Integer.parseInt(part);
					if (index >= 0 && index < TASKS.length) {
						done.add(index);
					}
				} catch (NumberFormatException ex) {
					AltarSMPMod.LOGGER.warn("[AltarSMP] Bingo record holds an unreadable task index '{}'", part);
				}
			}
			this.completed.put(uuid, done);
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			this.completed.computeIfAbsent(player.getUUID(), key -> new HashSet<>());
		}

		this.bar = Messaging.bossBar("<gold><bold>BINGO EVENT</bold> <gray>- <yellow>"
						+ formatTime(Math.max(0L, (this.endsAt - now) / 1000L)),
				BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
		this.bar.setProgress((float) (this.endsAt - now) / (float) DURATION_MILLIS);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.showBossBar(this.bar, player);
		}
		if (!this.blinded) {
			setupScoreboard(server);
		}
		giveBingoBooks(server);
		Messaging.broadcast(server, "<gray>The Bingo Event resumed after a server restart.");
		return true;
	}

	/** The plugin's repeating task: countdown, sidebar, and the 45-minute blinding. */
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
			this.bar.setName(Messaging.msg("<gold><bold>BINGO EVENT</bold> <gray>- <yellow>"
					+ formatTime(remaining / 1000L)));
		}
		if (now - this.startedAt >= BLIND_AFTER_MILLIS) {
			if (!this.blinded) {
				this.blinded = true;
				teardownScoreboard(server);
				Messaging.broadcast(server, "<red><bold>Scoreboard hidden! 15 minutes remaining - GO BLIND!");
			}
			return;
		}
		updateScoreboard(server);
	}

	// ------------------------------------------------------------------- book

	/** {@code BingoEvent#createBingoBook}. */
	static ItemStack createBingoBook() {
		ItemStack book = new ItemStack(Items.BOOK);
		book.set(DataComponents.CUSTOM_NAME, Messaging.msg("<gold><bold>Bingo Tasks").copy()
				.withStyle(style -> style.withItalic(false)));
		book.set(DataComponents.LORE, new ItemLore(List.of(
				Messaging.msg("<gray>Right-click to view tasks"),
				Messaging.msg("<yellow>Complete tasks to earn points!"))));
		// Bukkit added Unbreaking I with HIDE_ENCHANTS purely for the glint; 26.x has
		// a component that produces exactly that glint without a fake enchantment.
		book.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
		Identity.putLegacy(book, Identity.KEY_BINGO_BOOK, BOOK_VALUE);
		return book;
	}

	/** {@code BingoEvent#isBingoBook}. */
	public static boolean isBingoBook(@Nullable ItemStack stack) {
		return stack != null && stack.is(Items.BOOK)
				&& BOOK_VALUE.equals(Identity.legacyString(stack, Identity.KEY_BINGO_BOOK, ""));
	}

	/**
	 * {@code BingoEvent#onBookRightClick}. Registered on {@code UseItemCallback} by
	 * {@link CopperTrialService}; returning {@code true} makes the callback answer
	 * {@code SUCCESS}, which is the vanilla equivalent of Bukkit's
	 * {@code setCancelled(true)} plus opening the GUI.
	 */
	public boolean onBookUse(ServerPlayer player, @Nullable ItemStack used) {
		if (!this.running || !isBingoBook(used)) {
			return false;
		}
		openTasksGui(player);
		return true;
	}

	/** {@code BingoEvent#onBookDrop} - the book cannot be thrown away mid-event. */
	public boolean blockDrop(ServerPlayer player, @Nullable ItemStack dropped) {
		if (!this.running || !isBingoBook(dropped)) {
			return false;
		}
		Messaging.send(player, "<red>You cannot drop the Bingo Book!");
		return true;
	}

	/**
	 * {@code BingoEvent#onCommand} - the standalone {@code /bingo} command, whose
	 * messages differ slightly from {@code /coppertrial boots}.
	 *
	 * @return Brigadier's success count
	 */
	public int onCommand(CommandSourceStack source, @Nullable String subcommand) {
		if (subcommand == null) {
			tell(source, "<gold>Usage: /bingo <start|stop|status|tasks>");
			return 1;
		}
		switch (subcommand) {
			case "start":
				start();
				tell(source, "<green>Bingo event started!");
				return 1;
			case "stop":
				stop();
				tell(source, "<red>Bingo event stopped!");
				return 1;
			case "status":
				if (this.running) {
					tell(source, "<yellow>Bingo is running! Time remaining: "
							+ formatTime((this.endsAt - System.currentTimeMillis()) / 1000L));
				} else {
					tell(source, "<gray>Bingo is not running.");
				}
				return 1;
			case "tasks":
				ServerPlayer player = source.getPlayer();
				if (player != null) {
					openTasksGui(player);
					return 1;
				}
				return 0;
			default:
				tell(source, "<red>Unknown subcommand.");
				return 0;
		}
	}

	private static void tell(CommandSourceStack source, String markup) {
		source.sendSuccess(() -> Messaging.msg(markup), false);
	}

	/** {@code BingoEvent#giveBingoBooks}. */
	private void giveBingoBooks(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (hasBingoBook(player)) {
				continue;
			}
			ItemStack book = createBingoBook();
			if (player.getInventory().getFreeSlot() != -1) {
				player.getInventory().add(book);
				continue;
			}
			int worst = findWorstItemSlot(player);
			ItemStack evicted = player.getInventory().getItem(worst);
			if (!evicted.isEmpty()) {
				player.serverLevel().spawnAtLocation(player.blockPosition(), evicted.copy());
				player.getInventory().setItem(worst, book);
				Messaging.send(player, "<yellow>Your inventory was full! Dropped <gray>"
						+ formatItemName(evicted.getItem()) + " <yellow>to make room for the <gold>Bingo Book<yellow>.");
			}
		}
	}

	private static boolean hasBingoBook(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (isBingoBook(player.getInventory().getItem(slot))) {
				return true;
			}
		}
		return false;
	}

	private void removeBingoBooks(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
				if (isBingoBook(player.getInventory().getItem(slot))) {
					player.getInventory().setItem(slot, ItemStack.EMPTY);
				}
			}
		}
	}

	/** {@code BingoEvent#findWorstItemSlot}. */
	private int findWorstItemSlot(ServerPlayer player) {
		int worst = 0;
		int lowest = Integer.MAX_VALUE;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			int value = itemValue(stack);
			if (value < lowest) {
				lowest = value;
				worst = slot;
			}
		}
		return worst;
	}

	/** {@code BingoEvent#getItemValue} - lower means more disposable. */
	private static int itemValue(ItemStack stack) {
		Item item = stack.getItem();
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		if (stack.has(DataComponents.CUSTOM_NAME)) {
			return Integer.MAX_VALUE;
		}
		if (path.contains("diamond_") || path.contains("netherite_")) {
			return Integer.MAX_VALUE;
		}
		if (item == Items.ELYTRA || item == Items.TRIDENT || item == Items.MACE
				|| item == Items.TOTEM_OF_UNDYING) {
			return Integer.MAX_VALUE;
		}
		if (path.contains("wooden_")) {
			return 1;
		}
		if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) {
			return 2;
		}
		if (item == Items.STICK || item == Items.BOWL) {
			return 3;
		}
		if (item == Items.ROTTEN_FLESH || item == Items.SPIDER_EYE || item == Items.POISONOUS_POTATO) {
			return 4;
		}
		if (item == Items.DIRT || item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE
				|| item == Items.NETHERRACK || item == Items.ANDESITE || item == Items.DIORITE
				|| item == Items.GRANITE || item == Items.GRAVEL || item == Items.SAND
				|| item == Items.TUFF || item == Items.STONE) {
			return 5;
		}
		if (path.contains("stone_")) {
			return 6;
		}
		if (item instanceof net.minecraft.world.item.BlockItem && !path.contains("ore")) {
			return 10;
		}
		if (path.startsWith("raw_")) {
			return 15;
		}
		if (stack.has(DataComponents.FOOD)) {
			return 20;
		}
		return path.contains("iron_") ? 50 : 40;
	}

	/** {@code BingoEvent#formatMaterialName}. */
	static String formatItemName(Item item) {
		String path = BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
		StringBuilder builder = new StringBuilder();
		for (String word : path.split(" ")) {
			if (!word.isEmpty()) {
				builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
			}
		}
		return builder.toString().trim();
	}

	// --------------------------------------------------------------- task GUI

	/** {@code BingoEvent#openTasksGUI}. */
	public void openTasksGui(ServerPlayer player) {
		BingoTasksMenu.open(player, this);
	}

	/**
	 * {@code BingoEvent#getTaskIndexFromSlot} - the board's slot layout: five rows of
	 * seven tasks plus the five tasks tucked into the border columns.
	 */
	static int taskIndexFromSlot(int slot) {
		if (slot >= 10 && slot <= 16) {
			return slot - 10;
		}
		if (slot >= 19 && slot <= 25) {
			return slot - 19 + 7;
		}
		if (slot >= 28 && slot <= 34) {
			return slot - 28 + 14;
		}
		if (slot >= 37 && slot <= 43) {
			return slot - 37 + 21;
		}
		if (slot >= 46 && slot <= 52) {
			return slot - 46 + 28;
		}
		return switch (slot) {
			case 45 -> 35;
			case 53 -> 36;
			case 9 -> 37;
			case 18 -> 38;
			case 27 -> 39;
			default -> -1;
		};
	}

	/** The 28 grid slots, then the seven bottom-row slots - {@code openTasksGUI}'s layout. */
	static final int[] GRID_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
	static final int[] BOTTOM_SLOTS = {46, 47, 48, 49, 50, 51, 52};
	static final int[] CORNER_SLOTS = {45, 53, 9, 18, 27};
	static final int[] CORNER_TASKS = {35, 36, 37, 38, 39};

	Set<Integer> completedBy(UUID uuid) {
		return this.completed.getOrDefault(uuid, Set.of());
	}

	/** {@code BingoEvent#createTaskItem}. */
	static ItemStack createTaskItem(Task task, boolean done, int number) {
		ItemStack stack = new ItemStack(done ? Items.LIME_CONCRETE : task.item());
		if (done) {
			stack.set(DataComponents.CUSTOM_NAME, Messaging.msg("<green><bold>Task #" + number + " - COMPLETED")
					.copy().withStyle(style -> style.withItalic(false)));
			stack.set(DataComponents.LORE, new ItemLore(List.of(
					Messaging.msg(""),
					Messaging.msg("<strikethrough><gray>" + task.label()),
					Messaging.msg(""),
					Messaging.msg("<green><bold>COMPLETED"))));
		} else {
			stack.set(DataComponents.CUSTOM_NAME, Messaging.msg("<yellow><bold>Task #" + number).copy()
					.withStyle(style -> style.withItalic(false)));
			stack.set(DataComponents.LORE, new ItemLore(List.of(
					Messaging.msg(""),
					Messaging.msg("<white>Collect: <gold>" + task.label()),
					Messaging.msg("<gray>Item: <white>" + formatItemName(task.item())),
					Messaging.msg("<gray>Amount: <white>" + task.amount()),
					Messaging.msg(""),
					Messaging.msg("<yellow>Click to complete!"),
					Messaging.msg("<red>Items will be consumed!"))));
		}
		return stack;
	}

	/**
	 * {@code BingoEvent#attemptCompleteTask}. The count happens before anything is
	 * removed, so a player who is short keeps every item they have.
	 */
	public void attemptCompleteTask(ServerPlayer player, int index) {
		if (!this.running || index < 0 || index >= TASKS.length) {
			return;
		}
		Set<Integer> done = this.completed.computeIfAbsent(player.getUUID(), uuid -> new HashSet<>());
		if (done.contains(index)) {
			Messaging.send(player, "<red>You already completed this task!");
			return;
		}
		Task task = TASKS[index];
		int have = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(task.item())) {
				have += stack.getCount();
			}
		}
		if (have < task.amount()) {
			Messaging.send(player, "<red>You need " + task.amount() + " " + formatItemName(task.item())
					+ "! (You have " + have + ")");
			return;
		}
		int toRemove = task.amount();
		for (int slot = 0; slot < player.getInventory().getContainerSize() && toRemove > 0; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.is(task.item())) {
				continue;
			}
			if (stack.getCount() <= toRemove) {
				toRemove -= stack.getCount();
				player.getInventory().setItem(slot, ItemStack.EMPTY);
			} else {
				stack.shrink(toRemove);
				toRemove = 0;
			}
		}
		done.add(index);
		Messaging.send(player, "<green>Task completed: " + task.label() + "!");
		Fx.soundTo(player, "ENTITY_PLAYER_LEVELUP", 1.0F, 1.5F);
		MinecraftServer server = this.mod.server();
		if (server != null) {
			Messaging.broadcast(server, "<yellow>" + player.getGameProfile().getName() + " <gray>completed: <white>"
					+ task.label());
		}
		openTasksGui(player);
	}

	// --------------------------------------------------------------- scoreboard

	private void setupScoreboard(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();
		this.objective = scoreboard.getObjective(OBJECTIVE_NAME);
		if (this.objective == null) {
			this.objective = scoreboard.addObjective(OBJECTIVE_NAME, ObjectiveCriteria.DUMMY,
					Messaging.msg("<gold><bold>BINGO"), ObjectiveCriteria.RenderType.INTEGER, false, null);
		}
		scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, this.objective);
		updateScoreboard(server);
	}

	private void teardownScoreboard(MinecraftServer server) {
		if (this.objective == null) {
			return;
		}
		Scoreboard scoreboard = server.getScoreboard();
		scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, null);
		scoreboard.removeObjective(this.objective);
		this.objective = null;
	}

	/** {@code BingoEvent#updateScoreboard}. */
	private void updateScoreboard(MinecraftServer server) {
		if (this.objective == null) {
			return;
		}
		List<Map.Entry<UUID, Set<Integer>>> ranked = new ArrayList<>(this.completed.entrySet());
		ranked.sort(Comparator.comparingInt((Map.Entry<UUID, Set<Integer>> entry) -> entry.getValue().size())
				.reversed());
		int score = SIDEBAR_TOP_SCORE;
		setLine("----------------", score--);
		for (Map.Entry<UUID, Set<Integer>> entry : ranked) {
			if (score < 1) {
				break;
			}
			String name = nameOf(server, entry.getKey());
			if (name.length() > 12) {
				name = name.substring(0, 12);
			}
			setLine("<yellow>" + name + ": <white>" + entry.getValue().size(), score--);
		}
		setLine("-----------------", score);
	}

	private void setLine(String markup, int score) {
		if (this.objective == null) {
			return;
		}
		Scoreboard scoreboard = this.mod.server().getScoreboard();
		ScoreHolder holder = ScoreHolder.fromStringOnlyForParsing(com.altarsmp.fabric.util.TextFx.strip(markup));
		ScoreAccess access = scoreboard.getOrCreatePlayerScore(holder, this.objective);
		access.set(score);
	}

	private static String nameOf(MinecraftServer server, UUID uuid) {
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		return player == null ? "Unknown" : player.getGameProfile().getName();
	}

	/** {@code BingoEvent#formatTime}. */
	static String formatTime(long seconds) {
		return String.format("%d:%02d:%02d", seconds / 3600L, seconds % 3600L / 60L, seconds % 60L);
	}
}
