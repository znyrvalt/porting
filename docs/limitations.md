# Limitations

Everything the port could not carry over unchanged, and everything it does not do
yet. Nothing here is a silent difference: each entry names the plugin behaviour,
what the mod does instead, and why.

## Deliberate differences

### `/spawnaltarrandom` puts a real altar on each pillar

`SpawnAltarRandomCommand` is 948 lines: it picks up to five surface positions
inside a radius, and at each one builds a themed structure — a 15-block decorated
disc, a cleared 11 × 36 × 11 column, a 30-block pillar whose blocks come from a
per-type palette, one of six builders chosen by altar type, a deck at +31 with a
70% ring, corner lanterns and iron-bars railings, and an enchanting table, an
ender chest and two bookshelves on it. `altar/RandomAltarSpawner.java`
transliterates all of it, position for position and chance for chance, and the
command keeps the plugin's messages, its `altar-spawn.default-range` config key,
its five-pillar limit and its hundred candidate columns.

The last step is the one thing it does differently. The plugin finished each
pillar by spawning an invisible armour stand whose name tag carried the recipe
lines (`a(Location, String):846`): nothing recorded that stand, clicking it did
nothing, and `/destroyaltars` never saw it, so a pillar altar was decoration that
read like an altar. The port hands the same position — the stand's feet were at
base + 32.5 — to `AltarManager#createAltarAt`, which puts the same altar object
`/altar <type>` places: recorded in the altar store, craftable from its config
recipe, swept by `/destroyaltars` like every other altar.

The palettes are the plugin's, unchanged:

| Type | Pillar palette | Ground palette |
| --- | --- | --- |
| `hyperionshard` | cobblestone, mossy/stone bricks, deepslate family; glowstone every 4th | yellow/orange concrete, yellow terracotta, glowstone |
| `nightpiercershard` | same stone family; crying obsidian and obsidian by chance | obsidian, crying obsidian, purple concrete, end stone |
| `vulkanhead` | netherrack, nether/red nether bricks, blackstone family, magma, basalt | netherrack, magma block, red concrete, crimson nylium |
| `illusioncore` | copper in four oxidation states by height, tuff family | moss block, copper block, oxidised copper, tuff |
| `weaponhandle` | yellow/light-blue/orange concrete, polished blackstone, iron, copper | yellow/light-blue/orange concrete, yellow terracotta |
| `paleshard` | sculk, deepslate family, grey/light-grey concrete, tuff | sculk, grey/light-grey concrete, bone block |

Three of the plugin's pillar builders are not ported: `c()` (an ice tower),
`d()` (an obsidian and nether-brick tower) and `f()` (a reinforced-deepslate and
sculk-catalyst tower). The type switch never selected them, so nothing could
reach them in the plugin either. They are dead code, not a missing feature.

### The Pale Shard altar can be crafted from

The plugin offered 35 altars through `/altar` but only 33 of them have a dedicated
interact class. The Pale Shard would have gone through the generic
`CraftingAltarInteract`, and that class is empty: its list of craftable names is
`Arrays.asList()` and its item switch is only `default: return null`. So
`/altar paleshard` and the `paleshard` pillar both produced an altar nothing could
craft from. `config.yml` does define `recipes.paleshard` — bone block 16, cobweb
32, phantom membrane 8, candle 16, soul lantern 4, moss block 64 — and the Pale
Shard item exists in the content catalogue, so the port registers the altar with
the same ritual shape the other five crafting-component altars have, and it gives
the Pale Shard.

The plugin's 35th `/altar` entry, `wardenheart`, is deliberately not registered.
It pointed at `recipes.wardenheart`, which `config.yml` does not define — the
config has `recipes.wardenhead`, the Warden Head altar, which the port does
register and which crafts from netherite ingots, wither skeleton skulls and the
custom warden heart. `/wardenheart`, the command that gives the item, is ported.

## Mechanism changes

Bukkit and Paper offered hooks that vanilla Fabric does not. Each substitution
below produces the same result a player sees; none of them is a stub.

### Permissions

