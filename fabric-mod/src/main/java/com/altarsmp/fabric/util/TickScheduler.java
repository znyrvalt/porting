package com.altarsmp.fabric.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.server.MinecraftServer;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * Server-thread task scheduler that replaces Bukkit's {@code BukkitRunnable} /
 * {@code BukkitScheduler}.
 *
 * <p>Every AltarSMP subsystem (ability timelines, trial lifecycles, blood moon
 * phases, nuke countdowns, hologram rotation) is expressed as delayed or
 * repeating tick tasks.  Tasks are drained on {@code END_SERVER_TICK}, so they
 * always run on the server thread with a consistent world state, and they are
 * cancelled automatically when the server stops.</p>
 */
public final class TickScheduler {

	private static final AtomicLong IDS = new AtomicLong();

	private final ConcurrentLinkedQueue<Task> queued = new ConcurrentLinkedQueue<>();
	private final List<Task> active = new ArrayList<>();
	private long currentTick;

	public Task run(Runnable body) {
		return schedule(body, 0L, 0L);
	}

	public Task later(Runnable body, long delayTicks) {
		return schedule(body, Math.max(0L, delayTicks), 0L);
	}

	public Task timer(Runnable body, long delayTicks, long periodTicks) {
		return schedule(body, Math.max(0L, delayTicks), Math.max(1L, periodTicks));
	}

	/** Schedules a task that runs on the next tick, off the server thread's queue. */
	private Task schedule(Runnable body, long delay, long period) {
		if (body == null) {
			throw new IllegalArgumentException("task body must not be null");
		}
		Task task = new Task(IDS.incrementAndGet(), body, this.currentTick + delay, period);
		this.queued.add(task);
		return task;
	}

	public void tick(MinecraftServer server) {
		this.currentTick++;
		this.active.addAll(this.queued);
		this.queued.clear();
		if (this.active.isEmpty()) {
			return;
		}
		Iterator<Task> it = this.active.iterator();
		while (it.hasNext()) {
			Task task = it.next();
			if (task.isCancelled()) {
				it.remove();
				continue;
			}
			if (task.expired(this.currentTick)) {
				it.remove();
				continue;
			}
			if (task.nextRun > this.currentTick) {
				continue;
			}
			try {
				task.body.run();
			} catch (RuntimeException e) {
				AltarSMPMod.LOGGER.error("[AltarSMP] scheduled task #{} threw - task cancelled", task.id, e);
				it.remove();
				continue;
			}
			if (task.isCancelled()) {
				it.remove();
				continue;
			}
			if (task.period > 0) {
				task.nextRun = this.currentTick + task.period;
			} else {
				it.remove();
			}
		}
	}

	public void cancelAll() {
		for (Task task : this.active) {
			task.cancel();
		}
		this.active.clear();
		this.queued.clear();
	}

	public int pendingCount() {
		return this.active.size() + this.queued.size();
	}

	public long currentTick() {
		return this.currentTick;
	}

	/** A scheduled task handle. Mirrors {@code BukkitTask} semantics. */
	public static final class Task {
		private final long id;
		private final Runnable body;
		private final long period;
		private long nextRun;
		private long deadline = Long.MAX_VALUE;
		private volatile boolean cancelled;

		Task(long id, Runnable body, long nextRun, long period) {
			this.id = id;
			this.body = body;
			this.nextRun = nextRun;
			this.period = period;
		}

		public long id() {
			return this.id;
		}

		public void cancel() {
			this.cancelled = true;
		}

		public boolean isCancelled() {
			return this.cancelled;
		}

		public long nextRun() {
			return this.nextRun;
		}

		/**
		 * Cancels this task once {@code ticks} have elapsed since it was scheduled.
		 * The port's replacement for the "run N times then cancel" BukkitRunnable
		 * pattern used all over the weapon VFX code.
		 */
		public Task cancelAfter(long ticks) {
			this.deadline = this.nextRun + Math.max(0L, ticks);
			return this;
		}

		boolean expired(long currentTick) {
			return currentTick >= this.deadline;
		}
	}
}
