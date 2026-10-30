# Changelog

## 1.1.0 — 2026-10-30

- Settings screen: open it from the mod list's Config button (Mod Menu), on the title screen or from
  the pause menu's Mods screen in a world. Skin previews on top, then every setting with a short
  description. Changes apply at once (the game keeps running behind the screen); Done saves,
  Cancel puts everything back. Gameplay settings are greyed out on a remote server, where the
  server's own file decides. Direction in text is greyed out while Text reading is off.
- New display settings for this computer (`config/oresense-client.json`): the dial skin and
  Pulsing lights (on by default; off holds the needle glow, sonar sweep and charge gauge pulse
  steady, and the needle still turns).
- The dial has three skins, Concept (the original v0.0.1 64 px dial), Compass A (the 1.0 art, the
  default) and Compass B (Compass A with arrow lamps on the face), chosen on the settings screen.
- Charge modes (`charging.chargeMode`): per vein (as before, `shardsPerVein` shards, 0 makes the
  sensor free), per size (one shard per `blocksPerShard` blocks of the vein) or per block mined.
  A lock's price, vein size cap and grace time are fixed when it is made. A sensor holding fewer
  shards than the price does not lock, and its tooltip says how many shards the vein needs.
- When a lock costs something other than one shard per vein, its price shows above the hotbar
  the moment it locks, and in the sensor's tooltip until it is paid.
- Vein choice is a world setting (`targeting.targetPick`): sensors lock onto the closest vein, as
  before, or the largest vein in range, the nearer one on a tie. Changing it lets a lock that is
  not paid for yet go.
- Shorter sensor tooltip: the sample, the shards and any warning, then "Hold Shift to view more
  information" for how to use it. The game's own contents line ("1 x Diamond"), which repeated the
  sample, is hidden.
- Fabric: `/reload` now re-reads `config/oresense.json`. OreSense writes its own comment above every
  setting in that file, so comments of your own are not kept.
- Existing config files and sensors keep working: new settings start at their defaults, and
  1.0.3 clients and servers can still join 1.1.0 ones in both directions.

## 1.0.3 — 2026-10-17

- With `oresOnly` off, storage blocks, raw-ore blocks and cut copper now find their ore as well as themselves; previously they stood only for themselves.
- Removed a stray Python cache file from the repository.

## 1.0.2 — 2026-10-09

- Added Quilt support for Minecraft 1.21.6 with Quilt Loader and Fabric API.
- Preserved stored samples and charge when upgrading worlds to the new item component format.
- Added support for modern Fabric common material tags.

- Renamed the sensor menu's Ore slot to Sample to reflect that it also accepts ore drops and items made
  from them.
- Updated the empty sensor tooltip and instructions to use the same sample terminology.

## 1.0.1 — 2026-10-08

- Added Minecraft 1.20.1 support with Fabric Loader and Fabric API.
- Added Fabric registration, rendering, menus, networking, and configuration.
- Included all vanilla ores, plus Fabric common and Forge ore/ingredient tag compatibility.
- Lock and release cues use Minecraft 1.20.1’s amethyst resonance asset.
- The charge gauge now shows stored amethyst even when the ore sample slot is empty or the sensor is
  dormant. Adding or removing a sample no longer hides the charge level.
- Filled charge pixels pulse slowly between 75% and full brightness.

## 1.0.0 — 2026-09-24

First release.

- Ore Sensor item: a compass-style dial with a needle, above/below lamps, a charge gauge, and a sample
  window, drawn at vanilla pixel scale.
- Samples: ore blocks, their drops, and anything made purely of the material through smelting, blasting,
  or single-ingredient crafting. Data-driven extra targets via `sample_aliases`; amethyst shards find
  geodes out of the box.
- Charges: up to 64 amethyst shards. One shard per vein, spent when you mine the first block of a locked
  vein. Locks survive the sensor being in the inventory, drop for free after ten seconds out of range,
  and can be released by sneak-clicking.
- Feedback: the needle pulses faster as you close in, dims when a lock is out of range, and the lit lamp
  pulses with it. Chime on lock, crystal break when a shard is spent. Sonar rings while searching, a red
  pulsing gauge when out of shards.
- Screen: one slot for the sample, one for shards, labelled. The sample slot only accepts what the sensor
  can find, on both client and server.
- Recipe: gold ingots, amethyst shards, and an iron ingot; unlocks in the recipe book on picking up an
  amethyst shard.