The plugin declared nodes (`altarsmp.admin`, `altarsmps2.admin`,
`altarsmp.bloodmoon`, `altarsmp.tabcolor`) and every one of them defaulted to op.
Vanilla has no permission nodes and Fabric has no permission API of its own, so
admin commands require `Permissions.COMMANDS_GAMEMASTER` (level 2 — the level op
grants, and the level the plugin's default resolved to). Commands with no node
stay open to every player. Consequence: a server that used a permissions plugin
to grant `altarsmp.admin` without op cannot reproduce that split; level 2 is the
granularity vanilla offers.

### Tab completion

The plugin's `TabCompleter` methods returned `List<String>`; the port's are
Brigadier `suggests` lambdas. Same lists, same filtering, delivered by the
command tree instead of a callback.

### `/tabcolor`

Paper let a plugin set a player's display name and tab-list name directly.
Vanilla has no such field, so the port uses the mechanism that produces the same
visible result: a scoreboard team per colour (`asmp_tc_<colour>`) whose colour
tints the name in the tab list and in chat exactly like the old prefix did.
Consequence: a player can only be in one such team at a time, so `/tabcolor`
overrides any other team membership a different plugin or datapack gave them.

### The stat editor

`/legendaryconfig` was a Paper `Dialog`: a list of "Label: current value"
buttons, and clicking one asked the operator to type the new value into chat,
which an `AsyncChatEvent` listener at `HIGHEST` caught and cancelled.

26.2 has dialogs (`ServerPlayer#openDialog`, `MultiActionDialog`, `ActionButton`)
but they are display only — there is no serverbound dialog input packet, so a
server cannot learn which button was pressed. Catching the value in chat was also
rejected on purpose: a signed chat packet carries a seen-messages offset, and
cancelling `handleChat` before the server applies it leaves that player's chat
chain behind, which vanilla can treat as out-of-order messages. Desyncing a
player's chat to edit a number is not a trade this port makes.

So the same three steps — browse, choose, set — live in containers: a list of
items, a list of that item's values with what each holds, and a stepper per value
(presets for minimum and maximum, steps of one, four and ten in both directions,
bounded by the declared range). Typing an exact number is still available as
`/legendaryconfig set <entry> <path> <value>`, with the plugin's own parsing
rules (`true/yes/on/1`, whole numbers inside their range, decimals inside theirs)
and its own refusal messages.

### Morphs

The plugin soft-depended on LibsDisguises for the Wand of Illusion and Echo
morphs. LibsDisguises is a Bukkit library and has no Fabric equivalent, so the
port runs morphs on its own display entities and the morph lock counters the
plugin kept. No disguise library is required or used.

### Container vetoes run on both logical sides

Vanilla's container code — `Slot#mayPlace`, `HopperBlockEntity#addItem`,
`BundleItem`'s click actions — executes on the client and the server. The mixins
that keep protected weapons out of containers therefore run on both sides. That
is correct rather than accidental: the server is authoritative and resends the
menu when the two disagree, and `BundleItemMixin` restricts its veto to
`ServerPlayer` so client-side prediction is untouched. An `EnvType` check would
break singleplayer, where the integrated server shares the client's JVM.

### Config writes keep the file's comments

`YamlConfiguration#save` re-serialised the document from memory and threw every
comment away, so editing one number in the plugin cost the server owner the
annotated `config.yml` they shipped with. `YamlLite#setValue` edits the text
instead: the key's line is replaced in place with its trailing comment kept, and
a key that is not in the file yet is appended under the deepest parent that
exists, at the file's own indent width. One cosmetic difference: alignment
whitespace before a trailing comment collapses to a single space.

### GUIs are containers, not Bukkit inventories

The plugin's browsers were `Bukkit.createInventory(holder, size, title)` with
every `InventoryClickEvent` cancelled. The port's are `ChestMenu` subclasses over
a `SimpleContainer` whose `clicked` never calls `super`, which blocks pickup,
shift-move, split and drop in one place. Clicks are answered on the server only:
in singleplayer the same class runs on the client half of the integrated pair,
where `clicked` fires again for prediction, and without that guard a navigation
click would open two windows and an editor click would apply its step twice.

## Behavioural notes

- **`/legendaries2` opens the Season 2 page.** Upstream it printed "AltarSMP is
  loaded — Season 2 weapons appear in /legendaries" and stopped, while
  `/legendaries`' own page 2 (arrow, navigation handler and all) sat in the code
  with nothing able to reach it. The port wires the two halves together, which is
  what that message promised.
- **Season 2 weapons in the Season 1 browser.** `LegendariesGUI` dropped
  `wardenheart` and `weaponhandle` from its list when the Season 2 module was
  present, on the assumption that module showed them. In the merged plugin both
  modules were always present, so those two items were in neither browser. They
  are reachable by their give commands (`/wardenheart`, `/weaponhandle`) as they
  were upstream; the Season 2 page carries the Season 2 legendaries.
- **`/recipes` shows the Season 2 table.** Two classes named `RecipesCommand`
  shipped in the merged jar; only the Season 2 one was registered, so its nine
  entries are what `/recipes` shows. The Season 1 class was unreachable in the
  plugin too.
- **Altar holograms are display entities.** The plugin used armour stands and
  hologram libraries; the port uses vanilla `TextDisplay`/`ItemDisplay` entities
  with the access-widened setters, which is what a Fabric mod has.

## Environment

- The build needs network access the first time (Minecraft, loader, Fabric API,
  Loom, Gradle).
- `gradle-wrapper.jar` is not committed; `tools/bootstrap-wrapper.sh` fetches it
  from Gradle's own `v9.5.1` tag and prints its SHA-256.
- The mod requires Fabric API. It is not optional: the port uses
  `CommandRegistrationCallback`, the lifecycle events and the networking helpers
  that Fabric API provides.
