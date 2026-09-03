package com.altarsmp.fabric.faction;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.s1.NightpiercerWeapon;
import com.altarsmp.fabric.weapon.s1.PaleCrossbowWeapon;

/**
 * The faction/curse system - a port of {@code com.altarsmp.vampire.VampireManager}
 * plus the {@code /vampire}, {@code /pale} and {@code /human} commands.
 *
 * <p>Roles are kept exactly where the plugin kept them: entity tags. Bukkit's
 * {@code getScoreboardTags()} is Minecraft's {@code Entity#getTags()} set, so
 * {@code vampire}, {@code vampire_king}, {@code pale}, {@code pale_king},
 * {@code human}, {@code perma_vampire}, {@code perma_pale}, {@code perma_human} and
 * the transient {@code paleaffect} marker survive death, respawn and restarts the
 * same way they did on Paper, and worlds converted from the plugin keep their
 * factions for free.
 *
 * <p>Every second each online player gets their name coloured (Vampire King dark
 * red, Vampire red, Pale yellow during a Blood Moon), their curse passives
 * (vampires burn in daylight, and gain strength/speed/fire resistance at night;
 * pales gain speed on moss and weakness when standing in the rain) and the max
 * health modifier their rank deserves. The plugin ran those as two 20-tick
 * BukkitRunnables; here they ride {@link AltarSMPMod}'s server tick with the same
 * period.
 *
 * <p>Deaths follow the plugin's ladder exactly: killed by Nightpiercer while
 * {@code nightpiercer.perma_vampire_on_kill} is on -&gt; True Vampire; killed by the
 * Pale Crossbow -&gt; True Pale (or Pale Rot when only the pale effect was on the
 * victim); killed while standing on moss with the pale system awake -&gt; Pale Rot;
 * killed by a Hyperion holder while cursed -&gt; eternally purified human; killed by
 * a pale -&gt; pale rot; killed by a vampire -&gt; vampire. Each branch broadcasts its
 * own line and plays its own sound.
 *
 * <p>Damage amplification ({@code onDamage}) is asked for by
 * {@code weapon.CombatHooks#modifyDamage}: Hyperion holders add
 * {@code vampire.hyperion_bonus} against cursed players, vampires add
 * {@code vampire.night_damage} at night unless the Blood Moon stacking rule forbids
 * it, and pales multiply by {@code pale.backstab_multiplier} when hitting a player
 * in the back.
 */
public final class FactionManager {

	/** Bukkit scoreboard tags, kept verbatim so converted worlds still work. */
	public static final String TAG_VAMPIRE = "vampire";
	public static final String TAG_VAMPIRE_KING = "vampire_king";
	public static final String TAG_PALE = "pale";
	public static final String TAG_PALE_KING = "pale_king";
	public static final String TAG_HUMAN = "human";
	public static final String TAG_PERMA_VAMPIRE = "perma_vampire";
	public static final String TAG_PERMA_PALE = "perma_pale";
	public static final String TAG_PERMA_HUMAN = "perma_human";
	/** Transient "standing on moss" marker, consumed by the death ladder. */
	public static final String TAG_PALE_AFFECT = "paleaffect";

	/** The plugin's three fixed modifier UUIDs {@code d}/{@code e}/{@code f}. */
	private static final Identifier MOD_KING_HEALTH = Identifier.fromNamespaceAndPath("altarsmp", "king_health");
	private static final Identifier MOD_PALE_HEALTH = Identifier.fromNamespaceAndPath("altarsmp", "pale_health");
	private static final Identifier MOD_VAMPIRE_HEALTH = Identifier.fromNamespaceAndPath("altarsmp", "vampire_health");

	private static final int DAY_END = 12300;
	private static final int PASSIVE_DURATION = 40;
	private static final long NAME_TASK_PERIOD = 20L;
	private static final long JOIN_DELAY = 5L;
	private static final double BACKSTAB_DOT = -0.3D;

	private final AltarSMPMod mod;
	/** {@code VampireManager#c} - whether {@link #activatePaleSystem()} has run. */
	private boolean paleActivated;
	private long tickCount;
	/** Player -&gt; team name we last assigned, so we only touch the scoreboard on change. */
	private final Map<UUID, String> assignedTeam = new HashMap<>();
	/** Player -&gt; instance we last saw, which is how respawns are detected. */
	private final Map<UUID, ServerPlayer> seen = new HashMap<>();

