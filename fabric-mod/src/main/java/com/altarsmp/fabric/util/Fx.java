package com.altarsmp.fabric.util;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.core.particles.SpellParticleOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * Single point of contact with the Minecraft presentation APIs (particles,
 * sounds, lightning).
 *
 * <p>All AltarSMP VFX/SFX go through this class so that the exact 26.2 call
 * signatures live in one place and every ability can describe its effects
 * declaratively.  Nothing here is a placeholder: each call reaches the real
 * {@link ServerLevel} particle/sound broadcast or spawns a real entity.</p>
 */
public final class Fx {

	private Fx() {
	}

	// ------------------------------------------------------------- particles

	public static void send(ServerLevel level, ParticleOptions options, Vec3 pos, int count, double dx, double dy, double dz, double speed) {
		if (level == null || options == null) {
			return;
		}
		level.sendParticles(options, pos.x, pos.y, pos.z, count, dx, dy, dz, speed);
	}

	public static void send(ServerLevel level, ParticleOptions options, double x, double y, double z, int count, double dx, double dy, double dz, double speed) {
		if (level == null || options == null) {
			return;
		}
		level.sendParticles(options, x, y, z, count, dx, dy, dz, speed);
	}

	public static void simple(ServerLevel level, ParticleType<?> type, Vec3 pos, int count, double dx, double dy, double dz, double speed) {
		send(level, optionsFor(type), pos, count, dx, dy, dz, speed);
	}

	/**
	 * Bukkit {@code spawnParticle(Particle.X, location, count, dx, dy, dz, speed)}
	 * named the way the plugin named it - resolved through the registry so a name
	 * that no longer exists in this Minecraft version is logged, never guessed.
	 */
	public static void simple(ServerLevel level, String bukkitParticle, Vec3 pos, int count,
			double dx, double dy, double dz, double speed) {
		simple(level, GameRegistry.particle(bukkitParticle), pos, count, dx, dy, dz, speed);
	}

	private static final Set<ParticleType<?>> REPORTED_OPTIONLESS = new HashSet<>();

