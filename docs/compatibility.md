# Compatibility

## Target versions

| Component | Version | Where it is declared |
| --- | --- | --- |
| Minecraft Java | 26.2 | `gradle.properties` → `minecraft_version` |
| Fabric Loader | 0.19.3 | `gradle.properties` → `loader_version`, and `fabric.mod.json` → `depends.fabricloader` |
| Fabric API | 0.158.0+26.2 | `gradle.properties` → `fabric_api_version`, and `fabric.mod.json` → `depends.fabric-api` |
| Java | 25 | `build.gradle` → `options.release = 25`, and `fabric.mod.json` → `depends.java` |
| Loom | 1.17-SNAPSHOT | `build.gradle` → the `net.fabricmc.fabric-loom` plugin |
| Gradle | 9.5.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Mappings | Mojang official | none declared: 26.1+ ships de-obfuscated, so Loom resolves the official names directly |

`fabric.mod.json` pins `minecraft` to `~26.2`, so the mod loads on 26.2 patch
releases and refuses anything else rather than running against an API it was not
checked against.

## Environments

**Dedicated server.** Supported and the primary target. The mod has one
entrypoint (`main`) and one mixin config; there is no client entrypoint and no
client mixin config, so nothing in it requires a graphical environment. The
original plugin was server-side, and every system that had server-only semantics
keeps them: the container vetoes run on both logical sides because vanilla's
container code does, and the server remains authoritative.

**Client with the mod installed.** Supported. The same jar works, and because the
resource pack's assets are inside it, models, textures, sounds and tooltip
sprites load with no pack to install.

**Singleplayer.** Supported. The integrated server runs the same code paths; the
GUI classes guard their click handling to `ServerPlayer` because in singleplayer
the client half of the integrated pair also invokes `clicked` for prediction, and
an unguarded handler would open two windows or apply an editor step twice.

**Vanilla clients joining a server with the mod.** They cannot see the custom
models — a vanilla client has no mod and no pack. Serve
`dist/AltarSMP-ResourcePack.zip` through `resource-pack=true` in
`server.properties` (with `resource-pack-prompt` if wanted) and they get the same
visuals the plugin's players got. Gameplay is unaffected either way: abilities,
damage, cooldowns and persistence are all server-side.

**Geyser / Floodgate.** Bedrock players join through Geyser as Java players, so
server-side behaviour carries over unchanged. Two things to know:

- Bedrock clients take resource packs through Geyser's own `resource_packs`
  folder, not through `server.properties`. The bundled zip is the file to place
  there.
- The pack's item appearance depends on `custom_model_data` dispatch, which
  Geyser translates for Bedrock only where its own item definitions allow. Where
  it does not, a Bedrock player sees the vanilla item with the correct name, lore
  and behaviour — the plugin had the same ceiling, since Bedrock players reached
  it through Geyser too.
- Nothing in the port depends on a Java-only client feature: there are no custom
  shaders, no client-side-only rendering and no keybinds.

**Fabric API.** Required, not optional. The port uses
`CommandRegistrationCallback`, the server lifecycle events, the entity and player
lookup helpers and the networking utilities Fabric API provides.

**Not required, not used.** Paper, Bukkit, Spigot, LibsDisguises, MythicWeapons,
ProtocolLib, WorldEdit and any other Bukkit-era library. The plugin soft-depended
on LibsDisguises for morphs and on MythicWeapons for shared weapon detection; the
port runs morphs on its own display entities and detects its own weapons through
the identity components `Identity` writes, so neither dependency has an
equivalent here and neither is needed.

## Mod and datapack coexistence

- The twelve mixins are all `HEAD`/`TAIL`/`RETURN` injections into named methods,
  with no overwrite of any vanilla method, so other mods that inject the same
  methods coexist. `altarsmp.mixins.json` declares them and nothing else.
- One access widener (`altarsmp.accesswidener`) widens the private
  `Display`/`ArmorStand` setters the plugin reached through Paper's entity API.
  Widening is additive and cannot conflict with another mod.
- Scoreboard teams named `asmp_tc_<colour>` are the only scoreboard footprint, and
  only for players who use `/tabcolor`.
- Entities the mod creates (altar displays, holograms, morph displays, VFX) carry
  tags and scoreboard markers so other mods and datapacks can identify them;
  nothing is registered under another mod's namespace.
- No custom registries, no dimension or biome changes, no worldgen.

## Save compatibility

Altar records, player records and weapon state are written under the server's
config directory (`config/altarsmp/`) as the plugin wrote them, plus the mod's own
data files. A world created without the mod loads normally; a world with recorded
altars needs the mod present for those altars to be recognised, exactly as the
plugin's stored altars needed the plugin.

## What has not been verified by running it

The sandbox this port was written in has no JDK and no Minecraft, so nothing here
has been compiled or executed. Every API signature, field, method descriptor and
component type used by the port was checked against the 26.2 Mojang-mapped
sources, and every import resolved mechanically, but the first `./gradlew build`
on a real machine is the first compile. `docs/testing.md` states exactly what was
checked and what the first build should be asked to prove.
