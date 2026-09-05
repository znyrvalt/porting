# AltarSMP crossplay package

Generated. Do not edit anything in this directory by hand — rebuild it:

```
python3 tools/crossplay/build_custom_content.py
python3 tools/crossplay/validate_custom_content.py
```

| path | what it is | where it goes |
| --- | --- | --- |
| `pack.zip` | the Bedrock resource pack (icons, attachables, geometries, animations, sounds, names) | Geyser's `packs/` directory |
| `custom_mappings/geyser_item_mappings.json` | the Java item + model data → Bedrock item table | Geyser's `custom_mappings/` directory |
| `custom_mappings/geyser_{block,skull,waypoint}_mappings.json` | empty skeletons; those families map through block-state and entity paths, not item model data | Geyser's `custom_mappings/` directory |
| `config/Geyser-Fabric/locales/overrides/en_us.json` | the source packs' language overrides | the server's `config/` tree |
| `validation/missing-assets.txt` | everything the package deliberately does not ship, with the reason | reference |
| `validation/icon-report.tsv` | how each inventory icon was produced | reference |

The build reads only `AltarSMP-ResourcePack.zip` and `AltarSMP.zip` from the
repository root, so the package can never drift from the committed archives.
It is deterministic: two clean runs produce a byte-identical `pack.zip`.
