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

}
