package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.AbilityTracker;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TickScheduler;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Wand of Illusion - port of {@code com.altarsmp.weapons.WandOfIllusionWeapon}.
 *
 * <p><b>Capture</b>: killing anything while holding the wand stores that creature
 * (type, scale and whether it was a baby) - or a killed player's name - in the
 * wand's own components, and rewrites the wand lore exactly as
 * {@code updateWandLore} did. Ender dragons, snow golems and mooshrooms cannot be
 * captured and play the conduit-deactivate refusal sound.</p>
 *
 * <p><b>Disguise</b>: right-click charges for
 * {@code abilities.wandofillusion.disguise_charge_ticks} (100) ticks with a
 * segmented purple boss bar, spiralling enchant particles, beacon/mirror/amethyst
 * chimes and a smokescreen, then morphs the holder: max-health and scale
 * attributes are swapped for the mob's, the mob's passive effects and hazard
 * tasks start ({@link MorphEffects}), and the disguise lasts
 * {@code morph_duration} seconds with a countdown bar that turns yellow under 30s
 * and red under 10s. Bat morphs never expire on their own.</p>
 *
 * <p><b>Visual</b>: LibsDisguises is not used (and is not available on Fabric).
 * Instead the port renders a real, invulnerable, no-AI, no-physics mob entity
 * locked to the holder's position and rotation while the holder itself is made
 * invisible through the vanilla invisibility flag - so other players genuinely
 * see the creature rather than a glowing outline, which was the plugin's
 * fallback when LibsDisguises was missing.</p>
 *
 * <p><b>Abilities</b>: Shift + right-click while disguised uses the mob's ability
 * ({@link MorphAbilities}); Shift + left-click (or Shift + F) drops the disguise
 * and starts the {@code disguise_cooldown}. Morphed holders cannot receive
 * Invisibility, melee hits apply the mob's on-hit extras, and arrow hits apply the
 * skeleton-family bonuses.</p>
 */
public final class WandOfIllusionWeapon implements WeaponBehavior {

	static final String KEY_DISGUISE = "wand_disguise";
	static final String KEY_MIRROR = "altarsmp_morph_mirror";
	/** Component keys standing in for the original's four {@code NamespacedKey}s. */
	static final String STORED_MOB = "altarsmp:wand_stored_mob";
	static final String STORED_PLAYER = "altarsmp:wand_stored_player";
	static final String STORED_SCALE = "altarsmp:wand_stored_scale";
	static final String STORED_BABY = "altarsmp:wand_stored_baby";

	private static final int SILVERFISH_LIMIT = 10;

	private final AltarSMPMod mod;
	private final MorphEffects effects;
	private final MorphAbilities abilities;

	private final Map<UUID, String> disguisedPlayers = new HashMap<>();
	private final Map<UUID, String> storedPlayerNames = new HashMap<>();
	private final Map<UUID, Double> originalMaxHealth = new HashMap<>();
	private final Map<UUID, Double> originalScale = new HashMap<>();
	private final Map<UUID, List<TickScheduler.Task>> activeMorphTasks = new HashMap<>();
	private final Map<UUID, Collection<MobEffectInstance>> savedPotionEffects = new HashMap<>();
	private final Map<UUID, Boolean> beeStingReady = new HashMap<>();
	private final Map<UUID, Integer> morphLockUses = new HashMap<>();
	private final Map<UUID, Entity> morphVisuals = new HashMap<>();
	private final Map<UUID, TickScheduler.Task> chargingTasks = new HashMap<>();
	private final Map<UUID, ServerBossEvent> chargeBars = new HashMap<>();
	private final Map<UUID, ServerBossEvent> durationBars = new HashMap<>();
	private final Map<UUID, List<Entity>> silverfishSpawns = new HashMap<>();
	private final java.util.Set<UUID> batMorphPlayers = new java.util.HashSet<>();
	private final java.util.Set<UUID> blazeMorphPlayers = new java.util.HashSet<>();
	private final java.util.Set<UUID> horseMorphPlayers = new java.util.HashSet<>();

	public WandOfIllusionWeapon(AltarSMPMod mod) {
		this.mod = mod;
		this.effects = new MorphEffects(mod, this);
		this.abilities = new MorphAbilities(mod, this);
	}

	@Override
	public String id() {
		return "wandofillusion";
	}

	@Override
	public String displayName() {
		return "Wand of Illusion";
	}

	@Override
	public List<String> configFields() {
		return List.of("Disguise Cooldown (s)", "Ability Cooldown (s)", "Morph Duration (s)",
				"Disguise Charge (ticks)", "Extras Enabled");
	}

	// ------------------------------------------------------------------ capture

