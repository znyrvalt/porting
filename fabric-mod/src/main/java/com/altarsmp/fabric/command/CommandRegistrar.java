package com.altarsmp.fabric.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.math.Transformation;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.altar.AltarManager;
import com.altarsmp.fabric.altar.AltarRegistry;
import com.altarsmp.fabric.data.AltarRecord;
import com.altarsmp.fabric.data.PlayerRecord;
import com.altarsmp.fabric.faction.FactionManager;
import com.altarsmp.fabric.item.ContentCatalog;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TextFx;
import com.altarsmp.fabric.weapon.WeaponBehavior;
import com.altarsmp.fabric.weapon.s1.KnightfallWeapon;
import com.altarsmp.fabric.weapon.s1.MinorCrazySlotsWeapon;
import com.altarsmp.fabric.weapon.s1.PaleCrossbowWeapon;
import com.altarsmp.fabric.weapon.s1.WandOfIllusionWeapon;

/**
 * The whole command surface, in Brigadier.
 *
 * <p>The plugin registered roughly ninety {@code plugin.yml} commands, each pointing at a
 * {@code CommandExecutor}; this class is that registration list, one {@code register} call
 * per command, and every one of them calls the same game-side code the plugin called -
 * {@link AltarManager} for altars, {@link FactionManager} for curses, the weapon classes for
 * kill counters and morph locks, the trial and event managers for their events. Nothing here
 * implements gameplay of its own and nothing answers without doing the work: a give command
 * that cannot build the item reports the failure instead of returning success.
 *
 * <p>Two mechanical differences from Bukkit are unavoidable and documented in the porting
 * notes. Permissions collapse onto vanilla command levels - {@code altarsmp.admin},
 * {@code altarsmps2.admin}, {@code altarsmp.bloodmoon} and {@code altarsmp.tabcolor} all
 * defaulted to {@code op}, which is {@link Permissions#COMMANDS_GAMEMASTER} here - and tab
 * completion becomes Brigadier suggestions, which is why the plugin's {@code TabCompleter}
 * methods show up as {@code suggests} lambdas.
 *
 * <p>{@code /tabcolor} is the one command whose mechanism changed. Paper let a plugin set a
 * player's display name and tab-list name directly; vanilla has no such field, so the port
 * uses the vanilla mechanism that produces the same visible result - a scoreboard team per
 * colour, which tints the name in the tab list and in chat exactly like the old prefix did.
 */
public final class CommandRegistrar {

	/** Boss mobs whose morph locks for fifteen uses ({@code MorphLockCommand}). */
	private static final Set<String> BOSS_MORPHS = Set.of("WARDEN", "ENDER_DRAGON", "WITHER", "ELDER_GUARDIAN",
			"RAVAGER");
	/** Common mobs whose morph locks for three uses. */
	private static final Set<String> COMMON_MORPHS = Set.of("ZOMBIE", "SKELETON", "SPIDER", "CREEPER", "BEE",
			"PHANTOM");
	/** Every other morph locks for five. */
	private static final int DEFAULT_LOCK_USES = 5;

	/** {@code a/z.java}: the blue circle is paper with custom model data 1. */
	private static final int BLUE_CIRCLE_MODEL = 1;
	/** The PDC marker {@code altarsmps2:vfx} becomes a scoreboard tag here. */
	private static final String TAG_VFX = "altarsmps2_vfx";
	/** {@code TabColorCommand}'s team names, one per colour. */
	private static final String TAB_TEAM_PREFIX = "asmp_tc_";

	private final AltarSMPMod mod;
	private int registered;

	public CommandRegistrar(AltarSMPMod mod) {
		this.mod = mod;
	}

