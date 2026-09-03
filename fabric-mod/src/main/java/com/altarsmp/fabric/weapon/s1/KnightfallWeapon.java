package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.data.PlayerRecord;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TextFx;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Knightfall - port of {@code com.altarsmp.weapons.KnightfallWeapon}.
 *
 * <p>The kill-evolving mace. Kills are stored on the item
 * ({@code altarsmp:knightfall_kills}, mirrored into the structured state
 * component and the player record) and drive three things:</p>
 * <ul>
 *   <li>the model: {@code getCmdForKills} - 15 below 6 kills, 16 at 6+, 17 at 10+;</li>
 *   <li>Wind Burst level 1 from 2 kills;</li>
 *   <li>ability gates: Grapple at 4 kills, Hammer Throw at 10 kills.</li>
 * </ul>
 *
 * <p><b>Grapple</b> (F, {@code abilities.knightfall.grapple.cooldown}s): yanks the
 * holder towards the point they are aiming at, adding
 * {@code grapple.launch-height} of lift.</p>
 *
 * <p><b>Hammer Throw</b> (Shift+F, {@code hammer_throw.cooldown}s): the mace is
 * thrown as a spinning display dealing {@code hammer_throw.damage} true damage,
 * then returns to the thrower.</p>
 *
 * <p><b>Passive</b>: Speed {@code passives.knightfall.speed.level} once
 * {@code passives.knightfall.speed.kills-required} kills are banked.</p>
 */
public final class KnightfallWeapon implements WeaponBehavior {

	static final String KEY_GRAPPLE = "knightfall_grapple";
	static final String KEY_HAMMER = "knightfall_hammer";
	static final String KILLS_KEY = Identity.KEY_KNIGHTFALL_KILLS;

	private final AltarSMPMod mod;