	/**
	 * Builds real {@link ParticleOptions} for any particle type.
	 *
	 * <p>{@code SimpleParticleType} instances are their own options, but a handful
	 * of particles the plugin used are <em>typed</em>: {@code FLASH} carries a
	 * colour, {@code DRAGON_BREATH} a power value, {@code DUST} colour and size,
	 * {@code EFFECT}/{@code INSTANT_EFFECT} a spell colour. Passing such a type
	 * straight to {@code sendParticles} used to do nothing at all, so the defaults
	 * Bukkit applied are built here instead - and a type that genuinely needs data
	 * we do not have (an item stack, a block state, a vibration source) is logged
	 * once rather than silently dropped.</p>
	 */
	@Nullable
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static ParticleOptions optionsFor(@Nullable ParticleType<?> type) {
		if (type == null) {
			return null;
		}
		if (type instanceof ParticleOptions options) {
			return options;
		}
		if (type == ParticleTypes.DUST) {
			return new DustParticleOptions(0xFFFFFFFF, 1.0F);
		}
		if (type == ParticleTypes.FLASH || type == ParticleTypes.ENTITY_EFFECT || type == ParticleTypes.TINTED_LEAVES) {
			return ColorParticleOption.create((ParticleType) type, 0xFFFFFFFF);
		}
		if (type == ParticleTypes.DRAGON_BREATH) {
			return PowerParticleOption.create((ParticleType) type, 1.0F);
		}
		if (type == ParticleTypes.EFFECT || type == ParticleTypes.INSTANT_EFFECT) {
			return SpellParticleOption.create((ParticleType) type, 0xFFFFFF, 1.0F);
		}
		if (REPORTED_OPTIONLESS.add(type)) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] particle '{}' needs extra data (item stack, block state or "
					+ "vibration source) - use the matching Fx helper; nothing was spawned for this call",
					BuiltInRegistries.PARTICLE_TYPE.getKey(type));
		}
		return null;
	}

	/**
	 * Bukkit {@code spawnParticle(particle, loc, count, dx, dy, dz, speed, color)}:
	 * FLASH / ENTITY_EFFECT / TINTED_LEAVES take an ARGB colour, DUST a colour plus
	 * size and EFFECT/INSTANT_EFFECT a plain RGB.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void colored(ServerLevel level, String bukkitParticle, Vec3 pos, int argb, int count,
			double dx, double dy, double dz, double speed) {
		ParticleType<?> type = GameRegistry.particle(bukkitParticle);
		if (type == null) {
			return;
		}
		ParticleOptions options;
		if (type == ParticleTypes.DUST) {
			options = new DustParticleOptions(argb | 0xFF000000, 1.0F);
		} else if (type == ParticleTypes.EFFECT || type == ParticleTypes.INSTANT_EFFECT) {
			options = SpellParticleOption.create((ParticleType) type, argb & 0xFFFFFF, 1.0F);
		} else if (type == ParticleTypes.FLASH || type == ParticleTypes.ENTITY_EFFECT
				|| type == ParticleTypes.TINTED_LEAVES) {
			options = ColorParticleOption.create((ParticleType) type, argb | 0xFF000000);
		} else {
			options = optionsFor(type);
		}
		send(level, options, pos, count, dx, dy, dz, speed);
	}

	/** Bukkit's dragon-breath extra data - the particle's {@code power} value. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void power(ServerLevel level, String bukkitParticle, Vec3 pos, float power, int count,
			double dx, double dy, double dz, double speed) {
		ParticleType<?> type = GameRegistry.particle(bukkitParticle);
		if (type == null) {
			return;
		}
		ParticleOptions options = type == ParticleTypes.DRAGON_BREATH
				? PowerParticleOption.create((ParticleType) type, power)
				: optionsFor(type);
		send(level, options, pos, count, dx, dy, dz, speed);
	}

	/**
	 * {@code Particle.BLOCK_CRUMBLE} with a material's block state, falling back to
	 * {@code Particle.BLOCK} on builds that predate block_crumble.
	 */
	@SuppressWarnings("unchecked")
	public static void crumble(ServerLevel level, String bukkitMaterial, Vec3 pos, int count,
			double dx, double dy, double dz, double speed) {
		BlockState state = blockState(bukkitMaterial);
		if (state == null) {
			return;
		}
		ParticleType<?> type = GameRegistry.particle("BLOCK_CRUMBLE");
		if (type == null) {
			type = GameRegistry.particle("BLOCK");
		}
		if (type == null) {
			return;
		}
		send(level, new BlockParticleOption((ParticleType<BlockParticleOption>) type, state),
				pos, count, dx, dy, dz, speed);
	}

	/**
	 * Coloured dust particle - the workhorse of AltarSMP VFX (Paladin Axe golden
	 * arc, Bloodlust red mist, Frost Scythe ice spray, ...).
	 *
	 * @param rgb packed 0xRRGGBB
	 * @param size dust scale (Bukkit {@code DustOptions.size})
	 */
	public static void dust(ServerLevel level, Vec3 pos, int rgb, float size, int count, double dx, double dy, double dz) {
		send(level, new DustParticleOptions(rgb | 0xFF000000, size), pos, count, dx, dy, dz, 0.0D);
	}

	public static void dust(ServerLevel level, double x, double y, double z, int rgb, float size, int count, double dx, double dy, double dz) {
		send(level, new DustParticleOptions(rgb | 0xFF000000, size), x, y, z, count, dx, dy, dz, 0.0D);
	}

	/** Bukkit {@code Particle.BLOCK} / {@code Particle.BLOCK_CRUMBLE} equivalent. */
	public static void block(ServerLevel level, ParticleType<BlockParticleOption> type, BlockPos pos, int count, double dx, double dy, double dz, double speed) {
		BlockState state = level.getBlockState(pos);
		send(level, new BlockParticleOption(type, state), pos.getCenter(), count, dx, dy, dz, speed);
	}

	/** Bukkit {@code Particle.ITEM} equivalent. */
	public static void item(ServerLevel level, ItemStack stack, Vec3 pos, int count, double dx, double dy, double dz, double speed) {
		send(level, new ItemParticleOption(ParticleTypes.ITEM, stack), pos, count, dx, dy, dz, speed);
	}

	/** Ring of particles on the XZ plane - used by Stalwart release, lightning rings, gust. */
	public static void ring(ServerLevel level, ParticleOptions options, Vec3 center, double radius, int points, double y) {
		for (int i = 0; i < points; i++) {
			double angle = (Math.PI * 2.0D * i) / points;
			send(level, options, center.x + Math.cos(angle) * radius, center.y + y, center.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
		}
	}

	/** Vertical helix - used by Hyperion Holy Lance and Wither Symbiote infection. */
	public static void helix(ServerLevel level, ParticleOptions options, Vec3 base, double radius, double height, int points, int turns) {
		for (int i = 0; i < points; i++) {
			double t = i / (double) Math.max(1, points - 1);
			double angle = turns * Math.PI * 2.0D * t;
			send(level, options, base.x + Math.cos(angle) * radius, base.y + height * t, base.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
		}
	}

	// ---------------------------------------------------------------- sounds

	/** Plays a resource-pack provided sound (e.g. {@code custom:copper}). */
	public static void sound(ServerLevel level, Vec3 pos, Identifier soundId, float volume, float pitch) {
		sound(level, pos, SoundEvent.createVariableRangeEvent(soundId), SoundSource.MASTER, volume, pitch);
	}

	public static void sound(ServerLevel level, Vec3 pos, Identifier soundId, SoundSource source, float volume, float pitch) {
		sound(level, pos, SoundEvent.createVariableRangeEvent(soundId), source, volume, pitch);
	}

	public static void sound(ServerLevel level, Vec3 pos, SoundEvent event, float volume, float pitch) {
		sound(level, pos, event, SoundSource.MASTER, volume, pitch);
	}

	public static void sound(ServerLevel level, Vec3 pos, SoundEvent event, SoundSource source, float volume, float pitch) {
		if (level == null || event == null) {
			return;
		}
		level.playSound(null, pos.x, pos.y, pos.z, event, source, volume, pitch);
	}

	/** Sound heard by a single player only (UI feedback, ability confirmations). */
	public static void soundTo(ServerPlayer player, Identifier soundId, float volume, float pitch) {
		if (player == null) {
			return;
		}
		player.playNotifySound(SoundEvent.createVariableRangeEvent(soundId), SoundSource.MASTER, volume, pitch);
	}

	public static void soundTo(ServerPlayer player, SoundEvent event, float volume, float pitch) {
		if (player == null) {
			return;
		}
		player.playNotifySound(event, SoundSource.MASTER, volume, pitch);
	}

	// -------------------------------------------------------------- lightning

	/**
	 * Real lightning bolt entity.
	 *
	 * @param cosmetic when true the bolt does not set fire or damage entities -
	 *                 matches Bukkit's {@code World#strikeLightningEffect}.
	 */
	public static void lightning(ServerLevel level, Vec3 pos, boolean cosmetic) {
		if (level == null) {
			return;
		}
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
		if (bolt == null) {
			return;
		}
		bolt.snapTo(pos.x, pos.y, pos.z);
		bolt.setVisualOnly(cosmetic);
		level.addFreshEntity(bolt);
	}

	public static void lightning(ServerLevel level, BlockPos pos, boolean cosmetic) {
		lightning(level, Vec3.atBottomCenterOf(pos), cosmetic);
	}

	// ------------------------------------------------------------ entity helpers

	public static void forEachNearby(ServerLevel level, Vec3 center, double radius, Consumer<Entity> action) {
		if (level == null) {
			return;
		}
		AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius, center.x + radius, center.y + radius, center.z + radius);
		for (Entity entity : level.getEntities(null, box)) {
			if (entity != null && entity.isAlive()) {
				action.accept(entity);
			}
		}
	}

	// ------------------------------------------------- Bukkit-name convenience
	//
	// Every weapon class in the original named sounds/particles/materials with the
	// Bukkit enum constant. These helpers resolve those names through GameRegistry
	// so no weapon behaviour has to hard-code a Mojmap constant (which is exactly
	// how stale bytecode descriptors creep into a port).

	/** Plays a sound resolved from a Bukkit {@code Sound} enum name. */
	public static void sound(ServerLevel level, Vec3 pos, String bukkitSound, float volume, float pitch) {
		Identifier id = GameRegistry.soundId(bukkitSound);
		if (id != null) {
			sound(level, pos, id, volume, pitch);
		}
	}

	/** Plays a sound to one player only (Bukkit {@code player.playSound}). */
	public static void soundTo(ServerPlayer player, String bukkitSound, float volume, float pitch) {
		Identifier id = GameRegistry.soundId(bukkitSound);
		if (id != null) {
			soundTo(player, id, volume, pitch);
		}
	}

	/**
	 * Positional sound audible to one player only - Bukkit's
	 * {@code player.playSound(otherLocation, sound, volume, pitch)}, which the
	 * plugin used for echolocation-style "I hear them over there" cues.
	 */
	public static void soundAt(ServerPlayer viewer, Vec3 pos, String bukkitSound, float volume, float pitch) {
		if (viewer == null) {
			return;
		}
		Identifier id = GameRegistry.soundId(bukkitSound);
		if (id == null) {
			return;
		}
		net.minecraft.sounds.SoundEvent event = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(id);
		if (event == null) {
			return;
		}
		viewer.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
				net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event),
				SoundSource.PLAYERS, pos.x, pos.y, pos.z, volume, pitch,
				viewer.level().random.nextLong()));
	}

	/** {@code Particle.BLOCK} with a Bukkit material's block state. */
	public static void blockParticles(ServerLevel level, String bukkitMaterial, Vec3 pos, int count,
			double dx, double dy, double dz, double speed) {
		net.minecraft.world.level.block.state.BlockState state = blockState(bukkitMaterial);
		if (state != null) {
			block(level, net.minecraft.core.particles.ParticleTypes.BLOCK, state, pos, count, dx, dy, dz, speed);
		}
	}

	/** {@code Particle.ITEM} with a Bukkit material's item. */
	public static void itemParticles(ServerLevel level, String bukkitMaterial, Vec3 pos, int count,
			double dx, double dy, double dz, double speed) {
		net.minecraft.world.item.Item item = GameRegistry.item(bukkitMaterial);
		if (item != null) {
			item(level, new net.minecraft.world.item.ItemStack(item), pos, count, dx, dy, dz, speed);
		}
	}

	/** Any particle by Bukkit {@code Particle} enum name. */
	public static void simple(ServerLevel level, String bukkitParticle, Vec3 pos, int count,
			double dx, double dy, double dz, double speed) {
		net.minecraft.core.particles.ParticleType<?> type = GameRegistry.particle(bukkitParticle);
		if (type != null) {
			simple(level, type, pos, count, dx, dy, dz, speed);
		}
	}

	@javax.annotation.Nullable
	public static net.minecraft.world.level.block.state.BlockState blockState(String bukkitMaterial) {
		net.minecraft.world.level.block.Block block = GameRegistry.block(bukkitMaterial);
		return block == null ? null : block.defaultBlockState();
	}

	/** {@code Particle.BLOCK} overload taking a block state and a position vector. */
	public static void block(ServerLevel level, net.minecraft.core.particles.ParticleType<net.minecraft.core.particles.BlockParticleOption> type,
			net.minecraft.world.level.block.state.BlockState state, Vec3 pos, int count, double dx, double dy, double dz, double speed) {
		level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(type, state),
				pos.x, pos.y, pos.z, count, dx, dy, dz, speed);
	}


	/** Particle visible to one player only - Bukkit's {@code player.spawnParticle}. */
	public static void onlyTo(ServerPlayer viewer, ParticleOptions options, Vec3 pos, int count,
			double dx, double dy, double dz, double speed) {
		if (viewer.level() instanceof ServerLevel level) {
			level.sendParticles(viewer, options, false, pos.x, pos.y, pos.z, count, dx, dy, dz, speed);
		}
	}

	/** Dust particle visible to one player only. */
	public static void dustOnlyTo(ServerPlayer viewer, Vec3 pos, int rgb, float size, int count,
			double dx, double dy, double dz) {
		onlyTo(viewer, new DustParticleOptions(rgb, size), pos, count, dx, dy, dz, 0.0D);
	}

	public static Identifier parseId(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Identifier.parse(raw.trim());
		} catch (RuntimeException e) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] invalid identifier '{}'", raw);
			return null;
		}
	}
}
