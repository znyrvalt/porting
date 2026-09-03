package com.altarsmp.fabric.ability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.util.Messaging;

/**
 * The stun state the Bone Cage (and the S2 stun effects) apply.
 *
 * <p>{@code BoneBladeWeapon#stunPlayer} marked a player stunned for N seconds,
 * anchored them in place, blocked attacks/interactions/hand swaps and cancelled
 * fall damage while it lasted, showing a draining {@code !STUNNED!} bar. All of
 * that behaviour lives here so every weapon that stuns gets the identical rules.</p>
 */
public final class Stun {

	private static final Map<UUID, Long> UNTIL = new HashMap<>();
	private static final Map<UUID, Vec3> ANCHOR = new HashMap<>();
	private static boolean registered;

	private Stun() {
	}

	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = System.currentTimeMillis();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Long until = UNTIL.get(player.getUUID());
				if (until == null) {
					continue;
				}
				if (until <= now) {
					release(player);
					Messaging.actionBar(player, "");
					continue;
				}
				long remainingMillis = until - now;
				Vec3 anchor = ANCHOR.get(player.getUUID());
				if (anchor != null && player.position().distanceToSqr(anchor) > 0.09D) {
					Motion.teleport(player, anchor, player.getYRot(), player.getXRot());
				}
				double seconds = Math.max(0.0D, remainingMillis / 1000.0D);
				Messaging.actionBar(player, String.format(java.util.Locale.ROOT,
						"<red><bold>!STUNNED!</bold> <gray>%.1fs", seconds));
			}
		});
	}

	public static void stun(ServerPlayer player, int seconds) {
		stunMillis(player, seconds * 1000L);
	}

	public static void stunMillis(ServerPlayer player, long millis) {
		UUID uuid = player.getUUID();
		Long existing = UNTIL.get(uuid);
		long now = System.currentTimeMillis();
		long until = now + millis;
		if (existing != null && existing > until) {
			until = existing;
		}
		UNTIL.put(uuid, until);
		ANCHOR.put(uuid, player.position());
		Motion.stop(player);
		player.fallDistance = 0.0F;
		Messaging.actionBar(player, "<red><bold>!STUNNED!</bold>");
	}

	public static boolean isStunned(ServerPlayer player) {
		Long until = UNTIL.get(player.getUUID());
		if (until == null) {
			return false;
		}
		if (until <= System.currentTimeMillis()) {
			release(player);
			return false;
		}
		return true;
	}

	/** Cancel fall damage for a stunned player (BoneBladeWeapon#onFallDamage). */
	public static boolean cancelFallDamage(ServerPlayer player) {
		return isStunned(player);
	}

	public static void release(ServerPlayer player) {
		UNTIL.remove(player.getUUID());
		ANCHOR.remove(player.getUUID());
	}

	public static void clearAll() {
		UNTIL.clear();
		ANCHOR.clear();
	}

	public static int stunnedCount() {
		return UNTIL.size();
	}

	@Nullable
	public static Vec3 anchor(ServerPlayer player) {
		return ANCHOR.get(player.getUUID());
	}

	/** Stun the players inside a bone-cage style radius. */
	public static int stunArea(ServerLevel level, Vec3 center, double radius, int seconds, @Nullable ServerPlayer ignore) {
		int count = 0;
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (player.level() != level || player == ignore) {
				continue;
			}
			if (player.position().distanceTo(center) <= radius) {
				stun(player, seconds);
				count++;
			}
		}
		return count;
	}
}