	/** Hooks the dispatcher callback; {@link AltarSMPMod} calls this once at init. */
	public void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> this.registerAll(dispatcher));
	}

	/** How many root commands were registered, for the startup summary. */
	public int registeredCount() {
		return this.registered;
	}

	private void registerAll(CommandDispatcher<CommandSourceStack> dispatcher) {
		this.registered = 0;
		registerMain(dispatcher);
		registerWeaponGives(dispatcher);
		registerArmorGives(dispatcher);
		registerItemGives(dispatcher);
		registerFactionCommands(dispatcher);
		registerKillCommands(dispatcher);
		registerControlCommands(dispatcher);
		registerTrustCommands(dispatcher);
		registerMorphCommands(dispatcher);
		registerAltarCommands(dispatcher);
		registerTrialCommands(dispatcher);
		registerEventCommands(dispatcher);
		registerUtilityCommands(dispatcher);
		registerContentGuis(dispatcher);
		AltarSMPMod.LOGGER.info("[AltarSMP] registered {} commands", this.registered);
	}

	// ============================================================== /altarsmp

	/** {@code MainCommand}: help, reload, the content lists and the roulette-pool editor. */
	private void registerMain(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = admin("altarsmp")
				.executes(ctx -> {
					help(ctx.getSource());
					return 1;
				})
				.then(Commands.literal("help").executes(ctx -> {
					help(ctx.getSource());
					return 1;
				}))
				.then(Commands.literal("weapons").executes(ctx -> {
					weaponList(ctx.getSource());
					return 1;
				}))
				.then(Commands.literal("armor").executes(ctx -> {
					armorList(ctx.getSource());
					return 1;
				}))
				.then(Commands.literal("items").executes(ctx -> {
					itemList(ctx.getSource());
					return 1;
				}))
				.then(Commands.literal("reload").executes(ctx -> {
					reload(ctx.getSource());
					return 1;
				}))
				.then(Commands.literal("additemtominorcrazyslots").executes(ctx -> addHeldToPool(ctx.getSource())));
		add(dispatcher, root);
	}

	private void help(CommandSourceStack source) {
		tell(source, "<gold>=== AltarSMP Commands ===");
		tell(source, "<yellow>/altarsmp reload<gray> - Reload configuration");
		tell(source, "<yellow>/altarsmp additemtominorcrazyslots<gray> - Add held item to Minor Crazy Slots");
		tell(source, "<yellow>/altarsmp weapons<gray> - List all weapons");
		tell(source, "<yellow>/altarsmp armor<gray> - List all armor");
		tell(source, "<yellow>/altarsmp items<gray> - List all items");
		tell(source, "<yellow>/bingo <start|stop|status><gray> - Bingo event");
		tell(source, "<yellow>/hotpotato <start|stop|status><gray> - Hot Potato event");
	}

	private void weaponList(CommandSourceStack source) {
		tell(source, "<gold>=== AltarSMP Weapons ===");
		tell(source, "<dark_red>/bloodlust<gray> - Bloodlust (grows with kills)");
		tell(source, "<white>/boneblade<gray> - Bone Blade (bone cage ability)");
		tell(source, "<gold>/vulcanscrossbow<gray> - Vulcan's Crossbow (explosive arrows)");
		tell(source, "<gold>/hyperion<gray> - Hyperion (divine powers)");
		tell(source, "<aqua>/frostscythe<gray> - Frost Scythe (ice projectile)");
		tell(source, "<dark_red>/nightpiercer<gray> - Nightpiercer (bat swarm blade)");
		tell(source, "<green>/windweaver<gray> - Windweaver (wind powers)");
		tell(source, "<dark_gray>/witherbone<gray> - Witherbone (wither abilities)");
		tell(source, "<gold>/crazyslots<gray> - Crazy Slots (random effects)");
		tell(source, "<gold>/minorcrazyslots<gray> - Minor Crazy Slots (manual prize roulette)");
		tell(source, "<light_purple>/wandofillusion<gray> - Wand of Illusion (illusion magic)");
	}

	private void armorList(CommandSourceStack source) {
		tell(source, "<gold>=== AltarSMP Armor ===");
		tell(source, "<gold>/copperhelmet<gray> - Copper Helmet (water breathing)");
		tell(source, "<gold>/copperchestplate<gray> - Copper Chestplate (lightning strikes)");
		tell(source, "<gold>/copperleggings<gray> - Copper Leggings (mace strikes)");
		tell(source, "<gold>/copperboots<gray> - Copper Boots (speed + fire res)");
	}

	private void itemList(CommandSourceStack source) {
		tell(source, "<gold>=== AltarSMP Items ===");
		tell(source, "<gold>/copperpickaxe<gray> - Copper Pickaxe");
		tell(source, "<dark_aqua>/wardenheart<gray> - Warden Heart");
	}

	private void reload(CommandSourceStack source) {
		this.mod.config().reload();
		// The holograms quote recipe amounts from the config, so a reload rewrites them.
		for (AltarRecord record : this.mod.altars().recorded()) {
			AltarRegistry.Spec spec = this.mod.altarRegistry().byKey(record.altarType());
			ServerLevel level = levelOf(source.getServer(), record.dimension());
			if (spec == null || level == null) {
				continue;
			}
			for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
				if (record.altarId().equals(entity.getStringUUID()) && this.mod.altars().specOf(entity) != null) {
					this.mod.altars().refreshHologram(level, entity, spec);
				}
			}
		}
		tell(source, "<green>[AltarSMP] Configuration reloaded!");
	}

	private int addHeldToPool(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = player(source);
		MinorCrazySlotsWeapon slots = weapon(player, "minorcrazyslots", MinorCrazySlotsWeapon.class);
		if (slots == null) {
			tell(source, "<red>Minor Crazy Slots is not registered.");
			return 0;
		}
		slots.addHeldItemToPool(player);
		return 1;
	}

	// ================================================================== gives

	/**
	 * {@code BaseWeapon#onCommand}: every legendary has its own command name, and each one
	 * puts the built item straight into the sender's inventory.
	 */
	private void registerWeaponGives(CommandDispatcher<CommandSourceStack> dispatcher) {
		String[][] seasonOne = {
				{"bloodlust", "bloodlust"}, {"boneblade", "boneblade"}, {"vulcanscrossbow", "vulcanscrossbow"},
				{"hyperion", "hyperion"}, {"wandofillusion", "wandofillusion"}, {"frostscythe", "frostscythe"},
				{"crazyslots", "crazyslots"}, {"nightpiercer", "nightpiercer"}, {"windweaver", "windweaver"},
				{"witherbone", "witherbone"}, {"shadowblade", "shadowblade"}, {"pureblade", "pureblade"},
				{"earthgauntlet", "earthgauntlet"}, {"paladinbattleaxe", "paladinbattleaxe"}, {"cutlass", "cutlass"},
				{"palecrossbow", "palecrossbow"}, {"contagionsignal", "contagionsignal"},
				{"eclipsesword", "eclipsesword"}, {"knightfall", "knightfall"}, {"striker", "striker"},
				{"nukelauncher", "nukelauncher"}, {"echo", "echo"}, {"fireslash", "fireslash"},
		};
		for (String[] pair : seasonOne) {
			String contentId = pair[1];
			add(dispatcher, admin(pair[0]).executes(ctx -> give(ctx.getSource(), contentId)));
		}
		// Season 2's legendaries, registered by the merged AltarSMPS2 plugin.
		String[][] seasonTwo = {
				{"omen", "omen"}, {"ancientblade", "ancientblade"}, {"withersymbiote", "withersymbiote"},
				{"tidebreaker", "tidebreaker"}, {"dragonrend", "dragonrend"},
				{"bowofdeceptionandlies", "bowofdeception"},
		};
		for (String[] pair : seasonTwo) {
			String contentId = pair[1];
			add(dispatcher, admin(pair[0]).executes(ctx -> give(ctx.getSource(), contentId)));
		}
		// /minorcrazyslots [player|addpool]
		add(dispatcher, admin("minorcrazyslots")
				.executes(ctx -> give(ctx.getSource(), "minorcrazyslots"))
				.then(Commands.argument("target", StringArgumentType.word())
						.suggests((ctx, builder) -> {
							List<String> options = new ArrayList<>(List.of("addpool", "add", "pool"));
							for (ServerPlayer online : ctx.getSource().getServer().getPlayerList().getPlayers()) {
								options.add(online.getGameProfile().getName());
							}
							return suggest(options, builder);
						})
						.executes(ctx -> {
							String arg = StringArgumentType.getString(ctx, "target");
							if (arg.equalsIgnoreCase("addpool") || arg.equalsIgnoreCase("add")
									|| arg.equalsIgnoreCase("pool")) {
								return addHeldToPool(ctx.getSource());
							}
							ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(arg);
							if (target == null) {
								tell(ctx.getSource(), "<red>Player not found: " + arg);
								return 1;
							}
							return giveTo(ctx.getSource(), target, "minorcrazyslots");
						})));
		// /knightfallmax [player]
		add(dispatcher, admin("knightfallmax")
				.executes(ctx -> knightfallMax(ctx.getSource(), null))
				.then(Commands.argument("target", EntityArgument.player()).executes(ctx -> knightfallMax(ctx.getSource(),
						EntityArgument.getPlayer(ctx, "target")))));
	}

	/** {@code CopperDiamondArmor#onCommand}: diamond armour wearing the copper models. */
	private void registerArmorGives(CommandDispatcher<CommandSourceStack> dispatcher) {
		String[][] armor = {
				{"copperhelmet", "copper_helmet"}, {"copperchestplate", "copper_chestplate"},
				{"copperleggings", "copper_leggings"}, {"copperboots", "copper_boots"},
		};
		for (String[] pair : armor) {
			String contentId = pair[1];
			add(dispatcher, admin(pair[0]).executes(ctx -> give(ctx.getSource(), contentId)));
		}
		add(dispatcher, admin("copperdiamondarmor")
				.executes(ctx -> {
					tell(ctx.getSource(), "<gold>Usage: /copperdiamond <helmet|chestplate|leggings|boots|all>");
					return 1;
				})
				.then(Commands.argument("piece", StringArgumentType.word())
						.suggests((ctx, builder) -> suggest(
								List.of("helmet", "chestplate", "leggings", "boots", "all"), builder))
						.executes(ctx -> copperDiamond(ctx.getSource(),
								StringArgumentType.getString(ctx, "piece")))));
	}

	/** The crafted components and Season 2's give commands. */
	private void registerItemGives(CommandDispatcher<CommandSourceStack> dispatcher) {
		String[][] items = {
				{"copperpickaxe", "copperpickaxe"}, {"copperpickaxeupgrade", "copperpickaxeii"},
				{"wardenheart", "wardenheart"}, {"weaponhandle", "weaponhandle"}, {"illusioncore", "illusioncore"},
				{"hyperionshard", "hyperionshard"}, {"nightpiercershard", "nightpiercershard"},
				{"vulkanhead", "vulkanhead"}, {"chestplateshard", "chestplateshard"}, {"paleshard", "paleshard"},
				{"soulinabottle", "soulinabottle"}, {"fragmentofthesea", "fragmentofthesea"},
				{"dragonheart", "dragonheart"}, {"amethystpickaxe", "amethystpickaxe"},
				{"amethystaxe", "amethystaxe"}, {"blackghastsaddle", "blackghastsaddle"},
		};
		for (String[] pair : items) {
			String contentId = pair[1];
			add(dispatcher, admin(pair[0]).executes(ctx -> give(ctx.getSource(), contentId)));
		}
		// /copperfragment is the trial's own give path: the fragment leaks coordinates.
		add(dispatcher, admin("copperfragment").executes(ctx -> this.mod.trials().giveFragment(player(ctx.getSource()))));
	}

	private int copperDiamond(CommandSourceStack source, String piece) throws CommandSyntaxException {
		ServerPlayer player = player(source);
		List<String> ids = switch (piece.toLowerCase(Locale.ROOT)) {
			case "helmet" -> List.of("copper_diamond_helmet");
			case "chestplate" -> List.of("copper_diamond_chestplate");
			case "leggings" -> List.of("copper_diamond_leggings");
			case "boots" -> List.of("copper_diamond_boots");
			case "all" -> List.of("copper_diamond_helmet", "copper_diamond_chestplate", "copper_diamond_leggings",
					"copper_diamond_boots");
			default -> List.of();
		};
		if (ids.isEmpty()) {
			tell(source, "<red>Unknown piece. Use: helmet, chestplate, leggings, boots, or all");
			return 1;
		}
		for (String id : ids) {
			Optional<ItemStack> built = ItemFactory.content(id);
			if (built.isEmpty()) {
				tell(source, "<red>Cannot build " + id + " - the content catalogue has no such entry.");
				return 0;
			}
			if (!player.getInventory().add(built.get())) {
				player.drop(built.get(), false);
			}
		}
		Messaging.send(player, "<green>You received Copper Diamond Armor!");
		Fx.soundTo(player, SoundEvents.CONDUIT_ACTIVATE, 1.0F, 1.2F);
		return 1;
	}

	private int give(CommandSourceStack source, String contentId) throws CommandSyntaxException {
		return giveTo(source, player(source), contentId);
	}

	/** {@code BaseWeapon#onCommand}'s inventory drop plus the receipt line. */
	private int giveTo(CommandSourceStack source, ServerPlayer receiver, String contentId) {
		Optional<ItemStack> built = this.mod.weapons().create(contentId, receiver);
		if (built.isEmpty()) {
			tell(source, "<red>No such content: " + contentId);
			return 0;
		}
		ItemStack stack = built.get();
		if (!receiver.getInventory().add(stack)) {
			receiver.drop(stack, false);
		}
		Messaging.send(receiver, "<green>You received " + nameOf(contentId) + "<green>!");
		ServerPlayer sender = source.getPlayer();
		if (sender == null || sender != receiver) {
			tell(source, "<green>Gave " + TextFx.strip(nameOf(contentId)) + " to "
					+ receiver.getGameProfile().getName() + ".");
		}
		return 1;
	}

	/** The catalogue's display markup, or the id when an entry has no name of its own. */
	private static String nameOf(String contentId) {
		String id = Identity.normalise(contentId);
		ContentCatalog.WeaponDef weapon = ContentCatalog.weapon(id);
		if (weapon != null && weapon.displayName() != null) {
			return weapon.displayName();
		}
		ContentCatalog.ItemDef item = ContentCatalog.item(id);
		if (item != null && item.displayName() != null) {
			return item.displayName();
		}
		ContentCatalog.ArmorDef armor = ContentCatalog.armor(id);
		if (armor != null && armor.displayName() != null) {
			return armor.displayName();
		}
		return id;
	}

	private int knightfallMax(CommandSourceStack source, @Nullable ServerPlayer argument) throws CommandSyntaxException {
		ServerPlayer sender = player(source);
		ServerPlayer target = argument != null ? argument : sender;
		ItemStack held = target.getMainHandItem();
		if (!Identity.is(held, "knightfall")) {
			tell(source, "<red>Target must be holding Knightfall!");
			return 1;
		}
		KnightfallWeapon knightfall = weapon(target, "knightfall", KnightfallWeapon.class);
		if (knightfall == null) {
			tell(source, "<red>Knightfall is not registered.");
			return 0;
		}
		knightfall.applyKills(held, 10);
		PlayerRecord record = this.mod.store().player(target.getUUID());
		record.knightfallKills(10);
		this.mod.store().markDirty();
		Messaging.send(target, "<dark_purple>[Knightfall] <gold><bold>Kill count set to MAX (10)! All abilities unlocked!");
		if (target != sender) {
			tell(source, "<green>Set " + target.getGameProfile().getName() + "'s Knightfall kills to 10.");
		}
		Fx.soundTo(target, SoundEvents.PLAYER_LEVELUP, 1.0F, 2.0F);
		return 1;
	}

	// =============================================================== factions

	/** {@code VampireCommand}, {@code PaleCommand}, {@code HumanCommand} and {@code CurseCommands}. */
	private void registerFactionCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		add(dispatcher, admin("vampire").executes(ctx -> allPlayers(ctx.getSource(), "vampire")));
		add(dispatcher, admin("human").executes(ctx -> allPlayers(ctx.getSource(), "human")));
		// /pale is not "make everyone pale": it wakes the spreading system up.
		add(dispatcher, admin("pale").executes(ctx -> {
			ServerPlayer player = player(ctx.getSource());
			if (this.mod.factions().isPaleActivated()) {
				Messaging.send(player, "<gray>The pale system is already active!");
			} else {
				this.mod.factions().activatePaleSystem();
				Messaging.send(player, "<dark_gray>The pale has been awakened...");
			}
			return 1;
		}));
		add(dispatcher, curse(dispatcher, "setpale", "Usage: /setpale <player>", (source, target) -> {
			this.mod.factions().setPaleRot(target);
			tell(source, "<green>" + target.getGameProfile().getName() + " is now a Pale Rot.");
			Messaging.send(target, "<gray>You have been cursed with the pale rot...");
		}));
		add(dispatcher, curse(dispatcher, "setpaleking", "Usage: /setpaleking <player>", (source, target) -> {
			this.mod.factions().setPaleKing(target);
			tell(source, "<green>" + target.getGameProfile().getName() + " is now the Pale King.");
			Messaging.send(target, "<dark_gray>You have become the Pale King... Your kills spread the curse.");
		}));
		add(dispatcher, curse(dispatcher, "setvampire", "Usage: /setvampire <player>", (source, target) -> {
			this.mod.factions().setVampire(target);
			tell(source, "<green>" + target.getGameProfile().getName() + " is now a Vampire.");
			Messaging.send(target, "<red>You have been turned into a vampire... You deal +1 damage.");
		}));
		add(dispatcher, curse(dispatcher, "setvampireking", "Usage: /setvampireking <player>", (source, target) -> {
			this.mod.factions().setVampireKing(target);
			tell(source, "<green>" + target.getGameProfile().getName() + " is now the Vampire King.");
			Messaging.send(target, "<dark_red>You have become the Vampire King... Your kills create vampires.");
		}));
		add(dispatcher, curse(dispatcher, "settruevampire", "Usage: /settruevampire <player>", (source, target) -> {
			this.mod.factions().setPermaVampire(target);
			tell(source, "<green>" + target.getGameProfile().getName() + " is now a True Vampire (perma).");
		}));
		add(dispatcher, curse(dispatcher, "removecurse", "Usage: /removecurse <player>", (source, target) -> {
			this.mod.factions().removeCurse(target);
			tell(source, "<green>" + target.getGameProfile().getName() + " is now human (curse removed).");
			Messaging.send(target, "<green>Your curse has been lifted. You are human again.");
		}));
		add(dispatcher, curse(dispatcher, "sethuman", "Usage: /sethuman <player>", (source, target) -> {
			this.mod.factions().setHuman(target);
			tell(source, "<green>" + target.getGameProfile().getName() + " is now human.");
			Messaging.send(target, "<green>You are human again.");
		}));
		add(dispatcher, admin("clearperma")
				.executes(ctx -> {
					tell(ctx.getSource(), "<red>Usage: /clearperma <player> [vampire|pale|human|all]");
					return 1;
				})
				.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> clearPerma(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"), "all"))
						.then(Commands.argument("type", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionFactory
										.suggest(List.of("vampire", "pale", "human", "all"), builder))
								.executes(ctx -> clearPerma(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
										StringArgumentType.getString(ctx, "type"))))));
		add(dispatcher, curse(dispatcher, "removepermapale", "Usage: /removepermapale <player>", (source, target) -> {
			if (FactionManager.isPermaPale(target)) {
				this.mod.factions().removePermaPale(target);
				this.mod.factions().setHuman(target);
				tell(source, "<green>Removed permanent pale rot status from " + target.getGameProfile().getName());
				Messaging.send(target, "<gold>Your permanent pale rot curse has been lifted by an admin.");
			} else {
				tell(source, "<yellow>" + target.getGameProfile().getName() + " is not permanently pale.");
			}
		}));
		add(dispatcher, curse(dispatcher, "removepermahuman", "Usage: /removepermahuman <player>", (source, target) -> {
			if (FactionManager.isPermaHuman(target)) {
				this.mod.factions().removePermaHuman(target);
				tell(source, "<green>Removed permanent human status from " + target.getGameProfile().getName());
				Messaging.send(target, "<gray>Your permanent human protection has been removed by an admin.");
			} else {
				tell(source, "<yellow>" + target.getGameProfile().getName() + " is not permanently human.");
			}
		}));
		add(dispatcher, curse(dispatcher, "givepaleeffect", "Usage: /givepaleeffect <player>", (source, target) -> {
			if (PaleCrossbowWeapon.hasPaleEffect(target)) {
				tell(source, "<yellow>" + target.getGameProfile().getName() + " already has the Pale effect.");
			} else {
				PaleCrossbowWeapon.applyPaleEffect(target);
				tell(source, "<dark_gray>Applied Pale effect to " + target.getGameProfile().getName());
				Messaging.send(target, "<dark_gray>You feel something cold seeping into your soul...");
			}
		}));
	}

	/** One {@code <player>} admin command, wired the way {@code CurseCommands} did it. */
	private LiteralArgumentBuilder<CommandSourceStack> curse(CommandDispatcher<CommandSourceStack> dispatcher,
			String name, String usage, CurseAction action) {
		return admin(name)
				.executes(ctx -> {
					tell(ctx.getSource(), "<red>" + usage);
					return 1;
				})
				.then(Commands.argument("target", EntityArgument.player()).executes(ctx -> {
					action.run(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"));
					return 1;
				}));
	}

	private interface CurseAction {
		void run(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException;
	}

	private int allPlayers(CommandSourceStack source, String faction) throws CommandSyntaxException {
		ServerPlayer sender = player(source);
		for (ServerPlayer online : source.getServer().getPlayerList().getPlayers()) {
			if (faction.equals("vampire")) {
				FactionManager.makeVampire(online);
			} else {
				FactionManager.makeHuman(online);
			}
		}
		Messaging.send(sender, faction.equals("vampire")
				? "<yellow>All online players have been made into vampires!"
				: "<yellow>All online players have been made human!");
		return 1;
	}

	private int clearPerma(CommandSourceStack source, ServerPlayer target, String type) {
		String which = type.toLowerCase(Locale.ROOT);
		boolean cleared = false;
		if (which.equals("vampire") || which.equals("all")) {
			this.mod.factions().removePermaVampire(target);
			cleared = true;
		}
		if (which.equals("pale") || which.equals("all")) {
			this.mod.factions().removePermaPale(target);
			cleared = true;
		}
		if (which.equals("human") || which.equals("all")) {
			this.mod.factions().removePermaHuman(target);
			cleared = true;
		}
		if (!cleared) {
			tell(source, "<red>Unknown type. Use: vampire, pale, human, or all.");
		} else {
			tell(source, "<green>Cleared perma-" + which + " from " + target.getGameProfile().getName() + ".");
		}
		return 1;
	}

	// ================================================================== kills

	/** {@code SetBloodlustCommand}, {@code SetKillsCommand} and Season 2's {@code setkillss}. */
	private void registerKillCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		add(dispatcher, admin("setbloodlust")
				.executes(ctx -> {
					tell(ctx.getSource(), "<red>Usage: /setbloodlust <player> <1-5>");
					return 1;
				})
				.then(Commands.argument("target", EntityArgument.player())
						.then(Commands.argument("level", IntegerArgumentType.integer(1, 5))
								.executes(ctx -> setBloodlust(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
										IntegerArgumentType.getInteger(ctx, "level"))))));
		add(dispatcher, admin("setkills")
				.executes(ctx -> {
					tell(ctx.getSource(), "<gold>Usage: /setkills <bloodlust|knightfall> <player> <kills>");
					tell(ctx.getSource(), "<gray>Bloodlust: 0-5, Knightfall: 0-10");
					return 1;
				})
				.then(Commands.argument("weapon", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionFactory
								.suggest(List.of("bloodlust", "knightfall"), builder))
						.then(Commands.argument("target", EntityArgument.player())
								.then(Commands.argument("kills", IntegerArgumentType.integer(0, 10))
										.suggests((ctx, builder) -> {
											int max = StringArgumentType.getString(ctx, "weapon")
													.equalsIgnoreCase("knightfall") ? 10 : 5;
											List<String> values = new ArrayList<>();
											for (int value = 0; value <= max; value++) {
												values.add(String.valueOf(value));
											}
											return suggest(values, builder);
										})
										.executes(ctx -> setKills(ctx.getSource(),
												StringArgumentType.getString(ctx, "weapon"),
												EntityArgument.getPlayer(ctx, "target"),
												IntegerArgumentType.getInteger(ctx, "kills")))))));
		// Season 2: /setkillss ancientblade <player> <kills>
		add(dispatcher, admin("setkillss")
				.executes(ctx -> {
					tell(ctx.getSource(), "<gold>Usage: /setkillss ancientblade <player> <kills>");
					return 1;
				})
				.then(Commands.literal("ancientblade")
						.then(Commands.argument("target", EntityArgument.player())
								.then(Commands.argument("kills", IntegerArgumentType.integer(0, 1000000))
										.executes(ctx -> setAncientBladeKills(ctx.getSource(),
												EntityArgument.getPlayer(ctx, "target"),
												IntegerArgumentType.getInteger(ctx, "kills")))))));
		// /bloodkills - a player reads their own Bloodlust progress.
		add(dispatcher, Commands.literal("bloodkills").executes(ctx -> bloodKills(ctx.getSource())));
	}

	private int setBloodlust(CommandSourceStack source, ServerPlayer target, int level) {
		PlayerRecord record = this.mod.store().player(target.getUUID());
		record.bloodlustKills(level);
		this.mod.store().markDirty();
		tell(source, "<green>Set <yellow>" + target.getGameProfile().getName() + "<green>'s bloodlust level to "
				+ level + ".");
		Messaging.send(target, "<gold>Your bloodlust level has been set to " + level + "!");
		return 1;
	}

	private int setKills(CommandSourceStack source, String which, ServerPlayer target, int kills) {
		String weapon = which.toLowerCase(Locale.ROOT);
		if (weapon.equals("bloodlust")) {
			if (kills < 0 || kills > 5) {
				tell(source, "<red>Bloodlust kills must be 0-5.");
				return 1;
			}
			PlayerRecord record = this.mod.store().player(target.getUUID());
			record.bloodlustKills(kills);
			this.mod.store().markDirty();
			tell(source, "<green>Set <yellow>" + target.getGameProfile().getName()
					+ "<green>'s bloodlust kills to <yellow>" + kills);
			Messaging.send(target, "<red>[Bloodlust] <gray>Your kill count has been set to <red>" + kills);
			return 1;
		}
		if (!weapon.equals("knightfall")) {
			tell(source, "<red>Unknown weapon. Use: bloodlust or knightfall");
			return 1;
		}
		if (kills < 0 || kills > 10) {
			tell(source, "<red>Knightfall kills must be 0-10.");
			return 1;
		}
		ItemStack held = target.getMainHandItem();
		if (!Identity.is(held, "knightfall")) {
			tell(source, "<red>" + target.getGameProfile().getName() + " must be holding Knightfall!");
			return 1;
		}
		KnightfallWeapon knightfall = weapon(target, "knightfall", KnightfallWeapon.class);
		if (knightfall == null) {
			tell(source, "<red>Knightfall is not registered.");
			return 0;
		}
		knightfall.applyKills(held, kills);
		PlayerRecord record = this.mod.store().player(target.getUUID());
		record.knightfallKills(kills);
		this.mod.store().markDirty();
		tell(source, "<green>Set <yellow>" + target.getGameProfile().getName()
				+ "<green>'s knightfall kills to <yellow>" + kills);
		Messaging.send(target, "<dark_purple>[Knightfall] <light_purple>Kill count set to " + kills);
		return 1;
	}

	private int setAncientBladeKills(CommandSourceStack source, ServerPlayer target, int kills) {
		ItemStack held = target.getMainHandItem();
		if (!Identity.is(held, "ancientblade")) {
			tell(source, "<red>" + target.getGameProfile().getName() + " must be holding the Ancient Blade!");
			return 1;
		}
		int clamped = Math.max(0, kills);
		Identity.setKills(held, clamped);
		PlayerRecord record = this.mod.store().player(target.getUUID());
		record.ancientBladeKills(clamped);
		this.mod.store().markDirty();
		tell(source, "<green>Set <yellow>" + target.getGameProfile().getName()
				+ "<green>'s Ancient Blade kills to <yellow>" + clamped);
		Messaging.send(target, "<aqua>[Ancient Blade] <gray>Kill count set to <white>" + clamped);
		return 1;
	}

	private int bloodKills(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = player(source);
		int kills = this.mod.store().player(player.getUUID()).bloodlustKills();
		Messaging.send(player, "<red>Bloodlust Kill Count: <white>" + kills);
		Messaging.send(player, "<gold>Unlocked Abilities:");
		Messaging.send(player, "<yellow>- Infection <gray>(0+ kills) " + tick(kills >= 0));
		Messaging.send(player, "<yellow>- Speed II <gray>(1+ kills) " + tick(kills >= 1));
		Messaging.send(player, "<yellow>- Blood Tracker <gray>(2+ kills) " + tick(kills >= 2));
		Messaging.send(player, "<yellow>- Blood Trail <gray>(3+ kills) " + tick(kills >= 3));
		Messaging.send(player, "<yellow>- Strength I <gray>(4+ kills) " + tick(kills >= 4));
		Messaging.send(player, "<yellow>- Blood Hook <gray>(5+ kills) " + tick(kills >= 5));
		return 1;
	}

	private static String tick(boolean unlocked) {
		return unlocked ? "<green>✓" : "<red>✗";
	}

	// ================================================================ controls

	/** {@code ControlsCommand}, {@code AbilityCommand} and {@code CooldownCommand}. */
	private void registerControlCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		add(dispatcher, Commands.literal("controls")
				.executes(ctx -> {
					controlsPanel(player(ctx.getSource()));
					return 1;
				})
				.then(Commands.literal("toggle").executes(ctx -> {
					toggleCommandMode(player(ctx.getSource()));
					return 1;
				}))
				.then(Commands.literal("commands").executes(ctx -> {
					toggleCommandMode(player(ctx.getSource()));
					return 1;
				}))
				.then(Commands.literal("default").executes(ctx -> {
					toggleCommandMode(player(ctx.getSource()));
					return 1;
				})));
		add(dispatcher, Commands.literal("ability1").executes(ctx -> ability(ctx.getSource(), false)));
		add(dispatcher, Commands.literal("ability2").executes(ctx -> ability(ctx.getSource(), true)));
		add(dispatcher, admin("cooldown")
				.executes(ctx -> clearCooldowns(ctx.getSource(), ctx.getSource().getPlayer()))
				.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> clearCooldowns(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))));
	}

	private void controlsPanel(ServerPlayer player) {
		Messaging.send(player, "<gold><bold>========== WEAPON CONTROLS ==========</bold></gold>");
		Messaging.send(player, "");
		Messaging.send(player, "<yellow><bold>Primary Ability:</bold></yellow>");
		Messaging.send(player, "<gray>Press <white>F</white> (Swap Hands) while holding weapon</gray>");
		Messaging.send(player, "");
		Messaging.send(player, "<yellow><bold>Secondary Ability:</bold></yellow>");
		Messaging.send(player, "<gray>Press <white>Shift + F</white> while holding weapon</gray>");
		Messaging.send(player, "");
		Messaging.send(player, "<yellow><bold>View Weapon Info:</bold></yellow>");
		Messaging.send(player, "<gray>Press <white>Shift + Left Click</white> (some weapons)</gray>");
		Messaging.send(player, "");
		Messaging.send(player, "<yellow><bold>Passive Abilities:</bold></yellow>");
		Messaging.send(player, "<gray>Active automatically while holding weapon</gray>");
		Messaging.send(player, "");
		Messaging.send(player, "<gold><bold>=====================================</bold></gold>");
	}

	private void toggleCommandMode(ServerPlayer player) {
		boolean enabled = this.mod.controls().toggleCommandMode(player);
		Messaging.send(player, enabled
				? "<yellow>Command mode <green>enabled<yellow>. Use <white>/ability1<yellow> and <white>/ability2<yellow> to fire your weapon's abilities."
				: "<yellow>Command mode <red>disabled<yellow>. Swap hands (F) and Shift+F work again.");
	}

	private int ability(CommandSourceStack source, boolean secondary) throws CommandSyntaxException {
		ServerPlayer player = player(source);
		if (!this.mod.controls().isCommandMode(player)) {
			Messaging.send(player, "<red>You're not in command mode. Run <yellow>/controls toggle</yellow> first.");
			return 1;
		}
		this.mod.abilities().activateFromCommand(player, secondary);
		return 1;
	}

	private int clearCooldowns(CommandSourceStack source, @Nullable ServerPlayer argument) throws CommandSyntaxException {
		ServerPlayer target;
		if (argument != null) {
			target = argument;
		} else {
			ServerPlayer sender = source.getPlayer();
			if (sender == null) {
				tell(source, "<red>Console must specify a player: /cooldown <player>");
				return 1;
			}
			target = sender;
		}
		this.mod.cooldowns().clearAllCooldowns(target);
		tell(source, "<green>Cleared all cooldowns for <yellow>" + target.getGameProfile().getName() + "</yellow>.");
		if (source.getPlayer() != target) {
			Messaging.send(target, "<green>All your cooldowns have been cleared by an admin.");
		}
		return 1;
	}

	// ================================================================== trust

	/** {@code TrustCommand}: a per-player list the ability code consults before hitting. */
	private void registerTrustCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		add(dispatcher, Commands.literal("trust")
				.executes(ctx -> {
					trustList(player(ctx.getSource()));
					return 1;
				})
				.then(Commands.argument("target", EntityArgument.player()).executes(ctx -> trust(ctx.getSource(),
						EntityArgument.getPlayer(ctx, "target")))));
		add(dispatcher, Commands.literal("untrust")
				.executes(ctx -> {
					tell(ctx.getSource(), "<red>Usage: /untrust <player>");
					return 1;
				})
				.then(Commands.argument("target", EntityArgument.player()).executes(ctx -> untrust(ctx.getSource(),
						EntityArgument.getPlayer(ctx, "target")))));
		add(dispatcher, Commands.literal("trustlist").executes(ctx -> {
			trustList(player(ctx.getSource()));
			return 1;
		}));
	}

	private int trust(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
		ServerPlayer player = player(source);
		if (target == player) {
			Messaging.send(player, "<red>You cannot trust yourself!");
			return 1;
		}
		Set<String> trusted = this.mod.store().player(player.getUUID()).trusted();
		String id = String.valueOf(target.getUUID());
		if (trusted.contains(id)) {
			Messaging.send(player, "<yellow>" + target.getGameProfile().getName() + " <red>is already trusted!");
			return 1;
		}
		trusted.add(id);
		this.mod.store().markDirty();
		Messaging.send(player, "<green>You now trust <yellow>" + target.getGameProfile().getName()
				+ "<green>! Your weapon abilities won't hit them.");
		Messaging.send(target, "<green>" + player.getGameProfile().getName()
				+ " <yellow>now trusts you! Their weapon abilities won't hit you.");
		return 1;
	}

	private int untrust(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
		ServerPlayer player = player(source);
		Set<String> trusted = this.mod.store().player(player.getUUID()).trusted();
		String id = String.valueOf(target.getUUID());
		if (!trusted.remove(id)) {
			Messaging.send(player, "<yellow>" + target.getGameProfile().getName() + " <red>is not trusted!");
			return 1;
		}
		this.mod.store().markDirty();
		Messaging.send(player, "<red>You no longer trust <yellow>" + target.getGameProfile().getName()
				+ "<red>. Your weapon abilities can now hit them.");
		Messaging.send(target, "<red>" + player.getGameProfile().getName() + " <yellow>no longer trusts you.");
		return 1;
	}

	private void trustList(ServerPlayer player) {
		Set<String> trusted = this.mod.store().player(player.getUUID()).trusted();
		if (trusted.isEmpty()) {
			Messaging.send(player, "<gray>You don't trust anyone. Use <yellow>/trust <player><gray> to add someone.");
			return;
		}
		Messaging.send(player, "<gold><bold>===== Trusted Players =====</bold></gold>");
		MinecraftServer server = player.getServer();
		for (String id : trusted) {
			UUID uuid = parseUuid(id);
			ServerPlayer online = uuid == null ? null : server.getPlayerList().getPlayer(uuid);
			// Bukkit asked its offline-player cache for the name; the port keeps the last
			// known name in the player record, which is the same information.
			String name = online != null ? online.getGameProfile().getName() : nameOf(uuid, id);
			Messaging.send(player, "<yellow>- " + name + " " + (online != null ? "<green>(online)" : "<gray>(offline)"));
		}
		Messaging.send(player, "<gray>Use <red>/untrust <player><gray> to remove someone.");
	}

	/** The stored name for an offline player, or the raw id when nothing was ever recorded. */
	private String nameOf(@Nullable UUID uuid, String fallback) {
		if (uuid == null) {
			return fallback;
		}
		String stored = this.mod.store().player(uuid).name();
		return stored == null || stored.isEmpty() ? fallback : stored;
	}

	@Nullable
	private static UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException malformed) {
			return null;
		}
	}

	// ================================================================== morph

	/** {@code MorphLockCommand}: a stored morph can be frozen for a number of casts. */
	private void registerMorphCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		add(dispatcher, Commands.literal("lock").executes(ctx -> morphLock(player(ctx.getSource()))));
		add(dispatcher, Commands.literal("unlock").executes(ctx -> morphUnlock(player(ctx.getSource()))));
	}

	private int morphLock(ServerPlayer player) {
		WandOfIllusionWeapon wand = weapon(player, "wandofillusion", WandOfIllusionWeapon.class);
		if (wand == null) {
			Messaging.send(player, "<red>The Wand of Illusion is not registered.");
			return 0;
		}
		if (!wand.hasStoredMorph(player)) {
			Messaging.send(player, "<red>You don't have a morph stored in your wand!");
			return 1;
		}
		if (wand.isMorphLocked(player)) {
			Messaging.send(player, "<yellow>Your morph is already locked! " + wand.getRemainingLockUses(player)
					+ " uses remaining.");
			return 1;
		}
		String mob = wand.getStoredMob(player);
		int uses = BOSS_MORPHS.contains(mob) ? 15 : COMMON_MORPHS.contains(mob) ? 3 : DEFAULT_LOCK_USES;
		wand.lockMorph(player, uses);
		Messaging.send(player, "<green>Morph locked for " + uses + " uses! Your stored mob won't change until you use it "
				+ uses + " times or /unlock.");
		return 1;
	}

	private int morphUnlock(ServerPlayer player) {
		WandOfIllusionWeapon wand = weapon(player, "wandofillusion", WandOfIllusionWeapon.class);
		if (wand == null) {
			Messaging.send(player, "<red>The Wand of Illusion is not registered.");
			return 0;
		}
		if (!wand.isMorphLocked(player)) {
			Messaging.send(player, "<red>Your morph is not locked!");
			return 1;
		}
		wand.unlockMorph(player);
		Messaging.send(player, "<green>Morph unlocked! You can now capture new mobs.");
		return 1;
	}

	// ================================================================= altars

	/** {@code AltarSpawnCommand}, {@code DestroyAltarsCommand}, {@code LockAltarsCommand}. */
	private void registerAltarCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		add(dispatcher, admin("altar")
				.executes(ctx -> {
					altarUsage(ctx.getSource());
					return 1;
				})
				.then(Commands.argument("name", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionFactory
								.suggest(this.mod.altars().altarNames(), builder))
						.executes(ctx -> spawnAltar(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
		// /altarspawn is the plugin's alias, /altars2 the Season 2 spelling.
		add(dispatcher, admin("altarspawn")
				.executes(ctx -> {
					altarUsage(ctx.getSource());
					return 1;
				})
				.then(Commands.argument("name", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionFactory
								.suggest(this.mod.altars().altarNames(), builder))
						.executes(ctx -> spawnAltar(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
		add(dispatcher, admin("altars2")
				.executes(ctx -> {
					altarUsage(ctx.getSource());
					return 1;
				})
				.then(Commands.argument("name", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionFactory
								.suggest(seasonTwoAltarNames(), builder))
						.executes(ctx -> spawnAltar(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
		add(dispatcher, admin("destroyaltars")
				.executes(ctx -> destroyAltars(ctx.getSource(), 10))
				.then(Commands.argument("scope", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionFactory
								.suggest(List.of("5", "10", "20", "50", "100", "500", "1000", "all", "allworlds"),
										builder))
						.executes(ctx -> destroyAltars(ctx.getSource(),
								StringArgumentType.getString(ctx, "scope")))));
		add(dispatcher, admin("lockaltars").executes(ctx -> {
			boolean locked = this.mod.altars().toggleLock();
			tell(ctx.getSource(), "<yellow>Altars are now " + (locked ? "<red>LOCKED" : "<green>UNLOCKED")
					+ "<yellow>. Toggle again with /lockaltars.");
			return 1;
		}));
		add(dispatcher, admin("contagionstop").executes(ctx -> {
			if (!this.mod.contagion().isSignalActive()) {
				tell(ctx.getSource(), "<yellow>No Contagion Signal ritual is active.");
				return 1;
			}
			this.mod.contagion().forceStop();
			tell(ctx.getSource(), "<green>Contagion Signal ritual force-stopped.");
			return 1;
		}));
	}

	private void altarUsage(CommandSourceStack source) {
		tell(source, "<red>Usage: /altarspawn <altar_name>");
		tell(source, "<gray>Available altars: <yellow>" + String.join(", ", this.mod.altars().altarNames()));
	}

	private List<String> seasonTwoAltarNames() {
		List<String> names = new ArrayList<>();
		for (AltarRegistry.Spec spec : this.mod.altarRegistry().all()) {
			if (spec.season() == 2) {
				names.add(spec.plainDisplay().toLowerCase(Locale.ROOT));
			}
		}
		return names;
	}

	private int spawnAltar(CommandSourceStack source, String name) throws CommandSyntaxException {
		ServerPlayer player = player(source);
		String key = Identity.normalise(name);
		AltarRegistry.Spec spec = this.mod.altarRegistry().byKey(key);
		if (spec == null) {
			spec = this.mod.altarRegistry().byDisplay(name);
		}
		if (spec == null) {
			spec = this.mod.altarRegistry().byDisplay(TextFx.strip(name));
		}
		if (spec == null) {
			tell(source, "<red>Unknown altar: " + name);
			tell(source, "<gray>Available altars: <yellow>" + String.join(", ", this.mod.altars().altarNames()));
			return 1;
		}
		if (!this.mod.altars().createAltar(player, spec)) {
			// createAltar reports why (no block in range), so this is not a silent failure.
			return 1;
		}
		String color = spec.color() == null ? "white" : spec.color();
		Messaging.send(player, "<green>Spawned <" + color + ">" + spec.plainDisplay() + "<green> altar!");
		return 1;
	}

	private int destroyAltars(CommandSourceStack source, int radius) throws CommandSyntaxException {
		return destroyAltars(source, String.valueOf(radius));
	}

	/** {@code DestroyAltarsCommand}: a radius sweep, one world, or every world. */
	private int destroyAltars(CommandSourceStack source, String scope) throws CommandSyntaxException {
		ServerPlayer player = player(source);
		MinecraftServer server = source.getServer();
		String arg = scope.toLowerCase(Locale.ROOT);
		if (arg.equals("all")) {
			ServerLevel level = player.serverLevel();
			tell(source, "<yellow>Scanning world '" + levelName(level) + "' for altars...");
			AltarManager.Sweep sweep = this.mod.altars().sweep(level, null, 0);
			tell(source, "<green>Destroyed ALL altars in world '" + levelName(level) + "':");
			report(source, sweep);
			return 1;
		}
		if (arg.equals("allworlds")) {
			tell(source, "<yellow>Scanning ALL worlds for altars...");
			int stands = 0;
			int legacyItems = 0;
			int displays = 0;
			int holograms = 0;
			int blocks = 0;
			List<ServerLevel> levels = new ArrayList<>();
			for (ServerLevel level : server.getAllLevels()) {
				levels.add(level);
				AltarManager.Sweep sweep = this.mod.altars().sweep(level, null, 0);
				stands += sweep.stands();
				legacyItems += sweep.legacyItems();
				displays += sweep.displays();
				holograms += sweep.holograms();
				blocks += sweep.blocks();
			}
			tell(source, "<green>Destroyed ALL altars across " + levels.size() + " worlds:");
			report(source, new AltarManager.Sweep(stands, legacyItems, displays, holograms, blocks));
			return 1;
		}
		int radius;
		try {
			radius = Integer.parseInt(arg);
		} catch (NumberFormatException notANumber) {
			tell(source, "<red>Invalid argument. Usage: /destroyaltars [radius|all|allworlds]");
			return 1;
		}
		if (radius < 1 || radius > 1000) {
			tell(source, "<red>Radius must be between 1 and 1000.");
			return 1;
		}
		BlockPos at = player.blockPosition();
		AltarManager.Sweep sweep = this.mod.altars().sweep(player.serverLevel(), at, radius);
		tell(source, "<green>Destroyed altars in " + radius + " block radius:");
		report(source, sweep);
		return 1;
	}

	private static void report(CommandSourceStack source, AltarManager.Sweep sweep) {
		tell(source, "<gray>- <white>" + sweep.stands() + "<gray> armor stands");
		tell(source, "<gray>- <white>" + sweep.legacyItems() + "<gray> floating items (legacy)");
		tell(source, "<gray>- <white>" + sweep.displays() + "<gray> item displays");
		tell(source, "<gray>- <white>" + sweep.holograms() + "<gray> hologram texts");
		tell(source, "<gray>- <white>" + sweep.blocks() + "<gray> altar blocks");
		tell(source, "<green>Total removed: <yellow>" + sweep.total());
	}

	private static String levelName(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	// ================================================================= trials

	/** {@code CopperTrialEvent}, {@code BingoEvent}, {@code CopperFragment} and the shards. */
	private void registerTrialCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		add(dispatcher, admin("coppertrial")
				.executes(ctx -> this.mod.trials().onCommandNoArgs(ctx.getSource()))
				.then(Commands.argument("trial", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionFactory
								.suggest(List.of("helmet", "boots", "leggings", "chestplate"), builder))
						.executes(ctx -> this.mod.trials().onCommand(ctx.getSource(),
								StringArgumentType.getString(ctx, "trial"), "start"))
						.then(Commands.argument("action", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionFactory
										.suggest(List.of("start", "stop", "status"), builder))
								.executes(ctx -> this.mod.trials().onCommand(ctx.getSource(),
										StringArgumentType.getString(ctx, "trial"),
										StringArgumentType.getString(ctx, "action"))))));
		// /hotpotato is the plugin's alias for the leggings (Copper Core) trial.
		add(dispatcher, admin("hotpotato")
				.executes(ctx -> this.mod.trials().onCommand(ctx.getSource(), "leggings", "start"))
				.then(Commands.argument("action", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionFactory
								.suggest(List.of("start", "stop", "status"), builder))
						.executes(ctx -> this.mod.trials().onCommand(ctx.getSource(), "leggings",
								StringArgumentType.getString(ctx, "action")))));
		add(dispatcher, Commands.literal("bingo")
				.executes(ctx -> this.mod.trials().bingo().onCommand(ctx.getSource(), null))
				.then(Commands.argument("action", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionFactory
								.suggest(List.of("start", "stop", "status", "tasks"), builder))
						.executes(ctx -> this.mod.trials().bingo().onCommand(ctx.getSource(),
								StringArgumentType.getString(ctx, "action")))));
		add(dispatcher, admin("chestplatetrial")
				.executes(ctx -> this.mod.trials().onCommand(ctx.getSource(), "chestplate", "start")));
	}

	// ================================================================= events

	/** {@code /bloodmoon}, the three Arc5 events and the PVP switch. */
	private void registerEventCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		add(dispatcher, admin("bloodmoon").executes(ctx -> {
			tell(ctx.getSource(), this.mod.bloodMoon().start());
			return 1;
		}));
		add(dispatcher, admin("deathmatch")
				.executes(ctx -> {
					tell(ctx.getSource(), "<gold>Usage: /deathmatch <start|stop|status>");
					return 1;
				})
				.then(Commands.literal("start").executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayer();
					if (player == null) {
						tell(ctx.getSource(), "<red>Must be a player to start deathmatch.");
						return 1;
					}
					if (this.mod.deathmatch().isRunning()) {
						tell(ctx.getSource(), "<red>Deathmatch is already running!");
						return 1;
					}
					tell(ctx.getSource(), this.mod.deathmatch().start(player));
					return 1;
				}))
				.then(Commands.literal("stop").executes(ctx -> {
					if (!this.mod.deathmatch().isRunning()) {
						tell(ctx.getSource(), "<red>No deathmatch is running.");
						return 1;
					}
					tell(ctx.getSource(), this.mod.deathmatch().stop());
					return 1;
				}))
				.then(Commands.literal("status").executes(ctx -> {
					tell(ctx.getSource(), this.mod.deathmatch().status());
					return 1;
				})));
		add(dispatcher, admin("nukezone")
				.executes(ctx -> {
					tell(ctx.getSource(), "<gold>Usage: /nukezone <start|stop|force|status>");
					return 1;
				})
				.then(Commands.literal("start").executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayer();
					if (player == null) {
						tell(ctx.getSource(), "<red>Must be a player to start the nuke zone.");
						return 1;
					}
					if (this.mod.nukeZone().isActive()) {
						tell(ctx.getSource(), "<red>Nuke Zone is already active!");
						return 1;
					}
					tell(ctx.getSource(), this.mod.nukeZone().start(player));
					return 1;
				}))
				.then(Commands.literal("stop").executes(ctx -> {
					if (!this.mod.nukeZone().isActive()) {
						tell(ctx.getSource(), "<red>Nuke Zone is not active.");
						return 1;
					}
					tell(ctx.getSource(), this.mod.nukeZone().stop());
					return 1;
				}))
				.then(Commands.literal("force").executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayer();
					if (player == null) {
						tell(ctx.getSource(), "<red>Must be a player to force a nuke.");
						return 1;
					}
					tell(ctx.getSource(), this.mod.nukeZone().force(player));
					return 1;
				}))
				.then(Commands.literal("status").executes(ctx -> {
					tell(ctx.getSource(), this.mod.nukeZone().status());
					return 1;
				})));
		add(dispatcher, admin("banzone")
				.executes(ctx -> {
					tell(ctx.getSource(), "<gold>Usage: /banzone <on|off|status>");
					return 1;
				})
				.then(Commands.literal("on").executes(ctx -> {
					if (this.mod.banZone().isActive()) {
						tell(ctx.getSource(), "<red>Ban zone is already active!");
					} else {
						this.mod.banZone().activate();
						tell(ctx.getSource(), "<green>Ban zone activated!");
					}
					return 1;
				}))
				.then(Commands.literal("off").executes(ctx -> {
					if (!this.mod.banZone().isActive()) {
						tell(ctx.getSource(), "<red>Ban zone is not active.");
					} else {
						this.mod.banZone().deactivate();
						tell(ctx.getSource(), "<red>Ban zone deactivated!");
					}
					return 1;
				}))
				.then(Commands.literal("status").executes(ctx -> {
					if (this.mod.banZone().isActive()) {
						tell(ctx.getSource(), "<yellow>Ban zone is active! "
								+ this.mod.banZone().getEliminated().size() + " eliminated.");
					} else {
						tell(ctx.getSource(), "<gray>Ban zone is not active.");
					}
					return 1;
				})));
		add(dispatcher, admin("pvp")
				.executes(ctx -> {
					announcePvp(ctx.getSource(), this.mod.protection().setPvpEnabled(null));
					return 1;
				})
				.then(Commands.argument("state", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionFactory
								.suggest(List.of("on", "off", "status"), builder))
						.executes(ctx -> pvp(ctx.getSource(), StringArgumentType.getString(ctx, "state")))));
	}

	private int pvp(CommandSourceStack source, String state) {
		String which = state.toLowerCase(Locale.ROOT);
		switch (which) {
			case "on", "enable", "true" -> announcePvp(source, this.mod.protection().setPvpEnabled(Boolean.TRUE));
			case "off", "disable", "false" -> announcePvp(source, this.mod.protection().setPvpEnabled(Boolean.FALSE));
			case "status" -> tell(source, "<gray>PVP is currently <bold>"
					+ (this.mod.protection().isPvpEnabled() ? "<green>ENABLED" : "<red>DISABLED"));
			default -> tell(source, "<red>Usage: /pvp [on|off|status]");
		}
		return 1;
	}

	/** {@code PvpToggleCommand} told the whole server, and told console too. */
	private void announcePvp(CommandSourceStack source, boolean enabled) {
		String line = "<dark_purple>[AltarSMP] <gray>PVP has been <bold>"
				+ (enabled ? "<green>ENABLED" : "<red>DISABLED") + "</bold><gray>!";
		Messaging.broadcast(source.getServer(), line);
		if (source.getPlayer() == null) {
			tell(source, line);
		}
	}

	// ============================================================== utilities

	/** Tab colours, the tooltip debugger, the blue-particle debug util and the config paths. */
	private void registerUtilityCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> tabColor = admin("tabcolor")
				.executes(ctx -> {
					tabUsage(ctx.getSource());
					return 1;
				})
				.then(Commands.argument("action", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionFactory
								.suggest(List.of("reset", "clear", "set"), builder))
						.executes(ctx -> {
							tabUsage(ctx.getSource());
							return 1;
						})
						.then(Commands.argument("target", EntityArgument.player())
								.executes(ctx -> tabColor(ctx.getSource(), StringArgumentType.getString(ctx, "action"),
										EntityArgument.getPlayer(ctx, "target"), null))
								.then(Commands.argument("color", StringArgumentType.word())
										.suggests((ctx, builder) -> suggest(
												List.of("&0", "&1", "&2", "&3", "&4", "&5", "&6", "&7", "&8", "&9",
														"&a", "&b", "&c", "&d", "&e", "&f"),
												builder))
										.executes(ctx -> tabColor(ctx.getSource(),
												StringArgumentType.getString(ctx, "action"),
												EntityArgument.getPlayer(ctx, "target"),
												StringArgumentType.getString(ctx, "color"))))));
		add(dispatcher, tabColor);
		// /tc is the plugin's alias.
		add(dispatcher, admin("tc").executes(ctx -> {
			tabUsage(ctx.getSource());
			return 1;
		}));

		add(dispatcher, admin("altartooltip")
				.executes(ctx -> tooltipDebug(ctx.getSource(), null))
				.then(Commands.literal("set")
						.then(Commands.argument("key", StringArgumentType.greedyString())
								.executes(ctx -> tooltipDebug(ctx.getSource(),
										StringArgumentType.getString(ctx, "key"))))));
		add(dispatcher, admin("blueparticle")
				.executes(ctx -> blueParticle(ctx.getSource(), 1, 4.0D, 18))
				.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
						.executes(ctx -> blueParticle(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count"),
								4.0D, 18))
						.then(Commands.argument("peak", DoubleArgumentType.doubleArg(0.1D, 64.0D))
								.executes(ctx -> blueParticle(ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "count"),
										DoubleArgumentType.getDouble(ctx, "peak"), 18))
								.then(Commands.argument("duration", IntegerArgumentType.integer(1, 600))
										.executes(ctx -> blueParticle(ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "count"),
												DoubleArgumentType.getDouble(ctx, "peak"),
												IntegerArgumentType.getInteger(ctx, "duration")))))));
		// ConfigCommand: the GUI never existed, the plugin pointed admins at config.yml.
		add(dispatcher, admin("altarconfig")
				.executes(ctx -> {
					configHelp(ctx.getSource());
					return 1;
				})
				.then(Commands.literal("reload").executes(ctx -> {
					reload(ctx.getSource());
					return 1;
				})));
		for (String alias : new String[]{"asmpconfig", "altarsmpconfig"}) {
			add(dispatcher, admin(alias)
					.executes(ctx -> {
						configHelp(ctx.getSource());
						return 1;
					})
					.then(Commands.literal("reload").executes(ctx -> {
						reload(ctx.getSource());
						return 1;
					})));
		}
		add(dispatcher, admin("altarsmps2reload").executes(ctx -> {
			reload(ctx.getSource());
			return 1;
		}));
		add(dispatcher, admin("s2reload").executes(ctx -> {
			reload(ctx.getSource());
			return 1;
		}));
	}

	private void configHelp(CommandSourceStack source) {
		tell(source, "<gold><bold>AltarSMP Config</bold></gold>");
		tell(source, "<gray>Edit <yellow>config/altarsmp/config.yml <gray>directly.");
		tell(source, "<gray>Then run <yellow>/altarconfig reload <gray>to apply changes.");
		tell(source, "<gray>Loaded from: <yellow>" + this.mod.config().sourceDescription());
	}

	private void tabUsage(CommandSourceStack source) {
		tell(source, "<yellow>Usage:");
		tell(source, "<gray>/tabcolor reset <player> - Reset tab color to default");
		tell(source, "<gray>/tabcolor set <player> <color> - Set tab color (e.g., &c for red)");
		tell(source, "<gray>/tabcolor clear <player> - Same as reset");
	}

	/**
	 * {@code TabColorCommand}. Paper exposed {@code setDisplayName}/{@code setPlayerListName};
	 * vanilla does not, so the colour is applied with a scoreboard team per colour, which is
	 * the vanilla mechanism that tints the name in the tab list and in chat.
	 */
	private int tabColor(CommandSourceStack source, String action, ServerPlayer target, @Nullable String color) {
		String which = action.toLowerCase(Locale.ROOT);
		Scoreboard scoreboard = source.getServer().getScoreboard();
		String name = target.getGameProfile().getName();
		if (which.equals("reset") || which.equals("clear")) {
			PlayerTeam current = scoreboard.getPlayersTeam(name);
			if (current != null && current.getName().startsWith(TAB_TEAM_PREFIX)) {
				scoreboard.removePlayerFromTeam(name, current);
				if (current.getPlayers().isEmpty()) {
					scoreboard.removePlayerTeam(current);
				}
			}
			tell(source, "<green>Reset tab color for " + name);
			Messaging.send(target, "<gray>Your tab color has been reset by an admin.");
			return 1;
		}
		if (!which.equals("set")) {
			tell(source, "<red>Unknown action: " + action);
			tell(source, "<gray>Use: reset, clear, or set");
			return 1;
		}
		if (color == null) {
			tell(source, "<red>Usage: /tabcolor set <player> <color>");
			tell(source, "<gray>Color examples: &c (red), &a (green), &b (aqua), &e (yellow), &f (white)");
			return 1;
		}
		ChatFormatting format = colorOf(color);
		if (format == null || !format.isColor()) {
			tell(source, "<red>Unknown color: " + color);
			tell(source, "<gray>Color examples: &c (red), &a (green), &b (aqua), &e (yellow), &f (white)");
			return 1;
		}
		String teamName = TAB_TEAM_PREFIX + format.getName();
		PlayerTeam team = scoreboard.getPlayerTeam(teamName);
		if (team == null) {
			team = scoreboard.addPlayerTeam(teamName);
		}
		team.setColor(format);
		team.setPlayerPrefix(net.minecraft.network.chat.Component.empty());
		team.setPlayerSuffix(net.minecraft.network.chat.Component.empty());
		scoreboard.addPlayerToTeam(name, team);
		tell(source, "<green>Set tab color for " + name + " to <" + format.getName() + ">this color");
		Messaging.send(target, "<gray>Your tab color has been changed by an admin.");
		return 1;
	}

	/** {@code ChatColor.translateAlternateColorCodes} plus plain colour names. */
	@Nullable
	private static ChatFormatting colorOf(String raw) {
		String value = raw.startsWith("&") || raw.startsWith("§") ? raw.substring(1) : raw;
		if (value.length() == 1) {
			return ChatFormatting.getByCode(value.charAt(0));
		}
		return ChatFormatting.getByName(value.toLowerCase(Locale.ROOT));
	}

	/** {@code TooltipDebugCommand}: what the held item actually carries, and a forced set. */
	private int tooltipDebug(CommandSourceStack source, @Nullable String key) throws CommandSyntaxException {
		ServerPlayer player = player(source);
		ItemStack stack = player.getMainHandItem();
		if (stack.isEmpty()) {
			Messaging.send(player, "<red>Hold an item in your main hand first.");
			return 1;
		}
		Messaging.send(player, "<gray><bold>===== Tooltip Debug =====");
		Messaging.send(player, "<gray>Material: <white>" + BuiltInRegistries.ITEM.getKey(stack.getItem()));
		Messaging.send(player, "<gray>Identity component: <white>" + Identity.idOf(stack));
		Messaging.send(player, "<gray>altar_weapon PDC: <white>"
				+ Identity.legacyString(stack, Identity.KEY_WEAPON, "null"));
		Messaging.send(player, "<gray>altar_armor PDC:  <white>"
				+ Identity.legacyString(stack, Identity.KEY_ARMOR, "null"));
		Identifier style = stack.get(DataComponents.TOOLTIP_STYLE);
		Messaging.send(player, "<gray>Current tooltip_style: <white>" + style);
		if (key == null) {
			Messaging.send(player, "<gray>Hint: <white>/altartooltip set <key><gray> to force-apply"
					+ " (e.g. bloodlust/bloodlust)");
			return 1;
		}
		Identifier forced = key.contains(":") ? Identifier.parse(key)
				: Identifier.fromNamespaceAndPath(ItemFactory.TOOLTIP_NAMESPACE, key);
		stack.set(DataComponents.TOOLTIP_STYLE, forced);
		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
		Messaging.send(player, "<green>Force-applied tooltip_style = <white>" + forced);
		return 1;
	}

	/** {@code BlueParticleCommand}: paper model 1, grown to {@code peak} then faded out. */
	private int blueParticle(CommandSourceStack source, int count, double peak, int duration)
			throws CommandSyntaxException {
		ServerPlayer player = player(source);
		ServerLevel level = player.serverLevel();
		Vec3 at = player.position().add(0.0D, 1.0D, 0.0D);
		if (count <= 1) {
			blueCircle(level, at, peak, duration);
		} else {
			for (int spawned = 0; spawned < count; spawned++) {
				blueCircle(level, at.add(offset(level), offset(level, -1.0D, 1.5D), offset(level)), peak, duration);
			}
		}
		Messaging.send(player, "<aqua>Spawned " + count + " blue circle(s) - peak=" + peak + " duration=" + duration
				+ "t");
		return 1;
	}

	private static double offset(ServerLevel level) {
		return offset(level, -1.5D, 1.5D);
	}

	private static double offset(ServerLevel level, double min, double max) {
		return min + (max - min) * level.getRandom().nextDouble();
	}

	/** {@code a/z.java#a(Plugin, Location, int, double, double, int)} - the interpolated circle. */
	private void blueCircle(ServerLevel level, Vec3 at, double peak, int duration) {
		int rise = Math.max(1, duration / 2);
		int fall = Math.max(1, duration - rise);
		ItemStack paper = new ItemStack(Items.PAPER);
		ItemFactory.applyModelData(paper, BLUE_CIRCLE_MODEL);
		Display.ItemDisplay display = Displays.floating(level, at, paper, 0.2F);
		Displays.tag(display, TAG_VFX);
		Displays.interpolate(display, 0, rise);
		this.mod.scheduler().later(() -> {
			if (!display.isRemoved()) {
				display.setTransformation(circleScale((float) peak));
			}
		}, 1L);
		this.mod.scheduler().later(() -> {
			if (display.isRemoved()) {
				return;
			}
			Displays.interpolate(display, 0, fall);
			display.setTransformation(circleScale(0.0F));
		}, rise + 1L);
		this.mod.scheduler().later(() -> Displays.remove(display), duration + 2L);
	}

	/** {@code a/z.java#a(float)}: the scale transform, offset so the circle stays centred. */
	private static Transformation circleScale(float size) {
		return new Transformation(new Vector3f(-size / 2.0F, -size / 2.0F, -size / 2.0F),
				new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F), new Vector3f(size, size, size),
				new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F));
	}

	// ===================================================== /recipes /legendaries

	/**
	 * {@code RecipesCommand} and {@code LegendariesGUI}: the two browsing windows. Neither is an
	 * admin command in the plugin - any player may look - and only the click that hands out an item
	 * checks for op, which the windows do themselves.
	 */
	private void registerContentGuis(CommandDispatcher<CommandSourceStack> dispatcher) {
		add(dispatcher, Commands.literal("recipes").executes(ctx -> {
			RecipesGui.open(player(ctx.getSource()));
			return 1;
		}));
		add(dispatcher, Commands.literal("legendaries").executes(ctx -> {
			LegendariesGui.open(player(ctx.getSource()), 1);
			return 1;
		}));
		// Season 2's browser refused to open while season 1 was loaded and pointed here instead;
		// the port opens the season 2 page directly, which is what that message promised.
		add(dispatcher, Commands.literal("legendaries2").executes(ctx -> {
			LegendariesGui.open(player(ctx.getSource()), 2);
			return 1;
		}));
	}

	// ================================================================ plumbing

	private void add(CommandDispatcher<CommandSourceStack> dispatcher,
			LiteralArgumentBuilder<CommandSourceStack> node) {
		dispatcher.register(node);
		this.registered++;
	}

	/**
	 * Bukkit's {@code isOp()} / {@code hasPermission("altarsmp.admin")}: every admin node in
	 * the plugin defaulted to op, which is vanilla's gamemaster command level.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
		return Commands.literal(name)
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
	}

	/**
	 * Bukkit's {@code TabCompleter} lists, as Brigadier suggestions. 26.x dropped
	 * {@code SharedSuggestionFactory#suggest(Iterable, SuggestionsBuilder)} - the remaining
	 * helpers only deal in registry resources - so the filtering lives here.
	 */
	private static CompletableFuture<Suggestions> suggest(Collection<String> options, SuggestionsBuilder builder) {
		for (String option : options) {
			builder.suggest(option);
		}
		return builder.buildFuture();
	}

	private static void tell(CommandSourceStack source, String markup) {
		source.sendSuccess(() -> Messaging.msg(markup), false);
	}

	/** {@code Bukkit}: "This command can only be used by players." */
	private static ServerPlayer player(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			throw CommandSourceStack.ERROR_NOT_PLAYER.create();
		}
		return player;
	}

	@Nullable
	private <T extends WeaponBehavior> T weapon(ServerPlayer player, String id, Class<T> type) {
		WeaponBehavior behavior = this.mod.weapons().get(id);
		if (behavior == null || !type.isInstance(behavior)) {
			return null;
		}
		return type.cast(behavior);
	}

	@Nullable
	private static ServerLevel levelOf(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	/** Every altar key, for the {@code /altar} suggestions. */
	public Collection<String> altarKeys() {
		Set<String> keys = new LinkedHashSet<>();
		for (AltarRegistry.Spec spec : this.mod.altarRegistry().all()) {
			keys.add(spec.key());
		}
		return keys;
	}

	/** Every command name this registrar owns, for the docs and the audit report. */
	public List<String> commandNames() {
		List<String> names = new ArrayList<>(Arrays.asList("altarsmp", "altartooltip", "lockaltars", "bloodlust",
				"boneblade", "vulcanscrossbow", "hyperion", "wandofillusion", "frostscythe", "crazyslots",
				"minorcrazyslots", "nightpiercer", "windweaver", "witherbone", "shadowblade", "pureblade",
				"earthgauntlet", "paladinbattleaxe", "cutlass", "palecrossbow", "contagionsignal", "eclipsesword",
				"knightfall", "knightfallmax", "striker", "nukelauncher", "echo", "fireslash", "copperhelmet",
				"copperchestplate", "copperleggings", "copperboots", "copperdiamondarmor", "copperpickaxe",
				"copperpickaxeupgrade", "wardenheart", "weaponhandle", "illusioncore", "hyperionshard",
				"nightpiercershard", "vulkanhead", "copperfragment", "chestplateshard", "paleshard", "vampire",
				"pale", "human", "setpale", "setpaleking", "setvampire", "setvampireking", "settruevampire",
				"clearperma", "removecurse", "sethuman", "removepermapale", "removepermahuman", "givepaleeffect",
				"altar", "altarspawn", "altars2", "destroyaltars", "contagionstop", "setbloodlust", "setkills",
				"setkillss", "bloodkills", "lock", "unlock", "controls", "ability1", "ability2", "cooldown", "trust",
				"untrust", "trustlist", "bingo", "hotpotato", "coppertrial", "chestplatetrial", "bloodmoon", "pvp",
				"tabcolor", "tc", "deathmatch", "nukezone", "banzone", "blueparticle", "altarconfig", "asmpconfig",
				"altarsmpconfig", "altarsmps2reload", "s2reload", "omen", "ancientblade", "withersymbiote",
				"tidebreaker", "dragonrend", "bowofdeceptionandlies", "soulinabottle", "fragmentofthesea",
				"dragonheart", "amethystpickaxe", "amethystaxe", "blackghastsaddle", "recipes", "legendaries",
				"legendaries2"));
		return names;
	}
}
