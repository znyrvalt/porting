package com.altarsmp.fabric;

import com.altarsmp.AltarSMP;
import com.altarsmp.fabric.command.*;
import com.altarsmp.fabric.item.*;
import com.altarsmp.fabric.weapon.*;
import com.altarsmp.fabric.altar.*;
import com.altarsmp.faction.*;
import com.altarsmp.fabric.trial.*;
import com.altarsmp.fabric.vfx.*;
import com.altarsmp.fabric.config.*;
import com.altarsmp.fabric.vampire.FabricVampireManager;
import com.altarsmp.fabric.pale.FabricPaleManager;
import com.altarsmp.manager.*;
import com.altarsmp.data.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.ClientTickCallback;
import net.fabricmc.fabric.api.client.model.ModelLoadingRegistry;
import net.fabricmc.fabric.api.client.networking.v1.FabricClientNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.DefaultAttributeSupplier;
import net.fabricmc.fabric.api.registry.GameRegistry;
import net.fabricmc.fabric.api.registry.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class AltarSMPFabric implements ModInitializer, ClientModInitializer {
    public static final String MOD_ID = "altarsmp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Weapon registries
    public static final Map<String, BaseWeapon> WEAPON_REGISTRY = new HashMap<>();
    public static final Map<String, ItemStack> WEAPON_DEFAULTS = new HashMap<>();

    // Altar registries
    public static final Map<String, AltarBlock> ALTAR_REGISTRY = new HashMap<>();

    // Faction manager
    public static FactionManager FACTION_MANAGER;

    // Blood Moon manager
    public static BloodMoonManager BLOOD_MOON_MANAGER;
    
    // Vampire manager
    public static FabricVampireManager VAMPIRE_MANAGER;

    // Copper trial systems
    public static CopperHelmetTrial COPPER_HELMET_TRIAL;
    public static CopperBootsTrial COPPER_BOOTS_TRIAL;
    public static CopperLeggingsTrial COPPER_LEGGINGS_TRIAL;
    public static CopperChestplateTrial COPPER_CHESTPLATE_TRIAL;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing AltarSMP Fabric mod...");

        // Initialize configuration
        ConfigManager.init();

        // Initialize weapon registry
        initWeapons();

        // Initialize altar registry
        initAltars();

        // Initialize faction system
        FACTION_MANAGER = new FactionManager();

        // Initialize blood moon manager
        BLOOD_MOON_MANAGER = new BloodMoonManager();

        // Initialize vampire manager
        VAMPIRE_MANAGER = new FabricVampireManager(this);

        // Initialize pale manager
        // Note: Pale manager would be initialized similarly or through the vampire manager

        // Initialize copper trials
        initCopperTrials();

        // Initialize recipes
        initRecipes();

        // Initialize VFX/SFX
        initVFX();

        // Register commands
        registerCommands();

        // Register listeners
        registerListeners();

        LOGGER.info("AltarSMP Fabric mod initialized successfully!");
        LOGGER.info("Loaded {} weapons", WEAPON_REGISTRY.size());
        LOGGER.info("Loaded {} altars", ALTAR_REGISTRY.size());
    }

    @Override
    public void onClientInitialize() {
        // Client-side initialization
        ModelLoadingRegistry.registerModelProvider(new AltarModelLoader());
        
        // Register networking packets
        registerNetworkPackets();
    }

    private void initWeapons() {
        // Season 1 weapons
        WEAPON_REGISTRY.put("paladinbattleaxe", new PaladinBattleAxeWeapon(this));
        WEAPON_REGISTRY.put("vulcancrossbow", new VulcansCrossbowWeapon(this));
        WEAPON_REGISTRY.put("eclipsesword", new EclipseSwordWeapon(this));
        WEAPON_REGISTRY.put("bloodlust", new BloodlustWeapon(this));
        WEAPON_REGISTRY.put("boneblade", new BoneBladeWeapon(this));
        WEAPON_REGISTRY.put("hyperion", new HyperionWeapon(this));
        WEAPON_REGISTRY.put("wandofillusion", new WandOfIllusionWeapon(this));
        WEAPON_REGISTRY.put("frostscythe", new FrostScytheWeapon(this));
        WEAPON_REGISTRY.put("crazyslots", new CrazySlotsWeapon(this));
        WEAPON_REGISTRY.put("minorcrazyslots", new MinorCrazySlotsWeapon(this));
        WEAPON_REGISTRY.put("nightpiercer", new NightpiercerWeapon(this));
        WEAPON_REGISTRY.put("windweaver", new WindweaverWeapon(this));
        WEAPON_REGISTRY.put("witherbone", new WitherboneWeapon(this));
        WEAPON_REGISTRY.put("shadowblade", new ShadowBladeWeapon(this));
        WEAPON_REGISTRY.put("pureblade", new PureBladeWeapon(this));
        WEAPON_REGISTRY.put("earthgauntlet", new EarthGauntletWeapon(this));
        WEAPON_REGISTRY.put("cutlass", new CutlassWeapon(this));
        WEAPON_REGISTRY.put("palecrossbow", new PaleCrossbowWeapon(this));
        WEAPON_REGISTRY.put("contagionsignal", new ContagionSignalWeapon(this));

        // Season 2 weapons
        WEAPON_REGISTRY.put("ancientblade", new AncientBladeWeapon(this));
        WEAPON_REGISTRY.put("withersymbiote", new WitherSymbioteWeapon(this));
        WEAPON_REGISTRY.put("tidebreaker", new TidebreakerWeapon(this));
        WEAPON_REGISTRY.put("dragonrend", new DragonrendWeapon(this));
        WEAPON_REGISTRY.put("bowofdeception", new BowOfDeceptionWeapon(this));

        // Weapon handles and other items
        WEAPON_REGISTRY.put("weaponhandle", new WeaponHandleItem(this));
        WEAPON_REGISTRY.put("illusioncore", new IllusionCoreItem(this));
        WEAPON_REGISTRY.put("hyperionshard", new HyperionShardItem(this));
        WEAPON_REGISTRY.put("nightpiercershard", new NightpiercerShardItem(this));
        WEAPON_REGISTRY.put("paleshard", new PaleShardItem(this));
        WEAPON_REGISTRY.put("copperfragment", new CopperFragmentItem(this));
        WEAPON_REGISTRY.put("copperchestplatefragment", new CopperChestplateFragmentItem(this));
        WEAPON_REGISTRY.put("wardenheart", new WardenHeartItem(this));

        // Set default items
        for (var entry : WEAPON_REGISTRY.entrySet()) {
            WEAPON_DEFAULTS.put(entry.getKey(), entry.getValue().createWeapon());
        }
    }

    private void initAltars() {
        // Initialize all altars from both seasons
        ALTAR_REGISTRY.put("blood", new BloodAltarBlock(this));
        ALTAR_REGISTRY.put("bone", new BoneAltarBlock(this));
        ALTAR_REGISTRY.put("contagion_signal", new ContagionSignalAltarBlock(this));
        ALTAR_REGISTRY.put("copper_helmet", new CopperHelmetAltarBlock(this));
        ALTAR_REGISTRY.put("copper_chestplate", new CopperChestplateAltarBlock(this));
        ALTAR_REGISTRY.put("copper_pickaxe", new CopperPickaxeAltarBlock(this));
        ALTAR_REGISTRY.put("copper_pickaxe_upgrade", new CopperPickaxeUpgradeAltarBlock(this));
        ALTAR_REGISTRY.put("crafting", new CraftingAltarBlock(this));
        ALTAR_REGISTRY.put("crazy_slots", new CrazySlotsAltarBlock(this));
        ALTAR_REGISTRY.put("cutlass", new CutlassAltarBlock(this));
        ALTAR_REGISTRY.put("earth_gauntlet", new EarthGauntletAltarBlock(this));
        ALTAR_REGISTRY.put("echo", new EchoAltarBlock(this));
        ALTAR_REGISTRY.put("eclipse_sword", new EclipseSwordAltarBlock(this));
        ALTAR_REGISTRY.put("fire_slash", new FireSlashAltarBlock(this));
        ALTAR_REGISTRY.put("frost_scythe", new FrostScytheAltarBlock(this));
        ALTAR_REGISTRY.put("hyperion", new HyperionAltarBlock(this));
        ALTAR_REGISTRY.put("hyperion_shard", new HyperionShardAltarBlock(this));
        ALTAR_REGISTRY.put("illusion_core", new IllusionCoreAltarBlock(this));
        ALTAR_REGISTRY.put("knightfall", new KnightfallAltarBlock(this));
        ALTAR_REGISTRY.put("nightpiercer", new NightpiercerAltarBlock(this));
        ALTAR_REGISTRY.put("nightpiercer_shard", new NightpiercerShardAltarBlock(this));
        ALTAR_REGISTRY.put("nuke_launcher", new NukeLauncherAltarBlock(this));
        ALTAR_REGISTRY.put("paladin_battle_axe", new PaladinBattleAxeAltarBlock(this));
        ALTAR_REGISTRY.put("pale_crossbow", new PaleCrossbowAltarBlock(this));
        ALTAR_REGISTRY.put("pale_shard", new PaleShardAltarBlock(this));
        ALTAR_REGISTRY.put("player_tracker", new PlayerTrackerAltarBlock(this));
        ALTAR_REGISTRY.put("pure_blade", new PureBladeAltarBlock(this));
        ALTAR_REGISTRY.put("shadow_blade", new ShadowBladeAltarBlock(this));
        ALTAR_REGISTRY.put("spawn_random", new SpawnAltarRandomCommand.AltarBlock(this));
        ALTAR_REGISTRY.put("striker", new StrikerAltarBlock(this));
        ALTAR_REGISTRY.put("vulcan", new VulcanAltarBlock(this));
        ALTAR_REGISTRY.put("vulkan_head", new VulkanHeadAltarBlock(this));
        ALTAR_REGISTRY.put("wand", new WandAltarBlock(this));
        ALTAR_REGISTRY.put("warden_head", new WardenHeadAltarBlock(this));
        ALTAR_REGISTRY.put("weapon_handle", new WeaponHandleAltarBlock(this));
        ALTAR_REGISTRY.put("windweaver", new WindweaverAltarBlock(this));
        ALTAR_REGISTRY.put("witherbone", new WitherboneAltarBlock(this));
    }

    private void initCopperTrials() {
        COPPER_HELMET_TRIAL = new CopperHelmetTrial();
        COPPER_BOOTS_TRIAL = new CopperBootsTrial(this);
        COPPER_LEGGINGS_TRIAL = new CopperLeggingsTrial(this);
        COPPER_CHESTPLATE_TRIAL = new CopperChestplateTrial(this);
    }

    private void initRecipes() {
        RecipeManager.init();
    }

    private void initVFX() {
        VFXManager.init();
    }

    private void registerCommands() {
        // Main command
        getModCommand().withExecutor(new MainCommand(this)).register();
        
        // Sub-commands
        getModCommand().withExecutor(new AltarCommand(this)).register();
        getModCommand().withExecutor(new RecipesCommand(this)).register();
        getModCommand().withExecutor(new BloodMoonCommand(this)).register();
        getModCommand().withExecutor(new CoppertrialCommand(this)).register();
        getModCommand().withExecutor(new LegendaryCommand(this)).register();
        getModCommand().withExecutor(new LegendaryConfigCommand(this)).register();
        getModCommand().withExecutor(new AbilityCommand(this)).register();
        getModCommand().withExecutor(new CooldownCommand(this)).register();
        getModCommand().withExecutor(new ConfigCommand(this)).register();
        getModCommand().withExecutor(new GiveCommand(this)).register();
        getModCommand().withExecutor(new SetKillsCommand(this)).register();
        getModCommand().withExecutor(new TrustCommand(this)).register();
        getModCommand().withExecutor(new CurseCommands(this)).register();
        getModCommand().withExecutor(new VampireCommands(this)).register();
        getModCommand().withExecutor(new PaleCommands(this)).register();
        getModCommand().withExecutor(new FactionCommands(this)).register();
        getModCommand().withExecutor(new TrialCommands(this)).register();
        getModCommand().withExecutor(new ProtectionCommands(this)).register();
        getModCommand().withExecutor(new BloodmoonCommands(this)).register();
    }

    private void registerListeners() {
        // Register all event listeners
        getEventBus().register(new PlayerListener());
        getEventBus().register(new EntityListener());
        getEventBus().register(new BlockListener());
        getEventBus().register(new ItemListener());
        getEventBus().register(new AltarListener());
        getEventBus().register(new WeaponListener());
        getEventBus().register(new FactionListener());
        getEventBus().register(new TrialListener());
        getEventBus().register(new BloodMoonListener());
        getEventBus().register(new VFXListener());
        getEventBus().register(new SoundListener());
    }

    private void registerNetworkPackets() {
        // Register networking packets for client-server communication
        FabricClientNetworking.registerMessage(
            new ResourceLocation(MOD_ID, "weapon_ability"),
            WeaponAbilityPacket.class,
            WeaponAbilityPacket::new,
            WeaponAbilityPacket::write,
            WeaponAbilityPacket::handle
        );
        // Add more packets as needed
    }

    // Helper methods for weapon lookups
    public static BaseWeapon getWeapon(String id) {
        return WEAPON_REGISTRY.get(id);
    }

    public static ItemStack getWeaponDefault(String id) {
        return WEAPON_DEFAULTS.get(id);
    }

    public static AltarBlock getAltar(String id) {
        return ALTAR_REGISTRY.get(id);
    }

    public static FactionManager getFactionManager() {
        return FACTION_MANAGER;
    }

    public static BloodMoonManager getBloodMoonManager() {
        return BLOOD_MOON_MANAGER;
    }

    public static FabricVampireManager getVampireManager() {
        return VAMPIRE_MANAGER;
    }
    
    public static FabricPaleManager getPaleManager() {
        // Would return a PaleManager instance if created separately
        return null; // Placeholder - vampire manager handles pale effects too
    }
}