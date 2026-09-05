package com.altarsmp.fabric.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.config.YamlLite;

/**
 * The persistent AltarSMP store.
 *
 * <p>Backed by a single pretty-printed JSON document written to
 * {@code <world>/altarsmp/store.json} (atomic write via a temp file + move, so a
 * crash mid-save cannot truncate player progression).  Gson is Minecraft's own
 * JSON library - it is <em>not</em> bundled or shaded into the mod jar.</p>
 *
 * <p>Item-borne state (kill counters on a specific legendary, charge meters,
 * morph captures) lives on the item itself as data components so it follows the
 * item, not the player - exactly like the original PDC based implementation.
 * Player-borne state (faction mirror, trust list, head-drop cooldown, trial
 * scores) lives here.</p>
 */
public final class AltarStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String FILE_NAME = "store.json";
	private static final String DIR_NAME = "altarsmp";

	private final AltarConfig config;
	private final Map<UUID, PlayerRecord> players = new LinkedHashMap<>();
	private final Map<String, AltarRecord> altars = new LinkedHashMap<>();
	private final WorldRecord world = new WorldRecord();

	private Path file;
	private volatile boolean dirty;
	private long lastAutoSave;
	private boolean loaded;

	public AltarStore(AltarConfig config) {
		this.config = config;
	}

	public void attach(MinecraftServer server) {
		Path root = server.getWorldPath(LevelResource.ROOT);
		this.file = root.resolve(DIR_NAME).resolve(FILE_NAME);
	}

	public Path location() {
		return this.file;
	}

	public boolean isLoaded() {
		return this.loaded;
	}

	// ------------------------------------------------------------------- loading

	public void load() {
		if (this.file == null) {
			AltarSMPMod.LOGGER.error("[AltarSMP] store used before attach(server)");
			return;
		}
		try {
			Files.createDirectories(this.file.getParent());
			if (Files.exists(this.file)) {
				String json = Files.readString(this.file, StandardCharsets.UTF_8);
				StoreDocument doc = GSON.fromJson(json, StoreDocument.class);
				if (doc != null) {
					if (doc.players != null) {
						for (Map.Entry<String, PlayerRecord> entry : doc.players.entrySet()) {
							UUID id = parseUuid(entry.getKey());
							if (id != null && entry.getValue() != null) {
								entry.getValue().uuid(id);
								this.players.put(id, entry.getValue());
							}
						}
					}
					if (doc.altars != null) {
						this.altars.putAll(doc.altars);
					}
					if (doc.world != null) {
						copyWorld(doc.world);
					}
					AltarSMPMod.LOGGER.info("[AltarSMP] loaded store v{}: {} players, {} altars",
							doc.version, this.players.size(), this.altars.size());
				}
			} else {
				AltarSMPMod.LOGGER.info("[AltarSMP] no existing store at {} - starting fresh", this.file);
			}
			this.loaded = true;
			int migrated = migrateLegacyBukkitData();
			if (migrated > 0) {
				AltarSMPMod.LOGGER.info("[AltarSMP] migrated {} legacy player record(s) from the Bukkit plugin layout", migrated);
				this.dirty = true;
			}
		} catch (IOException | RuntimeException e) {
			AltarSMPMod.LOGGER.error("[AltarSMP] failed to load {} - keeping in-memory state", this.file, e);
			this.loaded = true;
		}
	}

	private void copyWorld(WorldRecord source) {
		this.world.pvpEnabled(source.pvpEnabled());
		this.world.altarsLocked(source.altarsLocked());
		this.world.terrainDestruction(source.terrainDestruction());
		this.world.flags().putAll(source.flags());
		this.world.counters().putAll(source.counters());
		WorldRecord.BloodMoon bm = source.bloodMoon();
		this.world.bloodMoon().active(bm.active());
		this.world.bloodMoon().startedAt(bm.startedAt());
		this.world.bloodMoon().endsAt(bm.endsAt());
		this.world.bloodMoon().savedDayTime(bm.savedDayTime());
		this.world.bloodMoon().activations(bm.activations());
		this.world.trials().putAll(source.trials());
		copyEvent(source.deathmatch(), this.world.deathmatch());
		copyEvent(source.nukeZone(), this.world.nukeZone());
		copyEvent(source.banZone(), this.world.banZone());
		copyEvent(source.contagion(), this.world.contagion());
	}

	private static void copyEvent(WorldRecord.EventState from, WorldRecord.EventState to) {
		to.active(from.active());
		to.startedAt(from.startedAt());
		to.endsAt(from.endsAt());
		to.owner(from.owner());
		to.data().putAll(from.data());
		to.numbers().putAll(from.numbers());
	}

	// ------------------------------------------------------------------- saving

	public void markDirty() {
		this.dirty = true;
	}

	/** Called every server tick from the tick bus; auto-saves every 5 minutes. */
	public void tick(long gameTime) {
		if (this.dirty && gameTime - this.lastAutoSave >= 6000L) {
			save();
		}
	}

	public synchronized void save() {
		if (this.file == null) {
			return;
		}
		try {
			Files.createDirectories(this.file.getParent());
			StoreDocument doc = new StoreDocument();
			doc.version = 2;
			doc.savedAt = System.currentTimeMillis();
			doc.players = new LinkedHashMap<>();
			for (Map.Entry<UUID, PlayerRecord> entry : this.players.entrySet()) {
				doc.players.put(entry.getKey().toString(), entry.getValue());
			}
			doc.altars = new LinkedHashMap<>(this.altars);
			doc.world = this.world;
			String json = GSON.toJson(doc);
			Path tmp = this.file.resolveSibling(FILE_NAME + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, this.file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicUnsupported) {
				Files.move(tmp, this.file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			this.dirty = false;
			this.lastAutoSave = System.currentTimeMillis();
		} catch (IOException | RuntimeException e) {
			AltarSMPMod.LOGGER.error("[AltarSMP] could not persist store to {}", this.file, e);
		}
	}

	// ------------------------------------------------------------------ accessors

	public PlayerRecord player(UUID uuid) {
		PlayerRecord record = this.players.computeIfAbsent(uuid, PlayerRecord::new);
		if (record.uuid() == null) {
			record.uuid(uuid);
		}
		return record;
	}

	public boolean hasPlayer(UUID uuid) {
		return this.players.containsKey(uuid);
	}

	public Collection<PlayerRecord> players() {
		return this.players.values();
	}

	public int playerCount() {
		return this.players.size();
	}

	public void removePlayer(UUID uuid) {
		this.players.remove(uuid);
		this.dirty = true;
	}

	public Map<String, AltarRecord> altars() {
		return this.altars;
	}

	public void putAltar(AltarRecord record) {
		this.altars.put(record.altarId(), record);
		this.dirty = true;
	}

	public void removeAltar(String altarId) {
		this.altars.remove(altarId);
		this.dirty = true;
	}

	public WorldRecord world() {
		return this.world;
	}

	public AltarConfig config() {
		return this.config;
	}

	private static UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	// ------------------------------------------------- legacy Bukkit migration

	/**
	 * Imports {@code plugins/AltarSMP/playerdata/*.yml} and
	 * {@code plugins/AltarSMP/trust.yml} when a server is migrating from the
	 * Bukkit/Paper plugin to this mod.  Nothing is overwritten: existing values in
	 * the new store always win, so re-running the migration is safe.
	 *
	 * @return number of players whose data was imported
	 */
	private int migrateLegacyBukkitData() {
		if (this.file == null) {
			return 0;
		}
		Path serverRoot = this.file.getParent().getParent();
		Path legacy = serverRoot.resolve("plugins").resolve("AltarSMP");
		if (!Files.isDirectory(legacy)) {
			return 0;
		}
		int imported = 0;
		Path playerData = legacy.resolve("playerdata");
		if (Files.isDirectory(playerData)) {
			try (var stream = Files.list(playerData)) {
				for (Path path : stream.filter(p -> p.getFileName().toString().endsWith(".yml")).toList()) {
					String fileName = path.getFileName().toString();
					UUID uuid = parseUuid(fileName.substring(0, fileName.length() - 4));
					if (uuid == null) {
						continue;
					}
					try {
						Map<String, Object> doc = YamlLite.load(Files.readString(path, StandardCharsets.UTF_8));
						com.altarsmp.fabric.config.ConfigView view = new com.altarsmp.fabric.config.ConfigView(doc);
						PlayerRecord record = player(uuid);
						boolean touched = false;
						touched |= applyIfAbsent(record::bloodlustKills, record.bloodlustKills(), view.getInt("bloodlust.kills", 0));
						touched |= applyIfAbsent(record::bloodlustCritHits, record.bloodlustCritHits(), view.getInt("bloodlust.critHits", 0));
						touched |= applyIfAbsent(record::bonebladeHits, record.bonebladeHits(), view.getInt("boneblade.hits", 0));
						touched |= applyIfAbsent(record::knightfallKills, record.knightfallKills(), view.getInt("knightfall.kills", 0));
						touched |= applyIfAbsent(record::ancientBladeKills, record.ancientBladeKills(), view.getInt("ancientblade.kills", 0));
						if (touched) {
							imported++;
						}
					} catch (IOException | RuntimeException e) {
						AltarSMPMod.LOGGER.warn("[AltarSMP] could not migrate legacy player file {}", path, e);
					}
				}
			} catch (IOException e) {
				AltarSMPMod.LOGGER.warn("[AltarSMP] could not list legacy playerdata directory {}", playerData, e);
			}
		}
		migrateLegacyTrust(legacy.resolve("trust.yml"));
		return imported;
	}

	private static boolean applyIfAbsent(java.util.function.IntConsumer setter, int current, int legacy) {
		if (current == 0 && legacy != 0) {
			setter.accept(legacy);
			return true;
		}
		return false;
	}

	private void migrateLegacyTrust(Path trustFile) {
		if (!Files.exists(trustFile)) {
			return;
		}
		try {
			Map<String, Object> doc = YamlLite.load(Files.readString(trustFile, StandardCharsets.UTF_8));
			for (Map.Entry<String, Object> entry : doc.entrySet()) {
				UUID owner = parseUuid(entry.getKey());
				if (owner == null || !(entry.getValue() instanceof List<?> list)) {
					continue;
				}
				PlayerRecord record = player(owner);
				for (Object o : list) {
					if (o != null) {
						record.trusted().add(String.valueOf(o));
					}
				}
			}
			this.dirty = true;
			AltarSMPMod.LOGGER.info("[AltarSMP] migrated legacy trust.yml");
		} catch (IOException | RuntimeException e) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] could not migrate legacy trust.yml", e);
		}
	}

	/** Raw JSON access for {@code /altarsmp debug store}. */
	public String debugDump() {
		JsonObject root = new JsonObject();
		root.addProperty("file", String.valueOf(this.file));
		root.addProperty("players", this.players.size());
		root.addProperty("altars", this.altars.size());
		root.add("world", JsonParser.parseString(GSON.toJson(this.world)));
		JsonElement element = root;
		return GSON.toJson(element);
	}

	public List<String> summary() {
		List<String> out = new ArrayList<>();
		out.add("players=" + this.players.size());
		out.add("altars=" + this.altars.size());
		out.add("bloodmoon=" + this.world.bloodMoon().active());
		out.add("pvp=" + this.world.pvpEnabled());
		return out;
	}

	/** Serialised shape of {@code store.json}. */
	private static final class StoreDocument {
		int version = 2;
		long savedAt;
		Map<String, PlayerRecord> players = new LinkedHashMap<>();
		Map<String, AltarRecord> altars = new LinkedHashMap<>();
		WorldRecord world = new WorldRecord();
	}
}
