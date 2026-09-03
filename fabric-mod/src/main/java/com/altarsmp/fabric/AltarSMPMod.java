package com.altarsmp.fabric;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;

import com.altarsmp.fabric.ability.AbilityBus;
import com.altarsmp.fabric.ability.AbilityImmunity;
import com.altarsmp.fabric.ability.CooldownManager;
import com.altarsmp.fabric.altar.AltarManager;
import com.altarsmp.fabric.altar.AltarRegistry;
import com.altarsmp.fabric.altar.RandomAltarSpawner;
import com.altarsmp.fabric.command.CommandRegistrar;
import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.data.AltarStore;
import com.altarsmp.fabric.event.BanZoneSystem;
import com.altarsmp.fabric.event.BloodMoonManager;
import com.altarsmp.fabric.event.ContagionSignalManager;
import com.altarsmp.fabric.event.DeathmatchManager;
import com.altarsmp.fabric.event.NukeZoneManager;
import com.altarsmp.fabric.faction.FactionManager;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.item.ModComponents;
import com.altarsmp.fabric.protection.WeaponProtection;
import com.altarsmp.fabric.recipe.RecipeRegistry;
import com.altarsmp.fabric.trial.CopperTrialService;
import com.altarsmp.fabric.util.TickScheduler;
import com.altarsmp.fabric.weapon.WeaponRegistry;

/**
 * AltarSMP - Fabric port entrypoint.
 *
 * <p>This is the single unified entrypoint for the whole port.  Every subsystem
 * is owned by a dedicated manager class; nothing is delegated through a chain of
 * compatibility shims and no subsystem is a stub that only prints messages.</p>
 *
 * <p>Boot order matters and is fixed here:</p>
 * <ol>
 *   <li>config (original config.yml + s2.yml, ported 1:1)</li>
 *   <li>data components + item factory (identity layer)</li>
 *   <li>weapon / altar / recipe registries (content)</li>
 *   <li>factions, blood moon, trials, events (gameplay)</li>
 *   <li>protection hooks + commands (surface)</li>
 * </ol>
 */
public final class AltarSMPMod implements ModInitializer {
	public static final String MOD_ID = "altarsmp";
	public static final String S2_ID = "altarsmps2";
	public static final Logger LOGGER = LoggerFactory.getLogger("altarsmp");

	private static AltarSMPMod instance;

	private AltarConfig config;
	private AltarStore store;
	private TickScheduler scheduler;
	private CooldownManager cooldowns;
	private AbilityImmunity immunity;
	private AbilityBus abilities;
	private WeaponRegistry weapons;
	private AltarRegistry altarRegistry;
	private AltarManager altars;
	private RandomAltarSpawner randomAltars;
	private RecipeRegistry recipes;
	private FactionManager factions;
	private BloodMoonManager bloodMoon;
	private CopperTrialService trials;
	private ContagionSignalManager contagion;
	private DeathmatchManager deathmatch;
	private NukeZoneManager nukeZone;
	private BanZoneSystem banZone;
	private WeaponProtection protection;
	private CommandRegistrar commands;
	private com.altarsmp.fabric.command.CommandControlsManager controls;
	private com.altarsmp.fabric.event.GameListeners listeners;

	private MinecraftServer server;

	public static AltarSMPMod get() {
		return instance;
	}

