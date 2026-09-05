# Bedrock crossplay

Java clients get the AltarSMP look from `AltarSMP-ResourcePack.zip`: item
definitions under `assets/<namespace>/items/*.json` dispatch on
`minecraft:custom_model_data`, and the models they select are ordinary
Blockbench models. A Bedrock client connected through Geyser sees none of that.
Geyser translates items by identity, and Bedrock has no concept of model data at
all, so every custom weapon arrives as the base item it was built on: seventeen
different netherite swords, three maces, a plain trident.

The fix has two halves, and the mod side has to be right before the pack side
can be built at all.

## Model data on the mod side

`ItemFactory#applyModelData` is the single place the port writes the number the
resource pack dispatches on. The plugin wrote it to the legacy integer slot
*and* to the float list of the `CustomModelData` component, because modern item
dispatch reads the float slot while older tooling reads the integer one. Both
are written, so a stack minted by the mod matches the pack whichever slot the
client consults.

Everything that mints a stack goes through that method:

| call site | base item | model data |
| --- | --- | --- |
| `ItemFactory#decorate` | whatever `base_material` the content table names | the table's `custom_model_data` |
| `AltarManager` | the altar entry's `material` | the altar entry's `cmd` |
| `KnightfallWeapon#applyKills` | `MACE` | 15, 16 or 17 by kill count |
| `DragonrendWeapon#voidClockPiece` | `PAPER` | 2, 3 and 5, the void-clock pieces |
| `TidebreakerWeapon` | `TRIDENT` | 1, the thrown model |
| `CommandRegistrar#blueCircle` | `PAPER` | 1, the interpolated circle |

Those are the numbers the crossplay package has to reproduce; nothing is
hard-coded twice.

## Ability artwork

The VFX item displays the abilities spawn (`Displays.*`, tagged
`altarsmps2_vfx`) are item displays holding a stack with model data, not
bespoke entities. The 2.0.5 asset set restores the artwork they reference —
the `mythicweapons` `fx_*` and `vfx_*` item definitions, their models and
textures, and `minecraft/textures/custom/paladin_hammer_fx.png` — so an ability
that spawns a display has something to show instead of falling back to the base
item. Geyser fetches a display entity's held item through the same item
mappings, so restoring the artwork fixes both platforms at once.

## The Bedrock package

`tools/crossplay/` builds `AltarSMP-Custom-Content/` out of the two committed
source archives: a Geyser item-mapping file, and a Bedrock resource pack with
inventory icons, held-item attachables, geometries, animations, sounds and
translated names. See the generated
`AltarSMP-Custom-Content/validation/missing-assets.txt` for everything the
package deliberately does not ship, and why.
