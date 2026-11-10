# DLSS SR development backend

This directory implements an optional Windows x64 NVIDIA NGX/Vulkan backend and an
offscreen GPU smoke test. `DlssBridge` binds its versioned C ABI through Java 25 FFM.
Minecraft 26.3 can now initialize this backend on its own Vulkan device through
an optional local runtime installation. The in-game experimental selector connects
the original Solas 3.9 render chain to temporal DLSS or DLAA. **This is a camera-motion
prototype with color-history rejection, not a completed production DLSS integration.**
Per-object motion vectors and full in-game visual regression remain outstanding.

## Local installation and use

Use Minecraft 26.3, Windows x64, an RTX GPU and the Vulkan backend. Put the mod jar in
the instance's `mods` directory, replacing the older WynnVitrail jar. Put the locally
built `wynn_dlss.dll`, NVIDIA's production `nvngx_dlss.dll` and its license together
under `<game directory>/vitrail/dlss/`, then restart Minecraft. The native bridge is
not embedded in the mod jar. `--enable-native-access=ALL-UNNAMED` is recommended for
Java 25 native access. No files are downloaded by the mod.

Under Video Settings -> WynnVitrail settings, select the experimental upscaler:
FSR1 / normal rendering, DLSS Quality, Balanced, Performance, or native-resolution
DLAA. Default is FSR1. The setting is stored in `vitrail/dlss.properties`; DLSS uses
NGX-recommended dimensions, while the render-scale slider continues to control the
FSR1 fallback. Select the official unmodified Solas 3.9 archive. Pack settings may
be changed; renamed archives and CRLF line endings are accepted, edited GLSL is not.

The tooltip reports backend availability and whether the current pack uses DLSS.
Missing runtime, unsupported hardware or a different pack retain the normal path.
An evaluation error uses spatial presentation for that frame and requests a normal
pack reload on the next frame, restoring Solas TAA. The saved preference is retained;
failure is latched for the current run until explicitly applying a preference again.

Implemented here:

- Query required instance/device extensions before Vulkan object creation.
- Initialize NGX with a project UUID and a caller-supplied runtime directory.
- Check Super Resolution availability and query recommended dimensions per mode.
- Create/evaluate DLSS SR on the caller's Vulkan command buffer.
- Validate HDR/depth/motion inputs and preserve native error codes.
- Retire the feature and shut down NGX after submitted GPU work completes.
- Keep an engine-side jitter/history policy that advances on submitted frames.
- Recognize the official Solas 3.9 source by content, independent of its zip name.
- Request extensions in Minecraft's instance/device creation, initialize a context
  only on a prepared NVIDIA device, and close it before Minecraft destroys the device.
- Record evaluation into the game's command buffer, returning sampled inputs to
  GENERAL layout and making DLSS output visible to subsequent game commands.
- Drain recorded commands before replacing a feature on resize or pack unload.
- Split Solas scene and display targets before composite13, convert packed HDR to
  RGBA16F, and run bloom/tone mapping/final presentation at display resolution.
- Preserve display-sized lens-flare history and generate reconstructed HDR mipmaps.
- Restore the full-sized main target and upsample its raw depth for later game draws.
- Apply centered Halton jitter to Solas geometry, keeping shadow and post passes unjittered.
- Reconstruct non-jittered camera motion from scene depth, with a current-color bias
  mask for changed color, off-screen history, sky and compressed hand depth.
- Commit history after Vulkan submission, and invalidate it on world/view/camera
  changes, teleport, resize, FOV change, paused/interrupted frames and Wynn transitions.
- Provide translated experimental settings, status and normal-renderer fallback.

This does not implement frame generation, ray reconstruction, object motion vectors,
or a universal shader-pack adapter. NVIDIA binaries and shader-pack source are not
stored in this repository or automatically included in the mod jar.

## Build

Requires MSVC x64, Windows SDK, CMake 3.24+, and these reference checkouts:

