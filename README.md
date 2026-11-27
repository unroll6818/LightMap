# LightMap

A lightweight minimap and world map for Minecraft 26.3 on Fabric. Client-side only.

A plain map square in the top-left corner and a full-screen map (`M`) that keeps what it has
recorded. Surface only, vanilla map colours, no frame, text, waypoints or cave view.

**Status:** runs in a real client (26.3, Vulkan back end, with the Sodium 0.9.3 fork). Not yet
measured with mcbench. The self-tests below cover the rest.

## Build and install

```
gradlew build          # jar lands in build/libs/lightmap-<version>.jar
```

The build uses the same Loom, Gradle, Minecraft, loader and Fabric API versions as the mcbench
harness and the Sodium 0.9.3 build, so it resolves from the same Gradle cache. Sodium's config API
is a compile-only dependency from `maven.caffeinemc.net`; to compile against a Sodium jar on disk
instead, pass `-PsodiumJar=<path to any Sodium 0.9.3 jar for 26.3>`.

At run time it needs Fabric API (modules `fabric-lifecycle-events-v1`, `fabric-rendering-v1`,
`fabric-key-mapping-api-v1`). Sodium is optional.

## Using it

- **Minimap:** top-left corner, turns with the view, an arrow for the player.
- **World map:** `M` opens and closes it. Hold any mouse button and drag to move the map; scroll to
  zoom about the pointer.
- **Settings:** with Sodium installed, Video Settings has a "LightMap" section with a Minimap page.
  Changes apply when you press Apply; none needs a restart.

## Settings

Changed in the game and kept in `config/lightmap.properties`:

| Setting | Key | Default | Meaning |
|---|---|---|---|
| Show minimap | `lightmap.minimap` | `true` | Draw the minimap. The map is recorded either way. |
| Opacity | `lightmap.minimap.opacity` | `100` | 0 to 100 percent, in steps of 5 in the screen. At 0 nothing is drawn. |
| Size | `lightmap.minimap.size` | `368` | Side of the square, in screen pixels (not GUI-scaled), 64 to 1024. |
| Rotate with view | `lightmap.minimap.rotate` | `true` | View direction up. Off is north up. |

A JVM system property with the same key wins over the file, is never written back to it, and
shows the control locked. That is how an mcbench run pins a value (`sysprops`).

Start-up only, JVM system properties:

| Property | Default | Meaning |
|---|---|---|
| `lightmap.minimap.margin` | `10` | Gap to the top-left corner, in screen pixels. |
| `lightmap.minimap.zoom` | `1` | Screen pixels per block. |
| `lightmap.persist` | `true` | Save the map under `<game dir>/lightmap/`. `false` keeps it in memory only. |
| `lightmap.pages` | `96` | Most region pages held in memory (about 2.8 MB each, plus a 1 MB texture once drawn). |
| `lightmap.uploadBytesPerTick` | `1048576` | Most pixel data sent to the GPU per client tick. |
| `lightmap.stats` | `false` | Log work counters every five seconds. |

For A/B runs, note that a saved map changes the work done on later runs of the same world (chunks
that match what is on disk are compared and skipped). Use `lightmap.persist=false`, or delete
`<game dir>/lightmap`, to make every run start cold.

## How it works

A region is 32x32 chunks and is held as one 512x512 page, one pixel per block.

1. **Capture, client thread.** When a chunk loads (Fabric's chunk event), read its 256 surface
   heights and copy the block-state containers of the sections around the surface, usually two.
   That is the only per-chunk work on the client thread.
   A block change marks its chunk for one fresh copy at the end of the tick, and only if it is at or
   above the lowest Y the last copy looked at; anything deeper is dropped after one hash lookup.
2. **Resolve and colour, worker thread.** Walk each column down to the first block with a map
   colour, measure fluid depth, and store block state, height and depth in the page. Colours come
   from a table indexed by block-state id, shaded by slope (land) or depth (fluid). If the recorded
   data did not change, nothing further happens; if the pixels did not change, nothing is uploaded.
3. **Upload, client thread, once per tick.** Changed 16x16 tiles are merged into rectangles and
   written into the page's texture through the game's normal texture write, within a byte budget.
   A page's first upload is the whole page.
4. **Draw.** The minimap and the world map draw the same page textures as quads in the game's GUI
   pass: no off-screen target, no extra render pass, no shader of its own. Rotation and zoom are
   applied to the quad corners and the GUI scissor cuts the minimap to its square. Each region in
   view is drawn once, as its page or as a blank square, so nothing overlaps and opacity is just
   the alpha of the quads' colour. The quads are reused render states, so a frame allocates
   nothing of ours.
5. **Save and load, worker thread.** A page is written ten seconds after it changes and when the
   world is left, and dropped from memory thirty seconds after it was last drawn or changed.
   Regions recorded earlier load from disk when they come into view. Files store block state (by
   name), height and fluid depth, not colours, so the picture follows later changes to colouring.

Everything is derived from chunks the client received, so it works the same on servers.

## Checking it

```
gradlew selfTest        # game-independent core: pages, colouring, dirty tiles, file format, worker
gradlew gameSelfTest    # needs the game's classes, but no window or GPU
```

`gameSelfTest` starts the game's registries and runs the real capture, colouring, storage and upload
code against real block states, chunk sections and heightmaps, and the session, minimap and
world map screen against a bare client object. Uploads go through the game's real
GPU front end (which validates every call) into a stand-in driver that keeps the bytes. Neither
task is part of `build`.

## Layout

| Path | Contents |
|---|---|
| `dev.lightmap` | Entry point, start-up settings (`Config`), in-game settings (`Settings`). |
| `dev.lightmap.core` | No game imports. Pages, colour table, shading, dirty-tile rectangles, file format, page store, worker, view transform. |
| `dev.lightmap.mc` | Game side. State table, chunk snapshot, session, textures and uploads, minimap, world map screen. |
| `dev.lightmap.compat` | The settings page for Sodium's video settings screen. Only loaded by Sodium. |
| `dev.lightmap.mixin` | Two mixins: the block-change hook on `ClientLevel`, and an accessor for the GUI render state. |
| `src/selfTest` | The self-tests, in their own source set. |

## Not done yet

- Texture-based block colours and biome tints. Biome is not recorded yet; adding it means a file format version bump.
- Zoom levels below 0.5 px/block on the world map, which need mipmaps or lower-detail pages.
- Dimensions with a ceiling show the ceiling.
- A column is only looked through (glass, water) to 32 blocks below the lowest surface block of its chunk.
- A circular minimap, which needs a shader of its own.
- A settings page without Sodium. Vanilla's own Video Settings screen gets nothing; edit
  `config/lightmap.properties` there.
