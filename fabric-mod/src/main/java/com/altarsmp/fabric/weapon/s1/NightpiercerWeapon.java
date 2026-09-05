package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.AbilityTracker;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Nightpiercer - port of {@code com.altarsmp.weapons.NightpiercerWeapon}.
 *
 * <p><b>Crimson Bite</b> (F, {@code abilities.nightpiercer.bite_cooldown}s): a
 * double redstone slash arc in front of the holder hits every living entity
 * within 5 blocks inside a 0.55-dot cone for
 * {@code crimson_bite_damage} true damage, then steals
 * {@code health_steal} hearts by moving a max-health attribute modifier from the
 * victim to the attacker for 200 ticks (10s) before both are removed. Trusted
 * players and immunity-flagged players are skipped.</p>
 *
 * <p><b>Bat Transformation</b> (Shift+F, {@code transform_cooldown}s): for
 * {@code bat_duration} seconds the holder is hidden from everyone else, towed
 * along their look vector at {@code bat_speed} with a soft velocity blend, and
 * trailed by a 12-bat swarm that flies in the original's exact three-band
 * formation. Squid-ink particles, mirror-move/bat-takeoff sounds, brief
 * invisibility on entry and slow falling on exit are all preserved.</p>
 *
 * <p><b>Passive</b>: while holding the blade between world time 13000 and 23000
 * the holder regenerates ({@code passive_regen_level}, 40-tick refresh).</p>
 */
public final class NightpiercerWeapon implements WeaponBehavior {

	static final String KEY_TRANSFORM = "nightpiercer_transform";
	static final String KEY_BITE = "nightpiercer_bite";
	static final String TAG_KILLED_BY = "killed_by_nightpiercer";

	private static final int SWARM_SIZE = 12;
	private static final double BITE_RANGE = 5.0D;
	private static final double BITE_CONE_DOT = 0.55D;
	private static final long MODIFIER_LIFETIME_TICKS = 200L;

	private final AltarSMPMod mod;
	private final Map<UUID, List<Bat>> activeBatSwarms = new HashMap<>();
	private final Map<UUID, Boolean> hidden = new HashMap<>();

