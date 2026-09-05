package com.altarsmp.fabric.trial;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.data.WorldRecord;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

import net.fabricmc.fabric.api.event.player.UseItemCallback;

/**
 * The four Copper Trials, a port of {@code com.altarsmp.events.CopperTrialEvent}
 * together with the helmet fragment half of {@code OminousVaultListener}.
 *
 * <p>Upstream, {@code CopperTrialEvent} was one Bukkit {@code CommandExecutor} that
 * owned the helmet fragment hunt directly and delegated the other three:
 *
 * <ul>
 *   <li><b>helmet</b> - fragments drop from Ominous Trial Vaults, five of them, and
 *       whoever holds one glows. This class implements it.</li>
 *   <li><b>boots</b> - the Bingo event ({@link BingoEvent}), with an extra
 *       {@code tasks} action that opens the task board.</li>
 *   <li><b>leggings</b> - the Copper Core hot-potato ({@link HotPotatoEvent}).</li>
 *   <li><b>chestplate</b> - the thunder island ({@link CopperChestplateTrial}),
 *       which must be started by a player because it builds at their position.</li>
 * </ul>
 *
 * <p>{@code /coppertrial <trial> [start|stop|status]} keeps the plugin's exact
 * messages, including the "must be a player" refusal for the chestplate trial and
 * the red permission line for non-operators, so the command reports what it did
 * rather than silently doing nothing.
 *
 * <p>The three hooks Bukkit got from events come from mixins here: vault loot
 * ({@link #onOminousVaultLoot}), shard pickup ({@link CopperChestplateTrial#onShardPickedUp})
 * and item dropping ({@link #blockDrop}). The book's right-click is a real Fabric
 * {@link UseItemCallback} registration below.
 */
public final class CopperTrialService {

	/** The command's four trial names. */
	public static final String HELMET = "helmet";
	public static final String BOOTS = "boots";
	public static final String LEGGINGS = "leggings";
	public static final String CHESTPLATE = "chestplate";

	/** {@code CopperTrialEvent#e} - only five fragments exist per hunt. */
	private static final int MAX_FRAGMENTS = 5;
	/** {@code OminousVaultListener#startGlowingTask}: every 60 ticks, 100-tick glow. */
	private static final long GLOW_PERIOD_TICKS = 60L;
	private static final int GLOW_DURATION_TICKS = 100;
	/** {@code trials.helmet.fragment-drop-chance}, default 0.1. */
	private static final double DEFAULT_FRAGMENT_CHANCE = 0.1D;
	/** How often the trial records are refreshed in the world store. */
	private static final long PERSIST_PERIOD_TICKS = 200L;
	private static final String FRAGMENT_ID = "copperfragment";

	private final AltarSMPMod mod;
	private final HotPotatoEvent hotPotato;
	private final BingoEvent bingo;
	private final CopperChestplateTrial chestplate;
	private final RandomSource random = RandomSource.create();
	private final Set<UUID> collectors = new HashSet<>();
	private final List<String> recordedIslands = new ArrayList<>();

	private boolean helmetRunning;
	private int fragmentsCollected;
	private double fragmentChance = DEFAULT_FRAGMENT_CHANCE;
	private long tickCount;

	public CopperTrialService(AltarSMPMod mod) {
		this.mod = mod;
		this.hotPotato = new HotPotatoEvent(mod);
		this.bingo = new BingoEvent(mod);
		this.chestplate = new CopperChestplateTrial(mod);
		reloadConfig();
	}

	/** {@code OminousVaultListener#reloadConfig}. */
	public void reloadConfig() {
		this.fragmentChance = this.mod.config().getDouble("trials.helmet.fragment-drop-chance", DEFAULT_FRAGMENT_CHANCE);
	}

	public HotPotatoEvent hotPotato() {
		return this.hotPotato;
	}

	public BingoEvent bingo() {
		return this.bingo;
	}

	public CopperChestplateTrial chestplate() {
		return this.chestplate;
	}

	// ------------------------------------------------------------------- hooks

