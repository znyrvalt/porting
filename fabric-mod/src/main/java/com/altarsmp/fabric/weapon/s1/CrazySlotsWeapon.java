package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.item.ContentCatalog;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Crazy Slots - port of {@code com.altarsmp.weapons.CrazySlotsWeapon}.
 *
 * <p>Right-click rolls the roulette: the Crazy Slots item in your hand is
 * replaced by a random weapon from {@code crazyslots.weapon_pool} for
 * {@code crazyslots.transform_duration} ticks. The copy is tagged with a random
 * transform id ({@code altarsmp:crazy_slots_transform_id}) and carries the
 * "Crazy Slots copy | vanishes in N seconds." lore line. When the timer expires
 * the copy is deleted from <i>any</i> online player's inventory, off-hand,
 * cursor or open container and a fresh Crazy Slots is returned; if it cannot be
 * found the roll is lost, exactly like the original.</p>
 *
 * <p>The transform cooldown ({@code crazyslots.transform_cooldown}s) starts only
 * after the transformation ends, so players cannot chain rolls. Re-logging while
 * transformed re-arms the deletion timer via {@link #onPlayerJoin}.</p>
 *
 * <p>{@code crazyslots.dragon-egg-glowing} is honoured here too: while the flag
 * is on, anyone carrying a Dragon Egg glows, which pairs with the Crazy Slots
 * altar requiring (but not consuming) a Dragon Egg.</p>
 */
public final class CrazySlotsWeapon implements WeaponBehavior {

	static final String KEY_TRANSFORM = "crazy_slots_transform";
	static final String DRAGON_EGG_TAG = "altarsmp:dragon_egg_tracker";

	/** The plugin's {@code ALL_WEAPON_IDS}, in the same order. */
	static final String[] ALL_WEAPON_IDS = {
			"bloodlust", "boneblade", "vulcanscrossbow", "hyperion", "wandofillusion",
			"frostscythe", "nightpiercer", "windweaver", "witherbone", "shadowblade",
			"pureblade", "earthgauntlet", "paladinbattleaxe", "cutlass", "palecrossbow",
			"contagionsignal", "eclipsesword", "knightfall", "striker", "nukelauncher",
			"echo", "fireslash"
	};

	private final AltarSMPMod mod;
	/** transform id -> owner uuid, mirroring {@code activeTransforms}. */
	private final Map<UUID, UUID> activeTransforms = new HashMap<>();

	public CrazySlotsWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "crazyslots";
	}

	@Override
	public String displayName() {
		return "Crazy Slots";
	}

	@Override
	public List<String> configFields() {
		return List.of("Transform Cooldown (s)", "Transform Duration (ticks)");
	}

	int transformCooldown(AbilityContext ctx) {
		return ctx.cfg("crazyslots.transform_cooldown", 30);
	}

	int transformDuration(AbilityContext ctx) {
		return ctx.cfg("crazyslots.transform_duration", 600);
	}

	// ------------------------------------------------------------------ rolling

	@Override
	public boolean onUse(AbilityContext ctx, net.minecraft.world.InteractionHand hand) {
		if (hand != net.minecraft.world.InteractionHand.MAIN_HAND || !Identity.is(ctx.weapon(), id())) {
			return false;
		}
		performTransformation(ctx);
		return true;
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		performTransformation(ctx);
	}

	/** {@code CrazySlotsWeapon#performTransformation}. */
	private void performTransformation(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		MinecraftServer server = ctx.server();
		if (ctx.mod().cooldowns().isOnCooldown(player, KEY_TRANSFORM)) {
			Messaging.send(player, "<gold>[Crazy Slots] <red>Still cooling down: <yellow>"
					+ ctx.remaining(KEY_TRANSFORM) + "</yellow> remaining.");
			return;
		}
		List<String> pool = weaponPool(ctx);
		if (pool.isEmpty()) {
			Messaging.send(player, "<gold>[Crazy Slots] <red>No weapons are enabled in <yellow>crazyslots.weapon_pool</yellow>.");
			return;
		}
		UUID transformId = UUID.randomUUID();
		this.activeTransforms.put(transformId, player.getUUID());

		int slot = player.getInventory().selected;
		player.getInventory().setItem(slot, ItemStack.EMPTY);
		ItemStack prize = randomWeapon(ctx, transformId, pool);
		if (!player.getInventory().add(slot, prize)) {
			player.drop(prize, false);
		}

		int seconds = Math.max(1, transformDuration(ctx) / 20);
		Messaging.send(player, "<gold>[Crazy Slots] <yellow>You received " + prizeName(prize)
				+ " for " + seconds + " seconds!");
		Fx.sound(ctx.level(), player.position(), "ENTITY_ILLUSIONER_CAST_SPELL", 1.0F, 1.0F);
		Fx.simple(ctx.level(), "TOTEM_OF_UNDYING", player.position().add(0.0D, 1.0D, 0.0D), 30, 0.5D, 0.5D, 0.5D, 0.1D);
		CooldownBars.show(player, KEY_TRANSFORM, "Crazy Slots", BossEvent.BossBarColor.YELLOW, seconds);

		long duration = transformDuration(ctx);
		this.mod.scheduler().later(() -> {
			CooldownBars.hide(player, KEY_TRANSFORM);
			boolean found = deleteTransformedWeapon(server, transformId);
			if (found) {
				returnCrazySlots(player);
			} else {
				Messaging.send(player, "<gold>[Crazy Slots] <red>You stored the weapon! Crazy Slots lost.");
			}
			this.activeTransforms.remove(transformId);
			ctx.mod().cooldowns().setCooldownSeconds(player, KEY_TRANSFORM, transformCooldown(ctx));
		}, duration);
	}

	/** {@code CrazySlotsWeapon#getWeaponPool} - config filter with an all-weapons fallback. */
	List<String> weaponPool(AbilityContext ctx) {
		List<String> pool = new ArrayList<>();
		for (String legacyId : ALL_WEAPON_IDS) {
			if (!ContentCatalog.isWeaponId(legacyId)) {
				continue;
			}
			if (ctx.cfgb("crazyslots.weapon_pool." + legacyId, true)) {
				pool.add(Identity.normalise(legacyId));
			}
		}
		if (pool.isEmpty()) {
			for (String legacyId : ALL_WEAPON_IDS) {
				if (ContentCatalog.isWeaponId(legacyId)) {
					pool.add(Identity.normalise(legacyId));
				}
			}
		}
		return pool;
	}

	private ItemStack randomWeapon(AbilityContext ctx, UUID transformId, List<String> pool) {
		String chosen = pool.get(ctx.random().nextInt(pool.size()));
		ItemStack stack = ItemFactory.weapon(chosen, ctx.player());
		Identity.setStateString(stack, Identity.KEY_CRAZY_SLOTS_TRANSFORM, transformId.toString());
		appendLore(stack, Component.literal("Crazy Slots copy | vanishes in "
				+ Math.max(1, transformDuration(ctx) / 20) + " seconds.").withStyle(style -> style
						.withColor(net.minecraft.ChatFormatting.DARK_GRAY).withItalic(false)));
		stack.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
		return stack;
	}

	static void appendLore(ItemStack stack, Component line) {
		ItemLore existing = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
		List<Component> lines = new ArrayList<>(existing.lines());
		if (!lines.isEmpty()) {
			lines.add(Component.empty());
		}
		lines.add(line);
		stack.set(DataComponents.LORE, new ItemLore(lines));
	}

	/** The plain-text name shown in the "You received X" message. */
	static String prizeName(ItemStack stack) {
		Component name = stack.getHoverName();
		return name == null ? "a weapon" : name.getString();
	}

	/** {@code CrazySlotsWeapon#deleteTransformedWeapon} - scans every online player. */
	boolean deleteTransformedWeapon(MinecraftServer server, UUID transformId) {
		boolean deleted = false;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Inventory inventory = player.getInventory();
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				ItemStack stack = inventory.getItem(slot);
				if (isTransformedCopy(stack, transformId)) {
					inventory.setItem(slot, ItemStack.EMPTY);
					deleted = true;
				}
			}
			if (isTransformedCopy(inventory.offhand.get(0), transformId)) {
				inventory.offhand.set(0, ItemStack.EMPTY);
				deleted = true;
			}
			if (player.containerMenu != null && isTransformedCopy(player.containerMenu.getCarried(), transformId)) {
				player.containerMenu.setCarried(ItemStack.EMPTY);
				deleted = true;
			}
			if (player.containerMenu != null) {
				for (net.minecraft.world.inventory.Slot menuSlot : player.containerMenu.slots) {
					if (isTransformedCopy(menuSlot.getItem(), transformId)) {
						menuSlot.set(ItemStack.EMPTY);
						deleted = true;
					}
				}
			}
		}
		return deleted;
	}

	/** {@code CrazySlotsWeapon#returnCrazySlots}. */
	void returnCrazySlots(ServerPlayer player) {
		if (player.isRemoved()) {
			return;
		}
		ItemStack fresh = create(player);
		if (!player.getInventory().add(fresh)) {
			player.drop(fresh.copy(), false);
		}
		Messaging.send(player, "<gold>[Crazy Slots] <green>Crazy Slots returned!");
		Fx.sound(player.serverLevel(), player.position(), "BLOCK_NOTE_BLOCK_BELL", 1.0F, 1.5F);
	}

	// ------------------------------------------------------------------- checks

	/** Whether {@code stack} is any Crazy Slots copy. */
	public static boolean isTransformedCopy(ItemStack stack) {
		return stack != null && !stack.isEmpty()
				&& !Identity.stateString(stack, Identity.KEY_CRAZY_SLOTS_TRANSFORM, "").isBlank();
	}

	/** Whether {@code stack} belongs to the given transform id. */
	public static boolean isTransformedCopy(ItemStack stack, UUID transformId) {
		return isTransformedCopy(stack)
				&& transformId.toString().equals(Identity.stateString(stack, Identity.KEY_CRAZY_SLOTS_TRANSFORM, ""));
	}

	public boolean isRolling(ServerPlayer player) {
		return this.activeTransforms.containsValue(player.getUUID());
	}

	// ---------------------------------------------------------------- lifecycle

	/** Re-arms the deletion timer for copies a player logged out while holding. */
	public void onPlayerJoin(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!isTransformedCopy(stack)) {
				continue;
			}
			UUID transformId = UUID.fromString(Identity.stateString(stack, Identity.KEY_CRAZY_SLOTS_TRANSFORM, ""));
			this.activeTransforms.put(transformId, player.getUUID());
			long duration = Math.max(20L, this.mod.config().getInt("crazyslots.transform_duration", 600));
			Messaging.send(player, "<gold>[Crazy Slots] <yellow>Your copied weapon vanishes in "
					+ (duration / 20) + " seconds.");
			this.mod.scheduler().later(() -> {
				if (deleteTransformedWeapon(player.server, transformId)) {
					returnCrazySlots(player);
				} else {
					Messaging.send(player, "<gold>[Crazy Slots] <red>You stored the weapon! Crazy Slots lost.");
				}
				this.activeTransforms.remove(transformId);
			}, duration);
		}
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		CooldownBars.hide(player, KEY_TRANSFORM);
		this.activeTransforms.values().removeIf(owner -> owner.equals(player.getUUID()));
	}

	@Override
	public void onTick(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		if (this.mod.scheduler().currentTick() % 20L != 0L) {
			return;
		}
		if (ctx.cfgb("crazyslots.dragon-egg-glowing", true) && carriesDragonEgg(player)) {
			Effects.apply(player, "GLOWING", 60, 0, true, false, false);
			Fx.dust(ctx.level(), player.position().add(0.0D, 1.2D, 0.0D), 0xC060FF, 0.9F, 2, 0.3D, 0.4D, 0.3D);
		}
	}

	/** {@code crazyslots.dragon-egg-glowing}: any Dragon Egg in the inventory marks you. */
	public static boolean carriesDragonEgg(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).is(net.minecraft.world.item.Items.DRAGON_EGG)) {
				return true;
			}
		}
		return false;
	}
}
