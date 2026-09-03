package com.altarsmp.fabric.data;

/**
 * A placed altar.  Mirrors the {@code altars.yml}-style bookkeeping the original
 * {@code AltarManager} kept in memory plus its {@code AltarBreakCleanup} listener.
 */
public final class AltarRecord {

	private String altarId = "";
	private String altarType = "";
	private String recipeId = "";
	private String dimension = "minecraft:overworld";
	private double x;
	private double y;
	private double z;
	private float yaw;
	private boolean locked;
	private long spawnedAt;
	private String spawnedBy = "";
	private boolean consumed;

	public AltarRecord() {
	}

	public AltarRecord(String altarId, String altarType, String dimension, double x, double y, double z) {
		this.altarId = altarId;
		this.altarType = altarType;
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.spawnedAt = System.currentTimeMillis();
	}

	public String altarId() { return this.altarId; }
	public void altarId(String v) { this.altarId = v; }
	public String altarType() { return this.altarType; }
	public void altarType(String v) { this.altarType = v; }
	public String recipeId() { return this.recipeId; }
	public void recipeId(String v) { this.recipeId = v; }
	public String dimension() { return this.dimension; }
	public void dimension(String v) { this.dimension = v; }
	public double x() { return this.x; }
	public void x(double v) { this.x = v; }
	public double y() { return this.y; }
	public void y(double v) { this.y = v; }
	public double z() { return this.z; }
	public void z(double v) { this.z = v; }
	public float yaw() { return this.yaw; }
	public void yaw(float v) { this.yaw = v; }
	public boolean locked() { return this.locked; }
	public void locked(boolean v) { this.locked = v; }
	public long spawnedAt() { return this.spawnedAt; }
	public void spawnedAt(long v) { this.spawnedAt = v; }
	public String spawnedBy() { return this.spawnedBy; }
	public void spawnedBy(String v) { this.spawnedBy = v; }
	public boolean consumed() { return this.consumed; }
	public void consumed(boolean v) { this.consumed = v; }
}