	@Override
	public void onKill(AbilityContext ctx, LivingEntity victim) {
		ServerPlayer player = ctx.player();
		ItemStack wand = ctx.weapon();
		if (!Identity.is(wand, id())) {
			return;
		}
		if (isMorphLocked(player)) {
			Messaging.send(player, "<light_purple>[Wand of Illusion] <yellow>Morph is locked! Use /unlock to capture new mobs.");
			return;
		}
		if (victim instanceof ServerPlayer killed) {
			String name = killed.getGameProfile().getName();
			Identity.setStateString(wand, STORED_PLAYER, name);
			Identity.setStateString(wand, STORED_MOB, "PLAYER");
			this.storedPlayerNames.put(player.getUUID(), name);
			Messaging.send(player, "<light_purple>[Wand of Illusion] <gray>Captured <white>" + name + "<gray>!");
			updateWandLore(wand, "PLAYER", name);
			captureFx(ctx);
			return;
		}
		EntityType<?> type = victim.getType();
		if (isUncapturable(type)) {
			Fx.sound(ctx.level(), player.position(), "BLOCK_CONDUIT_DEACTIVATE", 1.0F, 0.0F);
			Messaging.send(player, "<light_purple>[Wand of Illusion] <red>That creature cannot be captured.");
			return;
		}
		String stored = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
		Identity.setStateString(wand, STORED_MOB, stored);
		Identity.removeState(wand, STORED_PLAYER);
		double scale = 1.0D;
		AttributeInstance scaleAttribute = victim.getAttribute(Attributes.SCALE);
		if (scaleAttribute != null) {
			scale = scaleAttribute.getBaseValue();
		}
		Identity.setStateString(wand, STORED_SCALE, String.valueOf(scale));
		boolean baby = victim instanceof AgeableMob ageable && !ageable.isBaby();
		Identity.setStateBool(wand, STORED_BABY, baby);
		Messaging.send(player, "<light_purple>[Wand of Illusion] <gray>Captured <yellow>"
				+ (baby ? "Baby " : "") + MorphEffects.formatMobName(type) + "<gray>!");
		updateWandLore(wand, stored, null);
		captureFx(ctx);
	}

	private void captureFx(AbilityContext ctx) {
		Fx.sound(ctx.level(), ctx.player().position(), "ENTITY_ILLUSIONER_CAST_SPELL", 1.0F, 1.2F);
		Fx.simple(ctx.level(), "TOTEM_OF_UNDYING", ctx.player().position().add(0.0D, 1.0D, 0.0D),
				30, 0.5D, 0.5D, 0.5D, 0.1D);
	}

	/** {@code WandOfIllusionWeapon#isUncapturable}. */
	static boolean isUncapturable(EntityType<?> type) {
		return type == EntityType.ENDER_DRAGON || type == EntityType.SNOW_GOLEM || type == EntityType.MOOSHROOM;
	}

	/** {@code WandOfIllusionWeapon#updateWandLore} - the full instruction block. */
	void updateWandLore(ItemStack wand, String storedMob, String playerName) {
		String stored = "PLAYER".equals(storedMob) && playerName != null
				? "<white>" + playerName + " <gray>(Player)"
				: "<yellow>" + displayFor(storedMob);
		List<String> lines = List.of(
				"<gray>When you kill an enemy with the wand,",
				"<gray>you capture the mob and gain the ability",
				"<gray>to disguise as it, capturing its attributes",
				"<gray>and abilities.",
				"",
				"<gold>Holding Right-Click",
				"<gray>Disguises you as the mob/player that is currently",
				"<gray>stored in the wand.",
				"",
				"<green>Shift-Right-Clicking while disguised",
				"<gray>Uses the mob's ability if applicable.",
				"",
				"<red>Shift-Left-Clicking",
				"<gray>Removes your disguise, returning you",
				"<gray>back to normal.",
				"",
				"<yellow>On Mob Kill",
				"<gray>Stores the mob's attributes and abilities",
				"<gray>in the wand.",
				"",
				"<yellow>On Player Kill",
				"<gray>Stores the name and skin of the player",
				"<gray>in the wand, making you look identical",
				"<gray>to the player you killed when disguising.",
				"",
				"<dark_gray>Stored: " + stored,
				"",
				"<blue>When in Main Hand:",
				"<red> 8 Attack Damage",
				"<red> 1.6 Attack Speed",
				"<green>+0.75 Sweeping Damage Ratio",
				"<blue>Unbreakable");
		List<Component> lore = new ArrayList<>();
		for (String line : lines) {
			lore.add(Messaging.msg(line));
		}
		wand.set(net.minecraft.core.component.DataComponents.LORE,
				new net.minecraft.world.item.component.ItemLore(lore));
	}

	private static String displayFor(String storedMob) {
		EntityType<?> type = MorphEffects.typeOf(storedMob);
		return type == null ? String.valueOf(storedMob) : MorphEffects.formatMobName(type);
	}

	// --------------------------------------------------------------- activation

	@Override
	public boolean onUse(AbilityContext ctx, net.minecraft.world.InteractionHand hand) {
		ServerPlayer player = ctx.player();
		if (hand != net.minecraft.world.InteractionHand.MAIN_HAND || !Identity.is(ctx.weapon(), id())) {
			return false;
		}
		if (player.isShiftKeyDown()) {
			if (isDisguised(player) && areExtrasEnabled(ctx)) {
				this.abilities.use(ctx, disguiseOf(player));
			}
			return true;
		}
		if (!isDisguised(player)) {
			startDisguiseCharge(ctx);
			return true;
		}
		return false;
	}

