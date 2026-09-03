package com.altarsmp.fabric.weapon;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Stun;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.item.TooltipStyleEnforcer;
import com.altarsmp.fabric.weapon.s1.BloodlustWeapon;
import com.altarsmp.fabric.weapon.s1.BoneBladeWeapon;
import com.altarsmp.fabric.weapon.s1.ContagionSignalWeapon;
import com.altarsmp.fabric.weapon.s1.CopperPickaxeIIWeapon;
import com.altarsmp.fabric.weapon.s1.CopperPickaxeWeapon;
import com.altarsmp.fabric.weapon.s1.CrazySlotsWeapon;
import com.altarsmp.fabric.weapon.s1.CutlassWeapon;
import com.altarsmp.fabric.weapon.s1.EarthGauntletWeapon;
import com.altarsmp.fabric.weapon.s1.EchoWeapon;
import com.altarsmp.fabric.weapon.s1.EclipseSwordWeapon;
import com.altarsmp.fabric.weapon.s1.FireSlashWeapon;
import com.altarsmp.fabric.weapon.s1.FrostScytheWeapon;
import com.altarsmp.fabric.weapon.s1.HyperionWeapon;
import com.altarsmp.fabric.weapon.s1.KnightfallWeapon;
import com.altarsmp.fabric.weapon.s1.MinorCrazySlotsWeapon;
import com.altarsmp.fabric.weapon.s1.NightpiercerWeapon;
import com.altarsmp.fabric.weapon.s1.NukeLauncherWeapon;
import com.altarsmp.fabric.weapon.s1.PaladinBattleAxeWeapon;
import com.altarsmp.fabric.weapon.s1.PaleCrossbowWeapon;
import com.altarsmp.fabric.weapon.s1.PureBladeWeapon;
import com.altarsmp.fabric.weapon.s1.ShadowBladeWeapon;
import com.altarsmp.fabric.weapon.s1.StrikerWeapon;
import com.altarsmp.fabric.weapon.s1.VulcansCrossbowWeapon;
import com.altarsmp.fabric.weapon.s1.WandOfIllusionWeapon;
import com.altarsmp.fabric.weapon.s1.WindweaverWeapon;
import com.altarsmp.fabric.weapon.s1.WitherboneWeapon;
import com.altarsmp.fabric.weapon.s2.AncientBladeWeapon;
import com.altarsmp.fabric.weapon.s2.BowOfDeceptionWeapon;
import com.altarsmp.fabric.weapon.s2.DragonrendWeapon;
import com.altarsmp.fabric.weapon.s2.OmenWeapon;
import com.altarsmp.fabric.weapon.s2.TidebreakerWeapon;
import com.altarsmp.fabric.weapon.s2.WitherSymbioteWeapon;
import com.altarsmp.fabric.armor.CopperBoots;
import com.altarsmp.fabric.armor.CopperChestplate;
import com.altarsmp.fabric.armor.CopperHelmet;
import com.altarsmp.fabric.armor.CopperLeggings;

/**
 * Instantiates every weapon behaviour and wires the Fabric event hooks that feed
 * {@link com.altarsmp.fabric.ability.AbilityBus}.
 *
 * <p>The plugin registered one Bukkit listener per weapon class; the port
 * registers each hook exactly once and fans out by item identity, which is both
 * cheaper and easier to audit. The full Season&nbsp;1 + Season&nbsp;2 roster is
 * listed in {@link #registerAll()} - if a weapon is missing from that list it is
 * missing from the mod, and {@code /altarsmp debug weapons} will show it.</p>
 */
public final class WeaponRegistry {

	private final AltarSMPMod mod;
	private final Map<String, WeaponBehavior> behaviors = new LinkedHashMap<>();
	private final Map<String, com.altarsmp.fabric.armor.ArmorBehavior> armorBehaviors = new LinkedHashMap<>();

	public WeaponRegistry(AltarSMPMod mod) {
		this.mod = mod;
	}

	// ------------------------------------------------------------------ content

