package com.altarsmp.fabric.weapon.s2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import com.mojang.math.Transformation;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TickScheduler;
import com.altarsmp.fabric.weapon.WeaponBehavior;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Dragonrend (Season 2) - port of {@code com.altarsmps2.weapons.DragonrendWeapon}
 * and {@code com.altarsmps2.abilities.DragonrendAbilities}.
 *
 * <p><b>Hypersonic Slash</b> (F, {@code hypersonic.cooldown}s): the wielder charges
 * for {@code hypersonic.charge_duration_ms} while their own client is put into
 * slow motion (a real ticking-state packet at 8 TPS, exactly what the plugin sent
 * through PacketEvents), then gets a {@code hypersonic.duration_ms} window. Every
 * arm swing inside that window - at most one per
 * {@code hypersonic.strike_cooldown_ms} - teleports them behind the closest
 * untrusted player they are looking at within
 * {@code hypersonic.detection_range} blocks inside a
 * {@code hypersonic.detection_cone_dot} cone, draws the dragon slash arc and deals
 * {@code hypersonic.damage} true damage.</p>
 *
 * <p><b>Infinite Void</b> (Shift+F, {@code infinite_void.cooldown}s): places the
 * three-piece Void Clock model (paper with custom model data 2, 3 and 5, red glow,
 * grown from 0.01 to 4x over 20 ticks) at the caster's feet, rings it with white
 * smoke and end-rod particles for 6 seconds, spins the two arrow pieces at 9 and
 * 4.5 degrees per tick, and drops every untrusted player inside
 * {@code infinite_void.slow_radius} blocks to 4 TPS - the "time slows down for
 * them" effect - restoring 20 TPS when they leave or when the clock collapses.</p>
 *
 * <p>Melee hits always draw the dragon slash arc plus a sweep sound.</p>
 */
public final class DragonrendWeapon implements WeaponBehavior {

	static final String KEY_HYPERSONIC = "dr_hypersonic";
	static final String KEY_VOID = "dr_inf_void";

	/** Resource-pack sounds shipped in the AltarSMPS2 namespace. */
	private static final Identifier SOUND_DING = Identifier.fromNamespaceAndPath("altarsmps2", "dragonding");
	private static final Identifier SOUND_TICK_TACK = Identifier.fromNamespaceAndPath("altarsmps2", "dragontick_tack");

	/** Bukkit {@code Color.fromRGB(255, 30, 30)}. */
	private static final int VOID_GLOW = 0xFF1E1E;

	private static final float TICK_RATE_NORMAL = 20.0F;
	private static final float TICK_RATE_CHARGE = 8.0F;
	private static final float TICK_RATE_VOID = 4.0F;

	private static final int VOID_TOTAL_TICKS = 120;
	private static final long VOID_SPIN_START = 20L;
	private static final int VOID_SPIN_TICKS = 80;
	private static final long VOID_COLLAPSE_TICK = 100L;
	private static final long VOID_GROW_TICKS = 20L;
	private static final int VOID_RING_PERIOD = 2;
	private static final int VOID_SLOW_PERIOD = 4;
	private static final double VOID_RING_RADIUS = 6.0D;
	private static final double VOID_RING_GROW_TICKS = 14.0D;
	private static final int VOID_RING_POINTS = 60;
	private static final float VOID_GROWN_SCALE = 4.0F;
	private static final float VOID_HIDDEN_SCALE = 0.01F;
	private static final float SPIN_FAST_DEGREES = 9.0F;
	private static final float SPIN_SLOW_DEGREES = 4.5F;

	/** {@code Location#add(x, y, z)} offsets of the three clock pieces. */
	private static final float[][] VOID_PIECE_OFFSETS = {
			{0.75F, 0.125F, -0.186F},
			{0.75F, 0.0375F, -0.186F},
			{0.75F, 0.0875F, -0.186F},
	};
	/** {@code makeVfxItem(n)} custom model data for those pieces. */
	private static final int[] VOID_PIECE_MODELS = {2, 3, 5};

	private static final int SLASH_SEGMENTS = 16;
	private static final double SLASH_ARC = 1.7278759594743864D;
	private static final double SLASH_RADIUS = 1.4D;

	private final AltarSMPMod mod;

