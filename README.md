# Meridian ESP

Through-wall ESP for entities and blocks in the [Meridian Proxy](../meridian-proxy) —
a pure Layer-2 module built on top of `meridian-core`.

It talks only to neutral APIs (`meridian-api` + `meridian-core-api`) and never
touches raw Hytale packets, so a Hytale protocol update cannot break it. The
actual rendering uses `meridian-core`'s `DebugRender` service, which forwards
the same trigger-volume display packets the editor uses — depth-test-bypassed
by design, so the outlines stay visible through walls.

## Requirements

This module **requires `meridian-core` ≥ 0.2.0** — it draws through the
`DebugRender` service, tracks entities through `EntityTracker`, scans blocks
through `World`, and (optionally) publishes the user's "click to share"
selections via `SelectionBus`. Put **both** jars in the proxy's modules folder:

```
modules/
├── meridian-core-impl-*.jar
└── meridian-esp-*.jar
```

`meridian-core` loads first (esp's `module.json` declares
`dependsOn: meridian-core >=0.2.0`). Without it, esp is skipped with a warning.

## Features

Two sections in the module's settings panel:

### Entity ESP

- **Enabled** — flip on to start boxing entities (session-only — always starts off).
- **Players only** — restrict both the boxes and the list to real players.
  A player is any entity the server has sent a skin component for, so this is a
  definitive filter (no model-name guessing) — mobs, props, and projectiles
  drop out. Off by default; the choice persists across restarts.
- **Radius** — only entities within this many blocks of the local player are
  boxed. The server stops sending entity updates past the client's view
  distance, so a huge radius costs nothing — there's just nothing further to
  outline.
- **Nearest entities (top 100, click to share)** — read-only live list of the
  closest tracked entities, refreshed twice a second. Each row is
  `#id  d=dist  (x, y, z)`. Clicking a row publishes the entity id via
  `SelectionBus` for other modules to consume.

### Block ESP

- **Enabled** — flip on to start scanning and boxing matched blocks.
- **Block name (contains)** — substring filter applied to the block-type name
  (e.g. `Iron`, `Coal`, `Soil_`). Empty means no scan.
- **Radius** — half-side of the cube scanned every second.
- **Nearest blocks (top 100, click to share)** — read-only live list of matched
  blocks, sorted by distance. Clicking a row publishes the block position
  through `SelectionBus` — handy for piping into
  [meridian-interaction-examples](../meridian-interaction-examples)'s X/Y/Z fields.

The **Enabled** toggles always start off and don't persist; the radii, the
block-name filter, and the **Players only** toggle survive a restart.

## Cross-module flow

ESP is a publisher on `SelectionBus`:

1. User clicks a row in the "Nearest blocks" list.
2. ESP looks up the row's payload in its current atomic snapshot.
3. `selectionBus.publishBlock(pos)` fans the position out to every subscriber.
4. `meridian-interaction-examples` (if loaded) writes those coordinates into its
   X/Y/Z fields — ready for "Use on block", "Hit block", etc.

Same shape for entity rows via `publishEntity(id)` — though no consumer for
that exists in the tree yet.

## Build

```sh
mvn clean package
```

Needs `meridian-api` and `meridian-core-api` in the local Maven repo — build the
[`meridian-proxy`](../meridian-proxy) and [`meridian-core`](../meridian-core)
repos first (`mvn install`). Produces the loadable module:

```
target/meridian-esp-<version>.jar
```

Or build every Meridian module at once with the repo-root `build-releases.ps1`,
which collects all jars into `_releases/`.

## How it works

Rendering coloured outlines through walls is inherently packet-level, which a
pure Layer-2 module cannot do on its own. The split:

- **`meridian-core`** (Layer-1) owns the `DebugRender` service. It captures the
  client session from observed Default-channel traffic and translates calls
  into `AddOrUpdateTriggerVolumeDisplay` / `RemoveTriggerVolumeDisplay`
  packets. It also owns `EntityTracker`, `World`, and `SelectionBus`.
- **`meridian-esp`** (Layer-2) is just a declarative `SettingsSpec` plus two
  schedulers: one re-positions entity boxes every 100 ms, the other scans
  blocks every second. No raw packets, no protocol imports.

### Limits

The trigger-volume render in the client tints occluded surfaces grey — we set
`groupColor` to keep the wireframe edges in the configured colour, but the
fill behind walls stays grey. That's a client-side shader constant, not
something a packet field controls. The laser-pointer channel
(`BuilderToolLaserPointer`) renders cleanly through walls in colour, but it's
gated client-side to builder-tool mode and ignores packets sent outside that
context — so we can't use it for general ESP.

See [meridian-xray](../meridian-xray) and [meridian-camera-tweaks](../meridian-camera-tweaks)
for other worked Layer-2 examples.