	public void registerAll() {
		// ---- Season 1 (com.altarsmp.weapons) ----------------------------------
		register(new BloodlustWeapon(this.mod));
		register(new BoneBladeWeapon(this.mod));
		register(new ContagionSignalWeapon(this.mod));
		register(new CrazySlotsWeapon(this.mod));
		register(new MinorCrazySlotsWeapon(this.mod));
		register(new CutlassWeapon(this.mod));
		register(new EarthGauntletWeapon(this.mod));
		register(new EchoWeapon(this.mod));
		register(new EclipseSwordWeapon(this.mod));
		register(new FireSlashWeapon(this.mod));
		register(new FrostScytheWeapon(this.mod));
		register(new HyperionWeapon(this.mod));
		register(new KnightfallWeapon(this.mod));
		register(new NightpiercerWeapon(this.mod));
		register(new NukeLauncherWeapon(this.mod));
		register(new PaladinBattleAxeWeapon(this.mod));
		register(new PaleCrossbowWeapon(this.mod));
		register(new PureBladeWeapon(this.mod));
		register(new ShadowBladeWeapon(this.mod));
		register(new StrikerWeapon(this.mod));
		register(new VulcansCrossbowWeapon(this.mod));
		register(new WandOfIllusionWeapon(this.mod));
		register(new WindweaverWeapon(this.mod));
		register(new WitherboneWeapon(this.mod));
		// Copper tools are items in the catalogue but they have swap-hand
		// abilities exactly like the weapons, so they ride the same bus.
		register(new CopperPickaxeWeapon(this.mod));
		register(new CopperPickaxeIIWeapon(this.mod));

		// ---- Season 2 (com.altarsmps2.weapons) --------------------------------
		register(new AncientBladeWeapon(this.mod));
		register(new DragonrendWeapon(this.mod));
		register(new OmenWeapon(this.mod));
		register(new TidebreakerWeapon(this.mod));
		register(new WitherSymbioteWeapon(this.mod));
		register(new BowOfDeceptionWeapon(this.mod));

		// ---- Copper armour (com.altarsmp.armor) -------------------------------
		registerArmor(new CopperHelmet(this.mod));
		registerArmor(new CopperChestplate(this.mod));
		registerArmor(new CopperLeggings(this.mod));
		registerArmor(new CopperBoots(this.mod));

		AltarSMPMod.LOGGER.info("[AltarSMP] registered {} weapon/tool behaviours and {} armour behaviours",
				this.behaviors.size(), this.armorBehaviors.size());
	}

	private void register(WeaponBehavior behavior) {
		String id = Identity.normalise(behavior.id());
		this.behaviors.put(id, behavior);
		this.mod.abilities().register(behavior);
	}

	private void registerArmor(com.altarsmp.fabric.armor.ArmorBehavior behavior) {
		this.armorBehaviors.put(Identity.normalise(behavior.id()), behavior);
		this.mod.abilities().register(behavior);
	}

	@Nullable
	public WeaponBehavior get(String id) {
		return this.behaviors.get(Identity.normalise(id));
	}

	public Collection<WeaponBehavior> all() {
		return Collections.unmodifiableCollection(this.behaviors.values());
	}

	public Collection<String> ids() {
		return Collections.unmodifiableCollection(this.behaviors.keySet());
	}

	public int size() {
		return this.behaviors.size();
	}

	public int seasonOneCount() {
		return (int) this.behaviors.values().stream().filter(b -> b.season() == 1).count();
	}

	public int seasonTwoCount() {
		return (int) this.behaviors.values().stream().filter(b -> b.season() == 2).count();
	}

	public int armorCount() {
		return this.armorBehaviors.size();
	}

	public Optional<ItemStack> create(String id, @Nullable ServerPlayer owner) {
		WeaponBehavior behavior = get(id);
		if (behavior != null) {
			return Optional.of(behavior.create(owner));
		}
		return ItemFactory.content(id, owner);
	}

	// -------------------------------------------------------------------- hooks

