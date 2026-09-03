package com.altarsmp.fabric.armor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TickScheduler;

/**
 * Copper Helmet - port of {@code com.altarsmp.armor.CopperHelmet}.
 *
 * <p><b>Passives</b> (every 20 ticks while worn): Water Breathing and Fire
 * Resistance for 180 ticks, both switchable through
 * {@code copper-armor.helmet.water_breathing} / {@code .fire_resistance}, plus
 * Strength {@code strength_level} for 180 ticks.
 *
 * <p><b>Copper Vision</b> (hold shift): a 5-tick check notices a sneaking wearer
 * who is neither charging nor already running the ability and starts a
 * {@code charge_time}-second charge - a beacon ambient, then a note-block bell
 * every 10 ticks rising from 0.5 to 2.0 pitch as the charge fills. Letting go of
 * shift, taking the helmet off or logging out cancels it. On completion the cooldown
 * ({@code cooldown}s) starts, everyone else in the same dimension within
 * {@code radius} blocks is struck: a thunder clap at their position,
 * {@code lightning_damage} taken straight off their health (Bukkit's
 * {@code setHealth}, so no armour, totem or ability immunity is consulted) and a
 * message naming the caller, and then those same players glow for
 * {@code duration} seconds, re-applied every 10 ticks.
 *
 * <p>The item itself carries the two extra attribute modifiers the original wrote
 * through {@code ItemMeta#addAttributeModifier} - {@code copper-armor.helmet.armor}
 * and {@code copper-armor.helmet.armor_toughness} on the head slot, on top of the
 * netherite helmet's own values, which is why a Copper Helmet ends up at 6 armour
 * and 6 toughness. Only the helmet did this; the other three pieces rely on their
 * netherite defaults, as their lore describes.
 */
public final class CopperHelmet implements ArmorBehavior {

	/** Bukkit's {@code CopperHelmet#d} - the cooldown key, unprefixed. */
	private static final String KEY_COPPER_VISION = "copper_vision";
	private static final Identifier MODIFIER_ARMOR = Identifier.withDefaultNamespace("copper_helmet_armor");
	private static final Identifier MODIFIER_TOUGHNESS = Identifier.withDefaultNamespace("copper_helmet_toughness");

	/** {@code startPassiveEffects}'s {@code runTaskTimer(plugin, 20L, 20L)}. */
	private static final int PASSIVE_INTERVAL = 20;
	/** {@code startCopperVisionCheck}'s {@code runTaskTimer(plugin, 5L, 5L)}. */
	private static final int VISION_CHECK_INTERVAL = 5;
	/** The charge task's 2-tick period. */
	private static final int CHARGE_INTERVAL = 2;
	/** The glow task's 10-tick period. */
	private static final int GLOW_INTERVAL = 10;
	/** Glowing is refreshed for 40 ticks each pass. */
	private static final int GLOW_DURATION_TICKS = 40;

	private final AltarSMPMod mod;

	/** Bukkit's {@code Set<UUID> o} - players whose Copper Vision is running. */
	private final Set<UUID> visionActive = new HashSet<>();
	/** Bukkit's {@code Set<UUID> p} - players mid-charge. */
	private final Set<UUID> charging = new HashSet<>();
	/** Charge tasks, so taking the helmet off can stop the bells. */
	private final Map<UUID, TickScheduler.Task> chargeTasks = new HashMap<>();
	/** Copper Vision glow tasks, keyed by the caller. */
	private final Map<UUID, TickScheduler.Task> glowTasks = new HashMap<>();

	public CopperHelmet(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "copper_helmet";
	}

	@Override
	public EquipmentSlot slot() {
		return EquipmentSlot.HEAD;
	}

	@Override
	public Set<String> aliasIds() {
		return Set.of("copper_diamond_helmet");
	}