- [NVIDIA DLSS SDK](https://github.com/NVIDIA/DLSS), revision
  `9981c6350dc58c0ce21f024fd65e31e851c31689`.
- [Khronos Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers), revision
  `2e0a6e699e35c9609bde2ca4abb0d380c0378639` (`v1.4.325`).

From a developer shell, with the SDK paths adjusted:

```powershell
cmake -S native/dlss -B native/dlss/build -G Ninja -DCMAKE_BUILD_TYPE=Release `
  -DCMAKE_TRY_COMPILE_CONFIGURATION=Release `
  -DDLSS_SDK_ROOT=C:/src/DLSS -DVULKAN_HEADERS_ROOT=C:/src/Vulkan-Headers
cmake --build native/dlss/build
```

Place the NVIDIA production `lib/Windows_x86_64/rel/nvngx_dlss.dll` in a development
runtime directory. Use the production SR library, not the RR/FG libraries or the
watermarked development variant. Keep its SDK license with the locally obtained
binary. No installation into Minecraft is performed by these commands.

```powershell
native/dlss/build/dlss_smoke.exe C:/src/dlss-runtime
./gradlew :common:dlssProbe -PdlssBridge=C:/src/wynn_dlss.dll -PdlssRuntime=C:/src/dlss-runtime
./gradlew :common:solasDlssAudit -PsolasDlssPack=C:/packs/Solas-3.9.zip
./gradlew :common:test -PsolasDlssPack=C:/packs/Solas-3.9.zip
```

The smoke executable creates its own offscreen Vulkan device, selects an NVIDIA
GPU, queries Quality dimensions, evaluates real DLSS on constant HDR input, reads
back the output, and checks the RGB values. It exercises no Minecraft rendering.
The Java probe checks DLL loading, C ABI compatibility and extension queries.

FFM requires `--enable-native-access=ALL-UNNAMED` when native access is restricted;
the Gradle probe task supplies it. Missing runtime files or unsupported hardware
produce an explicit failure, never a simulated DLSS success.

## Minecraft device integration probe (26.3, Fabric)

This loads the built mod with Fabric Knot and applies its actual mixins, then uses
Minecraft's Vulkan backend and texture allocator. It does not open a game window,
load a world, or connect to a server. Logs and temporary configuration are under
`fabric/build/mc26.3/dlss-probe-run/`.

```powershell
./gradlew :fabric:dlssGameProbe -PdlssBridge=C:/src/wynn_dlss.dll -PdlssRuntime=C:/src/dlss-runtime
./gradlew :fabric:dlssGameProbe -PdlssProbeMode=disabled
./gradlew :fabric:dlssGameProbe -PdlssProbeMode=missing
./gradlew :fabric:dlssGameProbe -PdlssProbeMode=solas -PdlssBridge=C:/src/wynn_dlss.dll -PdlssRuntime=C:/src/dlss-runtime
./gradlew :fabric:dlssGameProbe -PdlssProbeMode=temporal -PdlssBridge=C:/src/wynn_dlss.dll -PdlssRuntime=C:/src/dlss-runtime
```

The enabled probe clears real HDR/depth/motion textures, records DLSS, and reads
back the reconstructed center pixel through Minecraft's own transfer API. It runs
two frames at each output size (960x540, 1280x720, 960x540), then destroys and
recreates the entire game device and repeats. The other modes require normal
device creation and cleanup with no DLSS context. An enabled probe fails if NGX
initialization, evaluation or readback fails; it cannot pass by taking a fallback.

The `solas` probe additionally exercises the production display-target allocator,
packed-HDR conversion, reconstructed HDR mipmaps, persistent lens-flare history and
raw-depth upsampling. It checks GPU readbacks across the same resizes and two device
lifetimes. It does not execute a complete shader pack or render a Minecraft scene.

The `temporal` probe compiles and executes the production motion/mask shaders and
NGX for Quality, Balanced, Performance and DLAA on two device lifetimes. It checks
static-camera jitter cancellation, the sign and magnitude of X/Y camera motion,
color-change rejection, view-change resets and recovery. These synthetic inputs
do not measure image quality on animated Minecraft geometry.

For the same startup diagnostic in a separately launched development game, use
these JVM options (absolute paths required):

```text
--enable-native-access=ALL-UNNAMED
-Dwynnvitrail.dlss.backend=true
-Dwynnvitrail.dlss.bridge=C:/src/wynn_dlss.dll
-Dwynnvitrail.dlss.runtime=C:/src/dlss-runtime
-Dwynnvitrail.dlss.selfTest=true
```

An explicit `backend=false` disables native loading. With no developer override,
the optional local installation described above is detected before device creation.
The backend and self-test alone do not change TAA or render scale; the in-game
preference enables the supported pack integration.

To exercise the Solas render-chain boundary, additionally set
`-Dwynnvitrail.dlss.solasSpatialProbe=true` and load the official, unmodified Solas
3.9 archive after the backend initializes. Other packs retain their normal path.
This separate switch selects Quality render dimensions, disables Solas TAA and its
old jitter, and runs DLSS with zero motion/jitter and a history reset on every frame.
It preserves composite15's lens-flare bookkeeping. It is a spatial integration
diagnostic distinct from the temporal selector; leave this override off when testing
the player-facing setting. It is retained for isolating the resolution boundary.

## Integration contract and remaining work

The initial native contract is deliberately narrow: RGBA16F linear HDR input and
output, R32F forward depth (0 near, 1 far), RG16F/RG32F current-to-previous motion
in render pixels with top-left origin, and non-jittered motion vectors. Exposure
is managed by DLSS. Input/output images may not alias. Inputs use
`SHADER_READ_ONLY_OPTIMAL`; output uses `GENERAL`. The caller must end any render
pass, arrange Vulkan barriers, submit commands, and retain resources until GPU
completion. Closing a session waits for the device; never close per frame.
`wv_dlss_evaluate_masked` adds an optional separate R8_UNORM input at render size;
the original four-image ABI entry remains available. The Java binding requires the
new symbol, so an older bridge is rejected and the normal renderer remains available.

Solas 3.9 source reference: Modrinth version `IsqhVpb8`, archive SHA-512:

```
754f88b030fc26a6284bc7fdede886af494b227a67e716ce89c8ec5a185f8ad33d0aeac573245c5e39621ac5b24b4778d50ce566647135c2f1b2f63643901c69
```

The reconstruction boundary is before `composite13`, reading HDR `colortex0`:

1. Geometry and early composites run at the DLSS-recommended render size.
2. DLSS reconstructs `colortex0` at display size.
3. `composite13` generates bloom, `composite14` applies DOF/bloom/tone mapping,
   and `composite15` retains its non-TAA bookkeeping at display size.

The experimental setting is not a claim that the following are complete:

- Supply actual per-object/deformation motion for entities, held hands, animated
  materials and Wynn effects. Current motion is camera-only. Color-history rejection
  reduces accumulation on some changed pixels but cannot replace correct object motion;
  same-colored moving objects, transparency and reflections can still ghost.
- Validate the implemented resolution split, per-pass uniforms, postprocessing and
  depth consumers in real gameplay, including later vanilla draws and overlays. Late
  depth consumers currently sample the scaled scene depth; fine depth edges with
  jitter, DOF, motion blur and Distant Horizons need visual verification.
- Validate real device loss, window resizing, world changes and resource reload in
  a complete Minecraft client, beyond the synthetic lifecycle tests.
- Validate real Solas/Wynncraft motion, transparency, glint, emotes and Fruma skies.
- Measure performance and VRAM. No performance gain has been established.

Content recognition is a compatibility guard, not proof of those remaining steps.
An unrecognized or edited pack must retain its normal rendering path.

## Local verification (2026-11-10)

- MSVC 19.44 / Windows SDK 10.0.26100: bridge DLL and smoke executable built with
  warnings treated as errors.
- RTX 3050 Laptop GPU: NGX selected 640x360 for Quality output at 960x540.
  Real DLSS evaluation returned center RGB `(0.249878, 0.499756, 0.749512)` from
  constant HDR input `(0.25, 0.5, 0.75)`; readback test passed.
- Java FFM bridge loading and instance-extension query passed.
- Fabric/NeoForge full Gradle build passed; 123 tests passed, including the external
  official Solas archive fixture, with no skipped tests.
- Minecraft 26.3 + Fabric 0.19.5 + Sodium 0.9.2 on the same RTX 3050: actual mixins
  applied and the game-device probe passed twelve GPU readbacks across two complete
  device lifetimes. Quality sizes were 640x360 -> 960x540 and 853x480 -> 1280x720.
- Both disabled and missing-DLL probe modes passed two device lifetimes with no
  DLSS context and normal Minecraft Vulkan creation/cleanup.
- The Solas boundary probe passed HDR, mipmap, retained-history and depth readbacks
  over twelve frames and two device lifetimes. Source fixtures verified the final
  pass order and TAA removal with lens-flare history retained in all three dimensions.
- The temporal probe passed all four quality modes on two device lifetimes (48
  reconstructed frames with motion, mask and HDR readbacks). CPU tests cover submitted
  history, stale callbacks, interrupted frames, cuts, resize, FOV and preference fallback.
- 53 supplied Solas programs across the three dimensions passed translation and
  shaderc compilation of both stages (106 stages), including geometry and late composites.
- Vulkan validation layers were requested but are not installed on the test host;
  GPU output checks passed, but this was not a validation-layer-clean run.

These checks verify the backend, Minecraft device interoperability, boundary resource
operations and source guard. They do not establish complete in-game shader-pack
integration, animated-scene quality, performance gains, NeoForge runtime
interoperability, or Solas visual parity.
