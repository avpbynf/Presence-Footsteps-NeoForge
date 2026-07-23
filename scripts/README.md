# Verification scripts

Standalone verification tools for the port. They only need a JDK (17+), with
no Gradle and no dependencies, and are meant to be re-run for every future
version bump.

## CheckAssets.java

Validates the sound asset layer end to end:

- strict JSON syntax of every `*.json` under `src/main/resources`;
- every entry of both `sounds.json` files (stereo `presencefootsteps` and mono
  `presencefootstepsmono` namespaces) resolves to an `.ogg` file on disk with
  the exact case; orphan `.ogg` files are listed;
- stereo/mono namespaces declare the same sound events (a stereo-only event is
  silent for other players in multiplayer);
- every acoustic name referenced by `blockmap.json`, `golemmap.json`,
  `primitivemap.json`, `config/blockmaps/entity/*.json` and the names
  hard-coded in the mod resolves to a `config/acoustics/<name>.json` file;
- every sound event used inside `config/acoustics/*.json` exists in
  `sounds.json` (walked along the `Acoustic.CODEC` schema; event selector keys
  checked against the `State` enum; names containing `:` are treated as
  external/vanilla events and listed for in-game verification);
- `locomotionmap.json` stances are valid `Locomotion` names;
- every `.ogg` parses as an Ogg/Vorbis stream (page structure, per-page CRC,
  EOS flag, channel count, sample rate) and mono-namespace files are actually
  1-channel;
- lang files: keys missing from `en_us.json` (orphans) and untranslated counts.

```bash
java scripts/CheckAssets.java .
```

Exit code 1 if at least one `[ERROR]` line was printed.

## CheckBlockCoverage.java

Cross-references the vanilla block registry against the mod's block maps:

- lists blocks with no explicit default-substrate mapping (they fall back to
  `primitivemap.json` or heuristic detection; informational, this mirrors the
  mod's own in-game block report);
- reports blockmap keys pointing at block ids or tags that no longer exist
  (stale entries after a Minecraft update);
- validates entity ids used in `golemmap.json` and `locomotionmap.json`.

The block registry is read from any jar containing
`assets/minecraft/blockstates/` and `data/minecraft/tags/block/`. With
ModDevGradle, use the patched jar produced during a normal build:

```bash
java scripts/CheckBlockCoverage.java . build/moddev/artifacts/minecraft-patched-<neo-version>-merged.jar build/moddev/artifacts/minecraft-patched-<neo-version>-sources.jar
```

The sources jar argument is optional and only enables the entity id checks.
Exit code 1 on stale/unknown ids; unmapped blocks alone do not fail the run.