	/** Bukkit's {@code Map<UUID, a>} charge bookkeeping. */
	private final Map<UUID, Long> chargeReadyAt = new HashMap<>();
	private final Map<UUID, TickScheduler.Task> chargeTasks = new HashMap<>();
	/** Bukkit's {@code Map<UUID, b>} slash windows. */
	private final Map<UUID, Window> windows = new HashMap<>();
	private final Map<UUID, TickScheduler.Task> windowTasks = new HashMap<>();
	/** Bukkit's {@code Map<UUID, Long>} per-strike cooldown. */
	private final Map<UUID, Long> lastStrike = new HashMap<>();
	private final Map<UUID, VoidCast> voidCasts = new HashMap<>();

	public DragonrendWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "dragonrend";
	}

	@Override
	public int season() {
		return 2;
	}

	@Override
	public String displayName() {
		return "Dragonrend";
	}

	@Override
	public List<String> configFields() {
		return List.of("Hypersonic Cooldown/Charge Duration/Window Duration/Strike Cooldown",
				"Hypersonic Detection Range/Detection Cone/Teleport Offset/Damage",
				"Infinite Void Cooldown/Slow Radius");
	}

	// ------------------------------------------------------------- activation

	@Override
	public void onPrimary(AbilityContext ctx) {
		startCharge(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		startInfiniteVoid(ctx);
	}

	// ---------------------------------------------------- hypersonic slash (F)

	/** {@code DragonrendAbilities#tryHypersonicSlash}. */
	private void startCharge(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		UUID uuid = player.getUUID();
		if (this.chargeReadyAt.containsKey(uuid) || this.windows.containsKey(uuid)) {
			return;
		}
		int cooldown = ctx.cfg("abilities.dragonrend.hypersonic.cooldown", 40);
		long chargeMillis = (long) ctx.cfgd("abilities.dragonrend.hypersonic.charge_duration_ms", 2000.0D);
		if (!ctx.gate(KEY_HYPERSONIC, "Hypersonic Slash", cooldown)) {
			return;
		}
		CooldownBars.show(player, KEY_HYPERSONIC, "Hypersonic Slash", BossEvent.BossBarColor.YELLOW, cooldown);
		this.chargeReadyAt.put(uuid, System.currentTimeMillis() + chargeMillis);

		ServerLevel level = ctx.level();
		Vec3 at = player.position();
		Fx.sound(level, at, "ENTITY_BREEZE_INHALE", 1.4F, 0.7F);
		Fx.sound(level, at, "BLOCK_RESPAWN_ANCHOR_CHARGE", 1.0F, 0.6F);
		sendTickRate(player, TICK_RATE_CHARGE);
		Messaging.actionBar(player, "<light_purple>Charging Hypersonic Slash...");

		final long readyAt = this.chargeReadyAt.get(uuid);
		final int[] tick = {0};
		this.chargeTasks.put(uuid, this.mod.scheduler().timer(() -> {
			ServerPlayer caster = level.getServer().getPlayerList().getPlayer(uuid);
			if (caster == null || !this.chargeReadyAt.containsKey(uuid)
					|| !Identity.is(caster.getMainHandItem(), id())) {
				cancelCharge(uuid, level);
				return;
			}
			if (System.currentTimeMillis() >= readyAt) {
				finishCharge(uuid, caster, ctx);
				return;
			}
			// Three dragon-breath wisps orbiting the caster, one third of a turn apart.
			double base = tick[0] * Math.PI / 6.0D;
			for (int index = 0; index < 3; index++) {
				double angle = base + index * Math.PI * 2.0D / 3.0D;
				double height = tick[0] % 16 / 16.0D * 2.0D;
				Vec3 wisp = caster.position().add(Math.cos(angle), height, Math.sin(angle));
				Fx.power(level, "DRAGON_BREATH", wisp, 1.0F, 2, 0.05D, 0.05D, 0.05D, 0.01D);
			}
			if (tick[0] % 8 == 0) {
				Fx.sound(level, caster.position(), "ENTITY_BREEZE_INHALE", 0.7F, 0.5F);
			}
			tick[0]++;
		}, 0L, 1L));
	}

	/** {@code DragonrendAbilities#cancelCharge}. */
	private void cancelCharge(UUID uuid, ServerLevel level) {
		cancel(this.chargeTasks.remove(uuid));
		if (this.chargeReadyAt.remove(uuid) == null) {
			return;
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
		if (player != null) {
			sendTickRate(player, TICK_RATE_NORMAL);
			Messaging.actionBar(player, "<gray>Slash interrupted");
		}
	}

	/** {@code DragonrendAbilities#finishCharge} - opens the slash window. */
	private void finishCharge(UUID uuid, ServerPlayer player, AbilityContext ctx) {
		cancel(this.chargeTasks.remove(uuid));
		this.chargeReadyAt.remove(uuid);
		sendTickRate(player, TICK_RATE_NORMAL);
		ServerLevel level = ctx.level();
		Fx.sound(level, player.position(), "ENTITY_BREEZE_SHOOT", 1.2F, 1.4F);
		int windowMillis = ctx.cfg("abilities.dragonrend.hypersonic.duration_ms", 5000);
		this.windows.put(uuid, new Window(System.currentTimeMillis() + windowMillis, 0));
		Messaging.actionBar(player, "<light_purple>Hypersonic ready - " + windowMillis / 1000 + "s window");
		this.windowTasks.put(uuid, this.mod.scheduler().timer(() -> {
			Window window = this.windows.get(uuid);
			if (window == null) {
				cancel(this.windowTasks.remove(uuid));
				return;
			}
			if (System.currentTimeMillis() >= window.endsAtMillis()) {
				this.windows.remove(uuid);
				this.lastStrike.remove(uuid);
				cancel(this.windowTasks.remove(uuid));
				ServerPlayer caster = level.getServer().getPlayerList().getPlayer(uuid);
				if (caster != null) {
					Messaging.actionBar(caster, "<gray>Hypersonic window closed");
				}
			}
		}, 5L, 5L));
	}

	/** {@code DragonrendAbilities#onArmSwing} - the strike itself. */
	@Override
	public void onArmSwing(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		UUID uuid = player.getUUID();
		Window window = this.windows.get(uuid);
		if (window == null || !Identity.is(player.getMainHandItem(), id())) {
			return;
		}
		if (System.currentTimeMillis() >= window.endsAtMillis()) {
			this.windows.remove(uuid);
			return;
		}
		long strikeCooldown = (long) ctx.cfgd("abilities.dragonrend.hypersonic.strike_cooldown_ms", 1000.0D);
		Long last = this.lastStrike.get(uuid);
		if (last != null && System.currentTimeMillis() - last < strikeCooldown) {
			return;
		}
		double range = ctx.cfgd("abilities.dragonrend.hypersonic.detection_range", 16.0D);
		double coneDot = ctx.cfgd("abilities.dragonrend.hypersonic.detection_cone_dot", 0.91D);
		ServerPlayer target = findLookedAtPlayer(ctx, player, range, coneDot);
		if (target == null) {
			return;
		}
		double offset = ctx.cfgd("abilities.dragonrend.hypersonic.teleport_offset", 1.5D);
		Vec3 behind = target.position().add(target.getViewVector(1.0F).scale(-offset));
		Vec3 toTarget = target.position().subtract(behind);
		Motion.teleport(player, behind, yawOf(toTarget), pitchOf(toTarget));

		ServerLevel level = ctx.level();
		Vec3 targetPos = target.position();
		Fx.sound(level, targetPos, "ENTITY_PLAYER_ATTACK_SWEEP", 1.6F, 0.6F);
		Fx.sound(level, targetPos, "ENTITY_BREEZE_WIND_BURST", 1.0F, 1.5F);
		Fx.sound(level, targetPos, "BLOCK_VAULT_OPEN_SHUTTER", 1.4F, 1.0F);
		for (int index = 0; index < 30; index++) {
			Vec3 spark = targetPos.add((level.random.nextDouble() - 0.5D) * 1.5D,
					level.random.nextDouble() * 2.0D, (level.random.nextDouble() - 0.5D) * 1.5D);
			Fx.power(level, "DRAGON_BREATH", spark, 1.0F, 1, 0.1D, 0.1D, 0.1D, 0.05D);
		}
		spawnDragonSlash(level, player, target);
		double damage = ctx.cfgd("abilities.dragonrend.hypersonic.damage", 4.0D);
		TrueDamage.apply(target, damage, player, false);

		this.lastStrike.put(uuid, System.currentTimeMillis());
		this.windows.put(uuid, new Window(window.endsAtMillis(), window.strikes() + 1));
		Messaging.actionBar(player, "<light_purple>Slash " + (window.strikes() + 2) + " - "
				+ strikeCooldown / 1000.0D + "s cd");
	}

	/** {@code DragonrendAbilities#findLookedAtPlayer}. */
	private ServerPlayer findLookedAtPlayer(AbilityContext ctx, ServerPlayer player, double range, double coneDot) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		ServerPlayer best = null;
		double bestDistance = range;
		for (ServerPlayer other : ctx.level().players()) {
			if (other == player || !ctx.abilityAllowedOn(other)) {
				continue;
			}
			Vec3 delta = other.getEyePosition().subtract(eye);
			double distance = delta.length();
			if (distance > bestDistance) {
				continue;
			}
			if (delta.normalize().dot(look) < coneDot) {
				continue;
			}
			best = other;
			bestDistance = distance;
		}
		return best;
	}

	/** {@code DragonrendAbilities#spawnDragonSlash} - the arc every hit draws. */
	private void spawnDragonSlash(ServerLevel level, LivingEntity attacker, LivingEntity target) {
		Vec3 origin = target.position().add(0.0D, 1.0D, 0.0D);
		Vec3 forward = attacker.getViewVector(1.0F);
		Vec3 right = forward.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
		Vec3 up = right.cross(forward).normalize();
		double angle = level.random.nextDouble() * Math.PI * 2.0D - Math.PI;
		Vec3 planeA = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
		Vec3 planeB = right.scale(-Math.sin(angle)).add(up.scale(Math.cos(angle)));
		for (int segment = 0; segment <= SLASH_SEGMENTS; segment++) {
			double progress = (double) segment / SLASH_SEGMENTS;
			double arc = (progress - 0.5D) * SLASH_ARC;
			Vec3 offset = planeA.scale(Math.sin(arc) * SLASH_RADIUS)
					.add(planeB.scale(Math.cos(arc) * SLASH_RADIUS * 0.25D));
			Vec3 at = origin.add(offset);
			Fx.simple(level, "WAX_OFF", at, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			Fx.power(level, "DRAGON_BREATH", at, 1.0F, 2, 0.05D, 0.05D, 0.05D, 0.01D);
		}
	}

	@Override
	public void onAttack(AbilityContext ctx, LivingEntity target, float damage, boolean critical) {
		ServerLevel level = ctx.level();
		spawnDragonSlash(level, ctx.player(), target);
		Fx.sound(level, target.position(), "ENTITY_PLAYER_ATTACK_SWEEP", 1.4F, 0.55F);
	}

	// ----------------------------------------------------- infinite void (F+shift)

	/** {@code DragonrendAbilities#tryInfiniteVoid}. */
	private void startInfiniteVoid(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		UUID uuid = player.getUUID();
		if (this.voidCasts.containsKey(uuid)) {
			return;
		}
		int cooldown = ctx.cfg("abilities.dragonrend.infinite_void.cooldown", 60);
		double slowRadius = ctx.cfgd("abilities.dragonrend.infinite_void.slow_radius", 6.0D);
		if (!ctx.gate(KEY_VOID, "Infinite Void", cooldown)) {
			return;
		}
		CooldownBars.show(player, KEY_VOID, "Infinite Void", BossEvent.BossBarColor.GREEN, cooldown);

		ServerLevel level = ctx.level();
		Vec3 anchor = player.position();
		Fx.sound(level, anchor, SOUND_DING, 1.0F, 1.0F);

		VoidCast cast = new VoidCast();
		for (int index = 0; index < VOID_PIECE_OFFSETS.length; index++) {
			Vec3 at = anchor.add(VOID_PIECE_OFFSETS[index][0], VOID_PIECE_OFFSETS[index][1],
					VOID_PIECE_OFFSETS[index][2]);
			Display.ItemDisplay display = Displays.model(level, at, voidClockPiece(VOID_PIECE_MODELS[index]));
			display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
			Displays.bright(display);
			Displays.glowColor(display, VOID_GLOW);
			VoidPiece piece = new VoidPiece(display);
			piece.scale = VOID_HIDDEN_SCALE;
			applyPiece(piece, (int) VOID_GROW_TICKS);
			cast.pieces.add(piece);
		}
		Fx.sound(level, anchor, SOUND_TICK_TACK, 1.0F, 1.0F);
		this.voidCasts.put(uuid, cast);

		// One tick later the pieces interpolate up to 4x scale.
		cast.growTask = this.mod.scheduler().later(() -> {
			for (VoidPiece piece : cast.pieces) {
				piece.scale = VOID_GROWN_SCALE;
				applyPiece(piece, (int) VOID_GROW_TICKS);
			}
		}, 1L);

		// Smoke + end-rod ring, growing to its full radius over 14 ticks.
		final int[] ringTick = {0};
		cast.ringTask = this.mod.scheduler().timer(() -> {
			if (ringTick[0] >= VOID_TOTAL_TICKS) {
				cancel(cast.ringTask);
				return;
			}
			double radius = ringTick[0] < VOID_RING_GROW_TICKS
					? VOID_RING_RADIUS * (ringTick[0] / VOID_RING_GROW_TICKS)
					: VOID_RING_RADIUS;
			for (int point = 0; point < VOID_RING_POINTS; point++) {
				double angle = Math.PI * 2.0D * point / VOID_RING_POINTS;
				Vec3 at = anchor.add(Math.cos(angle) * radius, 0.05D, Math.sin(angle) * radius);
				Fx.simple(level, "WHITE_SMOKE", at, 1, 0.0D, 0.0D, 0.0D, 0.0D);
				if (point % 4 == 0) {
					Fx.simple(level, "END_ROD", at, 1, 0.0D, 0.0D, 0.0D, 0.0D);
				}
			}
			ringTick[0] += VOID_RING_PERIOD;
		}, 0L, (long) VOID_RING_PERIOD);

		// The two arrow pieces spin from t=20 to t=100.
		final int[] spinTick = {0};
		cast.spinTask = this.mod.scheduler().timer(() -> {
			if (spinTick[0] >= VOID_SPIN_TICKS) {
				cancel(cast.spinTask);
				return;
			}
			spin(cast.pieces.get(1), SPIN_FAST_DEGREES);
			spin(cast.pieces.get(2), SPIN_SLOW_DEGREES);
			spinTick[0]++;
		}, VOID_SPIN_START, 1L);

		// The clock collapses back to nothing at t=100.
		cast.collapseTask = this.mod.scheduler().later(() -> {
			Fx.sound(level, anchor, SOUND_DING, 1.0F, 1.0F);
			for (VoidPiece piece : cast.pieces) {
				piece.scale = VOID_HIDDEN_SCALE;
				applyPiece(piece, (int) VOID_GROW_TICKS);
			}
		}, VOID_COLLAPSE_TICK);

		// Slow motion for everybody untrusted inside the radius, every 4 ticks.
		final int[] slowTick = {0};
		cast.slowTask = this.mod.scheduler().timer(() -> {
			if (slowTick[0] >= VOID_TOTAL_TICKS) {
				finishVoid(uuid, level);
				return;
			}
			Set<UUID> inside = new HashSet<>();
			for (ServerPlayer other : level.players()) {
				if (other == player || !ctx.abilityAllowedOn(other) || !withinBox(other.position(), anchor, slowRadius)) {
					continue;
				}
				inside.add(other.getUUID());
				if (cast.slowed.add(other.getUUID())) {
					sendTickRate(other, TICK_RATE_VOID);
				}
			}
			for (Iterator<UUID> it = cast.slowed.iterator(); it.hasNext();) {
				UUID id = it.next();
				if (inside.contains(id)) {
					continue;
				}
				ServerPlayer released = level.getServer().getPlayerList().getPlayer(id);
				if (released != null) {
					sendTickRate(released, TICK_RATE_NORMAL);
				}
				it.remove();
			}
			slowTick[0] += VOID_SLOW_PERIOD;
		}, 0L, (long) VOID_SLOW_PERIOD);
	}

	/** Ends a Void Clock cast: restores tick rates and removes the displays. */
	private void finishVoid(UUID uuid, ServerLevel level) {
		VoidCast cast = this.voidCasts.get(uuid);
		if (cast == null) {
			return;
		}
		cancel(cast.slowTask);
		for (UUID id : cast.slowed) {
			ServerPlayer released = level.getServer().getPlayerList().getPlayer(id);
			if (released != null) {
				sendTickRate(released, TICK_RATE_NORMAL);
			}
		}
		cast.slowed.clear();
		removeVoidDisplays(cast);
		this.voidCasts.remove(uuid);
	}

	private void removeVoidDisplays(VoidCast cast) {
		for (VoidPiece piece : cast.pieces) {
			Displays.remove(piece.display);
		}
		cast.pieces.clear();
	}

	/** {@code DragonrendAbilities#spinArrow}. */
	private void spin(VoidPiece piece, float degreesPerTick) {
		if (piece.display.isRemoved()) {
			return;
		}
		piece.leftRotation.rotateY((float) Math.toRadians(degreesPerTick));
		applyPiece(piece, 1);
	}

	/** {@code DragonrendAbilities#buildTransform} + the Bukkit transformation write. */
	private void applyPiece(VoidPiece piece, int interpolationTicks) {
		if (piece.display.isRemoved()) {
			return;
		}
		Displays.interpolate(piece.display, 0, interpolationTicks);
		piece.display.setTransformation(new Transformation(new Vector3f(0.0F, 0.0F, 0.0F), piece.leftRotation,
				new Vector3f(piece.scale, piece.scale, piece.scale), new Quaternionf()));
	}

	/** {@code DragonrendAbilities#makeVfxItem} - paper + custom model data. */
	private static ItemStack voidClockPiece(int customModelData) {
		ItemStack stack = new ItemStack(Items.PAPER);
		ItemFactory.applyModelData(stack, customModelData);
		return stack;
	}

	// ------------------------------------------------------------- housekeeping

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		UUID uuid = player.getUUID();
		ServerLevel level = player.serverLevel();
		cancelCharge(uuid, level);
		cancel(this.windowTasks.remove(uuid));
		this.windows.remove(uuid);
		this.lastStrike.remove(uuid);
		sendTickRate(player, TICK_RATE_NORMAL);
		VoidCast cast = this.voidCasts.remove(uuid);
		if (cast != null) {
			cancel(cast.slowTask);
			cancel(cast.spinTask);
			cancel(cast.ringTask);
			cancel(cast.growTask);
			cancel(cast.collapseTask);
			for (UUID id : cast.slowed) {
				ServerPlayer released = level.getServer().getPlayerList().getPlayer(id);
				if (released != null) {
					sendTickRate(released, TICK_RATE_NORMAL);
				}
			}
			removeVoidDisplays(cast);
		}
	}

	/**
	 * Bukkit's {@code sendTickRate}: a real client ticking-state packet. Vanilla
	 * uses the same packet for the {@code /tick} command, so the slow motion is a
	 * genuine client-side effect and needs no client mod of our own.
	 */
	static void sendTickRate(ServerPlayer player, float tickRate) {
		if (player == null || player.connection == null) {
			return;
		}
		player.connection.send(new ClientboundTickingStatePacket(tickRate, false));
	}

	private static void cancel(TickScheduler.Task task) {
		if (task != null) {
			task.cancel();
		}
	}

	private static boolean withinBox(Vec3 pos, Vec3 center, double radius) {
		return Math.abs(pos.x - center.x) <= radius
				&& Math.abs(pos.y - center.y) <= radius
				&& Math.abs(pos.z - center.z) <= radius;
	}

	/** Vanilla {@code LookControl} yaw: {@code atan2(dz, dx) - 90}. */
	private static float yawOf(Vec3 direction) {
		return (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0D);
	}

	/** Vanilla {@code LookControl} pitch: {@code -atan2(dy, horizontal)}. */
	private static float pitchOf(Vec3 direction) {
		double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
		return (float) -Math.toDegrees(Math.atan2(direction.y, horizontal));
	}

	// ------------------------------------------------------------------ state

	private record Window(long endsAtMillis, int strikes) {
	}

	/** One Void Clock piece: its display plus the mutable transform the spin uses. */
	private static final class VoidPiece {
		final Display.ItemDisplay display;
		final Quaternionf leftRotation = new Quaternionf();
		float scale = VOID_HIDDEN_SCALE;

		VoidPiece(Display.ItemDisplay display) {
			this.display = display;
		}
	}

	/** Everything one Infinite Void cast owns, so it can be cancelled cleanly. */
	private static final class VoidCast {
		final List<VoidPiece> pieces = new ArrayList<>(3);
		final Set<UUID> slowed = new HashSet<>();
		TickScheduler.Task growTask;
		TickScheduler.Task ringTask;
		TickScheduler.Task spinTask;
		TickScheduler.Task collapseTask;
		TickScheduler.Task slowTask;
	}
}
