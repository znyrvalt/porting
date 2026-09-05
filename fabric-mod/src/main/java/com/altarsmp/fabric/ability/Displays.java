package com.altarsmp.fabric.ability;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.mojang.math.Transformation;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.util.TextFx;

/**
 * Display-entity toolkit.
 *
 * <p>The plugin built nearly all of its VFX out of {@code ItemDisplay},
 * {@code BlockDisplay}, {@code TextDisplay} and invisible {@code ArmorStand}
 * entities (the altar totems, the bone shards, the orbital cage, the Paladin Axe
 * shockwave rings, the nuke marker). Everything display-related goes through this
 * class so the transformation math, tags and lifetime handling stay consistent -
 * and so the handful of version-sensitive display calls have exactly one home.</p>
 */
public final class Displays {

	private Displays() {
	}

	// ------------------------------------------------------------------- items

	public static Display.ItemDisplay item(ServerLevel level, Vec3 pos, ItemStack stack) {
		Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		display.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
		display.setItemStack(stack);
		display.setItemTransform(ItemDisplayContext.GROUND);
		common(display);
		level.addFreshEntity(display);
		return display;
	}

	public static Display.ItemDisplay item(ServerLevel level, Vec3 pos, ItemStack stack, float scale) {
		Display.ItemDisplay display = item(level, pos, stack);
		setScale(display, scale);
		return display;
	}

	/**
	 * A "model" display: the plugin spawned {@code ItemDisplay}s with the default
	 * {@code NONE} transform when it wanted a resource-pack model (custom model
	 * data) rendered exactly as authored, rather than as a dropped-item sprite.
	 */
	public static Display.ItemDisplay model(ServerLevel level, Vec3 pos, ItemStack stack) {
		Display.ItemDisplay display = item(level, pos, stack);
		display.setItemTransform(ItemDisplayContext.NONE);
		return display;
	}

	/** Floating "altar" presentation: full brightness, centred billboard. */
	public static Display.ItemDisplay floating(ServerLevel level, Vec3 pos, ItemStack stack, float scale) {
		Display.ItemDisplay display = item(level, pos, stack, scale);
		display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		bright(display);
		display.setViewRange(1.0F);
		return display;
	}

	// ------------------------------------------------------------------ blocks

	public static Display.BlockDisplay block(ServerLevel level, Vec3 pos, BlockState state) {
		Display.BlockDisplay display = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
		display.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
		display.setBlockState(state);
		common(display);
		level.addFreshEntity(display);
		return display;
	}

	// -------------------------------------------------------------------- text