	public static net.minecraft.resources.Identifier id(String path) {
		return net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		instance = this;
		long started = System.nanoTime();

		this.config = new AltarConfig();
		this.config.load(FabricLoader.getInstance().getConfigDir());
		this.scheduler = new TickScheduler();
		this.store = new AltarStore(this.config);

		ModComponents.initialize();
		ItemFactory.initialize(this.config);

		this.controls = new com.altarsmp.fabric.command.CommandControlsManager();
		this.cooldowns = new CooldownManager();
		this.immunity = new AbilityImmunity(this.config);
		this.abilities = new AbilityBus(this);
		this.weapons = new WeaponRegistry(this);
		this.weapons.registerAll();

		this.altarRegistry = new AltarRegistry(this);
		this.altarRegistry.registerAll();
		this.recipes = new RecipeRegistry(this);
		this.recipes.registerAll();
		this.altars = new AltarManager(this);
		this.randomAltars = new RandomAltarSpawner(this.altarRegistry, this.altars);

		this.factions = new FactionManager(this);
		this.bloodMoon = new BloodMoonManager(this);
		this.trials = new CopperTrialService(this);
		this.contagion = new ContagionSignalManager(this);
		this.deathmatch = new DeathmatchManager(this);
		this.nukeZone = new NukeZoneManager(this);
		this.banZone = new BanZoneSystem(this);

		this.listeners = new com.altarsmp.fabric.event.GameListeners(this);
		this.listeners.register();

		this.protection = new WeaponProtection(this);
		this.protection.register();

		this.weapons.registerEventHooks();
		this.factions.registerEventHooks();
		this.bloodMoon.registerEventHooks();
		this.trials.registerEventHooks();
		this.contagion.registerEventHooks();
		this.deathmatch.registerEventHooks();
		this.nukeZone.registerEventHooks();
		this.banZone.registerEventHooks();

		this.commands = new CommandRegistrar(this);
		this.commands.register();

		ServerTickEvents.END_SERVER_TICK.register(this.scheduler::tick);
		ServerTickEvents.END_SERVER_TICK.register(server -> this.abilities.tick(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> this.factions.tick(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> this.bloodMoon.tick(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> this.trials.tick(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> this.weapons.tick(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> this.altars.tick(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> this.contagion.tick(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> this.nukeZone.tick(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> this.cooldowns.tick(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> this.immunity.tick(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> this.store.tick(server.getTickCount()));

		ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

		long ms = (System.nanoTime() - started) / 1_000_000L;
		LOGGER.info("[AltarSMP] common init finished in {} ms", ms);
	}

	private void onServerStarting(MinecraftServer server) {
		this.server = server;
		this.store.attach(server);
		this.store.load();
		this.factions.migrateLegacyData();
		this.altars.loadFromStore();
		this.trials.loadFromStore();
		this.bloodMoon.loadFromStore();
		this.deathmatch.loadFromStore();
		this.nukeZone.loadFromStore();
		this.banZone.loadFromStore();
		this.contagion.loadFromStore();
	}

	private void onServerStarted(MinecraftServer server) {
		this.bloodMoon.resumeIfActive();
		this.deathmatch.resumeIfActive();
		this.trials.resumeActiveTrials();
		this.altars.rebuildDisplays();
		printStartupReport(server);
	}

	private void onServerStopping(MinecraftServer server) {
		try {
			this.trials.stopAllForShutdown();
			this.bloodMoon.shutdown();
			this.deathmatch.stopForShutdown();
			this.nukeZone.stopForShutdown();
			this.banZone.deactivateForShutdown();
			this.contagion.stopForShutdown();
			this.altars.removeDisplays();
		} catch (RuntimeException e) {
			LOGGER.error("[AltarSMP] error while shutting down subsystems - saving state anyway", e);
		} finally {
			this.store.save();
			this.server = null;
		}
	}

	/** Required startup diagnostics (see porting brief section 22). */
	private void printStartupReport(MinecraftServer server) {
		String modVersion = "unknown";
		String fabricApi = "not installed";
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			ModMetadata meta = mod.getMetadata();
			if (MOD_ID.equals(meta.getId())) {
				modVersion = meta.getVersion().getFriendlyString();
			}
			if ("fabric-api".equals(meta.getId())) {
				fabricApi = meta.getVersion().getFriendlyString();
			}
		}
		LOGGER.info("================================================================");
		LOGGER.info(" AltarSMP Fabric port v{}", modVersion);
		LOGGER.info("  Minecraft version      : {}", SharedConstants.getCurrentVersion().name());
		LOGGER.info("  Fabric Loader          : {}", FabricLoader.getInstance().getModContainer("fabricloader")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
		LOGGER.info("  Fabric API             : {}", fabricApi);
		LOGGER.info("  Config                 : {} ({} keys)", this.config.sourceDescription(), this.config.size());
		LOGGER.info("  Weapons discovered     : {} (S1={}, S2={})", this.weapons.size(),
				this.weapons.seasonOneCount(), this.weapons.seasonTwoCount());
		LOGGER.info("  Abilities registered   : {}", this.abilities.size());
		LOGGER.info("  Altar recipes          : {}", this.recipes.size());
		LOGGER.info("  Altar types            : {}", this.altarRegistry.size());
		LOGGER.info("  Live altars            : {}", this.altars.count());
		LOGGER.info("  Faction system         : enabled={} perma={} gateCrafts={}",
				this.config.getBoolean("curses.enabled", true),
				this.config.getBoolean("curses.allow_perma", false),
				this.config.getBoolean("curses.gate_faction_crafts", true));
		LOGGER.info("  Trial system           : helmet/boots/leggings/chestplate ready");
		LOGGER.info("  Blood Moon             : active={}", this.bloodMoon.isActive());
		LOGGER.info("  Weapon protection      : enabled={}", this.config.getBoolean("weapon-protection.enabled", true));
		LOGGER.info("  Commands registered    : {}", this.commands.registeredCount());
		LOGGER.info("  Persistent store       : {}", this.store.location());
		LOGGER.info("  Players tracked        : {}", this.store.playerCount());
		LOGGER.info("  Client assets          : bundled resource pack (assets/altarsmp, assets/altarsmps2, assets/custom, assets/mythicweapons, assets/minecraft)");
		LOGGER.info("================================================================");
	}

	public AltarConfig config() { return this.config; }
	public AltarStore store() { return this.store; }
	public TickScheduler scheduler() { return this.scheduler; }
	public CooldownManager cooldowns() { return this.cooldowns; }
	public AbilityImmunity immunity() { return this.immunity; }
	public AbilityBus abilities() { return this.abilities; }
	public WeaponRegistry weapons() { return this.weapons; }
	public AltarRegistry altarRegistry() { return this.altarRegistry; }
	public AltarManager altars() { return this.altars; }
	/** The pillar altars {@code /spawnaltarrandom} raises. */
	public RandomAltarSpawner randomAltars() { return this.randomAltars; }
	public RecipeRegistry recipes() { return this.recipes; }
	public FactionManager factions() { return this.factions; }
	public BloodMoonManager bloodMoon() { return this.bloodMoon; }
	public CopperTrialService trials() { return this.trials; }
	public ContagionSignalManager contagion() { return this.contagion; }
	public DeathmatchManager deathmatch() { return this.deathmatch; }
	public NukeZoneManager nukeZone() { return this.nukeZone; }
	public BanZoneSystem banZone() { return this.banZone; }
	public WeaponProtection protection() { return this.protection; }
	public com.altarsmp.fabric.command.CommandControlsManager controls() { return this.controls; }
	public com.altarsmp.fabric.event.GameListeners listeners() { return this.listeners; }
	public MinecraftServer server() { return this.server; }
}