	/**
	 * The book's right-click, which Bukkit handled with a
	 * {@code PlayerInteractEvent}. Everything else the trials need arrives from the
	 * mixins or from {@code WeaponRegistry}'s combat and disconnect hooks.
	 */
	public void registerEventHooks() {
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!(player instanceof ServerPlayer serverPlayer) || hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}
			if (!this.bingo.onBookUse(serverPlayer, serverPlayer.getItemInHand(hand))) {
				return InteractionResult.PASS;
			}
			return InteractionResult.SUCCESS;
		});
	}

	/** The plugin's repeating tasks: the three trials plus the fragment glow check. */
	public void tick(MinecraftServer server) {
		this.tickCount++;
		this.hotPotato.tick(server);
		this.bingo.tick(server);
		this.chestplate.tick(server);
		if (this.tickCount % GLOW_PERIOD_TICKS == 0L) {
			glowPass(server);
		}
		if (this.tickCount % PERSIST_PERIOD_TICKS == 0L) {
			persist();
		}
	}

	/** {@code OminousVaultListener#startGlowingTask}'s body: fragment holders glow. */
	private void glowPass(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (hasFragment(player)) {
				Effects.apply(player, "GLOWING", GLOW_DURATION_TICKS, 0, false, false, true);
			}
		}
	}

	/** {@code OminousVaultListener#hasFragment}. */
	public static boolean hasFragment(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (isFragment(player.getInventory().getItem(slot))) {
				return true;
			}
		}
		return isFragment(player.getMainHandItem()) || isFragment(player.getOffhandItem());
	}

	/**
	 * The mixin on vault loot calls this. Bukkit's
	 * {@code BlockDispenseLootEvent#getDispensedLoot().add(...)} is the same list the
	 * vault then throws out, so the fragment is added to the loot the caller passes in.
	 *
	 * @return {@code true} if a fragment was added
	 */
	public boolean onOminousVaultLoot(@Nullable ServerPlayer player, List<ItemStack> dispensedLoot) {
		if (player == null) {
			AltarSMPMod.LOGGER.debug("[AltarSMP] vault dispensed loot with no player attached - no fragment");
			return false;
		}
		if (!canDropFragment()) {
			AltarSMPMod.LOGGER.debug("[AltarSMP] helmet trial not running or all {} fragments collected",
					MAX_FRAGMENTS);
			return false;
		}
		if (this.random.nextDouble() > this.fragmentChance) {
			AltarSMPMod.LOGGER.debug("[AltarSMP] fragment roll failed for {} (chance {})",
					player.getGameProfile().getName(), this.fragmentChance);
			return false;
		}
		dispensedLoot.add(createFragment());
		onFragmentCollected(player);
		Fx.soundTo(player, "BLOCK_TRIAL_SPAWNER_OPEN_SHUTTER", 1.0F, 0.5F);
		Fx.soundTo(player, "ENTITY_PLAYER_LEVELUP", 1.0F, 2.0F);
		Messaging.send(player, "<gold><bold>You found a Copper Fragment!");
		Messaging.send(player, "<gray>Warning: This fragment will make you glow and reveal your location!");
		return true;
	}

	// ------------------------------------------------------------ helmet trial

	/** {@code CopperTrialEvent#isHelmetTrialRunning}. */
	public boolean isHelmetTrialRunning() {
		return this.helmetRunning;
	}

	/** {@code CopperTrialEvent#canDropFragment}. */
	public boolean canDropFragment() {
		return this.helmetRunning && this.fragmentsCollected < MAX_FRAGMENTS;
	}

	/** {@code CopperTrialEvent#getFragmentsRemaining}. */
	public int getFragmentsRemaining() {
		return MAX_FRAGMENTS - this.fragmentsCollected;
	}

	/** {@code CopperTrialEvent#startHelmetTrial}. */
	public void startHelmetTrial() {
		if (this.helmetRunning) {
			return;
		}
		this.helmetRunning = true;
		this.fragmentsCollected = 0;
		this.collectors.clear();
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.send(player, "");
			Messaging.send(player, "<gold><bold>The Copper Helmet Fragment Event has begun!");
			Messaging.send(player,
					"<gray>You can now discover <gold>Helmet Fragments</gold> from <gold>Ominous Trial Vaults</gold>!");
			Messaging.send(player, "<gray>Only <gold>" + MAX_FRAGMENTS + "</gold> fragments can be retrieved.");
			Messaging.send(player, "<gray>Holding a fragment will make you glow and be visible to all players!");
			Messaging.send(player, "");
			Fx.soundTo(player, "UI_TOAST_CHALLENGE_COMPLETE", 1.0F, 1.0F);
		}
	}

	/** {@code CopperTrialEvent#stopHelmetTrial}. */
	public void stopHelmetTrial() {
		if (!this.helmetRunning) {
			return;
		}
		this.helmetRunning = false;
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.send(player, "<red><bold>The Copper Helmet Fragment Event has ended!");
			Messaging.send(player, "<gray>Total fragments collected: <gold>" + this.fragmentsCollected + "/"
					+ MAX_FRAGMENTS);
		}
	}

	/** {@code CopperTrialEvent#onFragmentCollected}. */
	public void onFragmentCollected(ServerPlayer finder) {
		if (!this.helmetRunning || this.fragmentsCollected >= MAX_FRAGMENTS) {
			return;
		}
		this.fragmentsCollected++;
		this.collectors.add(finder.getUUID());
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.send(player, "<gold>" + finder.getGameProfile().getName()
					+ " <gray>has found a <gold>Copper Fragment</gold>! <gray>(<gold>" + this.fragmentsCollected + "/"
					+ MAX_FRAGMENTS + "</gold>)");
			Fx.soundTo(player, "BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE", 1.0F, 1.0F);
		}
		if (this.fragmentsCollected >= MAX_FRAGMENTS) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Messaging.send(player, "<red><bold>All Copper Fragments have been collected!");
				Messaging.send(player, "<gray>The hunt is now over. Beware of fragment holders!");
			}
		}
	}

	/** {@code CopperFragment#onCommand} - an operator gives themselves a fragment. */
	public int giveFragment(ServerPlayer player) {
		ItemStack fragment = createFragment();
		if (!player.getInventory().add(fragment)) {
			player.drop(fragment, false);
		}
		Messaging.send(player, "<green>You received a <gold>Copper Fragment<green>!");
		return 1;
	}

	/** {@code CopperFragment#createCopperFragment}. */
	public static ItemStack createFragment() {
		ItemStack stack = new ItemStack(Items.COPPER_INGOT);
		stack.set(DataComponents.CUSTOM_NAME, Messaging.msg("<gold>Copper Fragment").copy()
				.withStyle(style -> style.withItalic(false)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Messaging.msg("<gray>A strange copper fragment that"),
				Messaging.msg("<gray>emits a faint tracking signal."),
				Messaging.msg(""),
				Messaging.msg("<red>Warning: <dark_red>Leaks your location!"),
				Messaging.msg("<dark_gray>Coordinates broadcast every 3 minutes"))));
		Identity.putLegacy(stack, Identity.KEY_ITEM, FRAGMENT_ID);
		return stack;
	}

	/** {@code CopperFragment#isCopperFragment}. */
	public static boolean isFragment(@Nullable ItemStack stack) {
		return stack != null && stack.is(Items.COPPER_INGOT)
				&& FRAGMENT_ID.equals(Identity.legacyString(stack, Identity.KEY_ITEM, ""));
	}

	// -------------------------------------------------------- shared callbacks

	/** {@code CombatHooks}' attack notification - the Copper Core transfers on a hit. */
	public void onPlayerHit(ServerPlayer attacker, net.minecraft.world.entity.LivingEntity victim) {
		this.hotPotato.onPlayerHit(attacker, victim);
	}

	/** {@code HotPotatoEvent#onPlayerQuit}, from the disconnect hook. */
	public void onPlayerQuit(ServerPlayer leaving) {
		this.hotPotato.onPlayerQuit(leaving);
	}

	/**
	 * Item-drop veto for the trial items. The Bukkit plugin cancelled
	 * {@code PlayerDropItemEvent} for both the Bingo Book and the Copper Core; the
	 * drop mixin asks here and keeps the item in the player's hand when this says so.
	 */
	public boolean blockDrop(ServerPlayer player, ItemStack dropped) {
		return this.bingo.blockDrop(player, dropped) || this.hotPotato.blockDrop(player, dropped);
	}

	// ------------------------------------------------------------- persistence

	/** Reads the recorded trial state; entities are rebuilt in {@link #resumeActiveTrials}. */
	public void loadFromStore() {
		WorldRecord.TrialState helmet = this.mod.store().world().trial(HELMET);
		this.helmetRunning = helmet.active();
		this.fragmentsCollected = (int) Math.min(MAX_FRAGMENTS, helmet.numbers().getOrDefault("fragments", 0L));
		this.collectors.clear();
		for (String raw : helmet.data().getOrDefault("collectors", "").split(",")) {
			if (raw.isEmpty()) {
				continue;
			}
			try {
				this.collectors.add(UUID.fromString(raw));
			} catch (IllegalArgumentException ex) {
				AltarSMPMod.LOGGER.warn("[AltarSMP] helmet trial record holds an unreadable collector '{}'", raw);
			}
		}
		this.recordedIslands.clear();
		for (String record : this.mod.store().world().trial(CHESTPLATE).data().getOrDefault("islands", "").split(";")) {
			if (!record.isEmpty()) {
				this.recordedIslands.add(record);
			}
		}
	}

	/** Rebuilds whatever was mid-flight when the server stopped. */
	public void resumeActiveTrials() {
		if (!this.recordedIslands.isEmpty()) {
			this.chestplate.loadFromStore(this.recordedIslands);
			this.recordedIslands.clear();
			AltarSMPMod.LOGGER.info("[AltarSMP] rebuilt {} Chestplate Shard island(s) after restart",
					this.chestplate.describeIslands().size());
		}
		if (this.hotPotato.restoreFrom(this.mod.store().world().trial(LEGGINGS))) {
			AltarSMPMod.LOGGER.info("[AltarSMP] Copper Core Trial resumed after restart");
		}
		if (this.bingo.restoreFrom(this.mod.store().world().trial(BOOTS))) {
			AltarSMPMod.LOGGER.info("[AltarSMP] Bingo Event resumed after restart");
		}
		if (this.helmetRunning) {
			AltarSMPMod.LOGGER.info("[AltarSMP] helmet fragment hunt resumed at {}/{} fragments",
					this.fragmentsCollected, MAX_FRAGMENTS);
		}
	}

	/** Ends the timed trials properly and writes everything down before the JVM exits. */
	public void stopAllForShutdown() {
		this.hotPotato.stopForShutdown();
		this.bingo.stopForShutdown();
		this.chestplate.stopForShutdown();
		persist();
	}

	/** Refreshes the four world records from live state. */
	public void persist() {
		WorldRecord world = this.mod.store().world();
		WorldRecord.TrialState helmet = world.trial(HELMET);
		helmet.active(this.helmetRunning);
		helmet.numbers().put("fragments", (long) this.fragmentsCollected);
		StringBuilder joined = new StringBuilder();
		for (UUID collector : this.collectors) {
			if (joined.length() > 0) {
				joined.append(',');
			}
			joined.append(collector);
		}
		helmet.data().put("collectors", joined.toString());

		this.hotPotato.writeTo(world.trial(LEGGINGS));
		this.bingo.writeTo(world.trial(BOOTS));

		WorldRecord.TrialState chestplateState = world.trial(CHESTPLATE);
		chestplateState.active(this.chestplate.isRunning());
		chestplateState.data().put("islands", String.join(";", this.chestplate.describeIslands()));
	}

	// ----------------------------------------------------------------- command

	/**
	 * {@code CopperTrialEvent#onCommand}, minus the Bukkit plumbing. The
	 * {@code /coppertrial} registration in {@code CommandRegistrar} routes here with
	 * the trial name and the (defaulted) action.
	 *
	 * @return Brigadier's success count
	 */
	public int onCommand(CommandSourceStack source, String trial, String action) {
		if (!source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			tell(source, "<red>You don't have permission to use this command.");
			return 0;
		}
		switch (trial) {
			case HELMET:
				return handleHelmetTrial(source, action);
			case BOOTS:
				return handleBootsTrial(source, action);
			case LEGGINGS:
				return handleLeggingsTrial(source, action);
			case CHESTPLATE:
				return handleChestplateTrial(source);
			default:
				tell(source, "<red>Unknown trial type. Use: helmet, boots, leggings, or chestplate");
				return 0;
		}
	}

	/** {@code CopperTrialEvent#onCommand}'s usage line. */
	public int onCommandNoArgs(CommandSourceStack source) {
		if (!source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			tell(source, "<red>You don't have permission to use this command.");
			return 0;
		}
		tell(source, "<gold>Usage: /coppertrial <helmet|boots|leggings|chestplate> [start|stop|status]");
		return 1;
	}

	/** {@code CopperTrialEvent#handleHelmetTrial}. */
	private int handleHelmetTrial(CommandSourceStack source, String action) {
		switch (action) {
			case "start":
				startHelmetTrial();
				tell(source, "<green>Copper Helmet Fragment trial started!");
				return 1;
			case "stop":
				stopHelmetTrial();
				tell(source, "<red>Copper Helmet Fragment trial stopped!");
				return 1;
			case "status":
				tell(source, "<yellow>Helmet Trial: " + (this.helmetRunning ? "<green>Running" : "<red>Stopped")
						+ " <gray>| Fragments collected: <white>" + this.fragmentsCollected + "/" + MAX_FRAGMENTS);
				return 1;
			default:
				tell(source, "<red>Unknown action. Use: start, stop, or status");
				return 0;
		}
	}

	/** {@code CopperTrialEvent#handleBootsTrial}, including the {@code tasks} action. */
	private int handleBootsTrial(CommandSourceStack source, String action) {
		if (action.equals("tasks")) {
			ServerPlayer player = source.getPlayer();
			if (player == null) {
				tell(source, "<red>Only players can view tasks.");
				return 0;
			}
			this.bingo.openTasksGui(player);
			return 1;
		}
		switch (action) {
			case "start":
				this.bingo.start();
				MinecraftServer server = this.mod.server();
				if (server != null) {
					for (ServerPlayer player : server.getPlayerList().getPlayers()) {
						Messaging.send(player, "");
						Messaging.send(player, "<gold><bold>The Copper Boots Trial has begun!");
						Messaging.send(player, "<gray>Complete bingo tasks to earn points!");
						Messaging.send(player, "<gray>The winner will receive the <gold>Copper Boots</gold>!");
						Messaging.send(player, "<gray>Left-click items in the tasks menu to complete them!");
						Messaging.send(player, "");
					}
				}
				tell(source, "<green>Copper Boots Trial (Bingo) started!");
				return 1;
			case "stop":
				this.bingo.stop();
				tell(source, "<red>Copper Boots Trial (Bingo) stopped!");
				return 1;
			case "status":
				tell(source, "<yellow>Boots Trial (Bingo): "
						+ (this.bingo.isRunning() ? "<green>Running" : "<red>Stopped"));
				return 1;
			default:
				tell(source, "<red>Unknown action. Use: start, stop, status, or tasks");
				return 0;
		}
	}

	/** {@code CopperTrialEvent#handleLeggingsTrial}. */
	private int handleLeggingsTrial(CommandSourceStack source, String action) {
		switch (action) {
			case "start":
				this.hotPotato.start();
				tell(source, "<green>Copper Leggings Trial (Copper Core) started!");
				return 1;
			case "stop":
				this.hotPotato.stop();
				tell(source, "<red>Copper Leggings Trial (Copper Core) stopped!");
				return 1;
			case "status":
				tell(source, "<yellow>Leggings Trial (Copper Core): "
						+ (this.hotPotato.isRunning() ? "<green>Running" : "<red>Stopped"));
				return 1;
			default:
				tell(source, "<red>Unknown action. Use: start, stop, or status");
				return 0;
		}
	}

	/** {@code CopperTrialEvent#handleChestplateTrial} - a player must start it in person. */
	private int handleChestplateTrial(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			tell(source, "<red>This trial must be started by a player (spawns at your location).");
			return 0;
		}
		this.chestplate.start(player);
		return 1;
	}

	/**
	 * {@code CopperTrialEvent#onTabComplete}. Brigadier's suggestion provider feeds the
	 * partial word in; the same filtering rule applies.
	 */
	public List<String> suggest(String trial, @Nullable String partialAction) {
		List<String> options = new ArrayList<>();
		if (partialAction == null) {
			options.add(HELMET);
			options.add(BOOTS);
			options.add(LEGGINGS);
			options.add(CHESTPLATE);
			return options;
		}
		if (CHESTPLATE.equals(trial)) {
			return options;
		}
		options.add("start");
		options.add("stop");
		options.add("status");
		if (BOOTS.equals(trial)) {
			options.add("tasks");
		}
		return options;
	}

	/** Whether a trial is running - used by protection and altar code. */
	public boolean isAnyTrialRunning() {
		return this.helmetRunning || this.hotPotato.isRunning() || this.bingo.isRunning()
				|| this.chestplate.isRunning();
	}

	/** {@code CopperTrialEvent#isLeggingsTrialRunning}. */
	public boolean isLeggingsTrialRunning() {
		return this.hotPotato.isRunning();
	}

	private static void tell(CommandSourceStack source, String markup) {
		source.sendSuccess(() -> Messaging.msg(markup), false);
	}
}
