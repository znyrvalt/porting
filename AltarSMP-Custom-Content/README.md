# AltarSMP-Custom-Content (2.0.5)

Geyser/Bedrock crossplay package for the AltarSMP Fabric port, rebuilt from
scratch by `tools/crossplay/`.

- `geyser/altarsmp-custom-items.json` - Geyser custom-item mappings
  (format_version 2, `legacy` definitions keyed on `custom_model_data`, one
  entry per Java (item, model data) pair). Copy into Geyser's `custom_mappings/`
  folder.
- `packs/pack.zip` - the Bedrock resource pack: `manifest.json`, one Bedrock
  item definition per custom item, `item_texture.json`, rendered 128x128 icons,
  attachables + geometry + packed textures for the 3D weapon models, and the
  ability tooltip artwork restored from the Java pack. Copy into Geyser's
  `resource_packs/` folder (or a Bedrock server's resource packs).
- `inventory.json` - all sixty Java entries cross-referenced to their Bedrock
  item (or recorded as deliberately vanilla-looking).

Regenerate with `python3 tools/crossplay/generate_custom_content.py`, verify
with `python3 tools/crossplay/validate_custom_content.py`. See
docs/CROSSPLAY-HANDOFF.md for the full story.
