package com.altarsmp.fabric.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read/write view over one parsed YAML document with Bukkit-{@code
 * YamlConfiguration}-compatible path semantics.
 *
 * <p>Every AltarSMP tuning value is read through this class using the exact path
 * strings the original plugin used (for example
 * {@code abilities.paladinbattleaxe.shatter_cooldown}), so a server admin's
 * existing {@code config.yml} keeps working unchanged after the port.</p>
 */
public final class ConfigView {

	private final Map<String, Object> root;
	private final ConfigView fallback;

	public ConfigView(Map<String, Object> root) {
		this(root, null);
	}

	public ConfigView(Map<String, Object> root, ConfigView fallback) {
		this.root = root == null ? new LinkedHashMap<>() : root;
		this.fallback = fallback;
	}

	public Map<String, Object> root() {
		return this.root;
	}

	public boolean contains(String path) {
		return resolve(path) != null || (this.fallback != null && this.fallback.contains(path));
	}

	public int size() {
		return countKeys(this.root);
	}

	private static int countKeys(Map<String, Object> map) {
		int n = 0;
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			n++;
			if (entry.getValue() instanceof Map<?, ?> child) {
				@SuppressWarnings("unchecked")
				Map<String, Object> typed = (Map<String, Object>) child;
				n += countKeys(typed);
			}
		}
		return n;
	}

	@SuppressWarnings("unchecked")
	private Object resolve(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}
		Object current = this.root;
		for (String part : path.split("\\.")) {
			if (current instanceof Map<?, ?> map) {
				current = ((Map<String, Object>) map).get(part);
				if (current == null) {
					return null;
				}
			} else {
				return null;
			}
		}
		return current;
	}

	private Object value(String path) {
		Object direct = resolve(path);
		if (direct != null) {
			return direct;
		}
		return this.fallback == null ? null : this.fallback.value(path);
	}

	public int getInt(String path, int def) {
		Object v = value(path);
		if (v instanceof Number number) {
			return number.intValue();
		}
		if (v instanceof String s) {
			try {
				return Integer.parseInt(s.trim());
			} catch (NumberFormatException ignored) {
				return def;
			}
		}
		return def;
	}

	public long getLong(String path, long def) {
		Object v = value(path);
		if (v instanceof Number number) {
			return number.longValue();
		}
		if (v instanceof String s) {
			try {
				return Long.parseLong(s.trim());
			} catch (NumberFormatException ignored) {
				return def;
			}
		}
		return def;
	}

	public double getDouble(String path, double def) {
		Object v = value(path);
		if (v instanceof Number number) {
			return number.doubleValue();
		}
		if (v instanceof String s) {
			try {
				return Double.parseDouble(s.trim());
			} catch (NumberFormatException ignored) {
				return def;
			}
		}
		return def;
	}

	public float getFloat(String path, float def) {
		return (float) getDouble(path, def);
	}

	public boolean getBoolean(String path, boolean def) {
		Object v = value(path);
		if (v instanceof Boolean b) {
			return b;
		}
		if (v instanceof String s) {
			String lower = s.trim().toLowerCase(Locale.ROOT);
			if (lower.equals("true")) {
				return true;
			}
			if (lower.equals("false")) {
				return false;
			}
		}
		return def;
	}

	public String getString(String path, String def) {
		Object v = value(path);
		if (v == null) {
			return def;
		}
		return String.valueOf(v);
	}

	public String getString(String path) {
		return getString(path, null);
	}

	@SuppressWarnings("unchecked")
	public List<String> getStringList(String path) {
		Object v = value(path);
		List<String> out = new ArrayList<>();
		if (v instanceof List<?> list) {
			for (Object o : list) {
				out.add(o == null ? "" : String.valueOf(o));
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	public List<Object> getList(String path) {
		Object v = value(path);
		if (v instanceof List<?> list) {
			return new ArrayList<>(list);
		}
		return new ArrayList<>();
	}

	/** Returns the sub-map at {@code path}, or an empty map when absent. */
	@SuppressWarnings("unchecked")
	public Map<String, Object> getSection(String path) {
		Object v = value(path);
		if (v instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		return Collections.emptyMap();
	}

	/** Ordered keys directly under {@code path} ("" for the document root). */
	@SuppressWarnings("unchecked")
	public List<String> getKeys(String path) {
		Object v = path == null || path.isEmpty() ? this.root : value(path);
		if (v instanceof Map<?, ?> map) {
			return new ArrayList<>(((Map<String, Object>) map).keySet());
		}
		return Collections.emptyList();
	}

	@SuppressWarnings("unchecked")
	public void set(String path, Object newValue) {
		Map<String, Object> current = this.root;
		String[] parts = path.split("\\.");
		for (int i = 0; i < parts.length - 1; i++) {
			Object child = current.get(parts[i]);
			if (!(child instanceof Map)) {
				child = new LinkedHashMap<String, Object>();
				current.put(parts[i], child);
			}
			current = (Map<String, Object>) child;
		}
		current.put(parts[parts.length - 1], newValue);
	}

	public Map<String, Object> section(String path) {
		return getSection(path);
	}
}