	@Override
	public ItemStack create() {
		ItemStack stack = ItemFactory.armor(id());
		AltarConfig config = this.mod.config();
		double armor = config.getDouble("copper-armor.helmet.armor", 3.0D);
		double toughness = config.getDouble("copper-armor.helmet.armor_toughness", 3.0D);
		ItemAttributeModifiers modifiers =
				stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		modifiers = modifiers.withModifierAdded(Attributes.ARMOR,
				new AttributeModifier(MODIFIER_ARMOR, armor, AttributeModifier.Operation.ADD_VALUE),
				EquipmentSlotGroup.HEAD);
		modifiers = modifiers.withModifierAdded(Attributes.ARMOR_TOUGHNESS,
				new AttributeModifier(MODIFIER_TOUGHNESS, toughness, AttributeModifier.Operation.ADD_VALUE),
				EquipmentSlotGroup.HEAD);
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);
		return stack;
	}

	/** {@code CopperHelmet#onCommand} - the body of {@code /copperhelmet}. */
	public boolean give(ServerPlayer player) {
		ItemStack helmet = create();
		if (!player.getInventory().add(helmet)) {
			player.drop(helmet, false);
		}
		Messaging.send(player, "<green>You received Copper Helmet!");
		Fx.soundTo(player, "BLOCK_CONDUIT_ACTIVATE", 1.0F, 1.2F);
		return true;
	}

	// ------------------------------------------------------------------ worn

	@Override
	public void onWorn(ServerPlayer player, ItemStack stack) {
		int tick = player.tickCount;
		if (tick % PASSIVE_INTERVAL == 0) {
			applyPassives(player);
		}
		if (tick % VISION_CHECK_INTERVAL == 0) {
			UUID uuid = player.getUUID();
			if (player.isShiftKeyDown() && !this.charging.contains(uuid) && !this.visionActive.contains(uuid)) {
				startChargeUp(player);
			}
		}
	}

	/**
	 * {@code startCopperVisionCheck}'s other half: a player who stops wearing the
	 * helmet is dropped from both sets. The charge task notices on its own next pass
	 * and reports the cancellation, as it did upstream.
	 */
	@Override
	public void onUnequip(ServerPlayer player, ItemStack stack) {
		UUID uuid = player.getUUID();
		this.visionActive.remove(uuid);
		this.charging.remove(uuid);
	}

	/** {@code CopperHelmet#startPassiveEffects}. */
	private void applyPassives(ServerPlayer player) {
		AltarConfig config = this.mod.config();
		if (config.getBoolean("copper-armor.helmet.water_breathing", true)) {
			Effects.apply(player, "WATER_BREATHING", 180, 0, false, false, true);
		}
		if (config.getBoolean("copper-armor.helmet.fire_resistance", true)) {
			Effects.apply(player, "FIRE_RESISTANCE", 180, 0, false, false, true);
		}
		Effects.apply(player, "STRENGTH", 180, config.getInt("copper-armor.helmet.strength_level", 0),
				false, false, true);
	}

	// ---------------------------------------------------------- copper vision

	/** {@code CopperHelmet#startChargeUp}. */
	private void startChargeUp(ServerPlayer player) {
		UUID uuid = player.getUUID();
		if (this.mod.cooldowns().isOnCooldown(player, KEY_COPPER_VISION)) {
			// Upstream said nothing here: the charge simply never starts.
			return;
		}
		this.charging.add(uuid);
		Messaging.send(player, "<gold>Copper Vision <yellow>charging...");
		Fx.soundTo(player, "BLOCK_BEACON_AMBIENT", 1.0F, 0.5F);

		int chargeTicks = this.mod.config().getInt("copper-armor.helmet.charge_time", 3) * 20;
		final int[] elapsed = {0};
		TickScheduler.Task task = this.mod.scheduler().timer(() -> {
			ServerPlayer online = player(uuid);
			if (online != null && online.isShiftKeyDown() && wearing(online)) {
				elapsed[0] += CHARGE_INTERVAL;
				float progress = chargeTicks > 0 ? elapsed[0] / (float) chargeTicks : 1.0F;
				if (elapsed[0] % 10 == 0) {
					Fx.soundTo(online, "BLOCK_NOTE_BLOCK_BELL", 0.3F, 0.5F + progress * 1.5F);
				}
				if (elapsed[0] >= chargeTicks) {
					this.charging.remove(uuid);
					activateCopperVision(online);
					cancelCharge(uuid);
				}
			} else {
				this.charging.remove(uuid);
				if (online != null) {
					Messaging.send(online, "<red>Copper Vision charge cancelled!");
					Fx.soundTo(online, "BLOCK_BEACON_DEACTIVATE", 0.5F, 1.0F);
				}
				cancelCharge(uuid);
			}
		}, 0L, CHARGE_INTERVAL);
		this.chargeTasks.put(uuid, task);
	}

	/** {@code CopperHelmet#activateCopperVision}. */
	private void activateCopperVision(ServerPlayer player) {
		UUID uuid = player.getUUID();
		AltarConfig config = this.mod.config();
		int cooldown = config.getInt("copper-armor.helmet.cooldown", 30);
		int durationSeconds = config.getInt("copper-armor.helmet.duration", 15);
		int radius = config.getInt("copper-armor.helmet.radius", 100);
		double lightningDamage = config.getDouble("copper-armor.helmet.lightning_damage", 6.0D);

		this.mod.cooldowns().setCooldownSeconds(player, KEY_COPPER_VISION, cooldown);
		this.visionActive.add(uuid);
		Messaging.send(player, "<gold>Copper Vision <green>activated!");
		Fx.soundTo(player, "BLOCK_BEACON_ACTIVATE", 0.5F, 1.5F);

		ServerLevel level = player.serverLevel();
		String caller = player.getGameProfile().getName();
		List<ServerPlayer> struck = new ArrayList<>();
		for (ServerPlayer other : level.players()) {
			if (other.equals(player) || other.distanceTo(player) > radius) {
				continue;
			}
			struck.add(other);
			Fx.sound(level, other.position(), "ENTITY_LIGHTNING_BOLT_THUNDER", 1.0F, 1.0F);
			// Bukkit's setHealth: raw health, no armour, no totem, no ability immunity.
			other.setHealth((float) Math.max(0.0D, other.getHealth() - lightningDamage));
			Messaging.send(other, "<gold>You were struck by <yellow>" + caller + "'s <gold>Copper Vision!");
		}

		final int[] remaining = {durationSeconds * 20};
		TickScheduler.Task task = this.mod.scheduler().timer(() -> {
			ServerPlayer online = player(uuid);
			if (remaining[0] > 0 && online != null) {
				for (ServerPlayer target : struck) {
					ServerPlayer stillOnline = player(target.getUUID());
					if (stillOnline != null) {
						Effects.apply(stillOnline, "GLOWING", GLOW_DURATION_TICKS, 0, false, false, true);
					}
				}
				remaining[0] -= GLOW_INTERVAL;
			} else {
				this.visionActive.remove(uuid);
				if (online != null) {
					Messaging.send(online, "<gold>Copper Vision <red>ended.");
				}
				cancelGlow(uuid);
			}
		}, 0L, GLOW_INTERVAL);
		this.glowTasks.put(uuid, task);
	}

	// ---------------------------------------------------------------- helpers

	private boolean wearing(ServerPlayer player) {
		return this.mod.abilities().isWearing(player, id());
	}

	@javax.annotation.Nullable
	private ServerPlayer player(UUID uuid) {
		return this.mod.server() == null ? null : this.mod.server().getPlayerList().getPlayer(uuid);
	}

	private void cancelCharge(UUID uuid) {
		TickScheduler.Task task = this.chargeTasks.remove(uuid);
		if (task != null) {
			task.cancel();
		}
	}

	private void cancelGlow(UUID uuid) {
		TickScheduler.Task task = this.glowTasks.remove(uuid);
		if (task != null) {
			task.cancel();
		}
	}
}