	public KnightfallWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "knightfall";
	}

	@Override
	public String displayName() {
		return "Knightfall";
	}

	@Override
	public List<String> configFields() {
		return List.of("Grapple Cooldown (s)", "Grapple Launch Height", "Hammer Throw Cooldown (s)",
				"Hammer Throw Damage", "Passive Speed Kills Required", "Passive Speed Level");
	}

	// ------------------------------------------------------------------- kills

	/** {@code KnightfallWeapon#getCmdForKills}, verbatim. */
	public static int cmdForKills(int kills) {
		if (kills >= 10) {
			return 17;
		}
		return kills >= 6 ? 16 : 15;
	}

	private int kills(ServerPlayer player, ItemStack weapon) {
		int onItem = Identity.kills(weapon, id());
		PlayerRecord record = this.mod.store().player(player.getUUID());
		return Math.max(onItem, record.knightfallKills());
	}

	@Override
	public ItemStack create(ServerPlayer owner) {
		int kills = owner == null ? 0 : this.mod.store().player(owner.getUUID()).knightfallKills();
		return applyKills(ItemFactory.weapon(id(), owner), kills);
	}

	/** Rebuilds model data, Wind Burst and the kill lore for a stack. */
	public ItemStack applyKills(ItemStack stack, int kills) {
		ItemFactory.applyModelData(stack, cmdForKills(kills));
		if (kills >= 2) {
			ItemFactory.applyEnchant(stack, "wind_burst", 1);
		}
		Identity.putLegacy(stack, KILLS_KEY, kills);
		Identity.setKills(stack, kills);
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			List<Component> lines = new ArrayList<>();
			boolean replaced = false;
			for (Component line : lore.lines()) {
				String plain = line.getString();
				if (!replaced && plain.toLowerCase(java.util.Locale.ROOT).startsWith("kills")) {
					lines.add(TextFx.parse("<gray>Kills<dark_gray>: <white>" + kills).copy()
							.withStyle(style -> style.withItalic(false)));
					replaced = true;
					continue;
				}
				lines.add(line);
			}
			if (!replaced) {
				lines.add(1, TextFx.parse("<gray>Kills<dark_gray>: <white>" + kills).copy()
						.withStyle(style -> style.withItalic(false)));
			}
			stack.set(DataComponents.LORE, new ItemLore(lines));
		}
		return stack;
	}

	@Override
	public void onKill(AbilityContext ctx, LivingEntity victim) {
		ServerPlayer player = ctx.player();
		PlayerRecord record = ctx.record();
		record.knightfallKills(record.knightfallKills() + 1);
		record.addWeaponKill(id(), 1);
		this.mod.store().markDirty();
		int kills = record.knightfallKills();
		applyKills(ctx.weapon(), kills);
		Fx.sound(ctx.level(), victim.position(), "ENTITY_WARDEN_ROAR", 0.8F, 1.3F);
		Fx.dust(ctx.level(), victim.position().add(0.0D, 1.0D, 0.0D), 0x8A7A5A, 1.4F, 30, 0.5D, 0.6D, 0.5D);
		Messaging.send(player, "<gold>Knightfall grows heavier. <white>Kills: <yellow>" + kills);
		for (int tier : new int[]{2, 4, 6, 10}) {
			if (kills == tier) {
				String unlocked = switch (tier) {
					case 2 -> "Wind Burst I";
					case 4 -> "Grapple";
					case 6 -> "a heavier model";
					default -> "Hammer Throw";
				};
				Messaging.send(player, "<gold><bold>KNIGHTFALL</bold> <gray>unlocked <yellow>" + unlocked + "</yellow>!");
				Fx.sound(ctx.level(), player.position(), "ENTITY_PLAYER_LEVELUP", 0.9F, 1.5F);
			}
		}
	}

	@Override
	public void onTick(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int kills = kills(player, ctx.weapon());
		if (this.mod.scheduler().currentTick() % 20L == 0L) {
			int required = ctx.cfg("passives.knightfall.speed.kills-required", 1);
			if (kills >= required) {
				Effects.apply(player, "SPEED", 40, ctx.cfg("passives.knightfall.speed.level", 0), true, false, false);
			}
			// Self-heal: keep the held stack's model in sync with banked kills.
			ItemStack held = player.getMainHandItem();
			if (Identity.is(held, id()) && ItemFactory.tooltipStylePath(id()) != null) {
				Integer current = held.get(DataComponents.CUSTOM_MODEL_DATA) == null ? null
						: held.get(DataComponents.CUSTOM_MODEL_DATA).ints().isEmpty() ? null
								: held.get(DataComponents.CUSTOM_MODEL_DATA).ints().get(0);
				if (current == null || current != cmdForKills(kills)) {
					applyKills(held, kills);
				}
			}
		}
	}

	// ------------------------------------------------------------------ grapple

	@Override
	public void onPrimary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int kills = kills(player, ctx.weapon());
		if (kills < 4) {
			Messaging.send(player, "<red>Grapple unlocks at <yellow>4 kills</red> (you have <white>" + kills + "</white>).");
			return;
		}
		int cooldown = ctx.cfg("abilities.knightfall.grapple.cooldown", 15);
		if (!ctx.gate(KEY_GRAPPLE, "Grapple")) {
			return;
		}
		ctx.startCooldown(KEY_GRAPPLE, cooldown);
		CooldownBars.show(player, KEY_GRAPPLE, "Grapple", BossEvent.BossBarColor.YELLOW, cooldown);

		ServerLevel level = ctx.level();
		double launchHeight = ctx.cfgd("abilities.knightfall.grapple.launch-height", 0.7D);
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition();
		double maxDistance = ctx.cfgd("abilities.knightfall.grapple.range", 40.0D);
		Vec3 anchor = start.add(direction.scale(maxDistance));
		double step = 0.5D;
		for (double travelled = step; travelled <= maxDistance; travelled += step) {
			Vec3 candidate = start.add(direction.scale(travelled));
			BlockPos at = BlockPos.containing(candidate);
			if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
				anchor = candidate;
				break;
			}
			anchor = candidate;
		}

		// Chain visual from the mace to the anchor point.
		Vec3 finalAnchor = anchor;
		Display.ItemDisplay chain = Displays.item(level, start, new ItemStack(net.minecraft.world.item.Items.CHAIN), 0.5F);
		Displays.bright(chain);
		final int[] drawn = {0};
		this.mod.scheduler().timer(() -> {
			drawn[0]++;
			Vec3 point = start.add(finalAnchor.subtract(start).scale(Math.min(1.0D, drawn[0] / 6.0D)));
			chain.snapTo(point.x, point.y, point.z, 0.0F, 0.0F);
			Fx.dust(level, point, 0xD4AF37, 0.8F, 2, 0.05D, 0.05D, 0.05D);
		}, 0L, 1L).cancelAfter(8L);
		this.mod.scheduler().later(() -> Displays.remove(chain), 10L);

		Vec3 pull = finalAnchor.subtract(player.getEyePosition()).normalize().scale(2.4D);
		Motion.setVelocity(player, new Vec3(pull.x, pull.y + launchHeight, pull.z));
		Fx.sound(level, player.position(), "ENTITY_WIND_CHARGE_WIND_BURST", 1.0F, 1.2F);
		Fx.sound(level, player.position(), "ITEM_TRIDENT_RIPTIDE_3", 1.0F, 0.9F);
		Messaging.actionBar(player, "<gold><bold>GRAPPLE!");
	}

	// ------------------------------------------------------------- hammer throw

	@Override
	public void onSecondary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int kills = kills(player, ctx.weapon());
		if (kills < 10) {
			Messaging.send(player, "<red>Hammer Throw unlocks at <yellow>10 kills</red> (you have <white>" + kills + "</white>).");
			return;
		}
		int cooldown = ctx.cfg("abilities.knightfall.hammer_throw.cooldown", 30);
		if (!ctx.gate(KEY_HAMMER, "Hammer Throw")) {
			return;
		}
		ctx.startCooldown(KEY_HAMMER, cooldown);
		CooldownBars.show(player, KEY_HAMMER, "Hammer Throw", BossEvent.BossBarColor.YELLOW, cooldown);

		ServerLevel level = ctx.level();
		double damage = ctx.cfgd("abilities.knightfall.hammer_throw.damage", 10.0D);
		double speed = ctx.cfgd("abilities.knightfall.hammer_throw.speed", 2.0D);
		double range = ctx.cfgd("abilities.knightfall.hammer_throw.range", 60.0D);
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition();
		Display.ItemDisplay hammer = Displays.item(level, start, create(player), 1.0F);
		Displays.bright(hammer);
		Fx.sound(level, start, "ENTITY_WARDEN_ATTACK", 1.0F, 0.8F);

		final double[] travelled = {0.0D};
		final float[] spin = {0.0F};
		final boolean[] returning = {false};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				Displays.remove(hammer);
				return;
			}
			spin[0] += 22.0F;
			if (returning[0]) {
				Vec3 toPlayer = player.getEyePosition().subtract(hammer.position()).normalize().scale(speed * 1.6D);
				Vec3 next = hammer.position().add(toPlayer);
				hammer.snapTo(next.x, next.y, next.z, 0.0F, 0.0F);
				Displays.rotate(hammer, spin[0], 1.0F);
				if (next.distanceTo(player.getEyePosition()) < 1.4D) {
					Displays.remove(hammer);
					Fx.sound(level, player.position(), "ITEM_ARMOR_EQUIP_NETHERITE", 1.0F, 0.8F);
				}
				return;
			}
			travelled[0] += speed;
			Vec3 head = start.add(direction.scale(travelled[0]));
			hammer.snapTo(head.x, head.y, head.z, 0.0F, 0.0F);
			Displays.rotate(hammer, spin[0], 1.0F);
			Fx.dust(level, head, 0xD4AF37, 0.9F, 3, 0.1D, 0.1D, 0.1D);

			BlockPos at = BlockPos.containing(head);
			boolean hitBlock = !level.getBlockState(at).getCollisionShape(level, at).isEmpty();
			List<LivingEntity> struck = level.getEntitiesOfClass(LivingEntity.class,
					new AABB(head, head).inflate(1.5D), e -> e != player && e.isAlive());
			if (hitBlock || !struck.isEmpty() || travelled[0] >= range) {
				returning[0] = true;
				Fx.sound(level, head, "ENTITY_GENERIC_EXPLODE", 0.8F, 1.2F);
				Fx.dust(level, head, 0xD4AF37, 1.6F, 30, 0.6D, 0.6D, 0.6D);
				for (LivingEntity entity : struck) {
					TrueDamage.apply(entity, damage, player, false);
					Vec3 away = entity.position().subtract(head).normalize();
					entity.setDeltaMovement(away.x * 0.8D, 0.5D, away.z * 0.8D);
					entity.hurtMarked = true;
				}
			}
		}, 0L, 1L).cancelAfter((long) (range / speed) + 200L);
	}
}
