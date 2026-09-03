package com.altarsmp.fabric.weapon.s1;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.item.MineableBlocks;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Copper Pickaxe II - port of {@code com.altarsmp.items.CopperPickaxeII}.
 *
 * <ul>
 *   <li><b>5x5x5 Mining</b> (passive, always on): every block you break takes the
 *       whole 5x5x5 cube with it, guarded against recursion exactly like the
 *       original's {@code e}/{@code d} sets.</li>
 *   <li><b>Allay Buddy</b> (right-click, 45s cooldown): an invulnerable glowing
 *       allay named "{@code <player>'s Mining Buddy"} flies 4.5 blocks ahead every
 *       3 ticks, up to 120 blocks or 80 ticks, mining 3x3x3 along its path before
 *       despawning.</li>
 *   <li><b>Lightning Drill</b> (Shift + right-click, 30s cooldown): a 4-block-per-
 *       4-tick beam from your eyes out to 100 blocks, mining 5x5x5 at every step
 *       and dealing 2 true damage to each living entity it passes (once each).</li>
 * </ul>
 *
 * <p>Both abilities stop early if the holder drops the pickaxe, mirroring the
 * original's {@code isHoldingPickaxe} re-check inside the drill loop.</p>
 */
public final class CopperPickaxeIIWeapon implements WeaponBehavior {

	static final String KEY_ALLAY = "copper_pickaxe_ii_allay";
	static final String KEY_DRILL = "copper_pickaxe_ii_drill";

	/** {@code CopperPickaxeII#h} / {@code #i} - the real cooldowns in the plugin. */
	static final long ALLAY_COOLDOWN_MILLIS = 45000L;
	static final long DRILL_COOLDOWN_MILLIS = 30000L;

	private static final int ALLAY_STEP = 3;
	private static final double ALLAY_STEP_BLOCKS = 4.5D;
	private static final int ALLAY_MAX_TICKS = 80;
	private static final double ALLAY_MAX_DISTANCE = 120.0D;
	private static final int DRILL_STEP = 4;
	private static final double DRILL_STEP_BLOCKS = 4.0D;
	private static final double DRILL_MAX_DISTANCE = 100.0D;

	private final AltarSMPMod mod;
	private final Set<UUID> bulkMining = new HashSet<>();
	private final Map<UUID, Allay> buddies = new HashMap<>();