	public void registerEventHooks() {
		CooldownBars.register();
		Stun.register();
		TooltipStyleEnforcer.register();

		// Left-clicking an entity: altar hitboxes live here (the plugin used
		// EntityDamageByEntityEvent on the invisible marker armour stand).
		AttackEntityCallback.EVENT.register((player, level, hand, target, hitResult) -> {
			if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
				return InteractionResult.PASS;
			}
			if (this.mod.protection().blockAttack(serverPlayer, target, serverPlayer.getItemInHand(hand))) {
				return InteractionResult.FAIL;
			}
			if (target instanceof ArmorStand && this.mod.altars().handleLeftClick(serverPlayer, target)) {
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		// Right-click with a weapon in hand (some abilities are use-triggered).
		// Global interception runs first: Omen's forbidden circles cancel wind
		// charge use even though the player is not holding an Omen.
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (!(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			if (level instanceof ServerLevel serverLevel
					&& this.mod.abilities().interceptItemUse(serverLevel, serverPlayer, hand, stack)) {
				return InteractionResult.FAIL;
			}
			if (this.mod.abilities().onUse(serverPlayer, hand)) {
				return InteractionResult.CONSUME;
			}
			return InteractionResult.PASS;
		});

		// Right-click on a block (silverfish morph, tool interactions).
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (!(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			BlockPos pos = hitResult.getBlockPos();
			net.minecraft.core.Direction direction = hitResult.getDirection();
			if (direction == null) {
				direction = net.minecraft.core.Direction.UP;
			}
			return this.mod.abilities().onUseBlock(serverPlayer, pos, direction)
					? InteractionResult.SUCCESS : InteractionResult.PASS;
		});

		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (player instanceof ServerPlayer serverPlayer && Stun.isStunned(serverPlayer)) {
				return InteractionResult.FAIL;
			}
			if (player instanceof ServerPlayer serverPlayer
					&& this.mod.abilities().onAttackBlock(serverPlayer, pos)) {
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
				this.mod.abilities().onBlockBreak(serverPlayer, pos, state);
				this.mod.protection().onBlockBroken(serverPlayer, serverLevel, pos, state);
			}
		});

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				!(player instanceof ServerPlayer serverPlayer) || this.mod.protection().allowBlockBreak(serverPlayer, pos, state));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			TooltipStyleEnforcer.scan(player);
			this.mod.protection().onJoin(player);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();
			this.mod.abilities().onPlayerQuit(player);
			this.mod.trials().onPlayerQuit(player);
			TooltipStyleEnforcer.clear(player);
			CooldownBars.clear(player);
			Stun.release(player);
		});
	}

	/** Called by the death mixin - kill counters, head drops and weapon onKill. */
	public void onEntityDeath(LivingEntity victim, ServerLevel level, net.minecraft.world.damagesource.DamageSource source) {
		ServerPlayer killer = CombatHooks.killerOf(source);
		if (killer != null) {
			this.mod.abilities().onKill(killer, victim);
		}
		this.mod.factions().onDeath(victim, level, source, killer);
		this.mod.banZone().onDeath(victim, level, source, killer);
		this.mod.listeners().onDeath(victim, level, source, killer);
	}

	/** Called by the projectile mixin when an arrow/trident owned by a player lands. */
	public void onProjectileHit(Entity projectile, @Nullable Entity hit) {
		if (!(projectile.level() instanceof ServerLevel)) {
			return;
		}
		if (projectile.getOwner() instanceof ServerPlayer shooter) {
			this.mod.abilities().onProjectileHit(shooter, projectile, hit);
		}
	}

	public void onProjectileLaunch(Entity projectile) {
		if (!(projectile.level() instanceof ServerLevel level)) {
			return;
		}
		if (projectile.getOwner() instanceof ServerPlayer shooter) {
			if (this.mod.abilities().interceptProjectileLaunch(level, shooter, projectile)) {
				// Bukkit cancelled ProjectileLaunchEvent; the entity must not fly.
				projectile.discard();
				return;
			}
			this.mod.abilities().onProjectileLaunch(shooter, projectile);
		}
	}
}
