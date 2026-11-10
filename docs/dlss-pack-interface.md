# DLSS pack interface

A shader pack can ask this engine to run DLSS on its own image, without the engine knowing anything
about that pack's pipeline. The pack declares where its linear colour lives and which program is the
point reconstruction must happen at; the engine runs the model there and hands back a
screen-resolution image.

This is the same bargain the [Super Resolution mod](https://sr.187j3x1-114514.org/docs/zh/Shaderpack-Interface-documentation/Version-3/)
strikes with shader packs. The difference is what the pack has to do: there, the pack rebuilds its
pipeline around an outside controller that owns the viewport. Here the engine already owns the
scaled target and the pack keeps rendering exactly as it does today - the file only says where the
image is.

## Where the file goes

`vitrail-dlss.json`, beside `shaders.properties` (the pack root, next to the `shaders` directory).
Inside a zip or in a directory pack, both layouts are read.

Without the file nothing changes: a recognised Solas 3.9 keeps its built-in contract, every other
pack simply does not get DLSS.

## Schema

```json
{
  "schema_version": 1,
  "name": "My pack 1.2",
  "trigger": { "type": "BEFORE", "pass": "composite13" },
  "inputs": {
    "color": { "src": "colortex0" },
    "motion_vectors": { "src": "camera" }
  },
  "hdr": true,
  "disable_pack_taa": true
}
```

| Field | Required | Default | Meaning |
|---|---|---|---|
| `schema_version` | yes | - | Must be `1`. Anything else is refused and the pack runs untouched. |
| `name` | no | file name | Printed in the load log so a screenshot can name its contract. |
| `trigger.type` | no | `BEFORE` | Only `BEFORE` is read today. `AFTER` is refused with a warning rather than run at a point the pack did not ask for. |
| `trigger.pass` | yes | - | A program name that exists in this pack's chain. Reconstruction runs immediately before that program. |
| `inputs.color.src` | no | `colortex0` | The colour image to reconstruct. `colortex<N>` only. |
| `inputs.motion_vectors.src` | no | `camera` | `camera` means the engine derives vectors from depth and the camera matrices. Any other value is accepted with a warning and camera motion is still used, because a screen-space vector buffer is not consumed yet. |
| `hdr` | no | `true` | Whether the colour above is linear HDR. Informational today; the engine validates the format against what NGX requires. |
| `disable_pack_taa` | no | `true` | Adds `#undef TAA` to `lib/common.glsl`, because the pack's temporal accumulation and the model's would both run over the same frame. Set `false` if your pack's TAA macro is named something else, and disable it yourself. |

## What the pack still has to do

- **Render at the scaled resolution.** The engine already swaps the main target; `viewWidth` and
  `viewHeight` follow it. Do not hard-code the window size.
- **Leave the colour image intact at the trigger.** The engine reads it, runs the model and returns a
  screen-resolution image before the trigger program runs, so that program and everything after it
  see the full-resolution image.
- **Give up the pack's own TAA** (or rename its macro and set `disable_pack_taa` to `false`),
  otherwise two accumulators smear the same frame.

## What it costs

At the trigger, the four programs after it run at screen resolution instead of the scaled one. A pack
that wants the cheapest possible reconstruction should put the trigger as late as it can while the
image is still linear - ideally the program that would have done its tonemap.

## Verifying

The load log prints one line per activated contract:

```
DLSS pack contract: Solas 3.9: before composite13, colortex0, HDR, pack TAA off
```

If the trigger names a program the chain does not have, the load fails loudly with the whole program
list rather than silently drawing without reconstruction:

```
IllegalArgumentException: The declared DLSS trigger composite16 is not one of this pack's programs...
```

## Known limits

- `AFTER` triggers are not read yet; only the program before the trigger.
- Motion vectors are camera-only. Objects that move independently will ghost slightly, which is the
  same limitation the built-in Solas contract has.
- The reconstructed image is written back to the engine's main target, not to an arbitrary
  `colortex`. A pack that wants it in a specific buffer should declare an `endWorld`-style consumer
  instead, which does not exist yet.