	public CopperPickaxeIIWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "copperpickaxeii";
	}

	@Override
	public String displayName() {
		return "Copper Pickaxe II";
	}

	@Override
	public List<String> configFields() {
		return List.of("Allay Buddy Cooldown (ms)", "Lightning Drill Cooldown (ms)");
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		// Shift+F simply reports the state; the two abilities are right-click bound.
		Messaging.send(ctx.player(), "<gold>Copper Pickaxe II <gray>- <aqua>Allay Buddy <gray>[Right Click] "
				+ "<dark_gray>| <light_purple>Lightning Drill <gray>[Shift + Right Click]");
	}

	@Override
	public boolean onUse(AbilityContext ctx, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND || !Identity.is(ctx.weapon(), id())) {
			return false;
		}
		if (ctx.player().isShiftKeyDown()) {
			lightningDrill(ctx);
		} else {
			summonAllayBuddy(ctx);
		}
		return true;
	}

	// ------------------------------------------------------------------ allay

	/** {@code CopperPickaxeII#summonAllayBuddy}. */
	private void summonAllayBuddy(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		UUID id = player.getUUID();
		if (ctx.mod().cooldowns().isOnCooldown(player, KEY_ALLAY)) {
			Messaging.send(player, "<red>Allay Buddy on cooldown: <yellow>"
					+ ctx.remaining(KEY_ALLAY) + "</yellow> remaining.");
			return;
		}
		removeBuddy(player);
		Allay buddy = EntityType.ALLAY.create(level);
		if (buddy == null) {
			Messaging.send(player, "<red>Could not summon the Allay Buddy in this dimension.");
			return;
		}
		buddy.snapTo(player.getEyePosition().x, player.getEyePosition().y, player.getEyePosition().z, player.getYRot(), player.getXRot());
		buddy.setCustomName(Component.literal("\u00A7b" + player.getGameProfile().getName() + "'s Mining Buddy"));
		buddy.setCustomNameVisible(true);
		buddy.setInvulnerable(true);
		buddy.setGlowingTag(true);
		buddy.setPersistenceRequired();
		level.addFreshEntity(buddy);
		this.buddies.put(id, buddy);

		ctx.mod().cooldowns().setCooldown(player, KEY_ALLAY, ALLAY_COOLDOWN_MILLIS);
		CooldownBars.show(player, KEY_ALLAY, "Allay Buddy", BossEvent.BossBarColor.BLUE, ALLAY_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM", 1.0F, 1.2F);
		Messaging.actionBar(player, "<aqua>Allay Buddy sent mining!");

		Vec3 origin = player.getEyePosition();
		Vec3 direction = player.getLookAngle().normalize();
		ItemStack tool = player.getMainHandItem().copy();
		final int[] elapsed = {0};
		final double[] travelled = {0.0D};
		this.mod.scheduler().timer(() -> {
			elapsed[0] += ALLAY_STEP;
			travelled[0] += ALLAY_STEP_BLOCKS;
			if (buddy.isRemoved() || elapsed[0] > ALLAY_MAX_TICKS || travelled[0] > ALLAY_MAX_DISTANCE
					|| !Identity.is(player.getMainHandItem(), id())) {
				removeBuddy(player);
				return;
			}
			Vec3 point = origin.add(direction.scale(travelled[0]));
			buddy.snapTo(point.x, point.y, point.z);
			mineCube(player, BlockPos.containing(point), 1, tool);
			Fx.simple(level, "HAPPY_VILLAGER", point, 3, 0.2D, 0.2D, 0.2D, 0.0D);
		}, 0L, ALLAY_STEP);
	}

	private void removeBuddy(ServerPlayer player) {
		Allay buddy = this.buddies.remove(player.getUUID());
		if (buddy != null && !buddy.isRemoved()) {
			buddy.discard();
		}
	}

	// ------------------------------------------------------------------ drill

	/** {@code CopperPickaxeII#activateLightningDrill}. */
	private void lightningDrill(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		if (ctx.mod().cooldowns().isOnCooldown(player, KEY_DRILL)) {
			Messaging.send(player, "<red>Lightning Drill on cooldown: <yellow>"
					+ ctx.remaining(KEY_DRILL) + "</yellow> remaining.");
			return;
		}
		ctx.mod().cooldowns().setCooldown(player, KEY_DRILL, DRILL_COOLDOWN_MILLIS);
		CooldownBars.show(player, KEY_DRILL, "Lightning Drill", BossEvent.BossBarColor.PURPLE, DRILL_COOLDOWN_MILLIS);
		Fx.sound(level, player.position(), "ENTITY_LIGHTNING_BOLT_THUNDER", 0.5F, 1.5F);
		Messaging.actionBar(player, "<light_purple>Lightning Drill engaged!");

		ItemStack tool = player.getMainHandItem().copy();
		final int[] elapsed = {0};
		final double[] travelled = {2.0D};
		final Set<UUID> struck = new HashSet<>();
		this.mod.scheduler().timer(() -> {
			elapsed[0] += DRILL_STEP;
			if (!Identity.is(player.getMainHandItem(), id()) || elapsed[0] > 100
					|| travelled[0] > DRILL_MAX_DISTANCE || player.isRemoved()) {
				return;
			}
			Vec3 point = player.getEyePosition().add(player.getLookAngle().normalize().scale(travelled[0]));
			travelled[0] += DRILL_STEP_BLOCKS;

			mineCube(player, BlockPos.containing(point), 2, tool);

			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(point, point).inflate(2.5D), e -> e != player && e.isAlive())) {
				if (struck.add(entity.getUUID())) {
					TrueDamage.apply(entity, 2.0D, player, true);
				}
			}

			double spin = elapsed[0] * 0.5D;
			for (int i = 0; i < 8; i++) {
				double angle = spin + i * Math.PI / 4.0D;
				Vec3 ring = point.add(Math.cos(angle) * 1.5D, 0.0D, Math.sin(angle) * 1.5D);
				Fx.simple(level, "ELECTRIC_SPARK", ring, 2, 0.1D, 0.1D, 0.1D, 0.0D);
			}
			Fx.simple(level, "SOUL_FIRE_FLAME", point, 10, 0.5D, 0.5D, 0.5D, 0.02D);
			Fx.simple(level, "ELECTRIC_SPARK", point, 15, 1.0D, 1.0D, 1.0D, 0.1D);
			if (elapsed[0] % 2 == 0) {
				Fx.sound(level, point, "BLOCK_RESPAWN_ANCHOR_CHARGE", 0.3F, 2.0F);
			}
		}, 0L, DRILL_STEP);
	}

	// ------------------------------------------------------------- bulk mining

	@Override
	public void onBlockBreak(AbilityContext ctx, BlockPos pos, BlockState state) {
		ServerPlayer player = ctx.player();
		UUID id = player.getUUID();
		if (!Identity.is(ctx.weapon(), id()) || this.bulkMining.contains(id)) {
			return;
		}
		if (!MineableBlocks.isMineable(ctx.level(), pos, state)) {
			return;
		}
		mineCube(player, pos, 2, ctx.weapon());
	}

	/** Mines a {@code (2*radius+1)} cube centred on {@code pos}, skipping the centre block. */
	private void mineCube(ServerPlayer player, BlockPos pos, int radius, ItemStack tool) {
		UUID id = player.getUUID();
		ServerLevel level = player.serverLevel();
		if (!this.bulkMining.add(id)) {
			return;
		}
		try {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dy = -radius; dy <= radius; dy++) {
					for (int dz = -radius; dz <= radius; dz++) {
						BlockPos extra = pos.offset(dx, dy, dz);
						if (extra.equals(pos)) {
							continue;
						}
						BlockState extraState = level.getBlockState(extra);
						if (extraState.isAir() || !MineableBlocks.isMineable(level, extra, extraState)) {
							continue;
						}
						MineableBlocks.breakFor(player, extra);
					}
				}
			}
		} finally {
			this.bulkMining.remove(id);
		}
	}

	@Override
	public void onTick(AbilityContext ctx) {
		if (this.mod.scheduler().currentTick() % 20L != 0L) {
			return;
		}
		Allay buddy = this.buddies.get(ctx.player().getUUID());
		if (buddy != null && (buddy.isRemoved() || !Identity.is(ctx.player().getMainHandItem(), id()))) {
			removeBuddy(ctx.player());
		}
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		this.bulkMining.remove(player.getUUID());
		removeBuddy(player);
	}
}