	public FactionManager(AltarSMPMod mod) {
		this.mod = mod;
	}

	/**
	 * The plugin registered Bukkit listeners; the port's join/respawn/move work rides
	 * {@link #tick} and the damage amplification is pulled by
	 * {@code CombatHooks#modifyDamage}, so the only callback worth owning here is
	 * disconnect, which drops the per-player bookkeeping.
	 */
	public void registerEventHooks() {
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID uuid = handler.player.getUUID();
			this.seen.remove(uuid);
			this.assignedTeam.remove(uuid);
		});
	}

	/**
	 * Nothing to convert when the world came from the Paper plugin - the tags are the
	 * same storage - but a world that carried an earlier build of this mod may have
	 * written the roles into an attached component instead. Those are copied into
	 * tags once, then the component is dropped.
	 */
	public void migrateLegacyData() {
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		int migrated = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (ServerPlayer player : level.players()) {
				// An earlier build of this mod prefixed the tags; a Paper world never did.
				for (String legacy : Set.of("altarsmp_vampire", "altarsmp:vampire", "altarsmp_pale",
						"altarsmp:pale", "altarsmp_human", "altarsmp:human")) {
					if (player.getTags().contains(legacy)) {
						player.removeTag(legacy);
						player.addTag(legacy.substring(legacy.indexOf('_') + 1).replace(':', '_'));
						migrated++;
					}
				}
				// A crash between a role change and the next health pass leaves the rank
				// modifier behind; recomputing it here is what the plugin's onJoin did.
				updateKingHealth(player);
				updatePlayerNameColor(player);
			}
		}
		if (migrated > 0) {
			AltarSMPMod.LOGGER.info("[AltarSMP] migrated {} legacy faction tag(s) to plugin tag names", migrated);
		}
	}

	private static boolean isRoleTag(String tag) {
		return TAG_VAMPIRE.equals(tag) || TAG_PALE.equals(tag) || TAG_HUMAN.equals(tag)
				|| TAG_VAMPIRE_KING.equals(tag) || TAG_PALE_KING.equals(tag);
	}

	// ------------------------------------------------------------------- tick

	public void tick(MinecraftServer server) {
		this.tickCount++;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			updateMossTag(player);
			ServerPlayer previous = this.seen.put(player.getUUID(), player);
			if (previous == null) {
				onJoin(player);
			} else if (previous != player) {
				onRespawn(player);
			}
		}
		if (this.tickCount % NAME_TASK_PERIOD != 0L) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			updatePlayerNameColor(player);
		}
		if (!this.mod.config().getBoolean("curses.enabled", true)) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isVampire(player)) {
				applyVampireEffects(player);
			}
			if (isPale(player)) {
				applyPaleEffects(player);
			}
			updateKingHealth(player);
		}
	}

	/** {@code VampireManager#onJoin} - the 5-tick delayed name/health refresh. */
	private void onJoin(ServerPlayer player) {
		this.mod.scheduler().later(() -> {
			updatePlayerNameColor(player);
			updateKingHealth(player);
		}, JOIN_DELAY);
	}

	/** {@code VampireManager#onRespawn} - re-apply the rank health and re-announce. */
	private void onRespawn(ServerPlayer player) {
		this.mod.scheduler().later(() -> updateKingHealth(player), JOIN_DELAY);
		ServerLevel level = player.serverLevel();
		Vec3 at = player.position();
		if (isVampireKing(player)) {
			Messaging.send(player, "<dark_red>You feel the eternal thirst... You are the Vampire King.");
			Fx.simple(level, "FLAME", at, 40, 1.0D, 1.0D, 1.0D, 0.0D);
		} else if (isVampire(player)) {
			Messaging.send(player, "<red>You feel the thirst for blood... You are a vampire.");
			Fx.simple(level, "FLAME", at, 30, 1.0D, 1.0D, 1.0D, 0.0D);
		} else if (isPaleKing(player)) {
			Messaging.send(player, "<dark_gray>The pale still flows through you... You are the Pale King.");
		} else if (isPale(player)) {
			Messaging.send(player, "<gray>You feel rotted inside... You are a pale rot.");
		}
	}

	/** {@code VampireManager#onMove} - the moss marker that the death ladder reads. */
	private void updateMossTag(ServerPlayer player) {
		BlockState below = player.serverLevel().getBlockState(player.blockPosition().below());
		if (isMoss(below)) {
			if (!player.getTags().contains(TAG_PALE_AFFECT)) {
				player.addTag(TAG_PALE_AFFECT);
			}
		} else {
			player.removeTag(TAG_PALE_AFFECT);
		}
	}

	/** The plugin matched {@code Material#name().contains("MOSS")}. */
	private static boolean isMoss(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("moss");
	}

	// --------------------------------------------------------------- passives

	/** {@code VampireManager#applyVampireEffects}. */
	void applyVampireEffects(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		long time = level.getDayTime();
		boolean day = time >= 0L && time < DAY_END;
		if (day && skyLight(level, player.blockPosition()) >= 15) {
			player.setRemainingFireTicks(PASSIVE_DURATION);
		}
		if (!day && this.mod.config().getBoolean("vampire.enable_night_effects", true)) {
			Effects.apply(player, "STRENGTH", PASSIVE_DURATION,
					this.mod.config().getInt("vampire.night_strength_level", 0), false, false, true);
			Effects.apply(player, "SPEED", PASSIVE_DURATION,
					this.mod.config().getInt("vampire.night_speed_level", 1), false, false, true);
			if (this.mod.config().getBoolean("vampire.night_fire_resistance", true)) {
				Effects.apply(player, "FIRE_RESISTANCE", PASSIVE_DURATION, 0, false, false, true);
			}
		}
	}

	/** {@code VampireManager#applyPaleEffects}. */
	void applyPaleEffects(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		boolean onMoss = isMoss(level.getBlockState(player.blockPosition().below()));
		boolean inRain = level.isRaining() && skyLight(level, player.blockPosition()) > 0 && exposedToSky(level, player);
		if (onMoss && !inRain) {
			Effects.apply(player, "SPEED", PASSIVE_DURATION,
					this.mod.config().getInt("pale.moss_speed_level", 1), false, false, true);
		}
		if (inRain) {
			Effects.apply(player, "WEAKNESS", PASSIVE_DURATION,
					this.mod.config().getInt("pale.rain_weakness_level", 1), false, false, true);
		}
	}

	/** The plugin's "air, non-solid or leaves above the head" test. */
	private static boolean exposedToSky(ServerLevel level, ServerPlayer player) {
		BlockState above = level.getBlockState(player.blockPosition().above());
		return above.isAir() || !above.isSolid()
				|| BuiltInRegistries.BLOCK.getKey(above.getBlock()).getPath().contains("leaves");
	}

	private static int skyLight(ServerLevel level, net.minecraft.core.BlockPos pos) {
		return level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(pos);
	}

	/** {@code VampireManager#updateKingHealth} - the rank's max-health modifier. */
	void updateKingHealth(ServerPlayer player) {
		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth == null) {
			return;
		}
		maxHealth.removeModifier(MOD_KING_HEALTH);
		maxHealth.removeModifier(MOD_PALE_HEALTH);
		maxHealth.removeModifier(MOD_VAMPIRE_HEALTH);

		double bonus;
		Identifier id;
		if (isVampireKing(player)) {
			bonus = this.mod.config().getDouble("vampire.king_health", 10.0D);
			id = MOD_KING_HEALTH;
		} else if (isPaleKing(player)) {
			bonus = this.mod.config().getDouble("pale.king_health", 5.0D);
			id = MOD_KING_HEALTH;
		} else if (isVampire(player)) {
			bonus = this.mod.config().getDouble("vampire.vampire_health", 4.0D);
			id = MOD_VAMPIRE_HEALTH;
		} else if (isPale(player)) {
			bonus = this.mod.config().getDouble("pale.pale_health", 2.0D);
			id = MOD_PALE_HEALTH;
		} else {
			return;
		}
		if (bonus > 0.0D) {
			maxHealth.addModifier(new AttributeModifier(id, bonus, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	/**
	 * {@code VampireManager#updatePlayerNameColor}. Bukkit coloured the display name
	 * and the tab-list entry directly; 26.x has no setter for either, so the port uses
	 * the vanilla scoreboard team that produces the same coloured name everywhere
	 * (chat, tab list, nametag). Friendly fire is left on and nametags stay visible so
	 * joining a team changes nothing but the colour.
	 */
	void updatePlayerNameColor(ServerPlayer player) {
		ChatFormatting colour = null;
		if (isPaleKing(player)) {
			colour = null;
		} else if (isPale(player)) {
			colour = this.mod.bloodMoon().isActive() ? ChatFormatting.YELLOW : null;
		} else if (isVampireKing(player)) {
			colour = ChatFormatting.DARK_RED;
		} else if (isVampire(player)) {
			colour = ChatFormatting.RED;
		}

		String wanted = colour == null ? null : teamName(colour);
		if (wanted == null) {
			if (this.assignedTeam.remove(player.getUUID()) != null) {
				player.serverLevel().getScoreboard().removePlayerFromTeam(player.getGameProfile().getName());
			}
			return;
		}
		if (wanted.equals(this.assignedTeam.get(player.getUUID()))) {
			return;
		}
		Scoreboard scoreboard = player.serverLevel().getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(wanted);
		if (team == null) {
			team = scoreboard.addPlayerTeam(wanted);
			team.setColor(colour);
			team.setAllowFriendlyFire(true);
			team.setCanSeeFriendlyInvisibles(true);
			team.setNameTagVisibility(Team.Visibility.ALWAYS);
			team.setPlayerPrefix(net.minecraft.network.chat.Component.empty());
			team.setPlayerSuffix(net.minecraft.network.chat.Component.empty());
		}
		scoreboard.removePlayerFromTeam(player.getGameProfile().getName());
		if (scoreboard.addPlayerToTeam(player.getGameProfile().getName(), team)) {
			this.assignedTeam.put(player.getUUID(), wanted);
		}
	}

	private static String teamName(ChatFormatting colour) {
		return "altarsmp_" + colour.getName();
	}

	// ------------------------------------------------------------------ roles

	/** {@code VampireManager#activatePaleSystem} - {@code /pale activate}. */
	public void activatePaleSystem() {
		if (!this.mod.config().getBoolean("curses.enabled", true)) {
			return;
		}
		this.paleActivated = true;
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.getTags().contains(TAG_PALE_AFFECT)) {
				makePale(player);
				player.removeTag(TAG_PALE_AFFECT);
				Messaging.send(player, "<dark_gray>The pale consumes you...");
				Fx.soundTo(player, "ENTITY_WARDEN_DEATH", 0.7F, 0.5F);
			}
		}
		Messaging.broadcast(server, "<dark_gray>The pale has awakened... It spreads with every kill.");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Fx.soundTo(player, "ENTITY_WARDEN_SONIC_BOOM", 0.5F, 0.3F);
		}
	}

	public boolean isPaleActivated() {
		return this.paleActivated;
	}

	public void setPaleKing(ServerPlayer player) {
		if (isPermaHuman(player) || isPermaVampire(player)) {
			return;
		}
		clearAllCurseTags(player);
		player.addTag(TAG_PALE);
		player.addTag(TAG_PALE_KING);
		updatePlayerNameColor(player);
		updateKingHealth(player);
	}

	public void setPaleRot(ServerPlayer player) {
		if (isPermaHuman(player) || isPermaVampire(player)) {
			return;
		}
		clearAllCurseTags(player);
		player.addTag(TAG_PALE);
		updatePlayerNameColor(player);
	}

	public void setPermaPaleRot(ServerPlayer player) {
		if (isPermaHuman(player) || isPermaVampire(player)) {
			return;
		}
		if (this.mod.config().getBoolean("curses.allow_perma", false)
				&& this.mod.config().getBoolean("curses.allow_perma_pale", false)) {
			clearAllCurseTags(player);
			player.addTag(TAG_PALE);
			player.addTag(TAG_PERMA_PALE);
			updatePlayerNameColor(player);
			Messaging.send(player, "<dark_gray>The pale has consumed your soul forever...");
		} else {
			setPaleRot(player);
		}
	}

	public static boolean isPermaPale(ServerPlayer player) {
		return player.getTags().contains(TAG_PERMA_PALE);
	}

	public void removePermaPale(ServerPlayer player) {
		player.removeTag(TAG_PERMA_PALE);
	}

	public static boolean isPermaVampire(ServerPlayer player) {
		return player.getTags().contains(TAG_PERMA_VAMPIRE);
	}

	public void removePermaVampire(ServerPlayer player) {
		player.removeTag(TAG_PERMA_VAMPIRE);
	}

	public void setPermaVampire(ServerPlayer player) {
		if (isPermaHuman(player) || isPermaPale(player)) {
			return;
		}
		if (this.mod.config().getBoolean("curses.allow_perma", false)
				&& this.mod.config().getBoolean("curses.allow_perma_vampire", false)) {
			clearAllCurseTags(player);
			player.addTag(TAG_VAMPIRE);
			player.addTag(TAG_PERMA_VAMPIRE);
			updatePlayerNameColor(player);
			updateKingHealth(player);
			Messaging.send(player, "<dark_red>Your veins run eternally cold... You are a True Vampire.");
		} else {
			setVampire(player);
		}
	}

	public static void makePale(ServerPlayer player) {
		if (isPermaHuman(player) || isPermaVampire(player)) {
			return;
		}
		player.removeTag(TAG_VAMPIRE);
		player.removeTag(TAG_VAMPIRE_KING);
		player.removeTag(TAG_HUMAN);
		player.addTag(TAG_PALE);
	}

	public static boolean isPale(ServerPlayer player) {
		return player.getTags().contains(TAG_PALE);
	}

	public static boolean isPaleKing(ServerPlayer player) {
		return player.getTags().contains(TAG_PALE_KING);
	}

	public void setVampireKing(ServerPlayer player) {
		if (isPermaHuman(player) || isPermaPale(player)) {
			return;
		}
		clearAllCurseTags(player);
		player.addTag(TAG_VAMPIRE);
		player.addTag(TAG_VAMPIRE_KING);
		updatePlayerNameColor(player);
		updateKingHealth(player);
	}

	public void setVampire(ServerPlayer player) {
		if (isPermaHuman(player) || isPermaPale(player)) {
			return;
		}
		clearAllCurseTags(player);
		player.addTag(TAG_VAMPIRE);
		updatePlayerNameColor(player);
	}

	public static void makeVampire(ServerPlayer player) {
		if (isPermaHuman(player) || isPermaPale(player)) {
			return;
		}
		player.removeTag(TAG_PALE);
		player.removeTag(TAG_PALE_KING);
		player.removeTag(TAG_HUMAN);
		player.addTag(TAG_VAMPIRE);
	}

	public static boolean isVampire(ServerPlayer player) {
		return player.getTags().contains(TAG_VAMPIRE);
	}

	public static boolean isVampireKing(ServerPlayer player) {
		return player.getTags().contains(TAG_VAMPIRE_KING);
	}

	public void setHuman(ServerPlayer player) {
		if (isPermaVampire(player) || isPermaPale(player)) {
			return;
		}
		clearAllCurseTags(player);
		player.addTag(TAG_HUMAN);
		updatePlayerNameColor(player);
		updateKingHealth(player);
	}

	public void setPermaHuman(ServerPlayer player) {
		if (isPermaVampire(player) || isPermaPale(player)) {
			return;
		}
		if (this.mod.config().getBoolean("curses.allow_perma", false)
				&& this.mod.config().getBoolean("curses.allow_perma_human", false)) {
			clearAllCurseTags(player);
			player.addTag(TAG_HUMAN);
			player.addTag(TAG_PERMA_HUMAN);
			updatePlayerNameColor(player);
			updateKingHealth(player);
			Messaging.send(player, "<gold>You have been purified by divine light. You are forever immune to curses.");
		} else {
			setHuman(player);
		}
	}

	public static boolean isPermaHuman(ServerPlayer player) {
		return player.getTags().contains(TAG_PERMA_HUMAN);
	}

	public void removePermaHuman(ServerPlayer player) {
		player.removeTag(TAG_PERMA_HUMAN);
	}

	public static void makeHuman(ServerPlayer player) {
		if (isPermaVampire(player) || isPermaPale(player)) {
			return;
		}
		player.removeTag(TAG_VAMPIRE);
		player.removeTag(TAG_VAMPIRE_KING);
		player.removeTag(TAG_PALE);
		player.removeTag(TAG_PALE_KING);
		player.addTag(TAG_HUMAN);
	}

	public static boolean isHuman(ServerPlayer player) {
		return player.getTags().contains(TAG_HUMAN) || !isVampire(player) && !isPale(player);
	}

	public static boolean hasCurse(ServerPlayer player) {
		return isVampire(player) || isPale(player);
	}

	public void removeCurse(ServerPlayer player) {
		player.removeTag(TAG_PERMA_PALE);
		player.removeTag(TAG_PERMA_VAMPIRE);
		clearAllCurseTags(player);
		player.addTag(TAG_HUMAN);
		updatePlayerNameColor(player);
		updateKingHealth(player);
	}

	private static void clearAllCurseTags(ServerPlayer player) {
		Set<String> tags = player.getTags();
		tags.remove(TAG_VAMPIRE);
		tags.remove(TAG_VAMPIRE_KING);
		tags.remove(TAG_PALE);
		tags.remove(TAG_PALE_KING);
		tags.remove(TAG_HUMAN);
		tags.remove(TAG_PALE_AFFECT);
	}

	/**
	 * {@code ContagionSignalWeapon#getPlayerRole}'s answer: the role name the signal
	 * announces for its holder.
	 *
	 * @return {@code VampireKing}, {@code Vampire}, {@code PaleKing}, {@code Pale} or {@code Human}
	 */
	public String roleOf(ServerPlayer player) {
		if (isVampireKing(player)) {
			return "VampireKing";
		}
		if (isVampire(player)) {
			return "Vampire";
		}
		if (isPaleKing(player)) {
			return "PaleKing";
		}
		if (isPale(player)) {
			return "Pale";
		}
		return "Human";
	}

	// ------------------------------------------------------------------ death

	/** {@code VampireManager#onDeath} (HIGH) - the whole curse-spread ladder. */
	public void onDeath(LivingEntity victim, ServerLevel level, DamageSource source, @Nullable ServerPlayer killer) {
		if (!(victim instanceof ServerPlayer dead) || !this.mod.config().getBoolean("curses.enabled", true)) {
			return;
		}
		String name = dead.getGameProfile().getName();
		MinecraftServer server = this.mod.server();
		if (NightpiercerWeapon.wasKilledByNightpiercer(dead)
				&& this.mod.config().getBoolean("nightpiercer.perma_vampire_on_kill", false)) {
			NightpiercerWeapon.clearKilledByNightpiercer(dead);
			if (!isPermaHuman(dead) && !isPermaPale(dead)) {
				setPermaVampire(dead);
				broadcast(server, "<dark_red>" + name + "'s soul has been eternally bound to the night...");
				Fx.soundTo(dead, "ENTITY_WITHER_SPAWN", 0.7F, 0.5F);
			}
			return;
		}
		if (PaleCrossbowWeapon.wasKilledByCrossbow(dead)) {
			PaleCrossbowWeapon.clearKilledByCrossbow(dead);
			PaleCrossbowWeapon.removePaleEffect(dead);
			if (!isPermaHuman(dead) && !isPermaVampire(dead)) {
				setPermaPaleRot(dead);
				broadcast(server, "<dark_gray>" + name + "'s soul has been eternally consumed by the pale...");
				Fx.soundTo(dead, "ENTITY_WARDEN_SONIC_BOOM", 0.7F, 0.3F);
			}
			return;
		}
		if (PaleCrossbowWeapon.hasPaleEffect(dead)) {
			PaleCrossbowWeapon.removePaleEffect(dead);
			if (!isPermaHuman(dead) && !isPermaVampire(dead) && !isPale(dead)) {
				setPaleRot(dead);
				broadcast(server, "<gray>" + name + " has succumbed to the pale...");
				Fx.soundTo(dead, "ENTITY_WARDEN_DEATH", 0.7F, 0.5F);
			}
			return;
		}

		if (this.paleActivated && dead.getTags().contains(TAG_PALE_AFFECT)) {
			setPaleRot(dead);
			dead.removeTag(TAG_PALE_AFFECT);
		}
		if (killer == null) {
			return;
		}
		if (isHoldingHyperion(killer) && hasCurse(dead) && !isPermaVampire(dead) && !isPermaPale(dead)) {
			setPermaHuman(dead);
			broadcast(server, "<gold>" + name + " has been eternally purified by the Hyperion!");
			Fx.soundTo(dead, "BLOCK_BEACON_ACTIVATE", 1.0F, 1.5F);
			return;
		}
		if (this.paleActivated && isPale(killer) && !isPale(dead) && !isPermaHuman(dead) && !isPermaVampire(dead)) {
			setPaleRot(dead);
			if (isPaleKing(killer)) {
				broadcast(server, "<dark_gray>" + name + " has been consumed by the pale...");
			} else {
				broadcast(server, "<gray>" + name + " has become a pale rot...");
			}
			Fx.soundTo(dead, "ENTITY_WARDEN_DEATH", 0.7F, 0.5F);
		}
		if (isVampire(killer) && !isVampire(dead) && !isPale(dead) && !isPermaHuman(dead) && !isPermaPale(dead)) {
			setVampire(dead);
			broadcast(server, "<red>" + name + " has been turned into a vampire!");
			Fx.soundTo(dead, "ENTITY_WITHER_SPAWN", 0.5F, 1.2F);
		}
	}

	private static void broadcast(@Nullable MinecraftServer server, String markup) {
		if (server != null) {
			Messaging.broadcast(server, markup);
		}
	}

	/**
	 * {@code VampireManager#onDamage} - player-versus-player damage amplification.
	 *
	 * @param attacker the hitting player
	 * @param victim   the hit player
	 * @param damage   the incoming amount
	 * @return the amplified amount (unchanged when no rule applies)
	 */
	public float modifyPlayerDamage(ServerPlayer attacker, ServerPlayer victim, float damage) {
		float result = damage;
		long time = attacker.serverLevel().getDayTime();
		boolean night = time >= DAY_END || time < 0L;
		if (isHoldingHyperion(attacker) && hasCurse(victim)) {
			result += (float) this.mod.config().getDouble("vampire.hyperion_bonus", 2.0D);
		}
		if (isVampire(attacker) && night) {
			boolean bloodMoon = this.mod.bloodMoon().isActive();
			boolean noStack = this.mod.config().getBoolean("bloodmoon.disable-night-damage-stack", false);
			if (!bloodMoon || !noStack) {
				result += (float) this.mod.config().getDouble("vampire.night_damage", 2.0D);
			}
		}
		if (isPale(attacker) && isBackstab(attacker, victim)) {
			result *= (float) this.mod.config().getDouble("pale.backstab_multiplier", 1.4D);
		}
		return result;
	}

	/** {@code VampireManager#isBackstab} - attacker behind the victim's facing. */
	private static boolean isBackstab(ServerPlayer attacker, ServerPlayer victim) {
		Vec3 facing = victim.getLookAngle().normalize();
		Vec3 toAttacker = attacker.position().subtract(victim.position()).normalize();
		return facing.dot(toAttacker) < BACKSTAB_DOT;
	}

	/** {@code VampireManager#isHoldingHyperion}. */
	private boolean isHoldingHyperion(ServerPlayer player) {
		return this.mod.abilities().weaponContext(player)
				.map(context -> "hyperion".equals(context.weaponId()))
				.orElse(false);
	}

	/**
	 * The plugin had no test world in this mod's scope; the check existed so curses
	 * never fired inside the arena world. Worlds listed under
	 * {@code curses.disabled_worlds} are treated the same way here.
	 */
	boolean isInTestWorld(ServerPlayer player) {
		String disabled = this.mod.config().getString("curses.disabled_worlds", "");
		if (disabled.isBlank()) {
			return false;
		}
		String key = player.serverLevel().dimension().location().toString();
		for (String world : disabled.split(",")) {
			if (world.trim().equalsIgnoreCase(key)) {
				return true;
			}
		}
		return false;
	}
}
