package com.altarsmp.fabric.config;

import com.altarsmp.fabric.AltarSMPFabric;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.*;

public class ConfigManager {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec INVENTORY_SPEC;
    public static final ForgeConfigSpec DIMENSION_SPEC;
    
    public static final CommonConfig COMMON;
    public static final InventoryConfig INVENTORY;
    public static final DimensionConfig DIMENSION;
    
    static {
        ForgeConfigSpec.Builder commonBuilder = new ForgeConfigSpec.Builder();
        ForgeConfigSpec.Builder inventoryBuilder = new ForgeConfigSpec.Builder();
        ForgeConfigSpec.Builder dimensionBuilder = new ForgeConfigSpec.Builder();
        
        // ========================================================
        // GLOBAL SETTINGS
        // ========================================================
        
        commonBuilder.push("global-settings");
        
        // Announce legendary weapon coords
        commonBuilder.comment("Broadcast a chat message to all players when a legendary weapon is crafted.")
            .comment("When true, the altar's coordinates are included in the announcement.")
            .define("announce-craft-coords", false);
        
        // Anti-spam protection
        commonBuilder.comment("Two-layer anti-spam protection for ability damage.")
            .comment("victim-immunity   : after getting hit by an ability, the victim is immune to ALL")
            .comment("                     other abilities for the duration (stops getting stacked by 3 weapons).")
            .comment("attacker-cooldown : after your ability lands, you can't deal ability damage again for")
            .comment("                     the duration (stops chaining frost throw -> shadow dash etc.).")
            .push("ability-immunity");
        commonBuilder.comment("victim-immunity : after getting hit by an ability, the victim is immune to ALL")
            .comment("                     other abilities for the duration (stops getting stacked by 3 weapons).")
            .define("victim-immunity", true);
        commonBuilder.comment("victim-duration : duration of victim immunity in seconds")
            .define("victim-duration", 2);
        commonBuilder.comment("attacker-cooldown : after your ability lands, you can't deal ability damage again for")
            .comment("                     the duration (stops chaining frost throw -> shadow dash etc.).")
            .define("attacker-cooldown", true);
        commonBuilder.comment("attacker-duration : duration of attacker cooldown in seconds")
            .define("attacker-duration", 2);
        commonBuilder.pop(); // ability-immunity
        
        // Weapon protection
        commonBuilder.comment("Prevents legendary weapons from being placed in storage and/or burning in fire/lava.")
            .define("weapon-protection-enabled", true);
        
        commonBuilder.comment("burn-protection : block weapons from burning in fire/lava/cactus/void")
            .define("burn-protection", true);
        
        commonBuilder.push("weapon-protection-containers");
        commonBuilder.chest = define("chest", true);
        commonBuilder.barrel = define("barrel", true);
        commonBuilder.ender_chest = define("ender_chest", true);
        commonBuilder.hopper = define("hopper", true);
        commonBuilder.shulker = define("shulker", true);
        commonBuilder.bundle = define("bundle", true);
        commonBuilder.other = define("other", true); // droppers, dispensers, furnaces, brewing, crafters
        commonBuilder.pop(); // weapon-protection-containers
        
        // Weapon destruction protection
        commonBuilder.comment("Makes weapons completely indestructible (explosions, despawn, cactus, void).")
            .define("weapon-destruction-protection-enabled", false);
        
        // Head drop
        commonBuilder.comment("Players drop their head when killed; cooldown stops farming.")
            .define("head-drop-cooldown", 3600); // seconds
        
        // Tooltip styles
        commonBuilder.comment("Custom tooltip frames per canonical weapon.")
            .comment("Requires the AltarSMP_S1A1 resource pack (provides altarsmp:tooltip/<key>).")
            .comment("Disable if you're running without the pack or on a Paper build that lacks")
            .comment("the setTooltipStyle API (the code will silently no-op either way).")
            .define("tooltip-styles-enabled", true);
        
        // Altar settings
        commonBuilder.comment("How fast the floating altar item rotates / how high the hologram text sits.")
            .define("rotation-speed", 2.0F);
        commonBuilder.comment("Default block radius when /altarspawn places altars randomly.")
            .define("altar-spawn-default-range", 500);
        
        // Copper pickaxe upgrade
        commonBuilder.push("copper-pickaxe");
        commonBuilder.blocks_required = define("blocks_required", 25000);
        commonBuilder.pop(); // copper-pickaxe
        
        commonBuilder.pop(); // global-settings
        
        // ========================================================
        // ECLIPSE / STAR FALL
        // ========================================================
        
        commonBuilder.push("eclipse-revamp");
        commonBuilder.enabled = define("enabled", true);
        commonBuilder.striker-nuke-shot = define("striker-nuke-shot", false);
        
        commonBuilder.push("starfall");
        commonBuilder.cooldown = define("cooldown", 60); // seconds
        commonBuilder.radius = define("radius", 40); // block radius
        commonBuilder.inner_damage = define("inner-damage", 8.0); // damage within inner-radius (4 hearts)
        commonBuilder.outer_damage = define("outer-damage", 4.0); // damage outside inner-radius (2 hearts)
        commonBuilder.inner_radius = define("inner-radius", 20);
        commonBuilder.center_crater_radius = define("center-crater-radius", 15);
        commonBuilder.center_crater_depth = define("center-crater-depth", 10);
        commonBuilder.impact_count = define("impact-count", 40);
        commonBuilder.pop(); // starfall
        commonBuilder.pop(); // eclipse-revamp
        
        // ========================================================
        // COPPER TRIAL
        // ========================================================
        
        commonBuilder.push("trials");
        commonBuilder helmet = define("helmet", new ForgeConfigSpec.Default<Float>(0.10F));
        helmet.comment = "Fragment drop chance from ominous vaults (0.10 = 10%)";
        commonBuilder.pop(); // trials
        
        // ========================================================
        // WEAPON ABILITIES
        // ========================================================
        
        commonBuilder.push("abilities");
        
        // Bloodlust ability
        commonBuilder.push("bloodlust");
        commonBuilder.bleed_chance = define("bleed_chance", 0.15);
        commonBuilder.bleed_damage = define("bleed_damage", 1.0);
        commonBuilder.bleed_duration_ticks = define("bleed_duration_ticks", 100); // 5 seconds
        commonBuilder.bleed_damage_interval = define("bleed_damage_interval", 20); // 1 dmg/sec
        commonBuilder.bleed_particle_interval = define("bleed_particle_interval", 10);
        commonBuilder.hook_cooldown = define("hook_cooldown", 30);
        commonBuilder.hook_damage = define("hook_damage", 4.0);
        commonBuilder.hook_distance = define("hook_distance", 20.0);
        commonBuilder.hook_velocity = define("hook_velocity", 2.2);
        commonBuilder.hook_pull_speed = define("hook_pull_speed", 0.65);
        commonBuilder.hook_pull_arc = define("hook_pull_arc", 0.08);
        commonBuilder.hook_entity_radius = define("hook_entity_radius", 1.2);
        commonBuilder.hook_decel_factor = define("hook_decel_factor", 0.85);
        commonBuilder.hook_decel_start_tick = define("hook_decel_start_tick", 10);
        commonBuilder.hook_pull_duration = define("hook_pull_duration", 40); // ticks
        commonBuilder.cobweb_clear_radius = define("cobweb_clear_radius", 3);
        commonBuilder.trail_cooldown = define("trail_cooldown", 60);
        commonBuilder.trail_duration = define("trail_duration", 10);
        commonBuilder.trail_scale = define("trail_scale", 0.3);
        commonBuilder.trail_step_height = define("trail_step_height", 1.5);
        commonBuilder.trail_speed_level = define("trail_speed_level", 2); // Speed III
        commonBuilder.trail_resistance_level = define("trail_resistance_level", 9); // Resistance X
        commonBuilder.trail_mining_fatigue_level = define("trail_mining_fatigue_level", 3); // Mining Fatigue IV
        commonBuilder.trail_exit_boost = define("trail_exit_boost", 0.65);
        commonBuilder.trail_exit_boost_y = define("trail_exit_boost_y", 0.18);
        commonBuilder.trail_jump_exit_min_rise = define("trail_jump_exit_min_rise", 0.18);
        commonBuilder.trail_invisibility = define("trail_invisibility", true);
        commonBuilder.trail_safe_fall_distance = define("trail_safe_fall_distance", 20.0);
        commonBuilder.trail_disable_interaction = define("trail_disable_interaction", true);
        commonBuilder.trail_exit_on_jump = define("trail_exit_on_jump", true);
        commonBuilder.trail_exit_leap_velocity = define("trail_exit_leap_velocity", 0.5);
        commonBuilder.trail_exit_y_velocity = define("trail_exit_y_velocity", 0.8);
        commonBuilder.trail_exit_pause_ticks = define("trail_exit_pause_ticks", 5);
        commonBuilder.tracer_range = define("tracker_range", 30.0);
        commonBuilder.pop(); // bloodlust
        
        // Frost scythe ability
        commonBuilder.push("frostscythe");
        commonBuilder.throw_cooldown = define("throw_cooldown", 30);
        commonBuilder.throw_damage = define("throw_damage", 6.0);
        commonBuilder.throw_return_power = define("throw_return_power", 2.0);
        commonBuilder.throw_max_distance = define("throw_max_distance", 100);
        commonBuilder.throw_model_rotation_x = define("throw_model_rotation_x", 0.0);
        commonBuilder.throw_model_rotation_y = define("throw_model_rotation_y", 0.0);
        commonBuilder.throw_model_rotation_z = define("throw_model_rotation_z", 90.0);
        commonBuilder.throw_model_scale = define("throw_model_scale", 1.5);
        commonBuilder.throw_model_spin_speed = define("throw_model_spin_speed", 0.5);
        commonBuilder.freeze_ticks = define("freeze_ticks", 100); // 5 seconds
        commonBuilder.freeze_slowness_level = define("freeze_slowness_level", 1); // Slowness II
        commonBuilder.ice_count = define("ice_count", 3);
        commonBuilder.ice_velocity = define("ice_velocity", 1.5);
        commonBuilder.ice_hitbox = define("ice_hitbox", 4.0);
        commonBuilder.ice_spawn_height = define("ice_spawn_height", 4);
        commonBuilder.ice_timeout = define("ice_timeout", 15);
        commonBuilder.command_cooldown = define("command_cooldown", 45);
        commonBuilder.command_damage = define("command_damage", 3.0);
        commonBuilder.pop(); // frostscythe
        
        // Hyperion ability
        commonBuilder.push("hyperion");
        commonBuilder.holy_lance_cooldown = define("holy_lance_cooldown", 60);
        commonBuilder.holy_lance_damage = define("holy_lance_damage", 6.0);
        commonBuilder.holy_lance_radius = define("holy_lance_radius", 3.0);
        commonBuilder.holy_lance_charge_ticks = define("holy_lance_charge_ticks", 20);
        commonBuilder.holy_lance_fire_ticks = define("holy_lance_fire_ticks", 100);
        commonBuilder.scorching_cooldown = define("scorching_cooldown", 30);
        commonBuilder.scorching_blade_damage = define("scorching_blade_damage", 6.0);
        commonBuilder.scorching_radius = define("scorching_radius", 5.0);
        commonBuilder.scorching_hitbox = define("scorching_hitbox", 1.5);
        commonBuilder.scorching_lava_duration = define("scorching_lava_duration", 40); // ticks
        commonBuilder.vampire_bonus = define("vampire_bonus", 3.0);
        commonBuilder.hallowed_flames_damage = define("hallowed_flames_damage", 7.0);
        commonBuilder.pop(); // hyperion
        
        // Nightpiercer ability
        commonBuilder.push("nightpiercer");
        commonBuilder.transform_cooldown = define("transform_cooldown", 60); // fork (was 30)
        commonBuilder.bite_cooldown = define("bite_cooldown", 30); // fork (was 60)
        commonBuilder.bat_duration = define("bat_duration", 3);
        commonBuilder.bat_speed = define("bat_speed", 0.8);
        commonBuilder.bite_charge_timeout = define("bite_charge_timeout", 5); // legacy - fork bite is instant AOE, no charge
        commonBuilder.crimson_bite_damage = define("crimson_bite_damage", 4.0); // fork (was 12); now a multi-target cone
        commonBuilder.health_steal = define("health_steal", 4.0); // health bonus dealt to attacker via Crimson Bite
        commonBuilder.passive_regen_level = define("passive_regen_level", 0);
        commonBuilder.pop(); // nightpiercer
        
        // Pure blade ability
        commonBuilder.push("pureblade");
        commonBuilder.shade_soul_cooldown = define("shade_soul_cooldown", 30);
        commonBuilder.cyclone_cooldown = define("cyclone_cooldown", 60);
        commonBuilder.shade_soul_damage = define("shade_soul_damage", 6.0);
        commonBuilder.shade_soul_velocity = define("shade_soul_velocity", 2.5);
        commonBuilder.shade_soul_max_ticks = define("shade_soul_max_ticks", 60);
        commonBuilder.shade_soul_hit_radius = define("shade_soul_hit_radius", 3.0);
        commonBuilder.shade_soul_slowness_level = define("shade_soul_slowness_level", 1);
        commonBuilder.soul_slow_duration = define("soul_slow_duration", 100);
        commonBuilder.cyclone_speed_level = define("cyclone_speed_level", 9);
        commonBuilder.cyclone_duration = define("cyclone_duration", 30);
        commonBuilder.cyclone_radius = define("cyclone_radius", 3);
        commonBuilder.cyclone_damage = define("cyclone_damage", 1.8);
        commonBuilder.cyclone_hit_interval = define("cyclone_hit_interval", 6);
        commonBuilder.cyclone_cobweb_radius = define("cyclone_cobweb_radius", 3);
        commonBuilder.pop(); // pureblade
        
        // Shadowblade ability
        commonBuilder.push("shadowblade");
        commonBuilder.leap_cooldown = define("leap_cooldown", 30);
        commonBuilder.dagger_cooldown = define("dagger_cooldown", 15);
        commonBuilder.passive_speed_level = define("passive_speed_level", 1);
        commonBuilder.leap_distance = define("leap_distance", 30.0);
        commonBuilder.leap_invis_duration = define("leap_invis_duration", 36); // ticks
        commonBuilder.dagger_distance = define("dagger_distance", 100.0);
        commonBuilder.dagger_damage = define("dagger_damage", 3.0);
        commonBuilder.dagger_velocity = define("dagger_velocity", 1.5);
        commonBuilder.dagger_hitbox = define("dagger_hitbox", 0.8);
        commonBuilder.dagger_aoe_radius = define("dagger_aoe_radius", 5.0); // self-AOE burst around user on dagger throw
        commonBuilder.dagger_slow_duration = define("dagger_slow_duration", 60); // ticks
        commonBuilder.dagger_slow_level = define("dagger_slow_level", 1);
        commonBuilder.dagger_blind_duration = define("dagger_blind_duration", 60); // ticks
        commonBuilder.backstab_pull_chance = define("backstab_pull_chance", 0.3);
        commonBuilder.backstab_pull_strength = define("backstab_pull_strength", 0.35);
        commonBuilder.pop(); // shadowblade
        
        // Paladin battle axe ability
        commonBuilder.push("paladinbattleaxe");
        commonBuilder.shatter_cooldown = define("shatter_cooldown", 45);
        commonBuilder.stalwart_cooldown = define("stalwart_cooldown", 45);
        commonBuilder.passive_haste_level = define("passive_haste_level", 0);
        commonBuilder.shatter_radius = define("shatter_radius", 6);
        commonBuilder.earth_shatter_damage = define("earth_shatter_damage", 7.0);
        commonBuilder.shatter_impact_damage = define("shatter_impact_damage", 6.0);
        commonBuilder.shatter_knockback = define("shatter_knockback", 0.85);
        commonBuilder.stalwart_duration = define("stalwart_duration", 100); // ticks
        commonBuilder.stalwart_knockback = define("stalwart_knockback", 1.2);
        commonBuilder.stalwart_damage_cap = define("stalwart_damage_cap", 60.0);
        commonBuilder.stalwart_min_hit_damage = define("stalwart_min_hit_damage", 4.0); // hits below this don't absorb
        commonBuilder.stalwart_absorption_ratio = define("stalwart_absorption_ratio", 0.5);
        commonBuilder.stalwart_release_radius = define("stalwart_release_radius", 5);
        commonBuilder.pop(); // paladinbattleaxe
        
        // Cutlass ability
        commonBuilder.push("cutlass");
        commonBuilder.cuts_cooldown = define("cuts_cooldown", 15);
        commonBuilder.parry_cooldown = define("parry_cooldown", 30);
        commonBuilder.parry_duration = define("parry_duration", 7); // ticks
        commonBuilder.cut_damage = define("cut_damage", 2.0);
        commonBuilder.cuts_slash_count = define("cuts_slash_count", 10);
        commonBuilder.cuts_hit_radius = define("cuts_hit_radius", 3);
        commonBuilder.parry_block_count = define("parry_block_count", 3);
        commonBuilder.parry_slowness_level = define("parry_slowness_level", 1);
        commonBuilder.parry_knockback = define("parry_knockback", 1.8);
        commonBuilder.parry_knockback_y = define("parry_knockback_y", 0.4);
        commonBuilder.pop(); // cutlass
        
        // Earth gauntlet ability
        commonBuilder.push("earthgauntlet");
        commonBuilder.meteor_cooldown = define("meteor_cooldown", 30);
        commonBuilder.mudslide_cooldown = define("mudslide_cooldown", 18);
        commonBuilder.mud_damage = define("mud_damage", 2.0);
        commonBuilder.muddied_duration = define("muddied_duration", 60); // ticks
        commonBuilder.pop(); // earthgauntlet
        
        // Vulcan's crossbow ability
        commonBuilder.push("vulcanscrossbow");
        commonBuilder.quick_charge = define("quick_charge", 3);
        commonBuilder.multishot = define("multishot", 0);
        commonBuilder.piercing = define("piercing", 0);
        commonBuilder.pop(); // vulcanscrossbow
        
        // Pale crossbow ability
        commonBuilder.push("palecrossbow");
        commonBuilder.quick_charge = define("quick_charge", 3);
        commonBuilder.multishot = define("multishot", 0);
        commonBuilder.piercing = define("piercing", 0);
        commonBuilder.pop(); // palecrossbow
        
        // Omen ability (Season 2)
        commonBuilder.push("omen");
        commonBuilder.vault_omen = define("vault_omen", new ForgeConfigSpec.ConfigValue<ForgeConfigSpec.IntValue>() {
            @Override
            public IntValue parse(String s) { return null; }
            @Override
            public String get() { return null; }
        });
        commonBuilder.ominous_conjuring = define("ominous_conjuring", new ForgeConfigSpec.ConfigValue<ForgeConfigSpec.IntValue>() {
            @Override
            public IntValue parse(String s) { return null; }
            @Override
            public String get() { return null; }
        });
        commonBuilder.pop(); // omen
        
        // Ancient blade ability (Season 2)
        commonBuilder.push("ancientblade");
        commonBuilder.deep_connection = define("deep_connection", new ForgeConfigSpec.ConfigValue<ForgeConfigSpec.DoubleValue>() {
            @Override
            public DoubleValue parse(String s) { return null; }
            @Override
            public String get() { return null; }
        });
        commonBuilder.swift = define("swift", new ForgeConfigSpec.Default<ForgeConfigSpec.DoubleValue>(ForgeConfigSpec.DoubleValue.parse("0.25")));
        commonBuilder Presence = define("presence", new ForgeConfigSpec.Default<>(ForgeConfigSpec.IntValue.valueOf(45)));
        commonBuilder.tightened_grip = define("tightened_grip", new ForgeConfigSpec.Default<>(ForgeConfigSpec.IntValue.valueOf(4)));
        commonBuilder.echolocation = define("echolocation", new ForgeConfigSpec.Default<>(ForgeConfigSpec.IntValue.valueOf(3)));
        commonBuilder.unyielding_darkness = define("unyielding_darkness", new ForgeConfigSpec.Default<>(ForgeConfigSpec.IntValue.valueOf(60)));
        commonBuilder.pop(); // ancientblade
        
        // Withersymbiote ability (Season 2)
        commonBuilder.push("withersymbiote");
        commonBuilder.infection = define("infection", new ForgeConfigSpec.Default<>(ForgeConfigSpec.IntValue.valueOf(40)));
        commonBuilder.detonate_window = define("detonate_window", ForgeConfigSpec.IntValue.valueOf(3));
        commonBuilder.spread_radius = define("spread_radius", ForgeConfigSpec.IntValue.valueOf(3));
        commonBuilder.detonate_damage = define("detonate_damage", ForgeConfigSpec.DoubleValue.valueOf(8.0));
        commonBuilder.weakness_duration = define("weakness_duration", ForgeConfigSpec.IntValue.valueOf(8));
        commonBuilder.weakness_amplifier = define("weakness_amplifier", ForgeConfigSpec.IntValue.valueOf(0));
        commonBuilder.prime_glow_amplifier = define("prime_glow_amplifier", ForgeConfigSpec.IntValue.valueOf(0));
        commonBuilder.tick_particle_interval = define("tick_particle_interval", ForgeConfigSpec.IntValue.valueOf(5));
        commonBuilder.rampage = define("rampage", new ForgeConfigSpec.Default<>(ForgeConfigSpec.IntValue.valueOf(120)));
        commonBuilder.pop(); // withersymbiote
        
        // Tidebreaker ability (Season 2)
        commonBuilder.push("tidebreaker");
        commonBuilder.passive = define("passive", new ForgeConfigSpec.CompositeConfig() {
            @Override
            public WaterBreathingAmplifier water_breathing_amplifier() { return null; }
            @Override
            public IntValue nautilus_buff_radius() { return null; }
            @Override
            public IntValue nautilus_resistance_amplifier() { return null; }
            @Override
            public IntValue nautilus_speed_amplifier() { return null; }
        });
        commonBuilder.stormcall = define("stormcall", new ForgeConfigSpec.CompositeConfig() {
            @Override
            public IntValue cooldown() { return null; }
            @Override
            public IntValue duration() { return null; }
            @Override
            public IntValue radius() { return null; }
            @Override
            public DoubleValue damage() { return null; }
            @Override
            public IntValue hit_interval() { return null; }
            @Override
            public IntValue max_targets_per_tick() { return null; }
        });
        commonBuilder.rising_tide = define("rising_tide", new ForgeConfigSpec.CompositeConfig() {
            @Override
            public IntValue cooldown() { return null; }
            @Override
            public IntValue jet_height() { return null; }
            @Override
            public IntValue shockwave_radius() { return null; }
            @Override
            public DoubleValue shockwave_knockback() { return null; }
            @Override
            public IntValue wave_distance() { return null; }
            @Override
            public DoubleValue water_damage() { return null; }
            @Override
            public DoubleValue pull_velocity() { return null; }
        });
        commonBuilder.pop(); // tidebreaker
        
        // Dragonrend ability (Season 2)
        commonBuilder.push("dragonrend");
        commonBuilder.hypersonic = define("hypersonic", ForgeConfigSpec.IntValue.valueOf(40));
        commonBuilder.charge_duration_ms = define("charge_duration_ms", ForgeConfigSpec.IntValue.valueOf(2000));
        commonBuilder.duration_ms = define("duration_ms", ForgeConfigSpec.IntValue.valueOf(5000));
        commonBuilder.strike_cooldown_ms = define("strike_cooldown_ms", ForgeConfigSpec.IntValue.valueOf(1000));
        commonBuilder.damage = define("damage", ForgeConfigSpec.DoubleValue.valueOf(4.0));
        commonBuilder.detection_range = define("detection_range", ForgeConfigSpec.DoubleValue.valueOf(16.0));
        commonBuilder.detection_cone_dot = define("detection_cone_dot", ForgeConfigSpec.FloatValue.valueOf(0.91F));
        commonBuilder.teleport_offset = define("teleport_offset", ForgeConfigSpec.DoubleValue.valueOf(1.5));
        commonBuilder.infinite_void = define("infinite_void", ForgeConfigSpec.IntValue.valueOf(60));
        commonBuilder.pop(); // dragonrend
        
        commonBuilder.pop(); // abilities
        
        // ========================================================
        // WORKBENCH RECIPE TOGGLES
        // ========================================================
        
        commonBuilder.push("workbench-recipes");
        commonBuilder.weapon_handle = define("weapon_handle", true);
        commonBuilder.cobweb = define("cobweb", true);
        commonBuilder.golden_apple = define("golden_apple", true);
        commonBuilder.firework = define("firework", true); // 8 gunpowder + 1 paper (3x3) -> 16 rockets, flight 3
        commonBuilder.long_strength_splash = define("long_strength_splash", true); // Strength II potion + gunpowder -> 8-min Strength II splash
        commonBuilder.pop(); // workbench-recipes
        
        // ========================================================
        // SEASON 2 SETTINGS
        // ========================================================
        
        commonBuilder.push("season2");
        commonBuilder.bow_of_deception = define("bow-of-deception", true);
        commonBuilder.endragon = define("ender-dragon", new ForgeConfigSpec.Default<>(ForgeConfigSpec.IntValue.valueOf(25000)));
        commonBuilder.amethyst_tools = define("amethyst-tools", new ForgeConfigSpec.CompositeConfig() {
            @Override
            public PickaxeConfig pickaxe() { return null; }
            @Override
            public AxeConfig axe() { return null; }
            @Override
            public Float sound_pitch_min() { return null; }
            @Override
            public Float sound_pitch_max() { return null; }
        });
        commonBuilder.happy_ghast_saddle = define("happy-ghast-saddle", new ForgeConfigSpec.Default<>(ForgeConfigSpec.IntValue.valueOf(2))); // 0=Speed I, 1=Speed II, 2=Speed III
        commonBuilder.pop(); // season2
        
        // ========================================================
        // SEASON 2 CONFIGURATION (s2.yml merged)
        // ========================================================
        
        commonBuilder.push("s2-config");
        commonBuilder ability_immunity = new ForgeConfigSpec.ConfigValue<ForgeConfigSpec composite>() {
            @Override
            public void push(String s) {}
            @Override
            public void pop() {}
        };
        // ... s2 config values would go here
        commonBuilder.pop(); // s2-config
        
        COMMON_SPEC = commonBuilder.build();
        INVENTORY_SPEC = inventoryBuilder.build();
        DIMENSION_SPEC = dimensionBuilder.build();
        
        COMMON = COMMON_SPEC.getCommon();
        INVENTORY = INVENTORY_SPEC.getCommon();
        DIMENSION = DIMENSION_SPEC.getCommon();
    }
    
    public static void load() {
        // Load configuration from file
        AltarSMPFabric.LOGGER.info("Configuration loaded");
    }
}