	@Override
	public boolean onUseBlock(AbilityContext ctx, BlockPos pos, Direction direction) {
		return silverfishSpawn(ctx, pos);
	}

	@Override
	public boolean onAttackBlock(AbilityContext ctx, BlockPos pos) {
		ServerPlayer player = ctx.player();
		if (player.isShiftKeyDown() && isDisguised(player)) {
			removeDisguise(ctx, player);
			return true;
		}
		return false;
	}

	/** {@code WandOfIllusionWeapon#onSwapHands} - F cancels an active charge. */
	@Override
	public void onPrimary(AbilityContext ctx) {
		if (this.chargingTasks.containsKey(ctx.player().getUUID())) {
			cancelDisguiseCharge(ctx.player());
		}
	}

	/** Shift+F removes the disguise (the plugin bound it to shift-left-click). */
	@Override
	public void onSecondary(AbilityContext ctx) {
		if (isDisguised(ctx.player())) {
			removeDisguise(ctx, ctx.player());
		} else {
			Messaging.send(ctx.player(), "<light_purple>[Wand of Illusion] <red>You are not disguised.");
		}
	}

	// ------------------------------------------------------------------- charge

	/** {@code WandOfIllusionWeapon#startDisguiseCharge}. */
	private void startDisguiseCharge(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		if (this.chargingTasks.containsKey(player.getUUID())) {
			return;
		}
		ItemStack wand = player.getMainHandItem();
		String stored = Identity.stateString(wand, STORED_MOB, "");
		if (stored.isBlank()) {
			Messaging.send(player, "<light_purple>[Wand of Illusion] <red>No creature stored! Kill something first.");
			return;
		}
		if (ctx.mod().cooldowns().isOnCooldown(player, KEY_DISGUISE)) {
			Messaging.actionBar(player, "<light_purple>Disguise recharging: <yellow>" + ctx.remaining(KEY_DISGUISE));
			return;
		}
		double scale = parseScale(Identity.stateString(wand, STORED_SCALE, "1.0"));
		boolean baby = Identity.stateBool(wand, STORED_BABY, false);
		String label = "PLAYER".equals(stored)
				? Identity.stateString(wand, STORED_PLAYER, "Unknown Player")
				: (baby ? "Baby " : "") + displayFor(stored);

		ServerBossEvent bar = Messaging.bossBar("<light_purple>Transforming into <gold>" + label + "<light_purple>...",
				BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);
		bar.addPlayer(player);
		bar.setProgress(0.0F);
		this.chargeBars.put(player.getUUID(), bar);

		int chargeTicks = Math.max(1, ctx.cfg("abilities.wandofillusion.disguise_charge_ticks", 100));
		final int[] tick = {0};
		TickScheduler.Task task = this.mod.scheduler().timer(() -> {
			if (player.isRemoved() || !holdingWand(player)) {
				cancelDisguiseCharge(player);
				return;
			}
			tick[0]++;
			double progress = Math.min(1.0D, (double) tick[0] / chargeTicks);
			bar.setProgress((float) progress);
			if (progress >= 1.0D) {
				bar.setName(Messaging.msg("<green><bold>TRANSFORMING!"));
				bar.setColor(BossEvent.BossBarColor.GREEN);
			} else if (progress >= 0.75D) {
				bar.setColor(BossEvent.BossBarColor.YELLOW);
			}

			double lift = 0.5D + progress * 1.5D;
			Vec3 center = player.position().add(0.0D, lift, 0.0D);
			Fx.simple(ctx.level(), "WITCH", center.add(Math.cos(tick[0] * 0.3D) * 0.5D, 0.0D,
					Math.sin(tick[0] * 0.3D) * 0.5D), 2, 0.1D, 0.1D, 0.1D, 0.0D);
			if (tick[0] % 2 == 0) {
				double angle = tick[0] * 0.2D;
				double radius = 0.3D + progress * 0.3D;
				Fx.simple(ctx.level(), "ENCHANT", player.position().add(Math.cos(angle) * radius, lift,
						Math.sin(angle) * radius), 3, 0.05D, 0.05D, 0.05D, 0.0D);
			}
			if (tick[0] % 20 == 0) {
				float pitch = 0.5F + (float) progress;
				Fx.soundTo(player, "BLOCK_BEACON_AMBIENT", 0.5F, pitch);
				Fx.soundTo(player, "ENTITY_ILLUSIONER_PREPARE_MIRROR", 0.3F, pitch);
			}
			if (tick[0] % 5 == 0) {
				Fx.soundTo(player, "BLOCK_AMETHYST_BLOCK_CHIME", 0.2F, 0.8F + (float) (progress * 0.7D));
			}
			if (tick[0] >= chargeTicks) {
				completeDisguise(ctx, stored, label, scale, baby);
				this.chargingTasks.remove(player.getUUID());
				ServerBossEvent done = this.chargeBars.remove(player.getUUID());
				if (done != null) {
					done.removeAllPlayers();
				}
			}
		}, 0L, 1L);
		this.chargingTasks.put(player.getUUID(), task);
		Fx.sound(ctx.level(), player.position(), "ENTITY_ILLUSIONER_CAST_SPELL", 0.7F, 0.5F);
	}

