package com.altarsmp.fabric.weapon.s2;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.item.ModComponents;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Bow of Deception and Lies (Season 2) - port of
 * {@code com.altarsmps2.weapons.BowOfDeceptionWeapon}.
 *
 * <p>The whole weapon sits behind {@code bow-of-deception.enabled} (season 2 read
 * that key from {@code s2.yml}, where it ships as {@code false}). While it is off
 * the bow is an ordinary Sharpness X unbreakable bow: the melee hook, the eat hook
 * and the imposter punishment are all inert, exactly as the class's own
 * {@code isEnabled()} guard made them.
 *
 * <p>While it is on, and only for the player the bow is <em>bound</em> to:
 *
 * <ul>
 *   <li>a melee hit on a player makes them drop the item they are holding;</li>
 *   <li>a sneaking melee hit on a player makes them drop all four armour pieces;</li>
 *   <li>sneak + right-click <em>eats the evidence</em> - one bow is consumed with a
 *       burp and an eating sound.</li>
 * </ul>
 *
 * <p>Anyone else who swings or eats with the bow is an imposter: the hit is
 * cancelled, every copy of the bow is stripped from their inventory (36 slots plus
 * the offhand, armour untouched, as in the original), a TNT primed with a zero fuse
 * is spawned on top of them with them as its source, and they are told
 * "{@code ...that wasn't yours.}".
 *
 * <p>Binding is decided by the owner the item was created with: the
 * {@code /bowofdeceptionandlies} admin command creates an owner-bound bow, while
 * {@code /legendaries2 bowofdeceptionandlies} hands out {@code template()}, which
 * the original built with a {@code null} owner. An unbound bow therefore has no
 * owner at all and punishes <em>everybody</em> who uses it - the trap is preserved
 * rather than smoothed over. The {@code have fun <name>} lore line falls back to
 * {@code leekleek} for those unbound copies, again as upstream.
 */
public final class BowOfDeceptionWeapon implements WeaponBehavior {

	/** Bukkit's {@code getInventory().getSize()} - the 36 slots the purge walked. */
	private static final int MAIN_INVENTORY_SIZE = Inventory.INVENTORY_SIZE;
	/** Bukkit's {@code setFuseTicks(0)} - the TNT goes off on its first tick. */
	private static final int PUNISHMENT_FUSE = 0;

	private final AltarSMPMod mod;

