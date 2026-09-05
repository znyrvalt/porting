package com.altarsmp.fabric.ability;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Movement helpers for the leap/dash/knockback abilities.
 *
 * <p>Bukkit's {@code player.setVelocity(vector)} becomes
 * {@code setDeltaMovement} plus {@code hurtMarked = true}; without the second
 * half the client never hears about the new velocity and the leap looks like it
 * did nothing. Every ability in the plugin that moved a player went through
 * {@code setVelocity}, so they all come through here.</p>
 */
public final class Motion {

	private Motion() {
	}

	public static void setVelocity(ServerPlayer player, Vec3 velocity) {
		player.setDeltaMovement(velocity);
		player.hurtMarked = true;
		player.fallDistance = 0.0F;
	}

	/** The same treatment for any living entity (Pale Roots launches, ability knockback). */
	public static void setVelocity(LivingEntity entity, Vec3 velocity) {
		entity.setDeltaMovement(velocity);
		entity.hurtMarked = true;
	}

	/** Adds {@code delta} to the entity's current velocity and marks it for sync. */
	public static void addVelocity(LivingEntity entity, Vec3 delta) {
		setVelocity(entity, entity.getDeltaMovement().add(delta));
	}

	/** Launch along the player's look direction (Skeletal Leap, Shadow Leap, ...). */
	public static void launch(ServerPlayer player, double speed) {
		Vec3 direction = player.getLookAngle().normalize();
		setVelocity(player, direction.multiply(speed));
	}

	/** Launch horizontally with an upward component. */
	public static void launch(ServerPlayer player, double speed, double up) {
		Vec3 direction = player.getLookAngle().normalize();
		setVelocity(player, new Vec3(direction.x * speed, up, direction.z * speed));
	}

	/** Additive impulse - used by gusts, meteors and knockback abilities. */
	public static void push(ServerPlayer player, Vec3 delta) {
		setVelocity(player, player.getDeltaMovement().add(delta));
	}

	public static void stop(ServerPlayer player) {
		setVelocity(player, Vec3.ZERO);
	}

	/**
	 * Teleport that keeps the server from rubber-banding the player afterwards.
	 * Single call site on purpose: {@code teleportTo} gained/lost overloads across
	 * versions, so if the signature moved in a future Minecraft this is the only
	 * line to touch.
	 */
	public static void teleport(ServerPlayer player, Vec3 pos, float yaw, float pitch) {
		player.fallDistance = 0.0F;
		player.teleportTo(player.serverLevel(), pos.x, pos.y, pos.z,
				java.util.Set.<net.minecraft.world.entity.Relative>of(), yaw, pitch, false);
	}

	public static void teleport(ServerPlayer player, Vec3 pos) {
		teleport(player, pos, player.getYRot(), player.getXRot());
	}

	public static void recordLeap(ServerPlayer player) {
		AbilityTracker.recordLeap(player);
	}

	public static void recordDash(ServerPlayer player) {
		AbilityTracker.recordDash(player);
	}
}