	/** {@code WandOfIllusionWeapon#cancelDisguiseCharge}. */
	void cancelDisguiseCharge(ServerPlayer player) {
		TickScheduler.Task task = this.chargingTasks.remove(player.getUUID());
		if (task != null) {
			task.cancel();
			Fx.sound(player.serverLevel(), player.position(), "ENTITY_ILLUSIONER_MIRROR_MOVE", 0.5F, 0.5F);
		}
		ServerBossEvent bar = this.chargeBars.remove(player.getUUID());
		if (bar != null) {
			bar.removeAllPlayers();
		}
	}

	// ------------------------------------------------------------------ disguise

	/** {@code WandOfIllusionWeapon#completeDisguise}. */
	void completeDisguise(AbilityContext ctx, String stored, String label, double scale, boolean baby) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		this.savedPotionEffects.put(player.getUUID(), new ArrayList<>(player.getActiveEffects()));
		this.disguisedPlayers.put(player.getUUID(), stored);
		record(player).morphEntityType(stored);
		record(player).morphCustomName(label);
		record(player).morphBaby(baby);
		record(player).morphScale((float) scale);
		this.mod.store().markDirty();
		if (isMorphLocked(player) && !decrementLockUse(player)) {
			Messaging.send(player, "<light_purple>[Wand of Illusion] <yellow>Morph lock: "
					+ getRemainingLockUses(player) + " uses remaining.");
		}
		AbilityTracker.setMorphed(player, true);
		Effects.remove(player, "INVISIBILITY");
		Fx.sound(level, player.position(), "ENTITY_ILLUSIONER_PREPARE_BLINDNESS", 1.0F, 1.0F);
		Fx.sound(level, player.position(), "ENTITY_WITHER_SPAWN", 0.3F, 1.5F);
		Fx.sound(level, player.position(), "BLOCK_END_PORTAL_SPAWN", 0.5F, 1.2F);
		createSmokescreen(level, player.position());
		Messaging.title(player, "<green><bold>Disguised as:",
				("PLAYER".equals(stored) ? "<white>" : "<gold>") + label, 4, 40, 10);

		if ("PLAYER".equals(stored)) {
			String captured = Identity.stateString(player.getMainHandItem(), STORED_PLAYER, label);
			this.storedPlayerNames.putIfAbsent(player.getUUID(), captured);
			player.setCustomName(Component.literal(captured));
			player.setCustomNameVisible(true);
		} else {
			EntityType<?> type = MorphEffects.typeOf(stored);
			if (type != null) {
				if (areExtrasEnabled(ctx)) {
					List<TickScheduler.Task> tasks = this.effects.apply(ctx, player, type, scale, baby);
					tasks.removeIf(java.util.Objects::isNull);
					this.activeMorphTasks.put(player.getUUID(), tasks);
				}
				spawnMirror(ctx, type, scale, baby);
			}
		}

		Fx.simple(level, "REVERSE_PORTAL", player.position().add(0.0D, 1.0D, 0.0D), 50, 0.5D, 1.0D, 0.5D, 0.1D);
		Fx.simple(level, "PORTAL", player.position().add(0.0D, 1.0D, 0.0D), 100, 1.0D, 1.0D, 1.0D, 0.2D);

