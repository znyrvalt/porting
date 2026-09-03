package com.altarsmp.fabric.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-wide AltarSMP state that has to survive a restart.
 *
 * <p>The original plugin lost most of this on restart (blood moon, running
 * trials, deathmatch, nuke zone, ban zone, contagion ritual).  The port keeps it
 * so a server crash or a scheduled restart does not silently cancel a live
 * event; on startup each manager decides whether to resume or clean up.</p>
 */
public final class WorldRecord {

	private boolean pvpEnabled = true;
	private boolean altarsLocked;
	private boolean terrainDestruction = true;

	private final BloodMoon bloodMoon = new BloodMoon();
	private final Map<String, TrialState> trials = new LinkedHashMap<>();
	private final EventState deathmatch = new EventState();
	private final EventState nukeZone = new EventState();
	private final EventState banZone = new EventState();
	private final EventState contagion = new EventState();
	private final Map<String, String> flags = new LinkedHashMap<>();
	private final Map<String, Long> counters = new LinkedHashMap<>();

	public boolean pvpEnabled() { return this.pvpEnabled; }
	public void pvpEnabled(boolean v) { this.pvpEnabled = v; }
	public boolean altarsLocked() { return this.altarsLocked; }
	public void altarsLocked(boolean v) { this.altarsLocked = v; }
	public boolean terrainDestruction() { return this.terrainDestruction; }
	public void terrainDestruction(boolean v) { this.terrainDestruction = v; }

	public BloodMoon bloodMoon() { return this.bloodMoon; }
	public Map<String, TrialState> trials() { return this.trials; }
	public EventState deathmatch() { return this.deathmatch; }
	public EventState nukeZone() { return this.nukeZone; }
	public EventState banZone() { return this.banZone; }
	public EventState contagion() { return this.contagion; }
	public Map<String, String> flags() { return this.flags; }
	public Map<String, Long> counters() { return this.counters; }

	public TrialState trial(String key) {
		return this.trials.computeIfAbsent(key, k -> new TrialState());
	}

	/** Blood Moon state. */
	public static final class BloodMoon {
		private boolean active;
		private long startedAt;
		private long endsAt;
		private long savedDayTime;
		private int activations;

		public boolean active() { return this.active; }
		public void active(boolean v) { this.active = v; }
		public long startedAt() { return this.startedAt; }
		public void startedAt(long v) { this.startedAt = v; }
		public long endsAt() { return this.endsAt; }
		public void endsAt(long v) { this.endsAt = v; }
		public long savedDayTime() { return this.savedDayTime; }
		public void savedDayTime(long v) { this.savedDayTime = v; }
		public int activations() { return this.activations; }
		public void activations(int v) { this.activations = v; }
	}

	/** Generic event lifecycle state (deathmatch / nuke zone / ban zone / contagion). */
	public static final class EventState {
		private boolean active;
		private long startedAt;
		private long endsAt;
		private String owner = "";
		private final Map<String, String> data = new LinkedHashMap<>();
		private final Map<String, Long> numbers = new LinkedHashMap<>();

		public boolean active() { return this.active; }
		public void active(boolean v) { this.active = v; }
		public long startedAt() { return this.startedAt; }
		public void startedAt(long v) { this.startedAt = v; }
		public long endsAt() { return this.endsAt; }
		public void endsAt(long v) { this.endsAt = v; }
		public String owner() { return this.owner; }
		public void owner(String v) { this.owner = v == null ? "" : v; }
		public Map<String, String> data() { return this.data; }
		public Map<String, Long> numbers() { return this.numbers; }
	}

	/** Copper Trial state (helmet / boots / leggings / chestplate). */
	public static final class TrialState {
		private boolean active;
		private long startedAt;
		private long endsAt;
		private String holder = "";
		private final Map<String, Long> numbers = new LinkedHashMap<>();
		private final Map<String, String> data = new LinkedHashMap<>();
		private final Map<String, Long> playerScores = new LinkedHashMap<>();

		public boolean active() { return this.active; }
		public void active(boolean v) { this.active = v; }
		public long startedAt() { return this.startedAt; }
		public void startedAt(long v) { this.startedAt = v; }
		public long endsAt() { return this.endsAt; }
		public void endsAt(long v) { this.endsAt = v; }
		public String holder() { return this.holder; }
		public void holder(String v) { this.holder = v == null ? "" : v; }
		public Map<String, Long> numbers() { return this.numbers; }
		public Map<String, String> data() { return this.data; }
		public Map<String, Long> playerScores() { return this.playerScores; }
	}
}
