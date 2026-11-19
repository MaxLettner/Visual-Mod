# Visual Mod

A small, **client-side only** Fabric mod for Minecraft **26.3** that makes the game look a little nicer without costing performance.
It works on any server (vanilla, Paper, other modded servers), because nothing is sent to or needed on the server.

![In-game test screenshots: glowing ores, held torch, armor stand with lantern, dropped glowstone, and vanilla for comparison](docs/screenshots.png)

## Features

### Glowing ores
The ore pixels of every ore block always render at full brightness, as if they were at light level 15, no matter how dark it really is.
The rock around them is lit normally, and the ores don't light up their surroundings.
So in a pitch-black cave you see bright specks of diamond, gold, iron and so on, and nothing else.

This works for all vanilla ores (stone, deepslate and nether variants), for modded ores (any texture ending in `_ore`), and with resource packs:
- **How it finds the ore pixels:** when textures load, each ore texture is compared with the rock it is drawn on (stone, deepslate, netherrack or end stone). Every color that also appears in the rock is removed, which leaves only the ore.
- **Resource packs:** they work automatically. A texture that doesn't look like rock with ore in it is simply left alone.
- **Coal:** coal is black and grey, so it shows up as visible dark specks rather than glowing.

*Performance:* the ore-only textures are created once while resources load. The bright pixels are an extra layer baked into the chunk mesh, so there's no per-frame cost.

### Dynamic lights
Light sources light up their surroundings while they move:

- torches, lanterns, glowstone, sea lanterns, jack o'lanterns, … held by you or other players (main hand and off hand)
- the same items held or worn by mobs and armor stands
- dropped light-source items lying on the ground
- burning entities
- extra items: lava bucket, blaze rod and powder, glow berries, glow ink sac, nether star, …

Block items use their block's own light level automatically, which includes blocks from other mods.
Torches, campfires and similar fire items go out when the holder's head is underwater.
Mobs and items near a light source are lit by it too.

*Performance:*
- Light sources are picked up while entities tick, so there's no extra scan of the world.
- Only entities within 48 blocks of the camera count, and at most 24 sources at once (the closest ones).
- Terrain is re-meshed only when a source moves into a **different block** or changes brightness, and only the ≤ 8 chunk sections its light can reach (7.5 block radius).
- That re-meshing runs on vanilla's background chunk-builder threads.
- With no light sources nearby, the hook costs a single field read.

## Configuration

`config/visualmod.properties` is created the first time you launch the game. All features can be turned off and tuned:

| Key | Default | Meaning |
| --- | --- | --- |
| `oreGlow` | `true` | Enable glowing ores |
| `oreGlowLevel` | `15` | Light level (1–15) the ore pixels always render at |
| `ore.<block id>` | – | Per-ore level, e.g. `ore.minecraft\:coal_ore=0` turns coal glow off |
| `dynamicLights` | `true` | Enable dynamic lights |
| `heldItemLights` | `true` | Items held by players |
| `mobItemLights` | `true` | Items held or worn by mobs and armor stands |
| `droppedItemLights` | `true` | Dropped item entities |
| `burningEntityLights` | `true` | Burning entities |
| `waterSensitive` | `true` | Torches etc. go out underwater |
| `maxDistance` | `48` | Max distance (blocks) from the camera for light sources |
| `maxSources` | `24` | Max simultaneous light sources |
| `item.<item id>` | see file | Light level for non-block items, e.g. `item.minecraft\:blaze_rod=10` |

In `.properties` files, the `:` in ids must be written as `\:`. The generated file already does this.
Restart the game after changing settings. Ore changes also apply after a resource reload (F3+T).
Updating from 1.0.0 resets the ore settings once, because their meaning changed.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3 (Java 25 is required).
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and `visual-mod-<version>.jar` into your `mods` folder.

## Building

```sh
./gradlew build
```

The jar ends up in `build/libs/`. Building needs JDK 25.
Every push also builds the jar on GitHub Actions (see `.github/workflows/build.yml`).

## Testing

```sh
./gradlew runClientGameTest
```

This starts the real game, builds a sealed dark room with ores, and checks every feature:
it takes screenshots and compares their brightness.
The checks are: ore pixels bright while the rock stays dark, ore glow off, holding a torch, putting it away, an armor stand holding a lantern, and dropped glowstone.
It also forces every mixin to apply, so a broken mixin target fails the test.
Screenshots are saved to `build/run/clientGameTest/screenshots/`.

To run it without a monitor or GPU (Linux), you need Xvfb plus Mesa's software OpenGL/Vulkan drivers:

```sh
LIBGL_ALWAYS_SOFTWARE=1 SDL_VIDEO_FORCE_EGL=1 xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew runClientGameTest
```

## Compatibility notes

- Built for Fabric's default renderer (Indigo). Sodium implements the same Fabric rendering API, so ore glow should work there too, but this hasn't been tested yet.
- Animated ore textures from resource packs are skipped by ore glow (those ores just look vanilla).
- Don't use it together with another dynamic-lights mod (LambDynamicLights, Ryoamic Lights, …). Pick one, or set `dynamicLights=false` here.
