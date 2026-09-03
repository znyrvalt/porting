# Commands

The plugin declared 107 commands in `plugin.yml`; the port registers 107 of them in `CommandRegistrar`, one Brigadier tree per name.

Two mechanical differences apply to every row and are not repeated in the table:

- **Permissions.** Every `altarsmp.*` / `altarsmps2.*` node in the plugin defaulted to op. Vanilla has no permission nodes, so admin commands require `Permissions.COMMANDS_GAMEMASTER` (level 2, the level op grants) and the rest stay open to every player.
- **Tab completion.** The plugin's `TabCompleter` lists became Brigadier `suggests` lambdas.

Commands are admin interfaces only: every one of them calls the same game-side code the plugin called, and none of them returns success without doing the work.

| Command | Plugin description | Permission node | Usage | Ported | What it calls |
| --- | --- | --- | --- | --- | --- |
| `/altarsmp` | Main AltarSMP command | `altarsmp.admin` | `/altarsmp <give|reload>` | yes | help, reload, the content lists and the roulette-pool editor |
| `/altartooltip` | Debug tooltip_style component on the held item | `altarsmp.admin` | `/altartooltip [set <key>]` | yes | writes the `tooltip_display` component on the held item |
| `/lockaltars` | Toggle altar interactions on/off (no config change needed) | `altarsmp.admin` | `/lockaltars` | yes | AltarManager lock flag, read by every altar interaction |
| `/bloodlust` | Give Bloodlust weapon | `altarsmp.admin` | `/bloodlust` | yes |  |
| `/boneblade` | Give Bone Blade weapon | `altarsmp.admin` | `/boneblade` | yes |  |
| `/vulcanscrossbow` | Give Vulcan's Crossbow weapon | `altarsmp.admin` | `/vulcanscrossbow` | yes |  |
| `/hyperion` | Give Hyperion weapon | `altarsmp.admin` | `/hyperion` | yes |  |
| `/wandofillusion` | Give Wand of Illusion weapon | `altarsmp.admin` | `/wandofillusion` | yes |  |
| `/frostscythe` | Give Frost Scythe weapon | `altarsmp.admin` | `/frostscythe` | yes |  |
| `/crazyslots` | Give Crazy Slots weapon | `altarsmp.admin` | `/crazyslots` | yes |  |
| `/minorcrazyslots` | Give Minor Crazy Slots, or 'addpool' to add the held item to the roulette pool (admin only). | `altarsmp.admin` | `/minorcrazyslots [player|addpool]` | yes | takes a player or `addpool` |
| `/nightpiercer` | Give Nightpiercer weapon | `altarsmp.admin` | `/nightpiercer` | yes |  |
| `/windweaver` | Give Windweaver weapon | `altarsmp.admin` | `/windweaver` | yes |  |
| `/witherbone` | Give Witherbone weapon | `altarsmp.admin` | `/witherbone` | yes |  |
| `/shadowblade` | Give Shadow Blade weapon | `altarsmp.admin` | `/shadowblade` | yes |  |
| `/pureblade` | Give Pure Blade weapon | `altarsmp.admin` | `/pureblade` | yes |  |
| `/earthgauntlet` | Give Earth Gauntlet weapon | `altarsmp.admin` | `/earthgauntlet` | yes |  |
| `/paladinbattleaxe` | Give Paladin's Battle Axe weapon | `altarsmp.admin` | `/paladinbattleaxe` | yes |  |
| `/cutlass` | Give Cutlass weapon | `altarsmp.admin` | `/cutlass` | yes |  |
| `/palecrossbow` | Give Pale Crossbow weapon | `altarsmp.admin` | `/palecrossbow` | yes |  |
| `/contagionsignal` | Give Contagion Signal item | `altarsmp.admin` | `/contagionsignal` | yes |  |
| `/eclipsesword` | Give Eclipse Sword weapon | `altarsmp.admin` | `/eclipsesword` | yes |  |
| `/knightfall` | Give Knightfall weapon | `altarsmp.admin` | `/knightfall` | yes |  |
| `/knightfallmax` | Set Knightfall kills to max | `altarsmp.admin` | `/knightfallmax <player>` | yes | runs the held blade through KnightfallWeapon#applyKills |
| `/striker` | Give Striker weapon | `altarsmp.admin` | `/striker` | yes |  |
| `/nukelauncher` | Give Nuke Launcher weapon | `altarsmp.admin` | `/nukelauncher` | yes |  |
| `/echo` | Give Echo weapon | `altarsmp.admin` | `/echo` | yes |  |
| `/fireslash` | Give Fire Slash weapon | `altarsmp.admin` | `/fireslash` | yes |  |
| `/copperhelmet` | Give Copper Helmet armor | `altarsmp.admin` | `/copperhelmet` | yes |  |
| `/copperchestplate` | Give Copper Chestplate armor | `altarsmp.admin` | `/copperchestplate` | yes |  |
| `/copperleggings` | Give Copper Leggings armor | `altarsmp.admin` | `/copperleggings` | yes |  |
| `/copperboots` | Give Copper Boots armor | `altarsmp.admin` | `/copperboots` | yes |  |
| `/copperdiamondarmor` | Give diamond armor with copper appearance | `altarsmp.admin` | `/copperdiamondarmor <helmet|chestplate|leggings|boots|all>` | yes | takes helmet|chestplate|leggings|boots|all |
| `/copperpickaxe` | Give Copper Pickaxe | `altarsmp.admin` | `/copperpickaxe` | yes |  |
| `/copperpickaxeupgrade` | Give Copper Pickaxe II | `altarsmp.admin` | `/copperpickaxeupgrade` | yes |  |
| `/wardenheart` | Give Warden Heart | `altarsmp.admin` | `/wardenheart` | yes |  |
| `/weaponhandle` | Give Weapon Handle | `altarsmp.admin` | `/weaponhandle` | yes |  |
| `/illusioncore` | Give Illusion Core | `altarsmp.admin` | `/illusioncore` | yes |  |
| `/hyperionshard` | Give Hyperion Shard | `altarsmp.admin` | `/hyperionshard` | yes |  |
| `/nightpiercershard` | Give Nightpiercer Shard | `altarsmp.admin` | `/nightpiercershard` | yes |  |
| `/vulkanhead` | Give Vulkan Head | `altarsmp.admin` | `/vulkanhead` | yes |  |
| `/copperfragment` | Give Copper Fragment (leaks coordinates every 3 minutes) | `altarsmp.admin` | `/copperfragment` | yes | the trial's own give path: the fragment leaks coordinates |
| `/chestplateshard` | Give Chestplate Shard | `altarsmp.admin` | `/chestplateshard` | yes |  |
| `/chestplatetrial` | Start the Chestplate Shard Event | `altarsmp.admin` | `/chestplatetrial` | yes | the chestplate trial's shard counter |
| `/paleshard` | Give Pale Shard (leaks coordinates every 5 minutes) | `altarsmp.admin` | `/paleshard` | yes |  |
| `/vampire` | Make all players vampires | `altarsmp.admin` | `/vampire` | yes | FactionManager |
| `/pale` | Make all players pale rots | `altarsmp.admin` | `/pale` | yes | wakes the spreading pale system; it does not pale everyone |
| `/human` | Make all players human | `altarsmp.admin` | `/human` | yes | FactionManager |
| `/setpale` | Set a player to pale rot | `altarsmp.admin` | `/setpale <player>` | yes |  |
| `/setpaleking` | Set a player to pale king | `altarsmp.admin` | `/setpaleking <player>` | yes |  |
| `/setvampire` | Set a player to vampire | `altarsmp.admin` | `/setvampire <player>` | yes |  |
| `/setvampireking` | Set a player to vampire king | `altarsmp.admin` | `/setvampireking <player>` | yes |  |
| `/settruevampire` | Set a player to True Vampire (permanent, cannot be converted) | `altarsmp.admin` | `/settruevampire <player>` | yes |  |
| `/clearperma` | Clear a player's permanent faction tag (vampire/pale/human/all) | `altarsmp.admin` | `/clearperma <player> [vampire|pale|human|all]` | yes |  |
| `/removecurse` | Remove curse from a player (set to human) | `altarsmp.admin` | `/removecurse <player>` | yes |  |
| `/sethuman` | Set a player to human | `altarsmp.admin` | `/sethuman <player>` | yes |  |
| `/altar` | Spawn any altar by name (tab-completable) | `altarsmp.admin` | `/altar <altar_name>` | yes | AltarRegistry + AltarManager#createAltar |
| `/altarspawn` | Alias for /altar | `altarsmp.admin` | `/altarspawn <altar_name>` | yes | alias of altar |
| `/spawnaltarrandom` | Spawn a crafting item altar at a random location with themed structure | `altarsmp.admin` | `/spawnaltarrandom <type> [range]` | yes | RandomAltarSpawner raises five pillars around the sender; each deck carries a real, craftable altar |
| `/destroyaltars` | Destroy all altars within a radius | `altarsmp.admin` | `/destroyaltars [radius]` | yes | radius, `all` or `allworlds` through AltarManager#sweep |
| `/contagionstop` | Force-stop an active Contagion Signal ritual | `altarsmp.admin` | `/contagionstop` | yes | ContagionSignalManager |
| `/setbloodlust` | Set a player's bloodlust level (1-5) | `altarsmp.admin` | `/setbloodlust <player> <1-5>` | yes | stored kill count |
| `/setkills` | Set kills for Bloodlust, Knightfall, or Ancient Blade | `altarsmp.admin` | `/setkills <bloodlust|knightfall|ancientblade> <player> <kills>` | yes | bloodlust 0-5, knightfall 0-10 |
| `/lock` | Lock your current morph for 3 uses | `-` | `/lock` | yes | WeaponProtection lock on the held item |
| `/unlock` | Unlock your morph to capture new mobs | `-` | `/unlock` | yes | WeaponProtection lock on the held item |
| `/bloodkills` | Check your Bloodlust kill count | `-` | `/bloodkills` | yes | reads the stored count and prints the unlock table |
| `/controls` | Display weapon control information or toggle command-mode | `-` | `/controls [toggle]` | yes | CommandControlsManager |
| `/ability1` | Trigger your weapon's primary ability (command-mode) | `-` | `/ability1` | yes | ability bus, slot 1 |
| `/ability2` | Trigger your weapon's secondary ability (command-mode) | `-` | `/ability2` | yes | ability bus, slot 2 |
| `/cooldown` | Manage player cooldowns | `altarsmp.admin` | `/cooldown <clear|clearall|check> [player] [ability]` | yes | CooldownManager |
| `/trust` | Trust a player so your weapon abilities won't hit them | `-` | `/trust [player]` | yes | per-player trust list |
| `/untrust` | Remove a player from your trust list | `-` | `/untrust <player>` | yes | per-player trust list |
| `/trustlist` | View your trusted players | `-` | `/trustlist` | yes | per-player trust list |
| `/bingo` | Bingo event commands | `-` | `/bingo <start|stop|status|tasks>` | yes | BingoEvent board (BingoTasksMenu) |
| `/hotpotato` | Copper Core Trial (leggings) - alias for /coppertrial leggings | `altarsmp.admin` | `/hotpotato <start|stop|status>` | yes | the hot potato event |
| `/coppertrial` | Copper Trial events (helmet fragments, boots bingo, leggings core, chestplate shards) | `altarsmp.admin` | `/coppertrial <helmet|boots|leggings|chestplate> [start|stop|status]` | yes | CopperTrialService |
| `/legendaries` | Open the Legendaries GUI to browse all legendary items | `-` | `/legendaries` | yes | the item browser, season 1 page (LegendariesGui) |
| `/legendaryconfig` | Edit legendary weapon cooldowns and damage through chat input | `altarsmp.admin` | `/legendaryconfig` | yes | the stat editor plus `set <entry> <path> <value>` (ConfigFields) |
| `/altarconfig` | Open the AltarSMP configuration GUI | `altarsmp.admin` | `/altarconfig [reload]` | yes | prints the config.yml pointer - the plugin shipped no GUI class for it |
| `/pvp` | Toggle PVP on/off server-wide | `altarsmp.admin` | `/pvp [on|off|status]` | yes | DeathmatchManager pvp toggle |
| `/tabcolor` | Manage player tab list colors | `altarsmp.tabcolor` | `/tabcolor <reset|set|clear> <player> [color]` | yes | scoreboard team per colour (`asmp_tc_<colour>`), the vanilla mechanism for a tinted tab name |
| `/givepaleeffect` | Give a player the Pale potion effect | `altarsmp.admin` | `/givepaleeffect <player>` | yes |  |
| `/bloodmoon` | Toggle blood moon on/off | `altarsmp.bloodmoon` | `/bloodmoon` | yes | BloodMoonManager, which drives the whole event rather than the clock |
| `/removepermapale` | Remove permanent pale rot status from a player | `altarsmp.admin` | `/removepermapale <player>` | yes |  |
| `/removepermahuman` | Remove permanent human status from a player | `altarsmp.admin` | `/removepermahuman <player>` | yes |  |
| `/deathmatch` | Deathmatch event (shrinking border, disabled locators) | `altarsmp.admin` | `/deathmatch <start|stop|status>` | yes | DeathmatchManager |
| `/nukezone` | Nuke Zone event (random nukes every 30 minutes) | `altarsmp.admin` | `/nukezone <start|stop|force|status>` | yes | NukeZoneManager |
| `/banzone` | Ban Zone event (death = spectator, last standing wins) | `altarsmp.admin` | `/banzone <on|off|status>` | yes | BanZoneSystem |
| `/recipes` | Open the recipe list GUI (view craftable item recipes) | `-` | `/recipes` | yes | the season 2 recipe browser (RecipesGui) |
| `/legendaryconfig2` | Edit Season 2 legendary weapon stats through chat input | `altarsmp.admin` | `/legendaryconfig2` | yes | the season 2 stat editor |
| `/blueparticle` | Spawn a blue particle effect (debug/util) | `altarsmp.admin` | `/blueparticle` | yes | the `a/z.java` blue circle: paper with custom model data 1, tagged `altarsmps2_vfx` |
| `/soulinabottle` | Give a Soul in a Bottle | `altarsmp.admin` | `/soulinabottle` | yes |  |
| `/fragmentofthesea` | Give a Fragment of the Sea | `altarsmp.admin` | `/fragmentofthesea` | yes |  |
| `/dragonheart` | Give a Dragon Heart | `altarsmp.admin` | `/dragonheart` | yes |  |
| `/amethystpickaxe` | Give an Amethyst Pickaxe | `altarsmp.admin` | `/amethystpickaxe` | yes |  |
| `/amethystaxe` | Give an Amethyst Axe | `altarsmp.admin` | `/amethystaxe` | yes |  |
| `/blackghastsaddle` | Give a Black Ghast Saddle | `altarsmp.admin` | `/blackghastsaddle` | yes |  |
| `/altars2` | Spawn an altar (Season 2 weapon, fallback name) | `altarsmps2.admin` | `/altars2 <weapon_name>` | yes | the season 2 altar list |
| `/legendaries2` | Open the Season 2 Legendaries GUI | `-` | `/legendaries2` | yes | the item browser, season 2 page |
| `/omen` | Give Omen weapon | `altarsmps2.admin` | `/omen` | yes |  |
| `/ancientblade` | Give Ancient Blade weapon | `altarsmps2.admin` | `/ancientblade` | yes |  |
| `/withersymbiote` | Give Wither Symbiote weapon | `altarsmps2.admin` | `/withersymbiote` | yes |  |
| `/tidebreaker` | Give Tidebreaker weapon | `altarsmps2.admin` | `/tidebreaker` | yes |  |
| `/dragonrend` | Give Dragonrend weapon | `altarsmps2.admin` | `/dragonrend` | yes |  |
| `/bowofdeceptionandlies` | Give the Bow of Deception and Lies | `altarsmps2.admin` | `/bowofdeceptionandlies` | yes |  |
| `/altarsmps2reload` | Reload the AltarSMPS2 config.yml | `altarsmps2.admin` | `/altarsmps2reload` | yes | reloads both documents |
| `/setkillss` | Set kill counter on a held weapon (Ancient Blade) | `altarsmps2.admin` | `/setkillss ancientblade <player> <kills>` | yes | writes the Ancient Blade counter on the held blade and in the record |
