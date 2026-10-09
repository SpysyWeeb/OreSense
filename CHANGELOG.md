# Changelog

## 1.0.2 — 2026-10-09

- Added Minecraft 1.21.10 support for Forge 60.1.0 with Java 21.
- Updated rendering and data-pack paths for Minecraft 1.21.10 while preserving the animated dial and saved sensors.

- Renamed the sensor menu's Ore slot to Sample to reflect that it also accepts ore drops and items made
  from them.
- Updated the empty sensor tooltip and instructions to use the same sample terminology.

## 1.0.1 — 2026-10-08

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
