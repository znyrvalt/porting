# Testing

## What this port has not had

The sandbox it was written in has **no JDK and no Minecraft**. Nothing here has
been compiled, and nothing has been run. That is stated plainly because it
bounds every claim below: the verification was static, and static verification
proves that the code refers to real APIs and is internally consistent — not that
it behaves correctly in a live world.

## What was verified, and how

### 1. Every API reference against the 26.2 sources

`audit/mc-src` holds the Mojang-mapped Minecraft 26.2 sources (6,623 files,
re-fetched by `tools/fetch_mc.py`). Each signature the port depends on was read
out of those files rather than recalled. The ones that mattered most:

| Fact | Where it was checked |
| --- | --- |
| Damage entry point is `LivingEntity#hurtServer(ServerLevel, DamageSource, float)` | `world/entity/LivingEntity.java` |
| `Level#explode` is abstract; the concrete 13-argument method is on `ServerLevel` | `server/level/ServerLevel.java` |
| `CombatTracker` keeps its subject in `@Final LivingEntity mob` | `world/entity/CombatTracker.java` |
| `Projectile#onHit(HitResult)` is the universal hit entry point | `world/entity/projectile/Projectile.java` |
| `VaultBlockEntity.Server` is a public static final inner class; `VaultBlock.OMINOUS` is the ominous property | `world/level/block/entity/vault/`, `world/level/block/VaultBlock.java` |
| `ItemEntity#setUnlimitedLifetime()` sets age −32768 | `world/entity/item/ItemEntity.java` |
| `ServerPlayer#drop(ItemStack, boolean, boolean)` is the server drop path | `server/level/ServerPlayer.java` |
| `ServerGamePacketListenerImpl.player` is a public field; `handleChat(ServerboundChatPacket)` is the chat entry | `server/network/ServerGamePacketListenerImpl.java` |
| `Level#getEntity(UUID)` exists, so hologram refresh does not scan every entity | `world/level/Level.java` |
| `ServerPlayer#openDialog(Holder<Dialog>)` exists; there is **no** serverbound dialog input packet | `server/level/ServerPlayer.java`, `network/protocol/common/CommonPacketTypes.java` |
| `PotionContents(Optional<Holder<Potion>>, Optional<Integer>, List<MobEffectInstance>, Optional<String>)`; `Potions.STRONG_STRENGTH` is already a `Holder` | `world/item/alchemy/` |
| `Fireworks(int flightDuration, List<FireworkExplosion>)`, `ItemLore(List<Component>)`, `MobEffectInstance(Holder, int, int, boolean, boolean, boolean)` | `world/item/component/`, `world/effect/` |
| `ServerPlayer#closeContainer()` is public | `server/level/ServerPlayer.java` |
| Sound names: `CONDUIT_ACTIVATE`, `PLAYER_LEVELUP`, `AMETHYST_BLOCK_CHIME` are `SoundEvent`; `UI_BUTTON_CLICK` is a `Holder.Reference` and needs `.value()` | `sounds/SoundEvents.java` |

Two assumptions were **disproved** by that reading and corrected in the code:
`CommandSourceStack#hasPermission(int)` does not exist in 26.x (the port uses
`source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`), and
`SoundEvents.BLOCK_CONDUIT_ACTIVATE` / `ENTITY_PLAYER_LEVELUP` are not the 26.x
names. `SharedSuggestionFactory#suggest(Iterable, SuggestionsBuilder)` is gone
too, which is why the registrar carries its own suggestion helper.

### 2. Every import and fully-qualified name resolved mechanically

```bash
python3 tools/validate_imports.py
```

The script indexes the port's own classes, the 6,623 Minecraft classes in
`audit/mc-src` and the Fabric API sources, then resolves every `import` and every
inline fully-qualified reference in the port. Current result:

```
project classes: 269   minecraft classes: 6623
OK: every import and inline fully-qualified name resolves (109 files)
```

This catches a wrong package, a renamed class, a class that does not exist in
26.2, and a typo in an inline `net.minecraft.…` reference. It does not catch a
wrong method signature on a class that does exist.

### 3. Signature-level spot checks of the mixins

For each of the twelve mixins, the target method, its parameter list and the
injection point were read from `audit/mc-src` at the time the mixin was written —
including the full 13-argument `ServerLevel#explode` descriptor and the
`@Shadow @Final LivingEntity mob` field in `CombatTracker`. An earlier draft of
`CombatTrackerMixin` shadowed a field that did not exist and was rewritten
against the real one.

### 4. The config writer, by transliteration

`YamlLite#setValue` has no Java to run, so `tools/test_yaml_write.py` mirrors it
statement for statement in Python and runs it over the `config.yml` the mod
actually ships:

```bash
python3 tools/test_yaml_write.py
```

Six cases, all passing: an existing integer rewritten in place
(`holy_lance_cooldown: 60` → `45`), an existing decimal (`holy_lance_damage: 6.0`
→ `12.5`), a boolean (`weapon-protection.enabled`), a decimal whose line carries a
trailing comment (comment kept), a key that is not in the file yet appended under
the deepest existing parent at the file's own indent width
(`abilities.hyperion.brand_new_value`), and a brand-new section appended at the
end (`brand-new-section.nested.value`). The script also reports which original
lines were replaced, and only the intended ones are.

### 5. The command surface, against `plugin.yml`

```bash
python3 tools/generate_command_doc.py
```

