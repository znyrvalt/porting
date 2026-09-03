package com.altarsmp.fabric.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent per-player state.
 *
 * <p>Replaces the original {@code plugins/AltarSMP/playerdata/<uuid>.yml} files
 * plus the transient maps several listeners kept.  Everything the original plugin
 * remembered across a restart lives here:</p>
 *
 * <ul>
 *   <li>Bloodlust kills / crit hits (a.o.a record)</li>
 *   <li>Bone Blade hit counter</li>
 *   <li>Knightfall kills</li>
 *   <li>Ancient Blade kills (S2 {@code ab_kills})</li>
 *   <li>per-weapon kill counters for every legendary that tracks kills</li>
 *   <li>head-drop cooldown (players drop their head when killed, 1h cooldown)</li>
 *   <li>trust list ({@code trust.yml})</li>
 *   <li>tab-list colour</li>
 *   <li>Wand of Illusion morph lock + stored morph</li>
 *   <li>Copper Boots bingo board state</li>
 *   <li>Copper Leggings hot-potato hold time</li>
 *   <li>faction mirror (canonical faction state is the player's scoreboard tags)</li>
 * </ul>
 */
public final class PlayerRecord {

	private UUID uuid;
	private String name = "";

	// -- kill counters ---------------------------------------------------------
	private int bloodlustKills;
	private int bloodlustCritHits;
	private int bonebladeHits;
	private int knightfallKills;
	private int ancientBladeKills;
	private final Map<String, Integer> weaponKills = new LinkedHashMap<>();

	// -- cooldowns -------------------------------------------------------------
	private long headDropAvailableAt;
	private final Map<String, Long> timestamps = new LinkedHashMap<>();

	// -- social ----------------------------------------------------------------
	private final Set<String> trusted = new LinkedHashSet<>();
	private String tabColor = "";

	// -- Wand of Illusion ------------------------------------------------------
	private boolean morphLocked;
	private int morphLockUses;
	private String morphEntityType = "";
	private String morphCustomName = "";
	private boolean morphBaby;
	private float morphScale = 1.0f;

	// -- factions --------------------------------------------------------------
	private String faction = "";
	private boolean king;
	private boolean permaVampire;
	private boolean permaPale;
	private boolean permaHuman;

	// -- trials ----------------------------------------------------------------
	private final Map<String, Boolean> bingoDone = new LinkedHashMap<>();
	private final List<String> bingoBoard = new ArrayList<>();
	private long bingoStartedAt;
	private int bingoCompleted;
	private long hotPotatoHoldTicks;
	private int hotPotatoTransfers;
	private int chestplateShards;
	private int helmetFragments;

	// -- misc ------------------------------------------------------------------
	private boolean pvpOptOut;
	private boolean commandMode;
	private long lastContagionHit;
	private long lastBleedVictimAt;
	private final Map<String, String> strings = new LinkedHashMap<>();
	private final Map<String, Double> numbers = new LinkedHashMap<>();

	public PlayerRecord() {
	}

	public PlayerRecord(UUID uuid) {
		this.uuid = uuid;
	}

	public UUID uuid() { return this.uuid; }
	public void uuid(UUID value) { this.uuid = value; }
	public String name() { return this.name; }
	public void name(String value) { this.name = value == null ? "" : value; }

	public int bloodlustKills() { return this.bloodlustKills; }
	public void bloodlustKills(int v) { this.bloodlustKills = v; }
	public int bloodlustCritHits() { return this.bloodlustCritHits; }
	public void bloodlustCritHits(int v) { this.bloodlustCritHits = v; }
	public int bonebladeHits() { return this.bonebladeHits; }
	public void bonebladeHits(int v) { this.bonebladeHits = v; }
	public int knightfallKills() { return this.knightfallKills; }
	public void knightfallKills(int v) { this.knightfallKills = v; }
	public int ancientBladeKills() { return this.ancientBladeKills; }
	public void ancientBladeKills(int v) { this.ancientBladeKills = v; }

	public Map<String, Integer> weaponKills() { return this.weaponKills; }

	public int weaponKills(String weaponId) {
		return this.weaponKills.getOrDefault(weaponId, 0);
	}

	public void addWeaponKill(String weaponId, int delta) {
		this.weaponKills.merge(weaponId, delta, Integer::sum);
	}

	public void setWeaponKills(String weaponId, int value) {
		this.weaponKills.put(weaponId, value);
	}

	public long headDropAvailableAt() { return this.headDropAvailableAt; }
	public void headDropAvailableAt(long v) { this.headDropAvailableAt = v; }

	public Map<String, Long> timestamps() { return this.timestamps; }

	public long timestamp(String key) { return this.timestamps.getOrDefault(key, 0L); }
	public void timestamp(String key, long value) { this.timestamps.put(key, value); }

	public Set<String> trusted() { return this.trusted; }
	public String tabColor() { return this.tabColor; }
	public void tabColor(String v) { this.tabColor = v == null ? "" : v; }

	public boolean morphLocked() { return this.morphLocked; }
	public void morphLocked(boolean v) { this.morphLocked = v; }
	public int morphLockUses() { return this.morphLockUses; }
	public void morphLockUses(int v) { this.morphLockUses = v; }
	public String morphEntityType() { return this.morphEntityType; }
	public void morphEntityType(String v) { this.morphEntityType = v == null ? "" : v; }
	public String morphCustomName() { return this.morphCustomName; }
	public void morphCustomName(String v) { this.morphCustomName = v == null ? "" : v; }
	public boolean morphBaby() { return this.morphBaby; }
	public void morphBaby(boolean v) { this.morphBaby = v; }
	public float morphScale() { return this.morphScale; }
	public void morphScale(float v) { this.morphScale = v; }

	public String faction() { return this.faction; }
	public void faction(String v) { this.faction = v == null ? "" : v; }
	public boolean king() { return this.king; }
	public void king(boolean v) { this.king = v; }
	public boolean permaVampire() { return this.permaVampire; }
	public void permaVampire(boolean v) { this.permaVampire = v; }
	public boolean permaPale() { return this.permaPale; }
	public void permaPale(boolean v) { this.permaPale = v; }
	public boolean permaHuman() { return this.permaHuman; }
	public void permaHuman(boolean v) { this.permaHuman = v; }

	public Map<String, Boolean> bingoDone() { return this.bingoDone; }
	public List<String> bingoBoard() { return this.bingoBoard; }
	public long bingoStartedAt() { return this.bingoStartedAt; }
	public void bingoStartedAt(long v) { this.bingoStartedAt = v; }
	public int bingoCompleted() { return this.bingoCompleted; }
	public void bingoCompleted(int v) { this.bingoCompleted = v; }

	public long hotPotatoHoldTicks() { return this.hotPotatoHoldTicks; }
	public void hotPotatoHoldTicks(long v) { this.hotPotatoHoldTicks = v; }
	public int hotPotatoTransfers() { return this.hotPotatoTransfers; }
	public void hotPotatoTransfers(int v) { this.hotPotatoTransfers = v; }
	public int chestplateShards() { return this.chestplateShards; }
	public void chestplateShards(int v) { this.chestplateShards = v; }
	public int helmetFragments() { return this.helmetFragments; }
	public void helmetFragments(int v) { this.helmetFragments = v; }

	public boolean pvpOptOut() { return this.pvpOptOut; }
	public void pvpOptOut(boolean v) { this.pvpOptOut = v; }
	public boolean commandMode() { return this.commandMode; }
	public void commandMode(boolean v) { this.commandMode = v; }
	public long lastContagionHit() { return this.lastContagionHit; }
	public void lastContagionHit(long v) { this.lastContagionHit = v; }
	public long lastBleedVictimAt() { return this.lastBleedVictimAt; }
	public void lastBleedVictimAt(long v) { this.lastBleedVictimAt = v; }

	public Map<String, String> strings() { return this.strings; }
	public Map<String, Double> numbers() { return this.numbers; }

	public String string(String key, String def) { return this.strings.getOrDefault(key, def); }
	public void string(String key, String value) { this.strings.put(key, value); }
	public double number(String key, double def) { return this.numbers.getOrDefault(key, def); }
	public void number(String key, double value) { this.numbers.put(key, value); }
}
