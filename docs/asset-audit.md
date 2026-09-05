# Asset audit

The authoritative asset input is `AltarSMP-ResourcePack.zip`, committed at the
repository root. This audit lists what it contains, what the build does with it,
and what it does not contain — so nothing about the port's visuals is assumed.

## The pack

1,981 files: 1,980 under `assets/` plus `pack.mcmeta`.

```json
{ "pack": { "description": "AltarSMP + MythicWeapons", "min_format": 75, "max_format": 199 } }
```

`min_format 75` is the versioned format introduced with the 1.21.9-family client
item definitions, so the pack is a current-generation pack: it drives item
appearance through `assets/<namespace>/items/*.json` and `range_dispatch` on
`minecraft:custom_model_data`, not through the pre-1.21.4 `overrides` predicate
lists. Six model files still carry legacy `overrides` blocks (21 entries between
them, in `models/weapons/vulcan/`, `models/weapons/arc3/`, `models/weapons/pale`,
`models/custom/dragonheart` and `models/custom/vulcans_skull`); they are nested
models and the client ignores an `overrides` key it does not use.

| Kind | Files |
| --- | --- |
| textures (`.png` and their `.mcmeta`) | 746 |
| models | 631 |
| client item definitions (`items/*.json`) | 583 |
| `sounds.json` | 3 |
| sound samples (`.ogg`) | 9 |
| language file | 1 |
| other JSON | 8 |

## Namespace by namespace

### `mythicweapons` — 1,453 files

The bulk of the pack, shared with the MythicWeapons plugin the original
soft-depended on.

| Path | Files | What it is |
| --- | --- | --- |
| `models/vfx/` | 324 | the visual-effect models: whirlwinds, slashes, beams, trails |
| `textures/item/` | 191 | item textures, including the `weapons/` and `vfx/` subtrees |
| `models/item/` | 137 | item models |
| `textures/weapons/` | 119 | weapon blade and head textures |
| `models/weapons/` | 85 | the weapon models the item definitions dispatch to |
| `textures/vfx/` | 34 | effect textures, some animated (`.png.mcmeta`) |
| `textures/overlay/` | 6 | screen overlays |
| `items/` | 546 | client item definitions |
| `sounds/` + `sounds.json` | 5 + 1 | `electricity`, `bram`, `heartbeat`, `sword_spin_030_high`, `sword_spin_075` |

### `minecraft` — 314 files

Vanilla overrides, which is how a vanilla item becomes a legendary.

| Path | Files | What it is |
| --- | --- | --- |
| `items/` | 32 | **the dispatch layer**: `netherite_sword.json`, `crossbow.json`, `bow.json`, `trident.json`, `mace.json`, `netherite_pickaxe.json`, `netherite_axe.json`, the four `netherite_*` armour pieces, `paper.json`, `feather.json`, `snowball.json`, `clay_ball.json`, `echo_shard.json`, `amethyst_shard.json`, `breeze_rod.json`, `gold_nugget.json`, `iron_nugget.json`, `heavy_core.json`, `red_concrete.json`, `dragon_head.json`, `stripped_crimson_hyphae.json`, plus the plugin's own `diamond_spear`, `purple_harness`, `pale_logs`, `s1a2_beam`, `s1a2_pale_roots`, `lv1`–`lv3` |
| `textures/item/` | 119 | item textures, including the `altar/` subtree |
| `textures/custom/` | 63 | the plugin's `custom/...` textures, some named by UUID |
| `models/custom/` | 48 | models for those textures |
| `models/weapons/` | 26 | weapon models in the vanilla namespace |
| `textures/block/` | 11 | block textures (`contagionsignal.png` among them) |
| `models/block/` | 5 | block models |
| `sounds/custom/` + `sounds.json` | 2 + 13 entries | `custom.nuke_incoming`, `custom.nuke_explosion` (samples) and the `hv_dig.*` set |
| `lang/en_us.json` | 1 | one key: `effect.minecraft.luck` |

Each dispatch file is a `range_dispatch` on `minecraft:custom_model_data`, and
several entries nest a `minecraft:condition` on `minecraft:using_item` so a
crossbow or wand shows its drawn state. Example, `netherite_sword.json`: model
data 1 → `weapons/bone_blade`, 2 → `weapons/illusion_wand` (or `illusion_wand_2`
while using), 3 → `weapons/bloodlust`, 4 → `weapons/frost_2`/`weapons/frost`…
These are the same numbers the plugin stamped on its items and
`Identity`/`ItemFactory` stamp on the port's stacks, which is why shipping the
pack is enough to make a legendary look like a legendary — no `item_model`
component is needed.

### `altarsmp` — 192 files