Parses the plugin's `plugin.yml` (107 commands with their descriptions, usages
and permission nodes) and cross-checks each name against `CommandRegistrar`,
writing `docs/commands.md`. Current result: **107 declared, 106 registered, 1
missing** — `/spawnaltarrandom`, listed in `docs/limitations.md`. The check found
no other gap; names registered through helper methods (`curse(…)`,
`configEditor(…)`) and through the give-command tables are all detected.

### 6. The stat tables, generated rather than retyped

```bash
python3 tools/extract_config_fields.py
```

Reads every `getConfigFields()` / `configFields()` declaration in the decompiled
plugin and writes `config/ConfigFields.java`: **32 tables, 198 fields**, labels,
paths, ranges and steps exactly as declared. The extractor had to handle three
spellings of the field type (`c`, `a.c` in `PaleCrossbowWeapon`, `v` in Season 2),
two arities of range (int and float) and scientific notation
(`5.0E-4F` for the Pale Crossbow's shot gravity). It reports any file whose
fields it cannot place, and none are left unreported.

### 7. Structural checks

Brace and parenthesis balance on every file touched, and a read-back of each
generated or patched file. The resource pack inventory in `docs/asset-audit.md`
was produced by reading the zip directly (`zipfile`), not from any documentation.

## What none of this covers

- **Compilation.** No javac ran. A wrong overload, a missing `throws`, a generic
  mismatch or an access problem would survive every check above and appear on the
  first build.
- **Mixin application.** Descriptors were read from the sources, but Mixin's own
  resolution at runtime is untested: refmap generation, injection-point matching
  and the access widener's effect all need a real load.
- **Behaviour.** Damage numbers, cooldown timing, ability targeting, trial
  completion, faction spreading, Blood Moon state, persistence round-trips,
  hologram placement and GUI rendering are unverified by execution.
- **Item appearance.** The pack's `range_dispatch` definitions and the model data
  the port stamps were cross-read, but no client has rendered a legendary.

## First-build test plan

The order below puts the cheapest, most informative failures first.

1. **Compile.** `bash tools/bootstrap-wrapper.sh && cd fabric-mod && ./gradlew build`.
   Expect a jar in `build/libs/`. If a signature is wrong, the compiler names the
   file and line — that is the point of doing this first.
2. **Load.** `./gradlew runServer` (or `runClient`). Expect, in order: the config
   loaded from `config/altarsmp/` with its key counts, the content catalogue
   initialised, the recorded-altar summary, and
   `[AltarSMP] registered <n> commands`, where n is 106 plugin.yml names plus the
   aliases (`tc`, `altarspawn`, `asmpconfig`, `altarsmpconfig`, `s2reload`). Any
   mixin failure stops the load with a
   Mixin apply error naming the target — fix that before anything else.
3. **GUIs.** `/recipes`, `/legendaries` (both pages), `/legendaryconfig`,
   `/legendaryconfig2`. Each must open, show its items, and refuse every attempt
   to take something out (click, shift-click, drag, number keys, drop key).
4. **Give and inspect.** `/hyperion`, `/omen`, `/copperhelmet`. Check name, lore,
   tooltip frame, model, and that the item is protected: try to put it in a chest,
   a barrel, a shulker, a hopper, a bundle and an item frame, try to drop it, and
   try to burn it — each must be refused per the `weapon-protection.*` toggles.
5. **Combat.** Kill mobs with a kill-counter weapon (Bloodlust, Knightfall,
   Ancient Blade) and confirm the counter, lore and model data advance; use an
   ability and confirm the cooldown; die to a legendary and confirm the custom
   death message.
6. **Altars and crafting.** `/altar <type>` places an altar with its hologram and
   rotation. Craft with insufficient ingredients: it must refuse and leave the
   inventory untouched. Craft with sufficient ingredients: the item appears and
   the ingredients go. Then `/destroyaltars <radius>`.
7. **Explosions.** Detonate something next to a placed altar and confirm the
   altar is removed when its anchor is gone, and kept when it is not.
8. **Events and factions.** `/bloodmoon` start and stop, `/deathmatch`,
   `/nukezone`, `/banzone`, `/setvampire`, `/pale` (confirm the pale system
   spreads rather than instantly converting everyone), `/tabcolor`.
9. **Trials.** Loot an ominous vault and confirm the trial starts from real loot;
   open the bingo board; pick up a chestplate shard and confirm the counter.
10. **Persistence.** Restart the server and confirm recorded altars, player
    records and weapon state come back.
11. **Config editing.** In `/legendaryconfig`, step a value, then
    `/legendaryconfig set hyperion abilities.hyperion.holy_lance_cooldown 90`.
    Open `config/altarsmp/config.yml` on disk: the value must be the new one, the
    comments must still be there, and altar holograms must quote the new numbers.
12. **Reload.** `/altarsmp reload` and `/altarsmps2reload`, then re-check a value
    you changed.
13. **Clients without the mod.** Join with a vanilla client and with a Bedrock
    client through Geyser, with `dist/AltarSMP-ResourcePack.zip` served. Names,
    lore and behaviour must be identical; appearance depends on the pack.

## Reproducing the static checks

```bash
python3 tools/validate_imports.py        # imports and qualified names
python3 tools/generate_command_doc.py    # plugin.yml against the registrar
python3 tools/extract_config_fields.py   # regenerate the stat tables
python3 tools/test_yaml_write.py         # the config writer, by transliteration
```

All four are deterministic and read only this repository and `audit/`; none of
them needs a JDK.
