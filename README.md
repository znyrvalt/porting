# AltarSMP — Fabric port

AltarSMP was a Paper plugin: two seasons of altar crafting, thirty legendary
weapons with real abilities and kill counters, four factions with kings, a Blood
Moon, four Copper Trials, copper armour, and a resource pack that gave every one
of those items a model. This repository is that plugin rebuilt from scratch as a
**Fabric mod for Minecraft Java 26.2** — not a wrapper, not a command emulator,
and not a simplified recreation. Every command calls the same game-side code the
plugin called, and nothing answers without doing the work.

The port was written from four files, committed at the root of this repository:

| File | What it is |
| --- | --- |
| `Altar_SMPS1-2-sources-FRESH.jar` | decompiled sources of the merged Season 1 + Season 2 plugin |
| `Altar_SMPS1-2 (1).jar` | the compiled plugin, used to confirm class wiring and resources |
| `AltarSMP-ResourcePack.zip` | the client resource pack: models, textures, sounds, tooltip sprites |
| `AltarSMP.zip` | the plugin's shipped `config.yml` / `s2.yml` and supporting data |

No Paper, Bukkit, Spigot or LibsDisguises API is used anywhere; the morphs the
plugin got from LibsDisguises are handled by the mod's own display entities.

## Versions

| | |
| --- | --- |
| Minecraft Java | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.158.0+26.2 |
| Java | 25 |
| Loom | 1.17-SNAPSHOT |
| Gradle | 9.5.1 (wrapper) |
| Mappings | Mojang official — 26.1+ ships de-obfuscated, so no mapping dependency |

## Repository layout

```
fabric-mod/      the Gradle project: the mod itself (src/main/java, src/main/resources)
docs/            commands, asset audit, compatibility, limitations, testing
tools/           the scripts that generated tables from the sources and check the result
audit/           gitignored working area: extracted plugin sources + Minecraft reference sources
dist/            the release bundle, produced by ./gradlew releaseBundle
```

`audit/` is not committed. It holds third-party material (the decompiled plugin
and the Mojang-mapped Minecraft sources) that must not be redistributed from this
repository; `tools/fetch_mc.py` re-fetches the Minecraft reference sources when
they are needed.

The mod is **109 Java files / ~33,800 lines** in one source tree under
`com.altarsmp.fabric`, plus twelve mixins and a content catalogue extracted from
the plugin into JSON (`fabric-mod/src/main/resources/altarsmp/content/`).

## Building

### Desktop

```bash
bash tools/bootstrap-wrapper.sh     # once: fetches gradle-wrapper.jar
cd fabric-mod
./gradlew build                     # -> build/libs/altarsmp-fabric-2.0.0.jar
./gradlew releaseBundle             # -> ../dist/ (jar, pack, sources, docs, SHA256SUMS.txt)
```

The first build downloads Minecraft 26.2, the loader, Fabric API and the Loom
plugin, so it needs network access. Later builds do not.

### Termux

```bash
pkg update
pkg install openjdk-25 git curl unzip    # a JDK 25 is required; 26.2 will not run on less
git clone <this repository> && cd <repository>
bash tools/bootstrap-wrapper.sh
cd fabric-mod
./gradlew build
```

If `pkg` has no `openjdk-25`, install a JDK 25 by hand and point `JAVA_HOME` at
it before running the wrapper. Gradle itself does not need to be installed — the
wrapper fetches 9.5.1 — but if a local `gradle` exists,
`gradle wrapper --gradle-version 9.5.1` also produces the missing jar.

`gradle-wrapper.jar` is the one binary this repository does not carry;
`tools/bootstrap-wrapper.sh` fetches it from Gradle's own release tag and prints
its SHA-256 so the download can be checked.

## Installing

- **Dedicated server:** put the jar in `mods/` with Fabric Loader 0.19.3 and
  Fabric API. The mod is server-side: no client entrypoint, no client mixins.
- **Client / singleplayer:** the same jar works. Assets ship inside it, so
  legendary models, textures, sounds and tooltip sprites appear with no pack to
  install.
- **Vanilla and Geyser/Floodgate clients:** they cannot load a mod, so they take
  the resource pack the way they always did — `dist/AltarSMP-ResourcePack.zip`,
  served with `resource-pack` in `server.properties` or pushed by a proxy.

## Resource pack

The original pack is a current-format pack (`pack.mcmeta` declares
`min_format 75`), and its `assets/minecraft/items/*.json` are `range_dispatch`
definitions keyed on `minecraft:custom_model_data` — the same numbers the plugin
stamped on its items and this port stamps on its stacks. The build unpacks the
committed zip into the jar, so the two stay in step and the archive remains the
single source of truth:

| Namespace | Files | Contents |
| --- | --- | --- |
| `mythicweapons` | 1453 | weapon models, textures, 546 client item definitions, 5 sounds |
| `minecraft` | 314 | vanilla item definitions that dispatch on custom model data, item textures, 13 sounds |
| `altarsmp` | 192 | tooltip sprites (`textures/gui/sprites/tooltip/<weapon>/`) |
| `custom` | 15 | the plugin's `custom/...` textures |
| `altarsmps2` | 3 | season 2 sounds and definitions |
| `skyboxengine` | 3 | skybox assets the pack shipped |

`docs/asset-audit.md` has the full inventory, including what the pack does not
contain and what therefore cannot be reproduced.

## What is ported

- **Altars** — the registry, the holograms, the rotating display, the locked and
  unlocked states, `/altar`, `/altars2`, `/destroyaltars`, `/lockaltars`, and the
  explosion hook that removes an altar whose anchor is gone.
- **The recipe table** — every recipe, transactional: ingredients are only taken
  when the craft succeeds, and a failure leaves the player's inventory exactly as
  it was.
- **Weapons** — all Season 1 and Season 2 legendaries with their abilities,
  cooldowns, kill counters, morph locks, projectiles and death messages.
- **Factions** — Human, Vampire, Pale and Hyperion, with kings, curses, permanent
  states and the spreading pale system.
- **Events** — Blood Moon (a real event, not a clock change), deathmatch, nuke
  zones, ban zones, hot potato, bingo, the contagion signal.
- **Trials** — the four Copper Trials, the bingo board GUI, the chestplate shard
  counter, and the ominous-vault loot hook.
- **Protection** — weapon protection across item frames, hoppers, bundles,
  containers, drops, fire and explosions, with per-container config toggles.
- **Persistence** — altar records, player records and weapon state written to
  disk and reloaded on restart.
- **Configuration** — both documents, read live and writable in place through
  `/legendaryconfig`, keeping the file's comments.
- **Commands** — all 107 the plugin declared; see `docs/commands.md`.

## Documentation

| | |
| --- | --- |
| [`docs/commands.md`](docs/commands.md) | every plugin.yml command against what the port registers |
| [`docs/asset-audit.md`](docs/asset-audit.md) | the resource pack, namespace by namespace |
| [`docs/compatibility.md`](docs/compatibility.md) | loader, API, server, client, Geyser |
| [`docs/limitations.md`](docs/limitations.md) | every mechanism that had to change, and why |
| [`docs/testing.md`](docs/testing.md) | what was verified, how, and what could not be |
| [`CHANGELOG.md`](CHANGELOG.md) | the port's own history |

## Provenance

The plugin's code was read from the decompiled sources and rewritten against the
26.2 Mojang-mapped Minecraft API; no bytecode, method descriptor or class from
the plugin is reused. Its configuration files and its resource pack assets are
shipped verbatim, because they are the content players recognise. Those assets
belong to the original authors (Kuragami & Standzz) and are redistributed here
unchanged and attributed.