All of it `textures/gui/sprites/tooltip/`: the tooltip backgrounds and frames,
one pair per weapon (`hyperion`, `knightfall`, `omen`, `ancient_blade`,
`dragonrend`, `tidebreaker`, `wither_symbiote`, `paladin_axe`, `cutlass`,
`bloodlust`, `pure_blade`, `nightpiercer`, `windweaver`, `witherbone`,
`shadow_blade`, `earth_gauntlet`, `frost_scythe`, `crazy_slots`, `bone_blade`,
`pale_crossbow`, `fire_slash`, `eclipse_sword`, `nuke_launcher`, `contagion_signal`,
`wand_of_illusion`, `copper_armor`, `vulcans_crossbow`, plus the shared `red`,
`blue`, `fire`, `desert`, `dragon` sets and a handful of weapons that never
shipped: `biome_blade`, `amaranth`, `arbiter`, `daybreak`, `bloodroot`,
`blights_edge`, `palewake`, `skulkrend`, `galebreaker`, `kingsfall`, `permafrost`,
`prismatic_piercer`, `blaze_bringer`, `dead_weight`). Each `.png` has a
`.png.mcmeta` alongside it.

These are what the port's `tooltip_display` component points at
(`ItemFactory#tooltipStyleId`), and they are the reason a legendary's tooltip has
a painted frame instead of the vanilla grey.

### `custom` — 15 files

The copper armour's worn appearance, which lives outside the vanilla namespaces:

- `items/copper_helmet.json`, `copper_chestplate.json`, `copper_leggings.json`,
  `copper_boots.json` — client item definitions
- `models/item/` — the four matching models
- `textures/item/` — the four inventory textures
- `equipment/copper.json` plus `textures/entity/equipment/humanoid/copper.png`
  and `humanoid_leggings/copper.png` — the 26.x equipment asset that draws the
  armour on a player

### `altarsmps2` — 3 files

`sounds.json` and two samples, `dragonding.ogg` and `dragontick_tack.ogg`, used by
the Dragonrend and Dragon Heart abilities.

### `skyboxengine` — 3 files

`items/model_shader_1.json`, `models/item/shader/model_shader_1.json` and
`textures/shader/model/model_shader_1.png`. Assets belonging to a third-party
skybox helper that shared the pack; the plugin does not reference them and
neither does the port. They are shipped because they are in the archive, and
inert.

## What the build does with it

`fabric-mod/build.gradle` registers `extractResourcePack`, a `Sync` that unpacks
`assets/**` from the committed zip into `build/generated/altarsmp-pack`, which
`processResources` copies into the jar. `pack.mcmeta` is excluded: the jar is a
mod, not a pack, and already carries `fabric.mod.json`.

Consequences:

- The zip stays the single source of truth. Assets are not committed twice, and a
  change to the archive changes the jar on the next build.
- Clients with the mod get every texture, model, sound, tooltip sprite and vanilla
  item definition with nothing to install.
- Vanilla and Geyser clients cannot load a mod, so `dist/AltarSMP-ResourcePack.zip`
  — the same archive, byte for byte — is bundled for them and can be served
  through `resource-pack` in `server.properties` or pushed by a proxy.
- `assets/minecraft/**` inside a mod jar overrides vanilla assets for every player
  who has the mod. That is exactly what installing the pack did; the port does not
  narrow it, because the legendaries depend on those dispatch definitions.
- `sounds.json` from a mod jar merges with the vanilla file per namespace, as it
  does between packs, so the 13 `minecraft` entries and the 7 plugin entries are
  added rather than replacing vanilla's sounds.

## What the pack does not contain

Recorded so it is not mistaken for a porting gap:

- **One language key.** `assets/minecraft/lang/en_us.json` defines
  `effect.minecraft.luck` and nothing else. Item names in the plugin came from
  component markup in code, not from translation keys, and the port does the same
  (`ContentCatalog` carries each item's display markup), so there is no name table
  to translate and no localisation hook to preserve.
- **No particle definitions.** The pack has no `particles/` directory. Every
  particle the plugin used is a vanilla particle type spawned from code, which is
  what the port spawns.
- **No blockstates.** The 11 block textures and 5 block models in the `minecraft`
  namespace have no `blockstates/` entries; they are referenced by models and
  displays, not placed as blocks with their own state.
- **No player- or mob-model assets** beyond the copper equipment set. The morphs
  the plugin got from LibsDisguises were entity disguises, not textures, so the
  pack never carried them; the port's morphs are display entities.
- **Ten weapons' tooltip sprites with no weapon behind them** (`biome_blade`,
  `amaranth`, `arbiter`, `daybreak`, `bloodroot`, `blights_edge`, `palewake`,
  `skulkrend`, `galebreaker`, `kingsfall`, `prismatic_piercer`, `blaze_bringer`,
  `dead_weight`, `permafrost`). The plugin shipped no class for any of them in
  either season, so there is nothing to bind them to. They are shipped unchanged
  and unused, which is what the pack did.

## Verification

`tools/validate_imports.py` resolves every import and inline fully-qualified name
in the port against the 26.2 Mojang-mapped Minecraft sources in `audit/mc-src`
(6,623 files). The pack inventory above was produced by reading the archive
directly, not from documentation. `dist/SHA256SUMS.txt`, written by
`./gradlew releaseBundle`, covers the jar, the bundled pack and every document in
the bundle.