		boolean permanent = "bat".equalsIgnoreCase(stored);
		if (!isMorphLocked(player) && !permanent) {
			startDurationBar(ctx, label);
		}
	}

	/** The native stand-in for LibsDisguises: a real mob locked to the holder. */
	private void spawnMirror(AbilityContext ctx, EntityType<?> type, double scale, boolean baby) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		removeMirror(player);
		Entity mirror = type.create(level);
		if (mirror == null) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] could not render morph visual for {}", type);
			return;
		}
		mirror.snapTo(player.position().x, player.position().y, player.position().z,
				player.getYRot(), player.getXRot());
		if (mirror instanceof Mob mob) {
			mob.setNoAi(true);
			mob.setSilent(true);
			mob.setPersistenceRequired();
			mob.setRemoveWhenFarAway(false);
			if (baby && mob instanceof AgeableMob ageable) {
				ageable.setBaby(true);
			}
		}
		mirror.setInvulnerable(true);
		mirror.noPhysics = true;
		mirror.setCustomNameVisible(false);
		AttributeInstance mirrorScale = mirror instanceof LivingEntity living
				? living.getAttribute(Attributes.SCALE) : null;
		if (mirrorScale != null) {
			mirrorScale.setBaseValue(scale);
		}
		Displays.tag(mirror, KEY_MIRROR + "_" + player.getUUID());
		level.addFreshEntity(mirror);
		this.morphVisuals.put(player.getUUID(), mirror);
		player.setInvisible(true);
	}

	private void removeMirror(ServerPlayer player) {
		Entity mirror = this.morphVisuals.remove(player.getUUID());
		if (mirror != null && !mirror.isRemoved()) {
			mirror.discard();
		}
		Displays.removeTagged(player.serverLevel(), KEY_MIRROR + "_" + player.getUUID());
		player.setInvisible(false);
	}

	/** {@code WandOfIllusionWeapon#createSmokescreen}. */
	private void createSmokescreen(ServerLevel level, Vec3 at) {
		final int[] tick = {0};
		this.mod.scheduler().timer(() -> {
			if (tick[0]++ >= 40) {
				return;
			}
			for (int index = 0; index < 15; index++) {
				Vec3 point = at.add((Math.random() - 0.5D) * 6.0D, Math.random() * 3.0D,
						(Math.random() - 0.5D) * 6.0D);
				Fx.simple(level, "LARGE_SMOKE", point, 1, 0.3D, 0.3D, 0.3D, 0.02D);
				Fx.simple(level, "SMOKE", point, 1, 0.2D, 0.2D, 0.2D, 0.01D);
			}
			Fx.simple(level, "SQUID_INK", at.add(0.0D, 1.0D, 0.0D), 5, 1.5D, 1.0D, 1.5D, 0.05D);
			if (tick[0] % 10 == 0) {
				Fx.simple(level, "FLASH", at.add(0.0D, 1.0D, 0.0D), 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}, 0L, 1L).cancelAfter(45L);
	}

	/** The morph countdown bar, including the yellow/red warning states. */
	private void startDurationBar(AbilityContext ctx, String label) {
		ServerPlayer player = ctx.player();
		int seconds = Math.max(1, ctx.cfg("abilities.wandofillusion.morph_duration", 90));
		ServerBossEvent bar = Messaging.bossBar("<light_purple>Disguised as <gold>" + label,
				BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
		bar.addPlayer(player);
		bar.setProgress(1.0F);
		ServerBossEvent previous = this.durationBars.put(player.getUUID(), bar);
		if (previous != null) {
			previous.removeAllPlayers();
		}
		final double[] remaining = {seconds};
		this.mod.scheduler().timer(() -> {
			if (!isDisguised(player) || player.isRemoved() || isMorphLocked(player)) {
				bar.removeAllPlayers();
				this.durationBars.remove(player.getUUID(), bar);
				return;
			}
			if (remaining[0] <= 0.0D) {
				bar.removeAllPlayers();
				this.durationBars.remove(player.getUUID(), bar);
				removeDisguise(ctx, player);
				return;
			}
			bar.setProgress((float) Math.max(0.0D, Math.min(1.0D, remaining[0] / seconds)));
			int whole = (int) remaining[0];
			if (remaining[0] <= 10.0D) {
				bar.setColor(BossEvent.BossBarColor.RED);
				bar.setName(Messaging.msg("<red>Disguise fading! <gold>" + whole + "s"));
			} else if (remaining[0] <= 30.0D) {
				bar.setColor(BossEvent.BossBarColor.YELLOW);
				bar.setName(Messaging.msg("<yellow>Disguised as <gold>" + label + "<yellow> - " + whole + "s"));
			} else {
				bar.setName(Messaging.msg("<light_purple>Disguised as <gold>" + label
						+ "<light_purple> - " + whole + "s"));
			}
			remaining[0] -= 0.1D;
		}, 0L, 2L);
	}

	/** {@code WandOfIllusionWeapon#removeDisguise}. */
	public void removeDisguise(AbilityContext ctx, ServerPlayer player) {
		String stored = this.disguisedPlayers.remove(player.getUUID());
		if (stored == null) {
			return;
		}
		ServerLevel level = player.serverLevel();
		record(player).morphEntityType("");
		record(player).morphCustomName("");
		record(player).morphBaby(false);
		record(player).morphScale(1.0F);
		this.mod.store().markDirty();
		AbilityTracker.setMorphed(player, false);
		AbilityTracker.setMorphFlight(player, false);
		ctx.mod().cooldowns().setCooldownSeconds(player, KEY_DISGUISE,
				ctx.cfg("abilities.wandofillusion.disguise_cooldown", 60));
		CooldownBars.show(player, KEY_DISGUISE, "Disguise", BossEvent.BossBarColor.YELLOW,
				ctx.cfg("abilities.wandofillusion.disguise_cooldown", 60));

		player.setCustomName(null);
		player.setCustomNameVisible(false);
		removeMirror(player);
		ServerBossEvent bar = this.durationBars.remove(player.getUUID());
		if (bar != null) {
			bar.removeAllPlayers();
		}

		List<TickScheduler.Task> tasks = this.activeMorphTasks.remove(player.getUUID());
		if (tasks != null) {
			tasks.forEach(TickScheduler.Task::cancel);
		}

		Double health = this.originalMaxHealth.remove(player.getUUID());
		if (health != null) {
			AttributeInstance instance = player.getAttribute(Attributes.MAX_HEALTH);
			if (instance != null) {
				instance.setBaseValue(health);
				player.setHealth((float) health.doubleValue());
			}
		}
		Double scale = this.originalScale.remove(player.getUUID());
		if (scale != null) {
			AttributeInstance instance = player.getAttribute(Attributes.SCALE);
			if (instance != null) {
				instance.setBaseValue(scale);
			}
		}
		MorphEffects.disableFlight(player);

		for (String effect : new String[]{"SPEED", "STRENGTH", "RESISTANCE", "FIRE_RESISTANCE", "WATER_BREATHING",
				"SLOWNESS", "NIGHT_VISION", "SLOW_FALLING", "DARKNESS", "DOLPHINS_GRACE", "JUMP_BOOST"}) {
			Effects.remove(player, effect);
		}
		this.beeStingReady.remove(player.getUUID());
		this.batMorphPlayers.remove(player.getUUID());
		this.blazeMorphPlayers.remove(player.getUUID());
		this.horseMorphPlayers.remove(player.getUUID());
		List<Entity> silverfish = this.silverfishSpawns.remove(player.getUUID());
		if (silverfish != null) {
			silverfish.stream().filter(entity -> !entity.isRemoved()).forEach(Entity::discard);
		}
		Collection<MobEffectInstance> saved = this.savedPotionEffects.remove(player.getUUID());
		if (saved != null) {
			for (MobEffectInstance instance : saved) {
				if (instance.isInfiniteDuration() || instance.getDuration() > 0) {
					player.addEffect(new MobEffectInstance(instance));
				}
			}
		}
		Fx.sound(level, player.position(), "ENTITY_ILLUSIONER_MIRROR_MOVE", 1.0F, 0.5F);
		Fx.simple(level, "SMOKE", player.position().add(0.0D, 1.0D, 0.0D), 30, 0.5D, 1.0D, 0.5D, 0.05D);
	}

	// ---------------------------------------------------------------- silverfish

	/** {@code WandOfIllusionWeapon#onSilverfishSpawn}. */
	private boolean silverfishSpawn(AbilityContext ctx, BlockPos pos) {
		ServerPlayer player = ctx.player();
		if (!isDisguised(player) || !areExtrasEnabled(ctx) || !"silverfish".equals(disguiseOf(player))) {
			return false;
		}
		BlockState state = ctx.level().getBlockState(pos);
		boolean stone = state.is(net.minecraft.world.level.block.Blocks.STONE)
				|| state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)
				|| state.is(net.minecraft.world.level.block.Blocks.STONE_BRICKS)
				|| state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE);
		if (!stone) {
			return false;
		}
		List<Entity> spawned = this.silverfishSpawns.computeIfAbsent(player.getUUID(), key -> new ArrayList<>());
		spawned.removeIf(Entity::isRemoved);
		if (spawned.size() >= SILVERFISH_LIMIT) {
			Messaging.actionBar(player, "<gray>You already command " + SILVERFISH_LIMIT + " silverfish.");
			return true;
		}
		Vec3 at = Vec3.atBottomCenterOf(pos).add(0.0D, 1.0D, 0.0D);
		Entity silverfish = MorphEffects.spawn(ctx.level(), EntityType.SILVERFISH, at);
		if (silverfish != null) {
			if (silverfish instanceof Mob mob) {
				mob.setPersistenceRequired();
			}
			spawned.add(silverfish);
			Fx.sound(ctx.level(), at, "ENTITY_SILVERFISH_AMBIENT", 1.0F, 1.0F);
		}
		return true;
	}

	// -------------------------------------------------------------------- combat

	@Override
	public void onAttack(AbilityContext ctx, LivingEntity target, float damage, boolean critical) {
		if (isDisguised(ctx.player()) && areExtrasEnabled(ctx)) {
			this.abilities.onMeleeHit(ctx, target);
		}
	}

	@Override
	public boolean onDamaged(AbilityContext ctx, net.minecraft.world.damagesource.DamageSource source,
			float amount, LivingEntity attacker) {
		// Bat, horse and blaze morphs cannot fight back; the original cancelled
		// their outgoing hits, which is enforced in onAttack/onMeleeHit instead.
		return false;
	}

	@Override
	public void onProjectileHit(AbilityContext ctx, Entity projectile, Entity hit) {
		if (isDisguised(ctx.player()) && areExtrasEnabled(ctx) && hit instanceof LivingEntity victim
				&& !this.blazeMorphPlayers.contains(ctx.player().getUUID())) {
			this.abilities.onArrowHit(ctx, victim,
					projectile instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow);
		}
	}

	/** {@code WandOfIllusionWeapon#onPlayerInteractEntity} - horse morphs are rideable. */
	public boolean onInteractEntity(ServerPlayer interactor, Entity target) {
		if (!areExtrasEnabledStatic() || !(target instanceof ServerPlayer ridden)) {
			return false;
		}
		if (!this.horseMorphPlayers.contains(ridden.getUUID()) || interactor == ridden
				|| !ridden.getPassengers().isEmpty()) {
			return false;
		}
		interactor.startRiding(ridden);
		Fx.sound(ridden.serverLevel(), ridden.position(), "ENTITY_HORSE_SADDLE", 1.0F, 1.0F);
		return true;
	}

	private boolean areExtrasEnabledStatic() {
		return this.mod.config().getBoolean("abilities.wandofillusion.extras_enabled", true);
	}

	boolean areExtrasEnabled(AbilityContext ctx) {
		return ctx.cfgb("abilities.wandofillusion.extras_enabled", true);
	}

	// --------------------------------------------------------------------- tick

	@Override
	public void onTick(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		if (!isDisguised(player)) {
			return;
		}
		// The plugin cancelled any Invisibility applied to a morphed player.
		if (player.hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY)
				&& this.morphVisuals.containsKey(player.getUUID())) {
			Effects.remove(player, "INVISIBILITY");
		}
		Entity mirror = this.morphVisuals.get(player.getUUID());
		if (mirror != null) {
			if (mirror.isRemoved()) {
				this.morphVisuals.remove(player.getUUID());
				player.setInvisible(false);
			} else {
				Vec3 at = player.position();
				mirror.snapTo(at.x, at.y, at.z, player.getYRot(), player.getXRot());
				mirror.setDeltaMovement(player.getDeltaMovement());
				if (mirror instanceof Mob mob) {
					mob.setYBodyRot(player.getYRot());
					mob.yHeadRot = player.getYRot();
				}
			}
		}
	}

	// ------------------------------------------------------------------ state API

	public boolean isDisguised(ServerPlayer player) {
		return this.disguisedPlayers.containsKey(player.getUUID());
	}

	public String disguiseOf(ServerPlayer player) {
		return this.disguisedPlayers.get(player.getUUID());
	}

	public boolean isBatMorph(ServerPlayer player) {
		return this.batMorphPlayers.contains(player.getUUID());
	}

	public boolean isBlazeMorph(ServerPlayer player) {
		return this.blazeMorphPlayers.contains(player.getUUID());
	}

	public boolean isHorseMorph(ServerPlayer player) {
		return this.horseMorphPlayers.contains(player.getUUID());
	}

	void markBatMorph(ServerPlayer player) {
		this.batMorphPlayers.add(player.getUUID());
	}

	void markBlazeMorph(ServerPlayer player) {
		this.blazeMorphPlayers.add(player.getUUID());
	}

	void markHorseMorph(ServerPlayer player) {
		this.horseMorphPlayers.add(player.getUUID());
	}

	void armSting(ServerPlayer player) {
		this.beeStingReady.put(player.getUUID(), Boolean.TRUE);
	}

	boolean consumeSting(ServerPlayer player) {
		Boolean ready = this.beeStingReady.get(player.getUUID());
		if (Boolean.TRUE.equals(ready)) {
			this.beeStingReady.put(player.getUUID(), Boolean.FALSE);
			return true;
		}
		return false;
	}

	boolean randomChance(int percent) {
		return java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < percent;
	}

	double originalMaxHealth(ServerPlayer player) {
		return this.originalMaxHealth.getOrDefault(player.getUUID(), 20.0D);
	}

	void rememberOriginals(ServerPlayer player, double maxHealth, double scale) {
		this.originalMaxHealth.putIfAbsent(player.getUUID(), maxHealth);
		this.originalScale.putIfAbsent(player.getUUID(), scale);
	}

	boolean holdingWand(ServerPlayer player) {
		return Identity.is(player.getMainHandItem(), id()) || Identity.is(player.getOffhandItem(), id());
	}

	static double parseScale(String raw) {
		try {
			double value = Double.parseDouble(raw);
			return value > 0.0D ? value : 1.0D;
		} catch (NumberFormatException failure) {
			return 1.0D;
		}
	}

	/** {@code WandOfIllusionWeapon#hasStoredMorph} - scans every inventory slot. */
	public boolean hasStoredMorph(ServerPlayer player) {
		return getStoredMob(player) != null;
	}

	/** {@code WandOfIllusionWeapon#getStoredMob}. */
	public String getStoredMob(ServerPlayer player) {
		ItemStack wand = findWand(player);
		if (wand == null) {
			return null;
		}
		String stored = Identity.stateString(wand, STORED_MOB, "");
		return stored.isBlank() ? null : stored;
	}

	/** The name captured with a PLAYER morph, used by the chat formatter. */
	public String disguisedPlayerName(ServerPlayer player) {
		if (!"PLAYER".equals(disguiseOf(player))) {
			return null;
		}
		return this.storedPlayerNames.get(player.getUUID());
	}

	private ItemStack findWand(ServerPlayer player) {
		if (Identity.is(player.getMainHandItem(), id())) {
			return player.getMainHandItem();
		}
		if (Identity.is(player.getOffhandItem(), id())) {
			return player.getOffhandItem();
		}
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (Identity.is(stack, id())) {
				return stack;
			}
		}
		return null;
	}

	// --------------------------------------------------------------- morph lock

	public boolean isMorphLocked(ServerPlayer player) {
		return this.morphLockUses.getOrDefault(player.getUUID(), 0) > 0;
	}

	public int getRemainingLockUses(ServerPlayer player) {
		return this.morphLockUses.getOrDefault(player.getUUID(), 0);
	}

	/** The persistent record backing morph state. */
	private com.altarsmp.fabric.data.PlayerRecord record(ServerPlayer player) {
		return this.mod.store().player(player.getUUID());
	}

	/** {@code /wand lock <uses>}: the lock survives restarts through the player record. */
	public void lockMorph(ServerPlayer player, int uses) {
		this.morphLockUses.put(player.getUUID(), uses);
		record(player).morphLocked(true);
		record(player).morphLockUses(uses);
		this.mod.store().markDirty();
	}

	public void unlockMorph(ServerPlayer player) {
		this.morphLockUses.remove(player.getUUID());
		record(player).morphLocked(false);
		record(player).morphLockUses(0);
		this.mod.store().markDirty();
	}

	private boolean decrementLockUse(ServerPlayer player) {
		Integer uses = this.morphLockUses.get(player.getUUID());
		if (uses == null || uses <= 0) {
			return false;
		}
		int remaining = uses - 1;
		if (remaining <= 0) {
			this.morphLockUses.remove(player.getUUID());
			record(player).morphLocked(false);
			record(player).morphLockUses(0);
			this.mod.store().markDirty();
			return true;
		}
		this.morphLockUses.put(player.getUUID(), remaining);
		record(player).morphLockUses(remaining);
		this.mod.store().markDirty();
		return false;
	}

	/**
	 * Restores the morph lock after a restart and re-applies a morph the player
	 * logged out with, using the entity type, name, baby flag and scale kept in
	 * their record.
	 */
	public void onPlayerJoin(ServerPlayer player) {
		com.altarsmp.fabric.data.PlayerRecord record = record(player);
		if (record.morphLocked() && record.morphLockUses() > 0) {
			this.morphLockUses.put(player.getUUID(), record.morphLockUses());
		}
		String stored = record.morphEntityType();
		if (stored == null || stored.isBlank() || isDisguised(player)) {
			return;
		}
		String label = "PLAYER".equals(stored)
				? (record.morphCustomName() == null || record.morphCustomName().isBlank()
						? "Unknown Player" : record.morphCustomName())
				: (record.morphBaby() ? "Baby " : "") + displayFor(stored);
		double scale = parseScale(record.morphScale() <= 0.0F ? "1.0" : String.valueOf(record.morphScale()));
		AbilityContext ctx = new AbilityContext(this.mod, player, findWand(player) == null
				? player.getMainHandItem() : findWand(player), id());
		Messaging.send(player, "<light_purple>[Wand of Illusion] <gray>Restoring your disguise...");
		completeDisguise(ctx, stored, label, scale, record.morphBaby());
	}

	// -------------------------------------------------------------- housekeeping

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		if (isDisguised(player)) {
			AbilityContext ctx = this.mod.abilities().weaponContext(player)
					.orElseGet(() -> new AbilityContext(this.mod, player, player.getMainHandItem(), id()));
			removeDisguise(ctx, player);
		}
		cancelDisguiseCharge(player);
		removeMirror(player);
		this.storedPlayerNames.remove(player.getUUID());
		ServerBossEvent bar = this.durationBars.remove(player.getUUID());
		if (bar != null) {
			bar.removeAllPlayers();
		}
		ServerBossEvent charge = this.chargeBars.remove(player.getUUID());
		if (charge != null) {
			charge.removeAllPlayers();
		}
		CooldownBars.hide(player, KEY_DISGUISE);
	}

	/** Called when a disguised player dies ({@code onDisguisedPlayerDeath}). */
	public void onPlayerDeath(ServerPlayer player) {
		if (isDisguised(player)) {
			AbilityContext ctx = this.mod.abilities().weaponContext(player)
					.orElseGet(() -> new AbilityContext(this.mod, player, player.getMainHandItem(), id()));
			removeDisguise(ctx, player);
		}
	}

	/** Drops every mirror mob in {@code level} (world unload / shutdown safety). */
	public void clearVisuals(ServerLevel level) {
		this.morphVisuals.values().stream()
				.filter(mirror -> mirror.level() == level && !mirror.isRemoved())
				.forEach(Entity::discard);
	}

	/** Whether this player's disguise should hide them from other clients. */
	public boolean hidesPlayer(Player player) {
		return player instanceof ServerPlayer serverPlayer && this.morphVisuals.containsKey(serverPlayer.getUUID());
	}
}
