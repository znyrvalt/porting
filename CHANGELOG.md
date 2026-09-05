# Changelog

The AltarSMP Fabric port. The original plugin was version 1.2 (Season 1) plus the
AltarSMPS2 module; the port starts its own numbering at 2.0.0 because it is a
different artifact for a different platform, not a release of the plugin.

## 2.0.5 — Bedrock crossplay

### Added

**Bedrock/Geyser crossplay.** `docs/crossplay.md` explains what a Bedrock client
sees without help (every custom weapon as its base item, because Bedrock has no
model data) and what the port does about it. `tools/crossplay/` holds the build
tooling; `pngcodec.py` is the PNG reader/writer everything else stands on, built
on `struct` and `zlib` so the toolchain needs nothing outside the standard
library.

**Restored ability artwork.** The `mythicweapons` `fx_*` and `vfx_*` item
definitions, their models and textures, and `paladin_hammer_fx.png` are back in
the asset set, so the item displays the abilities spawn have artwork instead of
falling back to their base item.

### Fixed

**Model data.** `ItemFactory#applyModelData` writes the model number to the
legacy integer slot and the `CustomModelData` float list, matching what the
plugin did and what modern item dispatch reads. The call sites that compute a
number at runtime — Knightfall's kill tiers, Dragonrend's void-clock pieces,
Tidebreaker's thrown trident, the blue-circle VFX — all route through it.

### Changed

`mod_version` is 2.0.5, matching the shipped jar.

## 2.0.0 — the Fabric port

### Added

**Platform.** A single Fabric mod for Minecraft Java 26.2 on Loader 0.19.3 with
Fabric API 0.158.0+26.2, built by Loom 1.17 and Gradle 9.5.1 against Java 25 and
the official Mojang names. One source tree, `com.altarsmp.fabric`, 109 files and
roughly 33,800 lines. A `main` entrypoint only: no client entrypoint and no
client mixins, so the same jar serves a dedicated server, a client and
singleplayer.

**Altars.** `AltarRegistry` (the plugin's altar table, with its lock check and
`Spec` records) and `AltarManager` (placement, the rotating display, holograms
that quote recipe amounts out of the config, recorded altars, sweeping, and the
explosion hook that removes an altar whose structure-block anchor is gone).

**Crafting.** The full recipe table, transactional: ingredients are checked and
only then taken, so a craft that cannot complete leaves the player's inventory
untouched. Recipe definitions, ingredient lists and altar data were extracted
from the plugin into `resources/altarsmp/content/*.json` rather than retyped.

**Weapons.** Every Season 1 and Season 2 legendary with its abilities, cooldowns,
projectiles, kill counters, morph locks, armour death messages and VFX — the
Wand of Illusion's clones, Knightfall's kills, the Bone Blade's cage, the Ancient
Blade's counter, the Paladin Battle Axe's visual effects, Eclipse's starfall, the
Nuke Launcher's zone, the Contagion Signal's hologram.

**Factions.** Human, Vampire, Pale and Hyperion with kings, curses, permanent
states, and the pale system that spreads on its own once `/pale` wakes it.

**Events.** Blood Moon (an event with its own state, not a clock change),
deathmatch, nuke zones, ban zones, hot potato, bingo, the contagion signal.

**Trials.** The four Copper Trials, the bingo board GUI, the chestplate shard
counter, and the ominous-vault loot hook that starts them from real vault loot.

**Protection.** Weapon protection across item frames, hoppers, bundles, container
slots, player drops, fire and explosions, each container type individually
toggleable in the config.

**Persistence.** Altar records, player records and weapon state on disk, reloaded
at startup.

**Configuration.** `config.yml` and `s2.yml` shipped verbatim, read through a
minimal YAML reader (no SnakeYAML in the jar), with `s2.yml` falling back to
`config.yml` and then to the code defaults the plugin passed to Bukkit.

**Commands.** All 107 commands the plugin declared, as Brigadier trees in
`CommandRegistrar`, each calling the same game-side code its executor called.
`docs/commands.md` is generated from `plugin.yml` and cross-checked against the
registrar.

