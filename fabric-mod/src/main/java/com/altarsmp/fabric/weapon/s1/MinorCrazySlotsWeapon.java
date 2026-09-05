package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.StackSnbt;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Minor Crazy Slots - port of {@code com.altarsmp.weapons.MinorCrazySlotsWeapon}.
 *
 * <p>A one-time gacha roulette whose prize pool is admin-defined: hold an item
 * and run {@code /minorcrazyslots addpool}. Right-clicking consumes one Minor
 * Crazy Slots and starts a 34-step roll (2 ticks per step) that shows a
 * grey-pane preview of a random pool entry in the held slot, a yellow
 * "Minor Crazy Slots rolling" boss bar, a rising click sound and END_ROD
 * particles; the final step locks in the prize.</p>
 *
 * <p>The pool is stored as serialised item stacks ({@code ItemStack.CODEC} ->
 * SNBT) in the world record, seeded once from {@code minor-crazy-slots.pool} in
 * config.yml, so admin additions survive restarts without needing Bukkit's
 * {@code FileConfiguration#set}.</p>
 *
 * <p>Duplication guards from the original are preserved: inventory interaction,
 * dropping, hand-swapping and hotbar changes are refused while a roll is active
 * ({@link #isRolling}), preview items are tagged and swept from inventories and
 * the ground, leaving the server finishes the roll so the prize is still paid
 * out, and dying cancels the roll and strips previews from the drops.</p>
 */
public final class MinorCrazySlotsWeapon implements WeaponBehavior {

	static final String KEY_ROLL_BAR = "minor_crazy_slots_rolling";
	static final String POOL_PREFIX = "minor_crazy_pool#";
	static final String POOL_SEEDED = "minor_crazy_pool_seeded";
	static final int ROLL_STEPS = 34;
	static final int ROLL_PERIOD_TICKS = 2;

	private final AltarSMPMod mod;
	private final Map<UUID, Roll> activeRolls = new HashMap<>();

	/** Mirrors the plugin's private roll record {@code MinorCrazySlotsWeapon.a}. */
	private static final class Roll {
		final String id = UUID.randomUUID().toString();
		final int slot;
		ItemStack prize;
		boolean finished;

		Roll(int slot) {
			this.slot = slot;
		}
	}

	public MinorCrazySlotsWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "minorcrazyslots";
	}

	@Override
	public String displayName() {
		return "Minor Crazy Slots";
	}

	@Override
	public List<String> configFields() {
		return List.of("Roulette Pool Size");
	}

	// -------------------------------------------------------------------- pool

	/** Reads the pool, seeding it from config the first time the world is seen. */
	public List<ItemStack> pool() {
		Map<String, String> flags = this.mod.store().world().flags();
		if (!flags.containsKey(POOL_SEEDED)) {
			List<String> seeded = this.mod.config().main().getStringList("minor-crazy-slots.pool");
			int index = 0;
			for (String entry : seeded) {
				Optional<ItemStack> stack = StackSnbt.deserialize(entry);
				if (stack.isPresent() && !stack.get().isEmpty()) {
					flags.put(POOL_PREFIX + index++, entry);
				}
			}
			flags.put(POOL_SEEDED, String.valueOf(index));
			this.mod.store().markDirty();
			AltarSMPMod.LOGGER.info("[AltarSMP] seeded Minor Crazy Slots pool from config.yml ({} entries)", index);
		}
		List<ItemStack> pool = new ArrayList<>();
		for (int index = 0; ; index++) {
			String entry = flags.get(POOL_PREFIX + index);
			if (entry == null) {
				break;
			}
			Optional<ItemStack> stack = StackSnbt.deserialize(entry);
			if (stack.isPresent() && !stack.get().isEmpty()) {
				pool.add(clonePoolItem(stack.get()));
			}
		}
		return pool;
	}

	public int poolSize() {
		return pool().size();
	}

	/** {@code MinorCrazySlotsWeapon#addHeldItemToPool}. */
	public int addHeldItemToPool(ServerPlayer player) {
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) {
			Messaging.send(player, "<red>Hold the item you want to add to Minor Crazy Slots.");
			return -1;
		}
		if (Identity.is(held, id()) || isPreviewItem(held)) {
			Messaging.send(player, "<red>You can't add Minor Crazy Slots itself to its own pool.");
			return -1;
		}
		String snbt = StackSnbt.serialize(clonePoolItem(held));
		if (snbt == null) {
			Messaging.send(player, "<red>That item could not be serialised into the pool.");
			return -1;
		}
		Map<String, String> flags = this.mod.store().world().flags();
		pool(); // ensure seeded state exists
		int index = 0;
		while (flags.containsKey(POOL_PREFIX + index)) {
			index++;
		}
		flags.put(POOL_PREFIX + index, snbt);
		flags.put(POOL_SEEDED, String.valueOf(index + 1));
		this.mod.store().markDirty();
		int size = poolSize();
		Messaging.send(player, "<aqua><bold>ALTARLY</bold> <green>Added item to Minor Crazy Slots. Pool size: "
				+ size);
		return size;
	}

	/** Removes pool entry {@code index}; returns the removed stack or {@code null}. */
	public ItemStack removePoolEntry(ServerPlayer player, int index) {
		Map<String, String> flags = this.mod.store().world().flags();
		pool();
		String removed = flags.remove(POOL_PREFIX + index);
		if (removed == null) {
			Messaging.send(player, "<red>No pool entry #" + index + ".");
			return null;
		}
		// Keep the numbering dense so pool() never stops early.
		for (int cursor = index; ; cursor++) {
			String next = flags.remove(POOL_PREFIX + (cursor + 1));
			if (next == null) {
				break;
			}
			flags.put(POOL_PREFIX + cursor, next);
		}
		int size = poolSize();
		flags.put(POOL_SEEDED, String.valueOf(size));
		this.mod.store().markDirty();
		Messaging.send(player, "<aqua><bold>ALTARLY</bold> <green>Removed pool entry #" + index + ". Pool size: " + size);
		return StackSnbt.deserialize(removed).orElse(null);
	}

	public void clearPool(ServerPlayer player) {
		Map<String, String> flags = this.mod.store().world().flags();
		flags.keySet().removeIf(key -> key.startsWith(POOL_PREFIX));
		flags.put(POOL_SEEDED, "0");
		this.mod.store().markDirty();
		Messaging.send(player, "<aqua><bold>ALTARLY</bold> <green>Minor Crazy Slots pool cleared.");
	}

	static ItemStack clonePoolItem(ItemStack stack) {
		ItemStack copy = stack.copy();
		copy.setCount(1);
		return copy;
	}

	// -------------------------------------------------------------------- roll

	@Override
	public boolean onUse(AbilityContext ctx, net.minecraft.world.InteractionHand hand) {
		if (hand != net.minecraft.world.InteractionHand.MAIN_HAND || !Identity.is(ctx.weapon(), id())) {
			return false;
		}
		startRoll(ctx);
		return true;
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		startRoll(ctx);
	}

	/** {@code MinorCrazySlotsWeapon#startRoll}. */
	private void startRoll(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		if (this.activeRolls.containsKey(player.getUUID())) {
			Messaging.send(player, "<aqua><bold>ALTARLY</bold> <red>You are already rolling.");
			return;
		}
		List<ItemStack> pool = pool();
		if (pool.isEmpty()) {
			Messaging.send(player, "<aqua><bold>ALTARLY</bold> <red>The roulette pool is empty. "
					+ "An admin can add prizes with <yellow>/minorcrazyslots addpool</yellow>.");
			return;
		}
		int slot = player.getInventory().selected;
		ItemStack held = player.getInventory().getItem(slot);
		if (!Identity.is(held, id())) {
			return;
		}
		consumeOne(player, slot, held);

		Roll roll = new Roll(slot);
		roll.prize = clonePoolItem(pool.get(ctx.random().nextInt(pool.size())));
		this.activeRolls.put(player.getUUID(), roll);
		CooldownBars.show(player, KEY_ROLL_BAR, "Minor Crazy Slots rolling", BossEvent.BossBarColor.YELLOW,
				ROLL_STEPS * ROLL_PERIOD_TICKS * 50L);
		Fx.sound(ctx.level(), player.position(), "BLOCK_NOTE_BLOCK_CHIME", 1.0F, 1.2F);

		final int[] step = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved() || roll.finished) {
				CooldownBars.hide(player, KEY_ROLL_BAR);
				return;
			}
			if (step[0] >= ROLL_STEPS) {
				roll.finished = true;
				this.activeRolls.remove(player.getUUID());
				CooldownBars.hide(player, KEY_ROLL_BAR);
				finishRoll(player, roll);
				return;
			}
			removePreviewItems(player, roll.id);
			ItemStack preview = createPreview(ctx, pool.get(ctx.random().nextInt(pool.size())), roll.id);
			player.getInventory().setItem(roll.slot, preview);
			Messaging.actionBar(player, "<gold>\u1D0D\u1D07\u1D0F\u1D0F\u1D00 \u1D04\u1D1B\u1D00\u1D22\u1D22 \u1D05\u1D0F\u1D0F\u1D1B\u1D05 "
					+ "<dark_gray>| <yellow>Rolling...");
			Fx.sound(ctx.level(), player.position(), "UI_BUTTON_CLICK", 0.45F, 0.8F + step[0] * 0.025F);
			Fx.simple(ctx.level(), "END_ROD", player.position().add(0.0D, 1.0D, 0.0D), 4, 0.35D, 0.35D, 0.35D, 0.01D);
			step[0]++;
		}, 0L, ROLL_PERIOD_TICKS);
	}

	/** {@code MinorCrazySlotsWeapon#finishRoll}. */
	void finishRoll(ServerPlayer player, Roll roll) {
		removePreviewItems(player, roll.id);
		CooldownBars.hide(player, KEY_ROLL_BAR);
		ItemStack prize = clonePoolItem(roll.prize);
		preparePrize(prize);
		player.getInventory().setItem(roll.slot, prize);
		Messaging.actionBar(player, "<gold>\u1D0D\u1D07\u1D0F\u1D0F\u1D00 \u1D04\u1D1B\u1D00\u1D22\u1D22 \u1D05\u1D0F\u1D0F\u1D1B\u1D05 "
				+ "<dark_gray>| <green>Prize locked in");
		Messaging.send(player, "<aqua><bold>ALTARLY</bold> <green>You won <white>" + prize.getHoverName().getString()
				+ "</white><green>!");
		Fx.sound(player.serverLevel(), player.position(), "ENTITY_PLAYER_LEVELUP", 1.0F, 1.2F);
		Fx.simple(player.serverLevel(), "TOTEM_OF_UNDYING", player.position().add(0.0D, 1.0D, 0.0D), 35,
				0.5D, 0.5D, 0.5D, 0.08D);
	}

	/** {@code MinorCrazySlotsWeapon#consumeOne}. */
	private void consumeOne(ServerPlayer player, int slot, ItemStack stack) {
		if (stack.getCount() <= 1) {
			player.getInventory().setItem(slot, ItemStack.EMPTY);
		} else {
			stack.shrink(1);
			player.getInventory().setItem(slot, stack);
		}
	}

	/** {@code MinorCrazySlotsWeapon#createPreview}. */
	ItemStack createPreview(AbilityContext ctx, ItemStack sample, String rollId) {
		ItemStack preview = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
		preview.set(DataComponents.CUSTOM_NAME, Component.literal("Rolling: ").withStyle(ChatFormatting.GOLD)
				.append(sample.getHoverName()).withStyle(style -> style.withItalic(false)));
		preview.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Preview only. This is not the prize.")
				.withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY).withItalic(false)))));
		Identity.setStateString(preview, Identity.KEY_MINOR_CRAZY_ROLL, rollId);
		preview.set(DataComponents.MAX_STACK_SIZE, 1);
		preview.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
		preview.setCount(1);
		return preview;
	}

	/** {@code MinorCrazySlotsWeapon#preparePrize}. */
	private void preparePrize(ItemStack prize) {
		Identity.removeState(prize, Identity.KEY_MINOR_CRAZY_ROLL);
		prize.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
	}

	// ------------------------------------------------------------ dup protection

	/** {@code MinorCrazySlotsWeapon#removePreviewItems}. */
	public void removePreviewItems(ServerPlayer player, String rollId) {
		net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (isPreviewItem(inventory.getItem(slot), rollId)) {
				inventory.setItem(slot, ItemStack.EMPTY);
			}
		}
		if (isPreviewItem(inventory.offhand.get(0), rollId)) {
			inventory.offhand.set(0, ItemStack.EMPTY);
		}
		if (player.containerMenu != null && isPreviewItem(player.containerMenu.getCarried(), rollId)) {
			player.containerMenu.setCarried(ItemStack.EMPTY);
		}
		for (ItemEntity dropped : player.serverLevel().getEntitiesOfClass(ItemEntity.class,
				player.getBoundingBox().inflate(64.0D), entity -> isPreviewItem(entity.getItem(), rollId))) {
			dropped.discard();
		}
	}

	public void removeAnyPreviewItems(ServerPlayer player) {
		Roll roll = this.activeRolls.get(player.getUUID());
		removePreviewItems(player, roll == null ? null : roll.id);
	}

	public static boolean isPreviewItem(ItemStack stack) {
		return isPreviewItem(stack, null);
	}

	public static boolean isPreviewItem(ItemStack stack, String rollId) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		String tag = Identity.stateString(stack, Identity.KEY_MINOR_CRAZY_ROLL, "");
		if (tag.isBlank()) {
			return false;
		}
		return rollId == null || tag.equals(rollId);
	}

	/** Whether any interaction with this player's inventory must be refused right now. */
	public boolean isRolling(ServerPlayer player) {
		return this.activeRolls.containsKey(player.getUUID());
	}

	/** {@code MinorCrazySlotsWeapon#onDeath} - the roll is lost, previews never drop. */
	public void onPlayerDeath(ServerPlayer player, List<ItemStack> drops) {
		Roll roll = this.activeRolls.remove(player.getUUID());
		if (roll == null) {
			return;
		}
		roll.finished = true;
		CooldownBars.hide(player, KEY_ROLL_BAR);
		drops.removeIf(stack -> isPreviewItem(stack, roll.id));
	}

	/** {@code MinorCrazySlotsWeapon#onQuit} - finishing on logout still pays the prize. */
	@Override
	public void onPlayerQuit(ServerPlayer player) {
		Roll roll = this.activeRolls.remove(player.getUUID());
		if (roll != null && !roll.finished) {
			roll.finished = true;
			finishRoll(player, roll);
		}
		CooldownBars.hide(player, KEY_ROLL_BAR);
	}
}
