package com.altarsmp.fabric.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The editable-stat tables behind {@code /legendaryconfig} and {@code /legendaryconfig2}.
 *
 * <p>Each weapon and armour piece in the plugin declared the values an operator could
 * change through {@code BaseWeapon#getConfigFields()}; the season 1 command added a
 * global block of its own. Those declarations are reproduced here verbatim - labels,
 * config paths, ranges and steps - by {@code tools/extract_config_fields.py}, so the
 * editor offers exactly the numbers the plugin offered, bounded the way it bounded
 * them.
 */
public final class ConfigFields {
	/** What kind of value a field holds, which decides how an edit is parsed. */
	public enum Kind { TOGGLE, INTEGER, DECIMAL }

	/**
	 * One editable value: {@code a.c} in the plugin, whose three variants were a toggle,
	 * a whole number with a range, and a decimal with a range and a step.
	 */
	public record Field(String label, String path, Kind kind, double min, double max, double step) {
		public static Field toggle(String label, String path) {
			return new Field(label, path, Kind.TOGGLE, 0.0D, 1.0D, 1.0D);
		}

		public static Field integer(String label, String path, int min, int max) {
			return new Field(label, path, Kind.INTEGER, min, max, 1.0D);
		}

		public static Field decimal(String label, String path, float min, float max, float step) {
			return new Field(label, path, Kind.DECIMAL, min, max, step);
		}

		/** The value's default when the config has no entry: the range floor, as in the plugin. */
		public double defaultValue() {
			return this.kind == Kind.TOGGLE ? 0.0D : this.min;
		}
	}

	/** The global block's id; it is not a catalogue item. */
	public static final String GLOBAL = "globalsettings";

	private static final Map<String, List<Field>> TABLES = tables();

	private ConfigFields() {}

	private static Map<String, List<Field>> tables() {
		Map<String, List<Field>> tables = new LinkedHashMap<>();
		tables.put("hyperion", List.of(
				Field.integer("Holy Lance Cooldown (s)", "abilities.hyperion.holy_lance_cooldown", 0, 300),
				Field.decimal("Holy Lance Damage", "abilities.hyperion.holy_lance_damage", 0.0F, 40.0F, 0.5F),
				Field.decimal("Holy Lance Radius", "abilities.hyperion.holy_lance_radius", 1.0F, 20.0F, 0.5F),
				Field.integer("Scorching Cooldown (s)", "abilities.hyperion.scorching_cooldown", 0, 300),
				Field.decimal("Scorching Damage", "abilities.hyperion.scorching_blade_damage", 0.0F, 20.0F, 0.5F),
				Field.decimal("Scorching Radius", "abilities.hyperion.scorching_radius", 1.0F, 20.0F, 0.5F),
				Field.integer("Scorching Lava Duration (ticks)", "abilities.hyperion.scorching_lava_duration", 0, 400)
		));
		tables.put("nightpiercer", List.of(
				Field.integer("Transform Cooldown (s)", "abilities.nightpiercer.transform_cooldown", 0, 300),
				Field.integer("Crimson Bite Cooldown (s)", "abilities.nightpiercer.bite_cooldown", 0, 300),
				Field.decimal("Crimson Bite Damage", "abilities.nightpiercer.crimson_bite_damage", 0.0F, 30.0F, 0.5F),
				Field.decimal("Health Steal", "abilities.nightpiercer.health_steal", 0.0F, 20.0F, 0.5F)
		));
		tables.put("palecrossbow", List.of(
				Field.integer("Arrow Bonus Damage", "abilities.palecrossbow.arrow_bonus_damage", 0, 40),
				Field.decimal("Pale Shot Speed", "abilities.palecrossbow.pale_shot.speed", 0.0F, 5.0F, 0.1F),
				Field.decimal("Pale Shot Gravity", "abilities.palecrossbow.pale_shot.gravity", 0.0F, 0.1F, 5.0E-4F),
				Field.integer("Pale Roots Cooldown (s)", "abilities.palecrossbow.pale_roots.cooldown", 0, 300),
				Field.decimal("Pale Roots Damage", "abilities.palecrossbow.pale_roots.damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Pale Roots Arch Count", "abilities.palecrossbow.pale_roots.arch_count", 1, 20),
				Field.integer("Pale Roots Range", "abilities.palecrossbow.pale_roots.range", 1, 60)
		));
		tables.put("cutlass", List.of(
				Field.integer("Thousand Cuts Cooldown (s)", "abilities.cutlass.cuts_cooldown", 0, 300),
				Field.integer("Parry Cooldown (s)", "abilities.cutlass.parry_cooldown", 0, 300),
				Field.decimal("Cut Damage", "abilities.cutlass.cut_damage", 0.0F, 20.0F, 0.5F),
				Field.integer("Cuts Slash Count", "abilities.cutlass.cuts_slash_count", 1, 30),
				Field.integer("Parry Block Count", "abilities.cutlass.parry_block_count", 1, 10)
		));
		tables.put("boneblade", List.of(
				Field.integer("Leap Cooldown (s)", "abilities.boneblade.leap_cooldown", 0, 300),
				Field.integer("Bone Cage Cooldown (s)", "abilities.boneblade.cage_cooldown", 0, 300),
				Field.integer("Bone Cage Stun Duration (s)", "abilities.boneblade.stun_duration", 0, 30)
		));
		tables.put("shadowblade", List.of(
				Field.integer("Leap Cooldown (s)", "abilities.shadowblade.leap_cooldown", 0, 300),
				Field.integer("Dagger Cooldown (s)", "abilities.shadowblade.dagger_cooldown", 0, 300),
				Field.decimal("Leap Distance", "abilities.shadowblade.leap_distance", 1.0F, 100.0F, 1.0F),
				Field.decimal("Dagger Damage", "abilities.shadowblade.dagger_damage", 0.0F, 20.0F, 0.5F),
				Field.decimal("Dagger AoE Radius", "abilities.shadowblade.dagger_aoe_radius", 0.5F, 15.0F, 0.5F)
		));
		tables.put("earthgauntlet", List.of(
				Field.integer("Meteor Cooldown (s)", "abilities.earthgauntlet.meteor_cooldown", 0, 300),
				Field.integer("Mudslide Cooldown (s)", "abilities.earthgauntlet.mudslide_cooldown", 0, 300),
				Field.decimal("Meteor Damage", "abilities.earthgauntlet.meteor_damage", 0.0F, 30.0F, 0.5F),
				Field.decimal("Mud Damage", "abilities.earthgauntlet.mud_damage", 0.0F, 20.0F, 0.5F),
				Field.decimal("Meteor Knockback", "abilities.earthgauntlet.meteor_knockback", 0.0F, 15.0F, 0.5F),
				Field.integer("Meteor Active Window (ticks)", "abilities.earthgauntlet.meteor_active_ticks", 0, 400),
				Field.decimal("Meteor Vertical Knockback Cap", "abilities.earthgauntlet.meteor_vertical_velocity_cap", 0.0F, 3.0F, 0.05F)
		));
		tables.put("vulcanscrossbow", List.of(
				Field.integer("Wrath Cooldown (s)", "abilities.vulcanscrossbow.wrath_cooldown", 0, 300),
				Field.decimal("Wrath Damage", "abilities.vulcanscrossbow.wrath_damage", 0.0F, 60.0F, 1.0F),
				Field.decimal("Wrath Radius", "abilities.vulcanscrossbow.wrath_radius", 1.0F, 20.0F, 0.5F),
				Field.decimal("Middle Arrow Damage", "abilities.vulcanscrossbow.middle_arrow_damage", 0.0F, 15.0F, 0.5F),
				Field.decimal("Side Arrow Damage", "abilities.vulcanscrossbow.side_arrow_damage", 0.0F, 15.0F, 0.5F),
				Field.toggle("Fire Cooldown On Shoot", "abilities.vulcanscrossbow.fire_cooldown_enabled"),
				Field.integer("Fire Cooldown (ticks)", "abilities.vulcanscrossbow.fire_cooldown_ticks", 0, 200)
		));
		tables.put("bloodlust", List.of(
				Field.integer("Hook Cooldown (s)", "abilities.bloodlust.hook_cooldown", 0, 300),
				Field.decimal("Hook Damage", "abilities.bloodlust.hook_damage", 0.0F, 40.0F, 0.5F),
				Field.decimal("Hook Distance", "abilities.bloodlust.hook_distance", 1.0F, 60.0F, 0.5F),
				Field.integer("Trail Cooldown (s)", "abilities.bloodlust.trail_cooldown", 0, 300),
				Field.decimal("Bleed Damage", "abilities.bloodlust.bleed_damage", 0.0F, 20.0F, 0.5F),
				Field.integer("Bleed Cooldown (s)", "abilities.bloodlust.bleed_cooldown", 0, 60)
		));
		tables.put("echo", List.of(
				Field.decimal("Sonic Blast Speed", "abilities.echo.sonic_blast.speed", 0.0F, 10.0F, 0.1F),
				Field.decimal("Sonic Blast Damage", "abilities.echo.sonic_blast.damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Crater Radius", "abilities.echo.sonic_blast.crater_radius", 0, 20),
				Field.integer("Crater Depth", "abilities.echo.sonic_blast.crater_depth", 0, 20),
				Field.integer("Shriek Effect Duration (ticks)", "abilities.echo.shriek.effect_duration", 0, 600)
		));
		tables.put("eclipsesword", List.of(
				Field.integer("Moonlit Stars Cooldown (s)", "abilities.eclipse.moonlit_stars.cooldown", 0, 300),
				Field.integer("Moonlit Stars Range", "abilities.eclipse.moonlit_stars.range", 1, 100),
				Field.decimal("Moonlit Stars Damage", "abilities.eclipse.moonlit_stars.damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Moonlit Shadows Cooldown (s)", "abilities.eclipse.moonlit_shadows.cooldown", 0, 300),
				Field.integer("Moonlit Shadows Duration (s)", "abilities.eclipse.moonlit_shadows.duration", 0, 30)
		));
		tables.put("knightfall", List.of(
				Field.integer("Grapple Cooldown (s)", "abilities.knightfall.grapple.cooldown", 0, 300),
				Field.decimal("Grapple Launch Height", "abilities.knightfall.grapple.launch-height", 0.0F, 3.0F, 0.05F),
				Field.integer("Hammer Throw Cooldown (s)", "abilities.knightfall.hammer_throw.cooldown", 0, 300),
				Field.decimal("Hammer Throw Damage", "abilities.knightfall.hammer_throw.damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Passive Speed Kills Required", "passives.knightfall.speed.kills-required", 0, 30),
				Field.integer("Passive Speed Level", "passives.knightfall.speed.level", 0, 5)
		));
		tables.put("nukelauncher", List.of(
				Field.integer("Cooldown (s)", "abilities.nukelauncher.cooldown", 0, 1200),
				Field.integer("Target Range", "abilities.nukelauncher.target_range", 0, 500),
				Field.integer("Countdown (s)", "abilities.nukelauncher.countdown", 0, 120),
				Field.integer("Kill Radius", "abilities.nukelauncher.kill_radius", 0, 200),
				Field.integer("Crater Radius", "abilities.nukelauncher.crater_radius", 0, 300),
				Field.integer("Damage Radius", "abilities.nukelauncher.damage_radius", 0, 300),
				Field.decimal("Outer Damage", "abilities.nukelauncher.outer_damage", 0.0F, 40.0F, 0.5F)
		));
		tables.put("paladinbattleaxe", List.of(
				Field.integer("Shatter Cooldown (s)", "abilities.paladinbattleaxe.shatter_cooldown", 0, 300),
				Field.decimal("Earth Shatter Damage", "abilities.paladinbattleaxe.earth_shatter_damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Shatter Radius", "abilities.paladinbattleaxe.shatter_radius", 0, 20),
				Field.integer("Stalwart Cooldown (s)", "abilities.paladinbattleaxe.stalwart_cooldown", 0, 300),
				Field.integer("Stalwart Duration (ticks)", "abilities.paladinbattleaxe.stalwart_duration", 0, 600),
				Field.decimal("Stalwart Damage Cap", "abilities.paladinbattleaxe.stalwart_damage_cap", 0.0F, 200.0F, 1.0F)
		));
		tables.put("pureblade", List.of(
				Field.integer("Shade Soul Cooldown (s)", "abilities.pureblade.shade_soul_cooldown", 0, 300),
				Field.decimal("Shade Soul Velocity", "abilities.pureblade.shade_soul_velocity", 0.0F, 10.0F, 0.1F),
				Field.decimal("Shade Soul Hit Radius", "abilities.pureblade.shade_soul_hit_radius", 0.0F, 10.0F, 0.5F),
				Field.integer("Cyclone Cooldown (s)", "abilities.pureblade.cyclone_cooldown", 0, 300),
				Field.integer("Cyclone Hit Interval", "abilities.pureblade.cyclone_hit_interval", 1, 40),
				Field.integer("Cyclone Cobweb Radius", "abilities.pureblade.cyclone_cobweb_radius", 0, 15)
		));
		tables.put("striker", List.of(
				Field.decimal("TNT Arrow Damage", "abilities.striker.tnt_arrow.damage", 0.0F, 20.0F, 0.5F),
				Field.integer("TNT Arrow Radius", "abilities.striker.tnt_arrow.radius", 0, 15),
				Field.integer("Strike Shot Cooldown (s)", "abilities.striker.strike_shot.cooldown", 0, 300),
				Field.integer("Strike Shot Column Height", "abilities.striker.strike_shot.column_height", 0, 320),
				Field.decimal("Strike Shot Damage", "abilities.striker.strike_shot.damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Strike Shot Radius (XZ)", "abilities.striker.strike_shot.radius_xz", 0, 30),
				Field.toggle("Carve Terrain (break blocks)", "abilities.striker.carve_terrain")
		));
		tables.put("wandofillusion", List.of(
				Field.integer("Disguise Cooldown (s)", "abilities.wandofillusion.disguise_cooldown", 0, 300),
				Field.integer("Ability Cooldown (s)", "abilities.wandofillusion.ability_cooldown", 0, 300)
		));
		tables.put("windweaver", List.of(
				Field.integer("Leap Cooldown (s)", "abilities.windweaver.leap_cooldown", 0, 300),
				Field.decimal("Leap Velocity", "abilities.windweaver.leap_velocity", 0.0F, 5.0F, 0.1F),
				Field.integer("Gust Cooldown Min (s)", "abilities.windweaver.gust_cooldown_min", 0, 300),
				Field.integer("Gust Cooldown Max (s)", "abilities.windweaver.gust_cooldown_max", 0, 300),
				Field.decimal("Gust Knockback Base", "abilities.windweaver.gust_y_knockback_base", 0.0F, 3.0F, 0.05F),
				Field.decimal("Gust Knockback Bonus", "abilities.windweaver.gust_y_knockback_bonus", 0.0F, 3.0F, 0.05F)
		));
		tables.put("witherbone", List.of(
				Field.integer("Leap Cooldown (s)", "abilities.witherbone.leap_cooldown", 0, 300),
				Field.decimal("Leap Damage", "abilities.witherbone.leap_damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Strike Cooldown (s)", "abilities.witherbone.strike_cooldown", 0, 300),
				Field.decimal("Strike Damage", "abilities.witherbone.strike_damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Strike Range", "abilities.witherbone.strike_range", 0, 30),
				Field.integer("Wither Meter Size (hits)", "abilities.witherbone.full_charge", 1, 200)
		));
		tables.put("crazyslots", List.of(
				Field.integer("Transform Cooldown (s)", "abilities.crazyslots.transform_cooldown", 0, 300)
		));
		tables.put("copperhelmet", List.of(
				Field.integer("Copper Vision Cooldown (s)", "copper-armor.helmet.cooldown", 0, 300),
				Field.integer("Copper Vision Duration (s)", "copper-armor.helmet.duration", 0, 60),
				Field.integer("Copper Vision Radius", "copper-armor.helmet.radius", 0, 200),
				Field.decimal("Lightning Damage", "copper-armor.helmet.lightning_damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Charge Time (s)", "copper-armor.helmet.charge_time", 0, 20)
		));
		tables.put("copperchestplate", List.of(
				Field.decimal("Thorns Radius", "copper-armor.chestplate.thorns_radius", 0.0F, 30.0F, 0.5F),
				Field.decimal("Thorns Damage", "copper-armor.chestplate.thorns_damage", 0.0F, 40.0F, 0.5F),
				Field.decimal("Lightning Ring Radius", "copper-armor.chestplate.lightning_ring_radius", 0.0F, 20.0F, 0.5F),
				Field.integer("Lightning Ring Strikes", "copper-armor.chestplate.lightning_ring_strikes", 0, 30),
				Field.integer("Hits Required", "copper-armor.chestplate.hits_required", 1, 30)
		));
		tables.put("copperleggings", List.of(
				Field.integer("Min Fall Distance", "copper-armor.leggings.min_fall_distance", 0, 30),
				Field.decimal("Shockwave Base Damage", "copper-armor.leggings.shockwave_base_damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Shockwave Scaling Interval", "copper-armor.leggings.shockwave_scaling_interval", 1, 30),
				Field.decimal("Shockwave Scaling Damage", "copper-armor.leggings.shockwave_scaling_damage", 0.0F, 20.0F, 0.5F),
				Field.integer("Shockwave Radius", "copper-armor.leggings.shockwave_radius", 0, 20)
		));
		tables.put("copperboots", List.of(
				Field.integer("Speed Level", "copper-armor.boots.speed_level", 0, 5),
				Field.toggle("Fire Resistance", "copper-armor.boots.fire_resistance"),
				Field.integer("Copper Block Speed Level", "copper-armor.boots.copper_block_speed_level", 0, 5)
		));
		tables.put("copperpickaxe", List.of(
				Field.integer("Blocks Required to Upgrade", "copper_pickaxe.blocks_required", 0, 100000)
		));
		tables.put("omen", List.of(
				Field.integer("Vault Omen Cooldown (s)", "abilities.omen.vault_omen.cooldown", 0, 300),
				Field.integer("Vault Omen Duration (s)", "abilities.omen.vault_omen.duration", 0, 60),
				Field.integer("Vault Omen Tether Radius", "abilities.omen.vault_omen.tether_radius", 0, 30),
				Field.integer("Ominous Conjuring Cooldown (s)", "abilities.omen.ominous_conjuring.cooldown", 0, 300),
				Field.integer("Ominous Conjuring Buff Duration (s)", "abilities.omen.ominous_conjuring.buff_duration", 0, 60),
				Field.decimal("Ominous Projectile Damage", "abilities.omen.ominous_projectile.damage", 0.0F, 40.0F, 0.5F)
		));
		tables.put("ancientblade", List.of(
				Field.integer("Presence Cooldown (s)", "abilities.ancientblade.presence.cooldown", 0, 300),
				Field.integer("Presence Mark Duration (s)", "abilities.ancientblade.presence.mark_duration", 0, 60),
				Field.integer("Unyielding Darkness Cooldown (s)", "abilities.ancientblade.unyielding_darkness.cooldown", 0, 300),
				Field.decimal("Unyielding Darkness Damage", "abilities.ancientblade.unyielding_darkness.damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Unyielding Darkness Sonic Count", "abilities.ancientblade.unyielding_darkness.sonic_count", 1, 20),
				Field.integer("Echolocation Range", "abilities.ancientblade.echolocation.range", 1, 64)
		));
		tables.put("withersymbiote", List.of(
				Field.integer("Infection Cooldown (s)", "abilities.withersymbiote.infection.cooldown", 0, 300),
				Field.decimal("Infection Detonate Damage", "abilities.withersymbiote.infection.detonate_damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Infection Duration (s)", "abilities.withersymbiote.infection.duration", 0, 60),
				Field.integer("Rampage Cooldown (s)", "abilities.withersymbiote.rampage.cooldown", 0, 300),
				Field.integer("Rampage Duration (s)", "abilities.withersymbiote.rampage.duration", 0, 120),
				Field.decimal("Rampage Lifesteal", "abilities.withersymbiote.rampage.lifesteal", 0.0F, 10.0F, 0.1F)
		));
		tables.put("tidebreaker", List.of(
				Field.integer("Stormcall Cooldown (s)", "abilities.tidebreaker.stormcall.cooldown", 0, 300),
				Field.decimal("Stormcall Damage", "abilities.tidebreaker.stormcall.damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Stormcall Radius", "abilities.tidebreaker.stormcall.radius", 0, 20),
				Field.integer("Rising Tide Cooldown (s)", "abilities.tidebreaker.rising_tide.cooldown", 0, 300),
				Field.decimal("Rising Tide Water Damage", "abilities.tidebreaker.rising_tide.water_damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Rising Tide Jet Height", "abilities.tidebreaker.rising_tide.jet_height", 0, 40)
		));
		tables.put("dragonrend", List.of(
				Field.integer("Hypersonic Slash Cooldown (s)", "abilities.dragonrend.hypersonic.cooldown", 0, 300),
				Field.decimal("Hypersonic Slash Damage", "abilities.dragonrend.hypersonic.damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Hypersonic Strike Cooldown (ms)", "abilities.dragonrend.hypersonic.strike_cooldown_ms", 0, 5000),
				Field.decimal("Hypersonic Detection Range", "abilities.dragonrend.hypersonic.detection_range", 1.0F, 64.0F, 0.5F),
				Field.integer("Infinite Void Cooldown (s)", "abilities.dragonrend.infinite_void.cooldown", 0, 300),
				Field.decimal("Infinite Void Slow Radius", "abilities.dragonrend.infinite_void.slow_radius", 0.0F, 30.0F, 0.5F)
		));
		tables.put("globalsettings", List.of(
				Field.toggle("Announce Craft Coords", "announce-craft-coords"),
				Field.toggle("Victim Ability Immunity", "ability-immunity.victim-immunity"),
				Field.integer("Victim Immunity Duration (s)", "ability-immunity.victim-duration", 0, 30),
				Field.toggle("Attacker Ability Cooldown", "ability-immunity.attacker-cooldown"),
				Field.integer("Attacker Cooldown Duration (s)", "ability-immunity.attacker-duration", 0, 30),
				Field.toggle("Weapon Protection Enabled", "weapon-protection.enabled"),
				Field.toggle("Weapon Burn Protection", "weapon-protection.burn-protection"),
				Field.toggle("Block Weapons in Offhand", "weapon-protection.block-offhand"),
				Field.toggle("Block Weapons in Chests", "weapon-protection.containers.chest"),
				Field.toggle("Block Weapons in Barrels", "weapon-protection.containers.barrel"),
				Field.toggle("Block Weapons in Ender Chests", "weapon-protection.containers.ender_chest"),
				Field.toggle("Block Weapons in Hoppers", "weapon-protection.containers.hopper"),
				Field.toggle("Block Weapons in Shulkers", "weapon-protection.containers.shulker"),
				Field.toggle("Block Weapons in Bundles", "weapon-protection.containers.bundle"),
				Field.toggle("Weapons Indestructible (No Explosions)", "weapon-destruction-protection.enabled"),
				Field.integer("Head Drop Cooldown (s)", "head-drop.cooldown", 0, 86400),
				Field.toggle("Tooltip Styles Enabled", "cosmetics.tooltip_styles_enabled"),
				Field.decimal("Altar Rotation Speed", "altar.rotation_speed", 0.0F, 10.0F, 0.1F),
				Field.decimal("Altar Hologram Height", "altar.hologram_height", 0.0F, 10.0F, 0.1F),
				Field.integer("Altar Spawn Default Range", "altar-spawn.default-range", 0, 5000),
				Field.toggle("Eclipse Revamp Enabled", "eclipse-revamp.enabled"),
				Field.toggle("Striker Nuke Shot Enabled", "eclipse-revamp.striker-nuke-shot"),
				Field.integer("Starfall Cooldown (s)", "eclipse-revamp.starfall.cooldown", 0, 600),
				Field.integer("Starfall Radius", "eclipse-revamp.starfall.radius", 0, 100),
				Field.decimal("Starfall Inner Damage", "eclipse-revamp.starfall.inner-damage", 0.0F, 40.0F, 0.5F),
				Field.decimal("Starfall Outer Damage", "eclipse-revamp.starfall.outer-damage", 0.0F, 40.0F, 0.5F),
				Field.integer("Starfall Inner Radius", "eclipse-revamp.starfall.inner-radius", 0, 100),
				Field.integer("Starfall Crater Radius", "eclipse-revamp.starfall.center-crater-radius", 0, 50),
				Field.integer("Starfall Crater Depth", "eclipse-revamp.starfall.center-crater-depth", 0, 50),
				Field.integer("Starfall Impact Count", "eclipse-revamp.starfall.impact-count", 0, 200),
				Field.toggle("Terrain Destruction Enabled", "abilities.terrain_destruction"),
				Field.decimal("Ominous Vault Fragment Drop Chance", "trials.helmet.fragment-drop-chance", 0.0F, 1.0F, 0.01F)
		));
		tables.put("frostscythe", List.of(
				Field.integer("Throw Cooldown (s)", "abilities.frostscythe.throw_cooldown", 0, 300),
				Field.decimal("Throw Damage", "abilities.frostscythe.throw_damage", 0.0F, 30.0F, 0.5F),
				Field.decimal("Throw Model X Rotation", "abilities.frostscythe.throw_model_rotation_x", -360.0F, 360.0F, 1.0F),
				Field.decimal("Throw Model Y Rotation", "abilities.frostscythe.throw_model_rotation_y", -360.0F, 360.0F, 1.0F),
				Field.decimal("Throw Model Z Rotation", "abilities.frostscythe.throw_model_rotation_z", -360.0F, 360.0F, 1.0F),
				Field.decimal("Throw Model Scale", "abilities.frostscythe.throw_model_scale", 0.1F, 5.0F, 0.1F),
				Field.decimal("Throw Model Spin Speed", "abilities.frostscythe.throw_model_spin_speed", 0.0F, 3.0F, 0.05F),
				Field.integer("Command of Ice Cooldown (s)", "abilities.frostscythe.command_cooldown", 0, 300),
				Field.decimal("Command of Ice Damage (per ice)", "abilities.frostscythe.command_damage", 0.0F, 15.0F, 0.5F)
		));
		return tables;
	}

	/** Every content id that has editable stats, in the order the plugin listed them. */
	public static Set<String> contentIds() {
		return TABLES.keySet();
	}

	/** The editable stats of one catalogue entry; empty when it has none. */
	public static List<Field> forContent(String contentId) {
		return TABLES.getOrDefault(contentId, List.of());
	}

	/** Season 2's browser only offered its five weapons. */
	public static List<String> seasonTwoIds() {
		return List.of("omen", "ancientblade", "withersymbiote", "tidebreaker", "dragonrend");
	}

	/** Season 1's browser: everything except the global block and season 2's weapons. */
	public static List<String> seasonOneIds() {
		List<String> ids = new java.util.ArrayList<>(TABLES.keySet());
		ids.removeAll(seasonTwoIds());
		ids.remove(GLOBAL);
		return ids;
	}
}