	public BowOfDeceptionWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "bow_of_deception";
	}

	@Override
	public int season() {
		return 2;
	}

	@Override
	public String displayName() {
		return "Bow of Deception and Lies";
	}

	@Override
	public List<String> configFields() {
		return List.of("Bow Of Deception Enabled");
	}

	/** {@code BowOfDeceptionWeapon#isEnabled()} - read from {@code s2.yml}. */
	public boolean enabled() {
		return this.mod.config().s2().getBoolean("bow-of-deception.enabled", false);
	}

	/**
	 * {@code BowOfDeceptionWeapon#onCommand} - the body of
	 * {@code /bowofdeceptionandlies}. Returns {@code false} when the config has the
	 * weapon switched off so the command layer can report the refusal instead of
	 * pretending the give happened.
	 */
	public boolean give(ServerPlayer player) {
		if (!enabled()) {
			Messaging.send(player, "<red>Bow of Deception is disabled in config.");
			return false;
		}
		ItemStack bow = ItemFactory.weapon(id(), player);
		if (!player.getInventory().add(bow)) {
			// Bukkit's addItem silently dropped the leftovers on the floor of the
			// plugin's return value; nothing is lost here either.
			player.drop(bow, false);
		}
		Messaging.send(player, "<aqua><italic>...have fun " + player.getGameProfile().getName() + ".");
		return true;
	}

	/**
	 * Bukkit's {@code template()} - the unbound copy {@code /legendaries2} handed
	 * out. Kept public because the give-command layer builds its previews from it.
	 */
	public ItemStack template() {
		return ItemFactory.weapon(id(), null);
	}

	// ------------------------------------------------------------------ combat

	/**
	 * {@code BowOfDeceptionWeapon#onMeleeHit} (HIGH, ignoreCancelled). Returning
	 * {@code true} cancels the damage, which is what {@code setCancelled(true)} did
	 * for an imposter's swing.
	 */
	@Override
	public boolean interceptAttack(AbilityContext ctx, LivingEntity target, float damage) {
		if (!enabled()) {
			return false;
		}
		ServerPlayer player = ctx.player();
		ItemStack held = player.getMainHandItem();
		if (!isThis(held)) {
			return false;
		}
		ServerLevel level = ctx.level();
		if (!isOwner(player, held)) {
			punishImposter(level, player);
			return true;
		}
		if (!(target instanceof ServerPlayer victim)) {
			return false;
		}

		if (player.isShiftKeyDown()) {
			// ARMOR-DROP: all four pieces, helmet first, as upstream.
			dropAndClear(level, victim, EquipmentSlot.HEAD);
			dropAndClear(level, victim, EquipmentSlot.CHEST);
			dropAndClear(level, victim, EquipmentSlot.LEGS);
			dropAndClear(level, victim, EquipmentSlot.FEET);
		} else {
			// ITEM-DROP: whatever the victim is holding.
			Inventory inventory = victim.getInventory();
			ItemStack heldByVictim = inventory.getSelectedItem();
			if (!heldByVictim.isEmpty()) {
				ItemStack dropped = heldByVictim.copy();
				inventory.setSelectedItem(ItemStack.EMPTY);
				drop(level, victim, dropped);
			}
		}
		return false;
	}

	/** {@code BowOfDeceptionWeapon#onEat} (HIGHEST) - sneak + right-click. */
	@Override
	public boolean onUse(AbilityContext ctx, InteractionHand hand) {
		if (!enabled() || !ctx.player().isShiftKeyDown()) {
			return false;
		}
		ServerPlayer player = ctx.player();
		ItemStack held = player.getMainHandItem();
		if (!isThis(held)) {
			return false;
		}
		ServerLevel level = ctx.level();
		if (!isOwner(player, held)) {
			punishImposter(level, player);
			return true;
		}

		held.shrink(1);
		Vec3 at = player.position();
		Fx.sound(level, at, "ENTITY_PLAYER_BURP", 1.0F, 1.0F);
		Fx.sound(level, at, "ENTITY_GENERIC_EAT", 1.0F, 1.0F);
		Messaging.send(player, "<gray><italic>Evidence eaten.");
		return true;
	}

	// ----------------------------------------------------------------- helpers

	/** Bukkit's {@code isThis(ItemStack)} - the {@code altarsmps2:bow_of_deception} tag. */
	private boolean isThis(@Nullable ItemStack stack) {
		return stack != null && Identity.is(stack, id());
	}

	/** Bukkit's {@code getOwner(ItemStack)} - the bound owner, or {@code null}. */
	@Nullable
	private static UUID ownerOf(@Nullable ItemStack stack) {
		if (stack == null) {
			return null;
		}
		String provenance = stack.get(ModComponents.PROVENANCE);
		if (provenance == null || provenance.isEmpty()) {
			return null;
		}
		int separator = provenance.indexOf('|');
		String uuid = separator >= 0 ? provenance.substring(separator + 1) : provenance;
		try {
			return UUID.fromString(uuid);
		} catch (IllegalArgumentException e) {
			// Bukkit's getOwner swallowed a malformed UUID the same way: no owner.
			return null;
		}
	}

	/** Bukkit's {@code isOwner(Player, ItemStack)}. */
	private static boolean isOwner(ServerPlayer player, @Nullable ItemStack stack) {
		UUID owner = ownerOf(stack);
		return owner != null && owner.equals(player.getUUID());
	}

	/** {@code BowOfDeceptionWeapon#punishImposter}. */
	private void punishImposter(ServerLevel level, ServerPlayer imposter) {
		Inventory inventory = imposter.getInventory();
		for (int slot = 0; slot < MAIN_INVENTORY_SIZE; slot++) {
			if (isThis(inventory.getItem(slot))) {
				inventory.setItem(slot, ItemStack.EMPTY);
			}
		}
		if (isThis(inventory.getItem(Inventory.SLOT_OFFHAND))) {
			inventory.setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
		}

		Vec3 at = imposter.position();
		PrimedTnt tnt = new PrimedTnt(level, at.x, at.y, at.z, imposter);
		tnt.setFuse(PUNISHMENT_FUSE);
		level.addFreshEntity(tnt);

		Messaging.send(imposter, "<red><italic>...that wasn't yours.");
	}

	/** {@code BowOfDeceptionWeapon#dropAndClear} for one armour slot. */
	private void dropAndClear(ServerLevel level, ServerPlayer victim, EquipmentSlot slot) {
		ItemStack worn = victim.getItemBySlot(slot);
		if (worn == null || worn.isEmpty()) {
			return;
		}
		ItemStack dropped = worn.copy();
		victim.setItemSlot(slot, ItemStack.EMPTY);
		drop(level, victim, dropped);
	}

	/** Bukkit's {@code World#dropItemNaturally}. */
	private void drop(ServerLevel level, ServerPlayer victim, ItemStack stack) {
		Vec3 at = victim.position();
		ItemEntity entity = new ItemEntity(level, at.x, at.y, at.z, stack);
		entity.setDefaultPickUpDelay();
		level.addFreshEntity(entity);
	}
}