**GUIs.** `/recipes` (the Season 2 recipe table, all nine entries with their
grids), `/legendaries` and `/legendaries2` (both pages of the item browser), and
`/legendaryconfig` / `/legendaryconfig2` (the live stat editor, with 198 editable
values in 32 tables read straight out of the plugin's field declarations).

**`/spawnaltarrandom`.** The last unregistered command: `altar/RandomAltarSpawner`
transliterates all 948 lines of `SpawnAltarRandomCommand` — the dressed 15-block
disc, the cleared 11 × 36 × 11 column, the six per-type pillar builders with their
palettes, chances, corner columns and chains, the deck at +31 with its 70% ring,
corner lanterns and iron-bars railings, and the enchanting table, ender chest and
bookshelves on it. Five pillars are placed inside `altar-spawn.default-range` (or
the range given on the command line) over a hundred candidate columns, each one
tested for solid, water-free ground as the plugin did.

**Resource pack.** The committed pack is unpacked into the jar at build time, so
models, textures, sounds, tooltip sprites and the vanilla item definitions that
dispatch on custom model data all ship with the mod; the zip is also bundled for
vanilla and Geyser clients.

**Mixins.** Twelve, for the hooks Fabric has no event for: damage and death
(`LivingEntity`), death messages (`CombatTracker`), explosions and entity adds
(`ServerLevel`), projectile hits (`Projectile`), item entity lifetime and pickup
(`ItemEntity`), container placement (`Slot`), hoppers (`HopperBlockEntity`),
bundles (`BundleItem`), item frames (`ItemFrame`), player drops
(`ServerPlayer`), arm swings (`ServerGamePacketListenerImpl`) and ominous vault
loot (`VaultBlockEntity.Server`).

**Tooling.** `tools/validate_imports.py` resolves every import and inline
fully-qualified name in the port against the 26.2 Minecraft sources;
`tools/extract_config_fields.py` generates the stat tables from the decompiled
plugin; `tools/generate_command_doc.py` generates the command table from
`plugin.yml`; `tools/test_yaml_write.py` tests the config writer;
`tools/bootstrap-wrapper.sh` fetches the one binary the repository does not carry.

### Changed

Mechanisms that Bukkit or Paper provided and vanilla does not. Each is documented
in `docs/limitations.md` with the reason.

- Permissions collapse onto vanilla command levels: every `altarsmp.*` and
  `altarsmps2.*` node defaulted to op, which is level 2 here.
- Tab completion becomes Brigadier suggestions.
- `/tabcolor` uses a scoreboard team per colour — the vanilla mechanism that
  produces the tinted tab name the plugin set directly on the player.
- The stat editor's Paper dialog and its chat-typed values become containers plus
  `/legendaryconfig set <entry> <path> <value>`. 26.2 dialogs are display only,
  and cancelling a signed chat packet before the server applies its seen-messages
  offset risks desyncing that player's chat chain.
- Morphs run on the mod's own display entities; LibsDisguises is not used and not
  needed.
- Config writes edit the file's text, so the shipped `config.yml` keeps its
  comments — Bukkit's `saveConfig()` rewrote the document and dropped them.
- `/legendaries` page 2, which existed in the plugin with nothing able to reach
  it, is wired up, and `/legendaries2` opens it instead of printing "Season 2
  weapons appear in /legendaries" and stopping.

### Fixed

Bugs carried by the plugin that the port does not repeat:

- The Season 2 browser had five weapon slots for six weapons and silently dropped
  the Bow of Deception and Lies. It has slot 24 now.
- Frost Scythe declared nine editable values that the plugin's own list never
  showed. They appear in `/legendaryconfig`.
- `/copperdiamond`'s suggestion lambda called `SharedSuggestionFactory#suggest`,
  which 26.x removed; it uses the registrar's own helper like every other node.
- The altar registry had 34 of the plugin's 35 altars. The Pale Shard is in it
  now, with `recipes.paleshard`'s six ingredients and the ritual the other five
  crafting-component altars have. `tools/extract_altars.py` reads
  `AltarSpawnCommand`'s own table for altars that have no interact class, so the
  registry cannot lose one again.
- Pillar altars were decoration upstream: `SpawnAltarRandomCommand` finished each
  pillar with an invisible armour stand whose name tag carried the recipe lines,
  and nothing recorded it, so clicking it did nothing and `/destroyaltars` never
  saw it. `/spawnaltarrandom` now puts a real altar on each deck through
  `AltarManager#createAltarAt` — recorded, craftable, swept with the rest.

### Known gaps

- **Nothing here has been compiled or run.** The sandbox this port was written in
  has no JDK and no Minecraft, so verification was static: every API signature
  was checked against the 26.2 Mojang-mapped sources, every import resolved by
  `tools/validate_imports.py`, and the config writer tested by transliteration.
  `docs/testing.md` lists what that covers, what it does not, and the manual test
  plan for the first build.
