package com.altarsmp.fabric.ability;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.armor.ArmorBehavior;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * The event router for every weapon and armour ability.
 *
 * <p>The plugin gave each weapon class its own Bukkit listener registration; the
 * port registers the hooks once (see {@code WeaponRegistry#registerEventHooks})
 * and fans events out to the behaviour of the item the player is actually
 * wearing or holding. Dispatch is counted per ability so {@code /altarsmp debug
 * abilities} can prove that nothing is a silent no-op.</p>
 */
public final class AbilityBus {

	private final AltarSMPMod mod;
	private final Map<String, WeaponBehavior> weapons = new LinkedHashMap<>();
	private final Map<String, ArmorBehavior> armors = new LinkedHashMap<>();
	private final Map<String, ArmorBehavior> armorAliases = new LinkedHashMap<>();
	private final Map<String, Integer> dispatches = new LinkedHashMap<>();

	private final Map<UUID, Boolean> lastSneak = new HashMap<>();
	private final Map<UUID, String> lastMainHand = new HashMap<>();
	private final Map<UUID, Set<String>> lastArmor = new HashMap<>();

	public AbilityBus(AltarSMPMod mod) {
		this.mod = mod;
	}

	// ------------------------------------------------------------- registration

	public void register(WeaponBehavior behavior) {
		String id = Identity.normalise(behavior.id());
		WeaponBehavior previous = this.weapons.put(id, behavior);
		if (previous != null && previous != behavior) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] weapon behaviour '{}' was registered twice ({} replaced by {})",
					id, previous.getClass().getSimpleName(), behavior.getClass().getSimpleName());
		}
	}

	public void register(ArmorBehavior behavior) {
		this.armors.put(Identity.normalise(behavior.id()), behavior);
		// The diamond-looking trial pieces share the copper behaviours, so they are
		// indexed as aliases instead of as behaviours of their own.
		for (String alias : behavior.aliasIds()) {
			this.armorAliases.put(Identity.normalise(alias), behavior);
		}
	}

	@Nullable
	public WeaponBehavior weapon(String id) {
		return this.weapons.get(Identity.normalise(id));
	}

	@Nullable
	public ArmorBehavior armor(String id) {
		String key = Identity.normalise(id);
		ArmorBehavior behavior = this.armors.get(key);
		return behavior != null ? behavior : this.armorAliases.get(key);
	}

	public Collection<WeaponBehavior> weapons() {
		return Collections.unmodifiableCollection(this.weapons.values());
	}

	public Collection<ArmorBehavior> armors() {
		return Collections.unmodifiableCollection(this.armors.values());
	}

	public Set<String> weaponIds() {
		return Collections.unmodifiableSet(this.weapons.keySet());
	}

	public int size() {
		return this.weapons.size() + this.armors.size();
	}

	public Map<String, Integer> stats() {
		return Collections.unmodifiableMap(this.dispatches);
	}

	private void count(String what) {
		this.dispatches.merge(what, 1, Integer::sum);
	}

	// ---------------------------------------------------------------- dispatch

	/**
	 * F-key activation.
	 *
	 * @param bypass {@code true} when the activation came from
	 *               {@code /ability1} / {@code /ability2} in command mode
	 * @return {@code true} when an ability consumed the swap and the vanilla
	 *         hand swap must be cancelled (the plugin cancelled the event too)
	 */
	public boolean onSwapHand(ServerPlayer player, boolean bypass) {
		if (!bypass && this.mod.controls().shouldSkipDefaultActivation(player)) {
			return false;
		}
		Optional<AbilityContext> ctx = weaponContext(player);
		if (ctx.isEmpty()) {
			return false;
		}
		WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
		if (behavior == null) {
			return false;
		}
		AbilityContext context = ctx.get();
		AbilityTracker.recordAbilityUse(player);
		boolean sneaking = player.isShiftKeyDown();
		this.count(behavior.id() + (sneaking ? ".secondary" : ".primary"));
		if (sneaking) {
			behavior.onSecondary(context);
		} else {
			behavior.onPrimary(context);
		}
		return true;
	}

	/** Command-mode activation used by {@code /ability1} and {@code /ability2}. */
	public boolean activateFromCommand(ServerPlayer player, boolean secondary) {
		this.mod.controls().setBypass(player, true);
		try {
			Optional<AbilityContext> ctx = weaponContext(player);
			if (ctx.isEmpty()) {
				return false;
			}
			WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
			if (behavior == null) {
				return false;
			}
			AbilityContext context = ctx.get();
			AbilityTracker.recordAbilityUse(player);
			this.count(behavior.id() + (secondary ? ".secondary" : ".primary"));
			if (secondary) {
				behavior.onSecondary(context);
			} else {
				behavior.onPrimary(context);
			}
			return true;
		} finally {
			this.mod.controls().setBypass(player, false);
		}
	}

	/**
	 * Attacker-side pass. A held weapon may cancel the hit through
	 * {@link WeaponBehavior#interceptAttack} (the Bow of Deception's imposter
	 * punishment did exactly that with {@code setCancelled(true)}); when it does,
	 * neither the weapon's own on-hit effects nor the armour pass run, matching the
	 * Bukkit listener order where a cancelled event was ignored downstream.
	 *
	 * @return {@code true} when the damage must be cancelled
	 */
	public boolean onAttack(ServerPlayer attacker, LivingEntity target, float damage, boolean critical) {
		Optional<AbilityContext> ctx = weaponContext(attacker);
		if (ctx.isPresent()) {
			WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
			if (behavior != null) {
				if (behavior.interceptAttack(ctx.get(), target, damage)) {
					this.count(behavior.id() + ".attack_cancelled");
					return true;
				}
				this.count(behavior.id() + ".attack");
				behavior.onAttack(ctx.get(), target, damage, critical);
			}
		}
		for (ArmorEntry entry : wornArmor(attacker)) {
			this.count(entry.behavior().id() + ".attack");
			entry.behavior().onAttack(attacker, entry.stack(), target, damage);
		}
		return false;
	}

	public void onKill(ServerPlayer killer, LivingEntity victim) {
		Optional<AbilityContext> ctx = weaponContext(killer);
		if (ctx.isPresent()) {
			WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
			if (behavior != null) {
				this.count(behavior.id() + ".kill");
				behavior.onKill(ctx.get(), victim);
			}
		}
		for (ArmorEntry entry : wornArmor(killer)) {
			entry.behavior().onKill(killer, entry.stack(), victim);
		}
	}

	/**
	 * @return {@code true} when a defensive ability (Cutlass parry, Pure Blade
	 *         shade form, copper chestplate resistance, ...) consumed the hit and
	 *         the damage must be cancelled.
	 */
	public boolean onDamaged(LivingEntity victim, DamageSource source, float amount) {
		if (!(victim instanceof ServerPlayer player)) {
			return false;
		}
		boolean cancel = false;
		LivingEntity attacker = source.getEntity() instanceof LivingEntity living ? living : null;
		Optional<AbilityContext> ctx = weaponContext(player);
		if (ctx.isPresent()) {
			WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
			if (behavior != null) {
				this.count(behavior.id() + ".damaged");
				cancel |= behavior.onDamaged(ctx.get(), source, amount, attacker);
			}
		}
		for (ArmorEntry entry : wornArmor(player)) {
			this.count(entry.behavior().id() + ".damaged");
			cancel |= entry.behavior().onDamaged(player, entry.stack(), source, amount, attacker);
		}
		return cancel;
	}

	public void onProjectileHit(ServerPlayer shooter, Entity projectile, @Nullable Entity hit) {
		Optional<AbilityContext> ctx = weaponContext(shooter);
		if (ctx.isEmpty()) {
			return;
		}
		WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
		if (behavior != null) {
			this.count(behavior.id() + ".projectile_hit");
			behavior.onProjectileHit(ctx.get(), projectile, hit);
		}
	}

	/** Bukkit's {@code PlayerAnimationEvent} (ARM_SWING) for the held weapon. */
	public void onArmSwing(ServerPlayer player) {
		Optional<AbilityContext> ctx = weaponContext(player);
		if (ctx.isEmpty()) {
			return;
		}
		WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
		if (behavior != null) {
			this.count(behavior.id() + ".arm_swing");
			behavior.onArmSwing(ctx.get());
		}
	}

	/**
	 * Global item-use interception (Omen's forbidden circles suppress wind
	 * charges). @return {@code true} when a behaviour cancelled the use.
	 */
	public boolean interceptItemUse(net.minecraft.server.level.ServerLevel level, ServerPlayer player,
			InteractionHand hand, ItemStack used) {
		for (WeaponBehavior behavior : this.weapons.values()) {
			if (behavior.interceptItemUse(level, player, hand, used)) {
				this.count(behavior.id() + ".intercept_item_use");
				return true;
			}
		}
		return false;
	}

	/**
	 * Global projectile-launch interception. @return {@code true} when a behaviour
	 * suppressed the launch (the caller discards the projectile).
	 */
	public boolean interceptProjectileLaunch(net.minecraft.server.level.ServerLevel level, ServerPlayer shooter,
			Entity projectile) {
		for (WeaponBehavior behavior : this.weapons.values()) {
			if (behavior.interceptProjectileLaunch(level, shooter, projectile)) {
				this.count(behavior.id() + ".intercept_projectile");
				return true;
			}
		}
		return false;
	}

	public void onProjectileLaunch(ServerPlayer shooter, Entity projectile) {
		Optional<AbilityContext> ctx = weaponContext(shooter);
		if (ctx.isEmpty()) {
			return;
		}
		WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
		if (behavior != null) {
			this.count(behavior.id() + ".projectile_launch");
			behavior.onProjectileLaunch(ctx.get(), projectile);
		}
	}

	/** @return {@code true} when the held weapon consumed the right-click. */
	public boolean onUse(ServerPlayer player, InteractionHand hand) {
		Optional<AbilityContext> ctx = weaponContext(player);
		if (ctx.isEmpty()) {
			return false;
		}
		WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
		if (behavior == null) {
			return false;
		}
		this.count(behavior.id() + ".use");
		return behavior.onUse(ctx.get(), hand);
	}

	/** @return {@code true} when the held weapon consumed a right-click on a block. */
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, net.minecraft.core.Direction direction) {
		Optional<AbilityContext> ctx = weaponContext(player);
		if (ctx.isEmpty()) {
			return false;
		}
		WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
		if (behavior == null) {
			return false;
		}
		this.count(behavior.id() + ".use_block");
		return behavior.onUseBlock(ctx.get(), pos, direction);
	}

	/** @return {@code true} when the held weapon consumed a left-click on a block. */
	public boolean onAttackBlock(ServerPlayer player, BlockPos pos) {
		Optional<AbilityContext> ctx = weaponContext(player);
		if (ctx.isEmpty()) {
			return false;
		}
		WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
		if (behavior == null) {
			return false;
		}
		this.count(behavior.id() + ".attack_block");
		return behavior.onAttackBlock(ctx.get(), pos);
	}

	public void onBlockBreak(ServerPlayer player, BlockPos pos, BlockState state) {
		Optional<AbilityContext> ctx = weaponContext(player);
		if (ctx.isEmpty()) {
			return;
		}
		WeaponBehavior behavior = this.weapons.get(ctx.get().weaponId());
		if (behavior != null) {
			this.count(behavior.id() + ".block_break");
			behavior.onBlockBreak(ctx.get(), pos, state);
		}
	}

	public void onPlayerQuit(ServerPlayer player) {
		for (WeaponBehavior behavior : this.weapons.values()) {
			try {
				behavior.onPlayerQuit(player);
			} catch (RuntimeException e) {
				AltarSMPMod.LOGGER.error("[AltarSMP] {} failed to clean up for {}", behavior.id(), player.getGameProfile().getName(), e);
			}
		}
		this.lastSneak.remove(player.getUUID());
		this.lastMainHand.remove(player.getUUID());
		this.lastArmor.remove(player.getUUID());
		AbilityTracker.clearPlayer(player);
		this.mod.cooldowns().clearAllCooldowns(player);
		this.mod.immunity().clear(player);
		this.mod.controls().forget(player);
	}

	// --------------------------------------------------------------------- tick

	public void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			tickPlayer(player);
		}
	}

	private void tickPlayer(ServerPlayer player) {
		Optional<AbilityContext> ctx = weaponContext(player);
		String heldId = ctx.map(AbilityContext::weaponId).orElse(null);

		// Hand-over: fire onUnequip/onEquip exactly once per change.
		String previousHeld = this.lastMainHand.put(player.getUUID(), heldId);
		if (!java.util.Objects.equals(previousHeld, heldId)) {
			if (previousHeld != null) {
				WeaponBehavior old = this.weapons.get(previousHeld);
				if (old != null) {
					this.count(old.id() + ".unequip");
					old.onUnequip(new AbilityContext(this.mod, player, ItemStack.EMPTY, previousHeld));
				}
			}
			if (heldId != null) {
				WeaponBehavior fresh = this.weapons.get(heldId);
				if (fresh != null) {
					this.count(fresh.id() + ".equip");
					fresh.onEquip(ctx.orElseThrow());
				}
			}
		}

		// Sneak edge detection (Shift-triggered abilities and sneak passives).
		boolean sneaking = player.isShiftKeyDown();
		Boolean wasSneaking = this.lastSneak.put(player.getUUID(), sneaking);
		if (wasSneaking != null && wasSneaking != sneaking && ctx.isPresent()) {
			WeaponBehavior behavior = this.weapons.get(heldId);
			if (behavior != null) {
				this.count(behavior.id() + ".sneak");
				behavior.onSneakToggle(ctx.get(), sneaking);
			}
		}

		if (ctx.isPresent()) {
			WeaponBehavior behavior = this.weapons.get(heldId);
			if (behavior != null) {
				this.count(behavior.id() + ".tick");
				behavior.onTick(ctx.get());
			}
		}

		// Armour passives + equip tracking.
		Set<String> worn = new LinkedHashSet<>();
		for (ArmorEntry entry : wornArmor(player)) {
			worn.add(entry.behavior().id());
			this.count(entry.behavior().id() + ".worn");
			entry.behavior().onWorn(player, entry.stack());
		}
		Set<String> previousWorn = this.lastArmor.put(player.getUUID(), worn);
		if (previousWorn != null) {
			for (String id : previousWorn) {
				if (!worn.contains(id)) {
					ArmorBehavior behavior = armor(id);
					if (behavior != null) {
						this.count(id + ".unequip");
						behavior.onUnequip(player, ItemStack.EMPTY);
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	public Optional<AbilityContext> weaponContext(ServerPlayer player) {
		ItemStack main = player.getMainHandItem();
		String id = Identity.idOf(main);
		if (id == null || !this.weapons.containsKey(id)) {
			return Optional.empty();
		}
		return Optional.of(new AbilityContext(this.mod, player, main, id));
	}

	/** AltarSMP armour the player currently wears, resolved to its behaviour. */
	public java.util.List<ArmorEntry> wornArmor(ServerPlayer player) {
		java.util.List<ArmorEntry> out = new java.util.ArrayList<>();
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			String id = Identity.idOf(stack);
			if (id == null) {
				continue;
			}
			ArmorBehavior behavior = armor(id);
			if (behavior != null) {
				out.add(new ArmorEntry(behavior, stack, slot));
			}
		}
		return out;
	}

	public boolean isWearing(ServerPlayer player, String armorId) {
		for (ArmorEntry entry : wornArmor(player)) {
			if (entry.behavior().id().equals(Identity.normalise(armorId))) {
				return true;
			}
		}
		return false;
	}

	public record ArmorEntry(ArmorBehavior behavior, ItemStack stack, EquipmentSlot slot) {
	}
}