	public static Display.TextDisplay text(ServerLevel level, Vec3 pos, String markup, float scale) {
		Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, level);
		display.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
		display.setText(TextFx.parse(markup));
		// Bukkit: setSeeThrough(false) + setAlignment(CENTER) + setShadowed(true).
		// Vanilla stores all three in one style-flags byte.
		display.setFlags(textFlags(Display.TextDisplay.Align.CENTER, false, true));
		common(display);
		setScale(display, scale);
		bright(display);
		level.addFreshEntity(display);
		return display;
	}

	/**
	 * Multi-line altar hologram. The plugin built these from a single
	 * {@code TextDisplay} whose text contained newlines (Bukkit has no line-height
	 * property either) - the vertical spacing comes from the display's scale.
	 */
	public static Display.TextDisplay hologram(ServerLevel level, Vec3 pos, List<String> markupLines, float scale) {
		return text(level, pos, String.join("\n", markupLines), scale);
	}

	/**
	 * Packs the vanilla text-display style byte: bit0 shadow, bit1 see-through,
	 * bit2 use-default-background, bit3 align-left, bit4 align-right.
	 */
	public static byte textFlags(Display.TextDisplay.Align alignment, boolean seeThrough, boolean shadowed) {
		byte flags = 0;
		if (shadowed) {
			flags |= Display.TextDisplay.FLAG_SHADOW;
		}
		if (seeThrough) {
			flags |= Display.TextDisplay.FLAG_SEE_THROUGH;
		}
		if (alignment == Display.TextDisplay.Align.LEFT) {
			flags |= Display.TextDisplay.FLAG_ALIGN_LEFT;
		} else if (alignment == Display.TextDisplay.Align.RIGHT) {
			flags |= Display.TextDisplay.FLAG_ALIGN_RIGHT;
		}
		return flags;
	}

	// -------------------------------------------------------------- armorstand

	/** Invisible marker armour stand - the click hitbox the altars used. */
	public static ArmorStand marker(ServerLevel level, Vec3 pos) {
		ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
		stand.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
		stand.setInvisible(true);
		stand.setNoBasePlate(true);
		stand.setMarker(true);
		stand.setSmall(false);
		stand.setNoGravity(true);
		stand.setInvulnerable(true);
		stand.setSilent(true);
		level.addFreshEntity(stand);
		return stand;
	}

	public static ArmorStand namedMarker(ServerLevel level, Vec3 pos, String markup) {
		ArmorStand stand = marker(level, pos);
		stand.setCustomName(TextFx.parse(markup));
		stand.setCustomNameVisible(false);
		return stand;
	}

	// ------------------------------------------------------------- transforms

	public static void setScale(Entity display, float scale) {
		if (display instanceof Display d) {
			d.setTransformation(new Transformation(new Vector3f(0.0F, 0.0F, 0.0F), new Quaternionf(),
					new Vector3f(scale, scale, scale), new Quaternionf()));
		}
	}

	public static void rotate(Display display, float yawDegrees, float scale) {
		display.setTransformation(new Transformation(new Vector3f(0.0F, 0.0F, 0.0F),
				new Quaternionf().rotationY((float) Math.toRadians(yawDegrees)),
				new Vector3f(scale, scale, scale), new Quaternionf()));
	}

	public static void rotate(Display display, float yawDegrees) {
		rotate(display, yawDegrees, 1.0F);
	}

	public static void translate(Display display, float x, float y, float z, float scale) {
		display.setTransformation(new Transformation(new Vector3f(x, y, z), new Quaternionf(),
				new Vector3f(scale, scale, scale), new Quaternionf()));
	}

	/**
	 * Full-brightness rendering. Single call site: the {@code Brightness} record
	 * has moved package once already, so if a future Minecraft renames it this is
	 * the only line that needs updating.
	 */
	public static void bright(Display display) {
		display.setBrightnessOverride(Brightness.FULL_BRIGHT);
	}

	/**
	 * Transformation interpolation - Bukkit's {@code setInterpolationDelay} /
	 * {@code setInterpolationDuration}; the Mojang names carry the
	 * "transformation" prefix because a second pair exists for position/rotation
	 * lerping ({@link #teleportInterpolation}).
	 */
	public static void interpolate(Display display, int delayTicks, int durationTicks) {
		display.setTransformationInterpolationDelay(delayTicks);
		display.setTransformationInterpolationDuration(durationTicks);
	}

	/** Bukkit's {@code setTeleportDuration} (position/rotation interpolation). */
	public static void teleportInterpolation(Display display, int durationTicks) {
		display.setPosRotInterpolationDuration(durationTicks);
	}

	/** Bukkit's {@code setGlowColorOverride} - RGB, used for the red Void Clock glow. */
	public static void glowColor(Display display, int rgb) {
		display.setGlowingTag(true);
		display.setGlowColorOverride(rgb);
	}

	/**
	 * The shared "VFX entity" setup: no gravity, invulnerable, silent and - for
	 * the handful of entities that are mobs and would otherwise despawn -
	 * persistence. {@code setPersistenceRequired} only exists on {@link Mob}, and
	 * display entities/armour stands never despawn on their own.
	 */
	public static void common(Entity entity) {
		entity.setNoGravity(true);
		entity.setInvulnerable(true);
		entity.setSilent(true);
		if (entity instanceof Mob mob) {
			mob.setPersistenceRequired();
		}
	}

	public static void tag(Entity entity, String... tags) {
		for (String tag : tags) {
			entity.addTag(tag);
		}
	}

	public static void remove(@Nullable Entity entity) {
		if (entity != null && !entity.isRemoved()) {
			entity.discard();
		}
	}

	/** Removes every entity carrying one of these tags in a level. */
	public static int removeTagged(ServerLevel level, String... tags) {
		int removed = 0;
		for (Entity entity : level.getAllEntities()) {
			for (String tag : tags) {
				if (entity.getTags().contains(tag)) {
					entity.discard();
					removed++;
					break;
				}
			}
		}
		return removed;
	}

	// ------------------------------------------------------- physics displays

	/**
	 * A display entity that falls under gravity and tumbles - the "physics item
	 * display" the plugin used for bone shards, ice chunks and debris. The plugin
	 * simulated this with a repeating Bukkit task; the port does the same with the
	 * mod tick scheduler, so it works identically on a dedicated server.
	 *
	 * @param lifeTicks how long the shard stays in the world
	 */
	public static Display.ItemDisplay physicsItem(ServerLevel level, Vec3 pos, ItemStack stack, Vec3 velocity, int lifeTicks) {
		Display.ItemDisplay display = item(level, pos, stack, 0.6F);
		display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		final Vec3[] motion = {velocity};
		final float[] yaw = {level.random.nextFloat() * 360.0F};
		AltarSMPMod.get().scheduler().timer(() -> {
			if (display.isRemoved()) {
				return;
			}
			Vec3 next = display.position().add(motion[0]);
			BlockPos below = BlockPos.containing(next.x, next.y - 0.1D, next.z);
			if (!level.getBlockState(below).isAir() && motion[0].y < 0.0D) {
				motion[0] = new Vec3(motion[0].x * 0.6D, -motion[0].y * 0.35D, motion[0].z * 0.6D);
			} else {
				motion[0] = motion[0].subtract(0.0D, 0.04D, 0.0D).scale(0.99D);
			}
			display.snapTo(next.x, next.y, next.z, yaw[0], 0.0F);
			yaw[0] += 12.0F;
			rotate(display, yaw[0], 0.6F);
		}, 1L, 1L).cancelAfter(lifeTicks);
		return display;
	}

	/**
	 * An entity that orbits a centre point - the bone cage orbitals and the
	 * Paladin Axe's circling shockwave both used this pattern.
	 */
	public static Display.ItemDisplay orbital(ServerLevel level, Vec3 center, ItemStack stack, double radius,
			double height, double degreesPerTick, int lifeTicks, @Nullable Consumer<Display.ItemDisplay> perTick) {
		Display.ItemDisplay display = item(level, center, stack, 0.8F);
		bright(display);
		final double[] angle = {level.random.nextDouble() * 360.0D};
		AltarSMPMod.get().scheduler().timer(() -> {
			if (display.isRemoved()) {
				return;
			}
			angle[0] = (angle[0] + degreesPerTick) % 360.0D;
			double radians = Math.toRadians(angle[0]);
			Vec3 pos = new Vec3(center.x + Math.cos(radians) * radius, center.y + height, center.z + Math.sin(radians) * radius);
			display.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
			rotate(display, (float) angle[0], 0.8F);
			if (perTick != null) {
				perTick.accept(display);
			}
		}, 0L, 1L).cancelAfter(lifeTicks);
		return display;
	}

	/** Ring of particles/Displays around a point, used by shockwave abilities. */
	public static List<Entity> ring(ServerLevel level, Vec3 center, double radius, int points, ItemStack stack, float scale) {
		List<Entity> entities = new java.util.ArrayList<>();
		for (int i = 0; i < points; i++) {
			double radians = Math.toRadians(360.0D / points * i);
			Vec3 pos = new Vec3(center.x + Math.cos(radians) * radius, center.y, center.z + Math.sin(radians) * radius);
			Display.ItemDisplay display = item(level, pos, stack, scale);
			rotate(display, (float) (360.0D / points * i), scale);
			entities.add(display);
		}
		return entities;
	}

	/** Unique owner tag helper so a weapon can find (and clean up) its own entities. */
	public static String ownerTag(UUID owner, String kind) {
		return "altarsmp_" + kind + "_" + owner;
	}
}