	public NightpiercerWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "nightpiercer";
	}

	@Override
	public String displayName() {
		return "Nightpiercer";
	}

	@Override
	public List<String> configFields() {
		return List.of("Transformation Cooldown (s)", "Bite Cooldown (s)", "Bite Charge Timeout (s)",
				"Crimson Bite Damage", "Health Steal (hearts)", "Bat Duration (s)", "Bat Speed",
				"Passive Regen Level");
	}

	int transformCooldown(AbilityContext ctx) {
		return ctx.cfg("abilities.nightpiercer.transform_cooldown", 60);
	}

	int biteCooldown(AbilityContext ctx) {
		return ctx.cfg("abilities.nightpiercer.bite_cooldown", 30);
	}

	double crimsonBiteDamage(AbilityContext ctx) {
		return ctx.cfgd("abilities.nightpiercer.crimson_bite_damage", 4.0D);
	}

	double healthSteal(AbilityContext ctx) {
		return ctx.cfgd("abilities.nightpiercer.health_steal", 4.0D);
	}

	int passiveRegenLevel(AbilityContext ctx) {
		return ctx.cfg("abilities.nightpiercer.passive_regen_level", 0);
	}

	// ------------------------------------------------------------- passive regen

	@Override
	public void onTick(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		if (this.mod.scheduler().currentTick() % 20L != 0L) {
			return;
		}
		if (!Identity.is(player.getMainHandItem(), id()) && !Identity.is(player.getOffhandItem(), id())) {
			return;
		}
		long time = player.level().getDayTime() % 24000L;
		if (time >= 13000L && time <= 23000L) {
			Effects.apply(player, "REGENERATION", 40, passiveRegenLevel(ctx), false, false, false);
		}
	}

	// ------------------------------------------------------------ crimson bite

	@Override
	public void onPrimary(AbilityContext ctx) {
		useCrimsonBite(ctx);
	}

	/** {@code NightpiercerWeapon#useCrimsonBite}. */
	private void useCrimsonBite(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		if (!ctx.gate(KEY_BITE, "Crimson Bite")) {
			return;
		}
		ctx.startCooldown(KEY_BITE, biteCooldown(ctx));
		CooldownBars.show(player, KEY_BITE, "Crimson Bite", BossEvent.BossBarColor.RED, biteCooldown(ctx));
		Fx.sound(level, player.position(), "ENTITY_PHANTOM_BITE", 1.0F, 0.8F);
		Fx.sound(level, player.position(), "ITEM_TRIDENT_THROW", 1.0F, 0.7F);
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		spawnCrimsonBiteSlash(ctx, 0.5D);
		spawnCrimsonBiteSlash(ctx, -0.5D);
		hitCrimsonBiteTargets(ctx);
	}

	/** {@code NightpiercerWeapon#spawnCrimsonBiteSlash} - the redstone blade arc. */
	private void spawnCrimsonBiteSlash(AbilityContext ctx, double lateral) {
		ServerLevel level = ctx.level();
		Vec3 eye = ctx.player().getEyePosition();
		Vec3 forward = ctx.player().getLookAngle().normalize();
		Vec3 side = forward.cross(new Vec3(0.0D, 1.0D, 0.0D));
		if (side.lengthSqr() < 0.001D) {
			side = new Vec3(1.0D, 0.0D, 0.0D);
		}
		side = side.normalize();
		Vec3 origin = eye.add(side.scale(lateral)).add(forward.scale(1.8D));
		for (double offset = -1.6D; offset <= 1.6D; offset += 0.14D) {
			double lift = Math.sin((offset + 1.6D) / 3.2D * Math.PI) * 1.15D - 0.65D;
			Vec3 point = origin.add(side.scale(offset)).add(0.0D, lift, 0.0D);
			Fx.itemParticles(level, "REDSTONE_BLOCK", point, 1, 0.02D, 0.02D, 0.02D, 0.005D);
			Fx.itemParticles(level, "REDSTONE", point, 1, 0.03D, 0.03D, 0.03D, 0.1D);
		}
	}

	/** {@code NightpiercerWeapon#hitCrimsonBiteTargets}. */
	private void hitCrimsonBiteTargets(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		Vec3 eye = player.getEyePosition();
		Vec3 forward = player.getLookAngle().normalize();
		AABB search = new AABB(BlockPos.containing(eye)).inflate(BITE_RANGE, 3.0D, BITE_RANGE);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, search,
				entity -> entity != player && entity.isAlive())) {
			Vec3 toVictim = victim.position().add(0.0D, 1.0D, 0.0D).subtract(eye);
			if (toVictim.length() > BITE_RANGE || toVictim.normalize().dot(forward) < BITE_CONE_DOT) {
				continue;
			}
			if (!ctx.abilityAllowedOn(victim instanceof ServerPlayer serverPlayer ? serverPlayer : null)) {
				continue;
			}
			applyCrimsonBiteHit(ctx, victim);
		}
	}

	/** {@code NightpiercerWeapon#applyCrimsonBiteHit}. */
	private void applyCrimsonBiteHit(AbilityContext ctx, LivingEntity victim) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		double steal = healthSteal(ctx);
		TrueDamage.apply(victim, crimsonBiteDamage(ctx), player, true);

		Identifier victimId = Identifier.fromNamespaceAndPath(AltarSMPMod.MOD_ID,
				"crimson_bite_victim_" + victim.getUUID());
		Identifier attackerId = Identifier.fromNamespaceAndPath(AltarSMPMod.MOD_ID,
				"crimson_bite_attacker_" + player.getUUID());

		AttributeInstance victimHealth = victim.getAttribute(Attributes.MAX_HEALTH);
		if (victimHealth != null) {
			victimHealth.removeModifier(victimId);
			victimHealth.addOrUpdateTransientModifier(new AttributeModifier(victimId, -steal,
					AttributeModifier.Operation.ADD_VALUE));
			if (victim.getHealth() > victimHealth.getValue()) {
				victim.setHealth((float) Math.max(1.0D, victimHealth.getValue()));
			}
		}
		AttributeInstance attackerHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (attackerHealth != null) {
			attackerHealth.removeModifier(attackerId);
			attackerHealth.addOrUpdateTransientModifier(new AttributeModifier(attackerId, steal,
					AttributeModifier.Operation.ADD_VALUE));
		}

		Vec3 center = victim.position().add(0.0D, 1.0D, 0.0D);
		Fx.sound(level, center, "ENTITY_EVOKER_PREPARE_SUMMON", 1.0F, 1.2F);
		Fx.sound(level, center, "ENTITY_EVOKER_FANGS_ATTACK", 2.0F, 1.4F);
		Fx.sound(level, center, "ENTITY_PLAYER_HURT", 1.0F, 0.8F);
		startCrimsonBiteVictimParticles(victim);

		UUID attacker = player.getUUID();
		this.mod.scheduler().later(() -> {
			if (victim.isAlive()) {
				AttributeInstance instance = victim.getAttribute(Attributes.MAX_HEALTH);
				if (instance != null) {
					instance.removeModifier(victimId);
				}
			}
			ServerPlayer online = ctx.server().getPlayerList().getPlayer(attacker);
			if (online != null) {
				AttributeInstance instance = online.getAttribute(Attributes.MAX_HEALTH);
				if (instance != null) {
					instance.removeModifier(attackerId);
				}
			}
		}, MODIFIER_LIFETIME_TICKS);
	}

	/** {@code NightpiercerWeapon#startCrimsonBiteVictimParticles}. */
	private void startCrimsonBiteVictimParticles(LivingEntity victim) {
		final int[] ticks = {0};
		this.mod.scheduler().timer(() -> {
			if (ticks[0] >= 200 || !victim.isAlive()) {
				return;
			}
			ticks[0]++;
			Fx.itemParticles((ServerLevel) victim.level(), victim.position().add(0.0D, 1.0D, 0.0D),
					"REDSTONE", 1, 0.25D, 0.5D, 0.25D, 0.0D);
		}, 0L, 1L).cancelAfter(MODIFIER_LIFETIME_TICKS);
	}

	// -------------------------------------------------------- bat transformation

	@Override
	public void onSecondary(AbilityContext ctx) {
		useTransformation(ctx);
	}

	/** {@code NightpiercerWeapon#useTransformation}. */
	private void useTransformation(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		if (!ctx.gate(KEY_TRANSFORM, "Bat Transformation")) {
			return;
		}
		ctx.startCooldown(KEY_TRANSFORM, transformCooldown(ctx));
		CooldownBars.show(player, KEY_TRANSFORM, "Transformation", BossEvent.BossBarColor.YELLOW,
				transformCooldown(ctx));
		Fx.simple(level, "SQUID_INK", player.position().add(0.0D, 1.0D, 0.0D), 30, 0.3D, 0.5D, 0.3D, 0.0D);
		Fx.sound(level, player.position(), "ENTITY_ILLUSIONER_MIRROR_MOVE", 1.5F, 1.0F);
		Fx.sound(level, player.position(), "ENTITY_BAT_TAKEOFF", 1.5F, 1.0F);
		Effects.apply(player, "INVISIBILITY", 20, 0, false, false, false);
		AbilityTracker.recordAbilityUse(player);
		batDash(ctx, ctx.cfg("abilities.nightpiercer.bat_duration", 3),
				ctx.cfgd("abilities.nightpiercer.bat_speed", 0.8D));
	}

	/** {@code NightpiercerWeapon#batDash}. */
	private void batDash(AbilityContext ctx, int seconds, double speed) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int durationTicks = seconds * 20;
		cleanupBatTransform(player, false);
		List<Bat> swarm = spawnBatSwarm(player);
		hideFromOthers(player);

		final int[] remaining = {durationTicks};
		final int[] tick = {0};
		this.mod.scheduler().timer(() -> {
			if (remaining[0] <= 0 || player.isRemoved() || !player.isAlive()) {
				cleanupBatTransform(player, remaining[0] <= 0);
				return;
			}
			Vec3 direction = player.getLookAngle().normalize();
			Vec3 anchor = player.position().add(0.0D, 1.25D, 0.0D);
			for (int index = 0; index < swarm.size(); index++) {
				Bat bat = swarm.get(index);
				if (bat.isAlive()) {
					Vec3 point = batSwarmLocation(anchor, direction, index, tick[0]);
					bat.snapTo(point.x, point.y, point.z,
							(float) Math.toDegrees(Math.atan2(-direction.x, direction.z)),
							(float) Math.toDegrees(-Math.asin(direction.y)));
					bat.setAwake(true);
				}
			}
			if (tick[0] % 10 == 0) {
				hideFromOthers(player);
			}

			Vec3 push = direction.scale(speed);
			Vec3 blended = player.getDeltaMovement().scale(0.72D)
					.add(new Vec3(push.x, direction.y * speed * 0.7D + 0.05D, push.z).scale(0.28D));
			double cap = speed * 1.25D;
			if (blended.length() > cap) {
				blended = blended.normalize().scale(cap);
			}
			Motion.setVelocity(player, blended);
			player.resetFallDistance();
			player.invulnerableTime = 5;
			Fx.simple(level, "SQUID_INK", player.position().add(0.0D, 1.0D, 0.0D), 1, 0.0D, 0.0D, 0.0D, 9999999.0D);
			remaining[0]--;
			tick[0]++;
		}, 0L, 1L).cancelAfter(durationTicks + 5L);
	}

	/** {@code NightpiercerWeapon#spawnBatSwarm} - 12 invulnerable silent cosmetic bats. */
	private List<Bat> spawnBatSwarm(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		List<Bat> swarm = new ArrayList<>();
		Vec3 anchor = player.position().add(0.0D, 1.25D, 0.0D);
		for (int index = 0; index < SWARM_SIZE; index++) {
			Bat bat = EntityType.BAT.create(level);
			if (bat == null) {
				break;
			}
			bat.snapTo(anchor.x, anchor.y, anchor.z);
			bat.setNoAi(true);
			bat.setInvulnerable(true);
			bat.setNoGravity(true);
			bat.setSilent(true);
			bat.setAwake(true);
			bat.setPersistenceRequired();
			level.addFreshEntity(bat);
			swarm.add(bat);
		}
		this.activeBatSwarms.put(player.getUUID(), swarm);
		return swarm;
	}

	/** {@code NightpiercerWeapon#getBatSwarmLocation} - the three-band flight formation. */
	Vec3 batSwarmLocation(Vec3 anchor, Vec3 forward, int index, int tick) {
		Vec3 side = new Vec3(0.0D, 1.0D, 0.0D).cross(forward);
		if (side.lengthSqr() < 0.001D) {
			side = new Vec3(1.0D, 0.0D, 0.0D);
		}
		side = side.normalize();
		Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
		double scale = 1.0D;
		double phase = index * (Math.PI / 6.0D) + tick * 0.18D;
		double along;
		double lateral;
		double vertical;
		if (index < 5) {
			along = scale * (0.85D + index * 0.18D);
			lateral = Math.cos(phase) * scale * (0.28D + index * 0.04D);
			vertical = Math.sin(phase * 1.2D + index) * scale * 0.22D;
		} else if (index < 9) {
			int band = index - 5;
			along = -scale * (0.45D + band * 0.18D);
			lateral = Math.cos(phase) * scale * (0.45D + band * 0.06D);
			vertical = Math.sin(phase * 1.1D + index) * scale * 0.24D;
		} else {
			double spread = 0.85D + index % 3 * 0.12D;
			along = Math.sin(phase * 0.8D + index) * scale * 0.18D;
			lateral = Math.cos(phase) * scale * spread;
			vertical = Math.sin(phase * 1.35D + tick * 0.08D) * scale * 0.55D + (index % 3 - 1) * scale * 0.12D;
		}
		return anchor.add(forward.scale(along)).add(side.scale(lateral)).add(up.scale(vertical));
	}

	/**
	 * {@code NightpiercerWeapon#hideFromOthers}. Bukkit hid the player entity from
	 * every other connection; the port uses the vanilla invisibility flag, which
	 * is the same effect clients already understand and needs no packet pipeline.
	 */
	void hideFromOthers(ServerPlayer player) {
		this.hidden.put(player.getUUID(), Boolean.TRUE);
		player.setInvisible(true);
	}

	private void showToOthers(ServerPlayer player) {
		this.hidden.remove(player.getUUID());
		player.setInvisible(false);
	}

	public boolean isHidden(ServerPlayer player) {
		return this.hidden.containsKey(player.getUUID());
	}

	/** {@code NightpiercerWeapon#cleanupBatTransform}. */
	void cleanupBatTransform(ServerPlayer player, boolean completed) {
		List<Bat> swarm = this.activeBatSwarms.remove(player.getUUID());
		if (swarm != null) {
			for (Bat bat : swarm) {
				if (bat.isAlive()) {
					bat.discard();
				}
			}
		}
		if (this.hidden.containsKey(player.getUUID())) {
			showToOthers(player);
		}
		if (completed && !player.isRemoved()) {
			Fx.sound(player.serverLevel(), player.position(), "ENTITY_ILLUSIONER_MIRROR_MOVE", 1.5F, 0.8F);
			Fx.sound(player.serverLevel(), player.position(), "ENTITY_BAT_TAKEOFF", 1.5F, 0.8F);
			Effects.apply(player, "SLOW_FALLING", 10, 0, false, false, false);
		}
	}

	// -------------------------------------------------------------- kill tagging

	public static void markKilledByNightpiercer(ServerPlayer player) {
		player.addTag(TAG_KILLED_BY);
	}

	public static boolean wasKilledByNightpiercer(ServerPlayer player) {
		return player.getTags().contains(TAG_KILLED_BY);
	}

	public static void clearKilledByNightpiercer(ServerPlayer player) {
		player.removeTag(TAG_KILLED_BY);
	}

	@Override
	public void onKill(AbilityContext ctx, LivingEntity victim) {
		if (victim instanceof ServerPlayer killed) {
			markKilledByNightpiercer(killed);
		}
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		cleanupBatTransform(player, false);
		CooldownBars.hide(player, KEY_TRANSFORM);
		CooldownBars.hide(player, KEY_BITE);
	}
}
