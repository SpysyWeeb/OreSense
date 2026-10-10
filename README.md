# OreSense

A prospecting sensor for Minecraft Java Edition, available for **Forge, Fabric, Quilt, and NeoForge**.
Tune it with a sample, load it with amethyst shards, and follow the needle to the vein.

OreSense adds one item, the **Ore Sensor**. It is a compass-style dial with a red needle, two indicator
lamps for ore above or below you, a charge gauge, and a window that shows what you are looking for.

**Current mod version: 1.0.2** · [CurseForge downloads](https://www.curseforge.com/minecraft/mc-mods/oresense/files)
· [GitHub releases](https://github.com/SpysyWeeb/OreSense/releases)
· [Report an issue](https://github.com/SpysyWeeb/OreSense/issues)

## Supported versions

OreSense 1.0.2 has **122 published builds** covering all **34 stable Minecraft releases from 1.17 through
26.3**, wherever each loader is available. Each Minecraft version and loader has its own build.

| Loader | Minecraft coverage | Builds |
|---|---|---:|
| Forge | 1.17.1–26.3, except 1.20.5 and 1.21.2 | 31 |
| Fabric | Every stable release from 1.17–26.3 | 34 |
| Quilt | Every stable release from 1.17–26.3 | 34 |
| NeoForge | Every stable release from 1.20.1–26.3 | 23 |

This includes the 1.17.x, 1.18.x, 1.19.x, 1.20.x, and 1.21.x series, plus 26.1, 26.1.1, 26.1.2, 26.2,
and 26.3. Snapshots and Minecraft pre-releases are excluded. Forge has no releases for 1.17, 1.20.5,
or 1.21.2; NeoForge support starts at 1.20.1.

## Installation

1. Install the loader for your Minecraft version.
2. Download the OreSense **JAR for that exact Minecraft version and loader** from CurseForge or GitHub
   releases. On GitHub, download the `.jar` under **Assets**.
3. Put the JAR in your instance's `mods` folder. Replace any older OreSense JAR in that instance.
4. **Fabric and Quilt also require [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api)**
   for the same Minecraft version. Forge and NeoForge need no separate API mod.
5. For multiplayer, install the matching OreSense build and its dependencies on **both the client and
   the server**. Scanning, locking, and charge usage run on the server.

Check the selected release's notes for its loader and API requirements; these vary between Minecraft
versions. **Quilt on Minecraft 1.17, 1.17.1, 1.18, and 1.18.1 requires exactly Quilt Loader 0.26.4.**
The remaining Quilt builds require Quilt Loader 0.30.1 or newer.

| Minecraft version | Java runtime |
|---|---|
| 1.17–1.17.1 | Java 16 minimum; Java 17 recommended and tested |
| 1.18–1.20.4 | Java 17 |
| 1.20.5–1.21.11 | Java 21 |
| 26.x | Java 25 |

## How it works

1. **Tune it.** Right-click with the sensor to open it. Drop a sample into the **Sample** slot: an ore block,
   the item it drops, or anything made purely of that material. A diamond finds diamond ore, an iron ingot
   or nugget finds iron ore, cut copper finds copper ore, netherite scrap finds ancient debris, an amethyst
   shard finds geodes. Only things the sensor can actually find are accepted.
2. **Charge it.** Put amethyst shards in the **Shards** slot, up to 64. The gauge under the window shows
   the charge in four cells of 16 shards with a subtle brightness pulse, even when the Sample slot is empty.
3. **Hold it.** While held in either hand, the sensor scans around you. When it finds a matching vein it
   locks on with a chime: the needle turns red and pulses toward the nearest block of the vein, faster
   the closer you get.
   The top lamp lights when the vein is above you, the bottom lamp when it is below. Level with you, both
   stay dark.
4. **Mine it.** The lock stays while the sensor is anywhere in your inventory, so swap to a pickaxe. The
   first block of the vein you mine spends one shard, and the needle keeps pointing at the rest of the
   vein until it is gone. Spending about ten seconds outside scan range releases the lock without
   spending another shard.
5. **Skip it.** Sneak and right-click to release a lock you do not want. That vein is skipped and the
   sensor looks for another. Releasing within four blocks of the vein counts as found and spends one
   shard if that lock has not already been paid for.

A grey needle at rest with cyan rings sweeping the face means the sensor is searching and has found nothing
in range. If it finds a target but has no charge, the gauge flashes red. Creative mode never spends charges.

## Recipe

```
gold ingot     amethyst shard   gold ingot
amethyst shard iron ingot       amethyst shard
gold ingot     amethyst shard   gold ingot
```

Four gold ingots, four amethyst shards, and one iron ingot. Equivalent ingots from supported common tags
are accepted. The recipe unlocks in the recipe book when you pick up an amethyst shard.

## What it can find

- Vanilla ore families: **coal, copper, iron, gold, redstone, lapis lazuli, diamond, emerald, quartz, and
  ancient debris**, including deepslate and Nether variants where available.
- **Amethyst geodes**, using an amethyst shard as the sample.
- Modded ores registered in the supported ore tags, with samples discovered from their drops and recipes.

Samples can be ore blocks, their drops, or items made purely from those drops through smelting, blasting,
or crafting with one material. Examples include ingots, nuggets, storage blocks, raw ore blocks, cut copper,
and netherite scrap. New material variants are discovered from the recipes available in that Minecraft
version. Recipes that mix materials, such as pickaxes or golden apples, are not followed; custom machine
recipes may need explicit sample mappings.

### Mod and data pack compatibility

OreSense rebuilds its sample mapping from the server's loot tables and recipes on every data pack reload.
The ore tag depends on the loader and Minecraft version:

| Builds | Ore block tag |
|---|---|
| Forge through 1.21.11; NeoForge 1.20.1–1.20.4 | `forge:ores` |
| Forge 26.x; NeoForge 1.20.5 and later | `c:ores` |
| Fabric and Quilt | `oresense:ores`, including vanilla ores and the optional `c:ores` and `forge:ores` tags |

Data packs can also map a sample directly to extra targets. Any file under
`data/<namespace>/sample_aliases/*.json` maps an item to a list of blocks. OreSense ships this amethyst mapping:

```json
{
  "item": "minecraft:amethyst_shard",
  "blocks": [
    "minecraft:budding_amethyst",
    "minecraft:amethyst_cluster",
    "minecraft:large_amethyst_bud",
    "minecraft:medium_amethyst_bud",
    "minecraft:small_amethyst_bud",
    "minecraft:amethyst_block"
  ]
}
```

Connected matching blocks share a vein lock, including amethyst blocks in a geode. Each lock tracks up to
128 blocks and costs one shard when first mined or released nearby.

## Configuration

The configuration file is created on first launch. Edit it, then restart the game or server:

| Loader | File | Settings section |
|---|---|---|
| Forge / NeoForge | `config/oresense-common.toml` | `[scanning]` |
| Fabric / Quilt | `config/oresense.json` | `"scanning"` object |

| key | default | meaning |
|---|---|---|
| `horizontalRange` | 32 | how far out the sensor looks, in blocks |
| `verticalRange` | 16 | how far up and down it looks |
| `scanIntervalTicks` | 20 | how often a held sensor rescans (20 = once a second) |
| `showDirection` | true | include a compass direction in the optional text reading |
| `showActionBar` | false | also print the reading as text above the hotbar |
| `oresOnly` | true | false lets any block be used as a sample directly |

In multiplayer, edit the server configuration to change scanning and the optional action-bar readings.

## Source branches and building

The default branch, **`Forge-1.20.1`**, contains the Forge 1.20.1 source. Other builds live on
[their own branches](https://github.com/SpysyWeeb/OreSense/branches), named `<Loader>-<Minecraft version>`,
such as `Fabric-26.3`, `Quilt-1.17`, and `NeoForge-1.21.1`.

Release tags include the mod version, loader, and Minecraft version:
`v<mod version>-<loader>-<Minecraft version>`, for example `v1.0.2-quilt-26.3`.
Each GitHub release includes its compiled mod JAR.

Use **JDK 17** to build Minecraft 1.17–1.20.4 branches, **JDK 21** for 1.20.5–1.21.11, and **JDK 25** for
26.x. The 1.17.x branches use JDK 17 to produce Java 16-compatible bytecode.

For example, to build Forge 1.20.1:

```sh
git clone https://github.com/SpysyWeeb/OreSense.git
cd OreSense
git switch Forge-1.20.1
./gradlew build
```

Select the desired loader/version branch before building. On Windows, use `gradlew.bat build`.
The mod JAR is written to `build/libs/`.

The dial textures, the screen background, and the item models are generated by `tools/gen_textures.py`
(Python 3 with Pillow). Run it after changing the art, then build. It also writes `build/dial_preview.png`
showing every state.

## Reporting issues

Please include your Minecraft version, loader and loader version, OreSense version, steps to reproduce,
and the relevant log or crash report when [opening an issue](https://github.com/SpysyWeeb/OreSense/issues).

## Licence

MIT. See [LICENSE](LICENSE).
