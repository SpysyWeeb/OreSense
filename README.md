# OreSense

A prospecting sensor for Minecraft 26.3 on NeoForge. Tune it with an ore, with what the ore drops, or with
something made from it, load it with amethyst shards, and follow the needle to the vein.

OreSense adds one item, the **Ore Sensor**. It is a compass-style dial with a red needle, two indicator
lamps for ore above or below you, a charge gauge, and a window that shows what you are looking for.

## How it works

The steps below use the default charge settings: one shard per vein, up to 128 blocks per lock,
and ten seconds of grace outside scan range; the Configuration section lists the other modes.

1. **Tune it.** Right-click with the sensor to open it. Drop a sample into the **Sample** slot: an ore block,
   the item it drops, or anything made purely of that material. A diamond finds diamond ore, an iron ingot
   or nugget finds iron ore, cut copper finds copper ore, netherite scrap finds ancient debris, an amethyst
   shard finds geodes. Only things the sensor can actually find are accepted.
2. **Charge it.** Put amethyst shards in the **Shards** slot, up to 64. The gauge under the window shows
   the charge in four cells of 16 shards with a subtle brightness pulse, even when the Sample slot is empty.
3. **Hold it.** While held, the sensor scans around you. When it finds a matching vein it locks on with a
   chime: the needle turns red and pulses toward the nearest block of the vein, faster the closer you get.
   The top lamp lights when the vein is above you, the bottom lamp when it is below. Level with you, both
   stay dark.
4. **Mine it.** The lock stays while the sensor is anywhere in your inventory, so swap to a pickaxe. The
   first block of the vein you mine spends one shard, and the needle keeps pointing at the rest of the
   vein until it is gone. Leaving the vein behind for more than ten seconds drops the lock for free.
5. **Skip it.** Sneak and right-click to release a lock you do not want. That vein is skipped and the
   sensor looks for another. Releasing within four blocks of the vein counts as found and spends the shard.

A grey needle at rest with cyan rings sweeping the face means the sensor is searching and has found nothing
in range. A pulsing red bar under the window means it cannot pay the lock price.
Creative mode never spends charges.

## Recipe

```
gold ingot     amethyst shard   gold ingot
amethyst shard iron ingot       amethyst shard
gold ingot     amethyst shard   gold ingot
```

Gold and iron accept any `c:ingots/gold` and `c:ingots/iron` item. The recipe unlocks in the recipe
book when you pick up an amethyst shard.

## What it can find

- Any block in the `c:ores` tag, from vanilla or any mod.
- Whatever those blocks drop, and anything made purely from those drops by smelting, blasting, or crafting
  with a single ingredient (ingots, nuggets, storage blocks, raw ore blocks, cut copper, and so on).
  Recipes that mix materials, such as tools or golden apples, are not followed.
- Extra targets from data packs: any file under `data/<namespace>/sample_aliases/*.json` maps an item to a
  list of blocks. The mod ships one for amethyst:

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

  A geode counts as one vein, so it costs one shard with the default charge settings.

Samples and sensor readings are saved using Minecraft's item components. Sensors upgraded from an
older world migrate their saved sample automatically while preserving their charge and vein data.

The mapping is rebuilt from the server's loot tables and recipes on every data pack reload.

## Configuration

`config/oresense-common.toml` is created on first start, with `[scanning]`, `[charging]` and
`[targeting]` sections.

Keys below use section.name notation; keep each setting name inside its section.

Where the settings screen is available, its edits apply in memory and Done saves changed files;
Cancel and Esc restore the opening values without writing. Multiplayer gameplay uses the server's
configuration; edit the server's file to change it.

| key | default | meaning |
|---|---|---|
| `scanning.horizontalRange` | 32 | horizontal scan radius in blocks |
| `scanning.verticalRange` | 16 | vertical scan radius in blocks |
| `scanning.scanIntervalTicks` | 20 | ticks between scans (20 = once a second) |
| `scanning.showDirection` | true | include direction in the optional text reading |
| `scanning.showActionBar` | false | also show the reading above the hotbar |
| `scanning.oresOnly` | true | false permits any block as a sample |
| `charging.chargeMode` | PER_VEIN | PER_VEIN, PER_SIZE, or PER_BLOCK |
| `charging.shardsPerVein` | 1 | PER_VEIN price; 0 makes the sensor free |
| `charging.blocksPerShard` | 16 | PER_SIZE blocks per shard, rounded up |
| `charging.veinCap` | 128 | maximum blocks covered by one lock |
| `charging.lockGraceTicks` | 200 | ticks a lock survives out of range (20 = one second) |
| `targeting.targetPick` | CLOSEST | CLOSEST or LARGEST vein; the nearer vein wins a size tie |

Display settings for this computer are in `config/oresense-client.toml`, which the settings screen (the
Config button for OreSense in the mod list) edits. The dial has three skins, Concept (the original v0.0.1
64 px dial), Compass A (the 1.0 art, the default) and Compass B (Compass A with arrow lamps on the face),
chosen on the settings screen.

## Requirements

Minecraft 26.3 with NeoForge 26.3.0.64-beta or newer within the 26.3.0 series, and Java 25. The mod must be installed on both the client and the server: the
scanning, locking, and charge logic runs on the server, and the sensor's screen uses a networked menu.
A client with OreSense will not connect to a server without it, and the other way round.

NeoForge for Minecraft 26.3 is currently available as a beta loader.

## Building

```
./gradlew build
```

The dial textures, the screen background, and the item models are generated by `tools/gen_textures.py`
(Python 3 with Pillow). Run it after changing the art, then build. It also writes `build/dial_preview.png`
showing every state.

## Licence

MIT. See `LICENSE`.
