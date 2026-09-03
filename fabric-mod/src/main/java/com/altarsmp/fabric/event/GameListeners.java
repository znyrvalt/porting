package com.altarsmp.fabric.event;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.GameRegistry;

/**
 * The grab-bag of small Bukkit listeners the plugin registered from
 * {@code AltarSMP#onEnable}:
 *
 * <ul>
 *   <li>{@code PlayerHeadDrop} - a player killed by a vampire/legendary weapon
 *       drops their own head, on a per-player cooldown;</li>
 *   <li>{@code WardenHeartDrop} - the Warden drops the Warden's Heart item;</li>
 *   <li>{@code PlayerDeathLightning} - cosmetic lightning strike on death;</li>
 *   <li>{@code OminousVaultListener} - trial vault opening hooks;</li>
 *   <li>{@code PaleEffectProtection} / {@code PvpProtection} - veto damage in
 *       protected situations (these live in {@code WeaponProtection} in the
 *       port, which is where the damage pipeline calls them);</li>
 *   <li>{@code AltarBreakCleanup} - removing altar displays when their anchor
 *       block is broken.</li>
 * </ul>
 */
public final class GameListeners {

	private final AltarSMPMod mod;

	public GameListeners(AltarSMPMod mod) {
		this.mod = mod;
	}

	public void register() {
		// Nothing to subscribe to directly: the death pipeline calls onDeath from
		// WeaponRegistry, and block breaks arrive through PlayerBlockBreakEvents
		// registered in WeaponRegistry#registerEventHooks.
	}

	public void onDeath(LivingEntity victim, ServerLevel level, DamageSource source, @Nullable ServerPlayer killer) {
		AltarConfig config = this.mod.config();
		if (victim instanceof ServerPlayer player) {
			if (config.getBoolean("death.lightning", true)) {
				Fx.lightning(level, player.position(), true);
			}
			this.mod.abilities().onArmorDeath(player, source, killer);
			if (killer != null && shouldDropHead(killer, player)) {
				dropHead(player, level, killer);
			}
			return;
		}
		String typeId = level.registryAccess()
				.lookupOrThrow(net.minecraft.core.registries.Registries.ENTITY_TYPE)
				.getKey(victim.getType()).toString();
		if (typeId.endsWith(":warden") && config.getBoolean("drops.warden_heart", true)) {
			java.util.Optional<ItemStack> heart = com.altarsmp.fabric.item.ItemFactory.content("wardenheart");
			heart.ifPresent(stack -> level.addFreshEntity(new ItemEntity(level, victim.getX(), victim.getY(), victim.getZ(), stack)));
			Fx.sound(level, victim.position(), GameRegistry.soundId("ENTITY_WARDEN_DEATH"), 1.0F, 1.0F);
		}
	}

	private boolean shouldDropHead(ServerPlayer killer, ServerPlayer victim) {
		AltarConfig config = this.mod.config();
		if (!config.getBoolean("drops.player_head", true)) {
			return false;
		}
		boolean factionKill = this.mod.factions().isVampire(killer) || this.mod.factions().isPale(killer);
		boolean legendaryKill = com.altarsmp.fabric.item.Identity.isWeapon(killer.getMainHandItem());
		if (!factionKill && !legendaryKill) {
			return false;
		}
		com.altarsmp.fabric.data.PlayerRecord record = this.mod.store().player(victim.getUUID());
		long now = System.currentTimeMillis();
		int cooldownSeconds = config.getInt("drops.player_head_cooldown", 300);
		if (record.headDropAvailableAt() > now) {
			return false;
		}
		record.headDropAvailableAt(now + cooldownSeconds * 1000L);
		this.mod.store().markDirty();
		return true;
	}

	private void dropHead(ServerPlayer victim, ServerLevel level, ServerPlayer killer) {
		ItemStack head = new ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD);
		head.set(net.minecraft.core.component.DataComponents.PROFILE,
				new net.minecraft.world.item.component.ResolvableProfile(victim.getGameProfile()));
		com.altarsmp.fabric.item.ItemFactory.putTag(head, "altarsmp:head_owner", victim.getGameProfile().getName());
		ItemEntity entity = new ItemEntity(level, victim.getX(), victim.getY(), victim.getZ(), head);
		entity.setExtendedLifetime();
		level.addFreshEntity(entity);
		com.altarsmp.fabric.util.Messaging.send(killer, "<gold>" + victim.getGameProfile().getName()
				+ " <gray>dropped their head!");
	}

	/** {@code AltarBreakCleanup}: the anchor block went away, so remove the altar. */
	public void onAnchorBroken(ServerLevel level, net.minecraft.core.BlockPos pos) {
		this.mod.altars().removeAt(level, pos);
	}
}
