# Crossplay series handoff

## What this is

Six commits on top of `fac814a` that take the AltarSMP Fabric port to 2.0.5 and
add the Bedrock/Geyser crossplay package. Oldest first:

1. `baseline: AltarSMP Fabric port state before the Bedrock crossplay hardening pass`
   — the port tree as it stood before the pass, so the series applies to the
   four-archive commit `fac814a` and nothing has to be assumed about the
   starting point.
2. `AltarSMP 2.0.5: Bedrock/Geyser crossplay, restored ability artwork, model-data fixes`
   — `mod_version` 2.0.5, `docs/crossplay.md`, the changelog entry, and
   `tools/crossplay/pngcodec.py` (a PNG reader/writer on `struct` + `zlib`).
3. `AltarSMP 2.0.5: rebuild Bedrock/Geyser crossplay package from scratch`
   — the generator, the validator and the generated `AltarSMP-Custom-Content/`.
4. `crossplay: fix GUI icon projection (canvas-relative units, auto-fit oversized models)`
   — icons were projected at one model unit per icon pixel, so anything larger
   than a 16-unit box was clipped at the slot edge. The projection now works in
   canvas-relative units (one model unit is `canvas / 16`) and uniformly scales
   an oversized silhouette to fit.
5. `crossplay: honour Blockbench texture_size when sampling UVs`
   — Blockbench UVs are expressed in the model's declared `texture_size`
   layout, not in 0..16. Both the icon renderer and the Bedrock geometry
   converter now scale UVs by (real texture size / declared size). `v` grows
   downwards in both formats, so nothing is flipped.
6. `patches: handoff doc now applies the whole series` — this document and
   `patches/`, the mailbox series for commits 1–5.

## Applying it

From a clean checkout at `fac814a`:

```
git am --3way patches/*.patch
```

That replays commits 1–5. `patches/` cannot contain the patch for the commit
that introduces it, so the sixth patch is generated from the tip:

```
git format-patch --binary --start-number 6 -1 <tip> -o patches
```

The rescue tarball `crossplay-rescue.tar.gz` ships all six patches together, so
`git am --3way patches/*.patch` from that tarball replays the whole series in
one command.

## Regenerating and re-verifying

No JDK and no third-party Python are needed; the toolchain is `python3` and its
standard library.

```
python3 tools/crossplay/build_custom_content.py     # rewrites AltarSMP-Custom-Content/
python3 tools/crossplay/validate_custom_content.py  # 29 checks
```

The build reads only `AltarSMP-ResourcePack.zip` and `AltarSMP.zip` from the
repository root, so the package cannot drift from the committed archives. It is
deterministic — sorted zip entries, timestamps pinned to 1980-02-01 00:00:00, a
fixed compression level, stable JSON indentation and key order, no absolute
paths — so two clean runs produce a byte-identical `pack.zip`. Verify that with
`sha256sum` before trusting a rebuild.

The validator's last line is exactly:

```
validation: 29 checks, 0 failed
```

## The 29 checks

Manifest validity and deterministic v4 UUIDs; zip integrity; no absolute or
traversing zip paths; no Java `items/*.json` inside the Bedrock pack; zip
determinism (sorted entries, pinned timestamps); mapping format version 2 with
namespaced base items; unique (base, custom_model_data); unique
(base, identifier) in the `altarsmp` namespace; positive model-data values;
every icon declared in `item_texture.json` with its PNG present; no blank icon;
an icon-report row with a real source for every mapping; attachable identifiers
equal to the mapping name they belong to; attachable geometry, texture, render
controller and animation references all resolving inside the pack; no cube-less
geometry; no zero-thickness cube; the hold animation branching on
`query.is_first_person`; the charge animation driving off
`query.main_hand_item_use_duration`; flipbooks with a real sheet, a positive
tick rate and more than one frame; every sound definition backed by a shipped
`.ogg`; every terrain texture entry shipping its PNG; every mod-side and
pack-side (base, model data) pair mapped or documented in
`missing-assets.txt`; every special model state covered or written down; the
Geyser-Fabric locale overrides shipped intact; every mapping named in
`texts/en_US.lang`; and the GeyserDisplayEntity fetch note present.

## Recorded sizes and hashes

| artefact | size (bytes) | sha256 |
| --- | --- | --- |
| `AltarSMP-Custom-Content/pack.zip` | 811518 | `c18c8c4bb123f7ee593025c608568d41223c0e8054cc395da6867fc2a32ab886` |

`pack.zip` holds 279 entries. The package maps 66 (base item, model data) pairs
over 25 base items; everything the packs or the mod can produce that is *not*
mapped is listed with its reason in
`AltarSMP-Custom-Content/validation/missing-assets.txt`.
