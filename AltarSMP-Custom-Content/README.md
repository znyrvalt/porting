# AltarSMP-Custom-Content (2.0.5, first pass)

Geyser/Bedrock crossplay package for the AltarSMP Fabric port:

- `geyser/altarsmp-custom-items.json` - Geyser custom-item mappings (format_version 2,
  `legacy` definitions keyed on `custom_model_data`). Drop it into Geyser's
  `custom_mappings/` folder.
- `packs/pack.zip` - the Bedrock resource pack (manifest, item definitions,
  `textures/item_texture.json`, icons, ability tooltip artwork). Drop it into Geyser's
  `resource_packs/` folder.
- `inventory.json` - the full Java-entry to Bedrock-item cross-reference.

This first pass ships the mapping, the definitions and the ability artwork with
the resolved Java textures as stand-in icons. The 3D weapon models are not
projected yet, so weapon icons are their source texture rather than a rendered
view - the follow-up rebuild adds a real icon renderer, attachables and geometry.
