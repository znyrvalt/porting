package com.altarsmp.fabric.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * The ported AltarSMP configuration.
 *
 * <p>Two documents are kept exactly as the original plugin kept them:</p>
 * <ul>
 *   <li>{@code config.yml} - Season 1 + merged Season 2 content.  Read through
 *       {@link #main()}.</li>
 *   <li>{@code s2.yml} - the standalone Season 2 module config, which the S2
 *       ability code actually reads.  Read through {@link #s2()}; keys that are
 *       missing there fall back to {@code config.yml} and finally to the
 *       code default that the original plugin passed to Bukkit.</li>
 * </ul>
 *
 * <p>Both files are copied verbatim out of the mod jar into
 * {@code <config>/altarsmp/} on first start so server owners can tune them, and
 * {@code /altarsmp reload} (and {@code /s2reload}) re-read them from disk.</p>
 */
public final class AltarConfig {

	public static final String DIR_NAME = "altarsmp";
	public static final String MAIN_FILE = "config.yml";
	public static final String S2_FILE = "s2.yml";

	private Path directory;
	private Path mainPath;
	private Path s2Path;
	private ConfigView main = new ConfigView(new LinkedHashMap<>());
	private ConfigView s2 = new ConfigView(new LinkedHashMap<>());
	private String sourceDescription = "empty";
	private int warnings;

	public void load(Path configRoot) {
		this.directory = configRoot.resolve(DIR_NAME);
		this.mainPath = this.directory.resolve(MAIN_FILE);
		this.s2Path = this.directory.resolve(S2_FILE);
		try {
			Files.createDirectories(this.directory);
			extractDefault("altarsmp/config/" + MAIN_FILE, this.mainPath);
			extractDefault("altarsmp/config/" + S2_FILE, this.s2Path);
		} catch (IOException e) {
			AltarSMPMod.LOGGER.error("[AltarSMP] could not prepare config directory {}", this.directory, e);
		}
		reload();
	}

	public void reload() {
		this.warnings = 0;
		Map<String, Object> mainDoc = read(this.mainPath);
		Map<String, Object> s2Doc = read(this.s2Path);
		this.main = new ConfigView(mainDoc);
		// s2 falls back to the merged config.yml, then to code defaults.
		this.s2 = new ConfigView(s2Doc, this.main);
		this.sourceDescription = MAIN_FILE + "=" + this.main.size() + " keys, " + S2_FILE + "=" + s2Doc.size() + " root keys";
		AltarSMPMod.LOGGER.info("[AltarSMP] config loaded from {} ({})", this.directory, this.sourceDescription);
		if (this.warnings > 0) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] config loaded with {} warning(s)", this.warnings);
		}
	}

	private Map<String, Object> read(Path path) {
		if (path == null || !Files.exists(path)) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] config file {} is missing - using built-in defaults", path);
			this.warnings++;
			return new LinkedHashMap<>();
		}
		try {
			String content = Files.readString(path, StandardCharsets.UTF_8);
			return YamlLite.load(content);
		} catch (IOException e) {
			AltarSMPMod.LOGGER.error("[AltarSMP] could not read config file {}", path, e);
			this.warnings++;
			return new LinkedHashMap<>();
		}
	}

	private void extractDefault(String resource, Path target) throws IOException {
		if (Files.exists(target)) {
			return;
		}
		try (InputStream in = AltarConfig.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				AltarSMPMod.LOGGER.warn("[AltarSMP] bundled default config '{}' not found in jar", resource);
				this.warnings++;
				return;
			}
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
			AltarSMPMod.LOGGER.info("[AltarSMP] wrote default config {}", target);
		}
	}

	/** Season 1 + merged document. */
	public ConfigView main() {
		return this.main;
	}

	/** Season 2 module document (falls back to {@link #main()}). */
	public ConfigView s2() {
		return this.s2;
	}

	// -- short-cuts used all over the port (mirrors plugin.getConfig().getX) -----

	public int getInt(String path, int def) { return this.main.getInt(path, def); }
	public double getDouble(String path, double def) { return this.main.getDouble(path, def); }
	public boolean getBoolean(String path, boolean def) { return this.main.getBoolean(path, def); }
	public String getString(String path, String def) { return this.main.getString(path, def); }

	public int size() {
		return this.main.size();
	}

	public String sourceDescription() {
		return this.sourceDescription;
	}

	public Path directory() {
		return this.directory;
	}

	public Path mainPath() {
		return this.mainPath;
	}

	public Path s2Path() {
		return this.s2Path;
	}


	// -- writing (the /legendaryconfig editor) ----------------------------------

	/**
	 * Stores one edited value in the file that owns it and re-reads both documents.
	 *
	 * <p>This is the port's {@code plugin.getConfig().set(path, value)} plus
	 * {@code plugin.saveConfig()} plus {@code reloadConfigAndServices()}: the editor
	 * calls it once per change and every system that reads the config sees the new
	 * number immediately afterwards. The edit is written into the text of the file
	 * rather than re-serialised from memory, so the annotated {@code config.yml} the
	 * mod ships keeps its comments.
	 *
	 * @return true when the file was written; false (and a logged reason) when it was not
	 */
	public boolean write(String path, Object value) {
		if (path == null || path.isBlank()) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] refused to write an empty config path");
			return false;
		}
		Path file = ownedByS2(path) ? this.s2Path : this.mainPath;
		try {
			String content = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
			String updated = YamlLite.setValue(content, path, value);
			Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temporary, updated, StandardCharsets.UTF_8);
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			AltarSMPMod.LOGGER.error("[AltarSMP] could not write {} to {}", path, file, e);
			return false;
		}
		reload();
		AltarSMPMod.LOGGER.info("[AltarSMP] config {} set to {} in {}", path, YamlLite.scalarText(value),
				file.getFileName());
		return true;
	}

	/** True when {@code s2.yml} carries the key itself instead of inheriting {@code config.yml}. */
	private boolean ownedByS2(String path) {
		return resolveOwn(this.s2.root(), path);
	}

	private static boolean resolveOwn(Map<String, Object> root, String path) {
		Object current = root;
		for (String part : path.split("\\.")) {
			if (!(current instanceof Map<?, ?> map)) {
				return false;
			}
			current = map.get(part);
			if (current == null) {
				return false;
			}
		}
		return true;
	}

}
