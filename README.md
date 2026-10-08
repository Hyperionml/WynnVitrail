<p align="center">
  <img src="common/src/main/resources/vitrail.png" width="128" alt="">
</p>

<h1 align="center">WynnVitrail</h1>

<p align="center">
  <a href="https://github.com/avpbynf/Vitrail-Shaders">Vitrail</a>, adapted to Wynncraft. Minecraft 26.3.
</p>

---

## What this is

**[Vitrail](https://github.com/avpbynf/Vitrail-Shaders) runs OptiFine-format shader packs, unmodified, on
Minecraft's native Vulkan renderer.** It knows nothing about Wynncraft and does not need to: a pack
draws the world it is handed.

What it cannot know is that this one server hides things in that world. A weapon's effect number is
written into its mesh's vertex colour. A sky's identity is three bytes in one texel of the art its dome
is textured with. Armour is drawn by the client onto a body the server placed, at the size of a body it
did not place. A pack asked to draw all of that without being told gets all of it wrong, and gets it
wrong quietly.

**So this fork tells it.** It is the same adaptation
[WynnIris](https://github.com/clpotvin/WynnIris) makes to Iris, ported onto this engine: the effects
are woven into the pack's own programs rather than drawn over them, and what cannot be a program is a
pass of the engine's own.

If you are not playing on Wynncraft, use [Vitrail](https://github.com/avpbynf/Vitrail-Shaders). On any
other server none of the signals below can be found, so this builds a jar that behaves as upstream
does and carries a few hundred lines nothing calls.

## What the adaptation adds

- **The glint.** All 32 of Wynncraft's item effects, drawn over whatever colour the pack already
  decided, under any pack.
- **The skies.** Seven procedural skies drawn where the dome is, read off the marking the art carries
  - and the scene under one of them tinted, darkened and fogged towards the sky in the direction each
  piece of ground lies in, so the horizon meets the dome instead of cutting against it.
- **Entities and letters lifted back out of that darkening**, because a mob in a storm was being
  darkened along with the ground it stood on, and a sign's letters kept describing a world that was no
  longer on screen.
- **The Mist Woods**, whose close fog a pack draws or does not draw, put back as a pass over the
  finished picture.
- **The transition screens**, which arrive as a text display carrying a private-use character under a
  font no shader can see, caught on the entity and painted from the pattern the font meant.
- **Smaller things**: a beacon another mod has hidden no longer casts a shadow, and every effect
  animates on the world's own day with the frame's fraction of a tick on it rather than on a whole-tick
  clock.

## Vibe coded

**The Wynncraft half of this fork was written by an AI agent, and it is worth saying so rather than
leaving it to be inferred from the commit history.** The code, the tests and the commit messages were
written by DeepSeek Harness driving an agent that read the WynnIris source a file at a time and wrote
what it found into this tree. The decisions were a person's: what to port and in what order, which pack
to test against, and what the game actually did when it ran.

That is a different way to build software from the way the engine underneath was built, and the failure
modes are different in a way worth naming rather than hiding. An agent writes two hundred lines of
transcription that a compiler accepts and that nothing can check until somebody stands in front of the
picture. It also writes assertions about its own code, and an assertion that is false is worse than no
assertion at all, because it is a claim that the thing was checked. Both have happened here and both
are in the commit history with their fixes: a uniform block allocated one matrix short, which took a
session down the first time a Wynncraft sky arrived and was found from a player's log, and a test
asserting a partition the reference does not have.

WynnIris, Vitrail and Iris were written by people. What they contributed is credited below and in
[NOTICE](NOTICE); the Wynncraft half of this fork is the part that was not.

## Requirements

- **Minecraft 26.3**, and only 26.3. The jar refuses the next game version and the one before it.
- **Fabric or NeoForge**, and **[Sodium](https://modrinth.com/mod/sodium)**, which the metadata asks
  for by version range.
- **The Vulkan backend.** Options, then Video Settings, then restart onto it.
- Client only.

## Installing

Put the jar in `mods/` next to Sodium and restart. Shader packs go in `shaderpacks/` as they always
have and are picked from WynnVitrail's own settings screen.

The Wynncraft half is **on by default**, and one file turns it off: `wynnvitrail/no-wynncraft` in the
game directory, read again at every pack load. `-Dwynnvitrail.enabled=false` outranks the file, for a
launcher that wants the state named in its own argument list. Whichever way it was decided, the engine
says which state it is in at every load, so that a picture can name the state it was drawn under.

## Building

```
gradlew build
```

One jar for both loaders lands in `build/libs/`, beside the two each loader reads separately. The game
is fixed at 26.3 in `gradle.properties`. `-Pminecraft=26.2` points the build at the other game and
nothing promises that one compiles, because this fork's Wynncraft code is written against 26.3's names
where the shared tree around it is written against 26.2's.

## What is not in yet

Stated rather than left to be found, because the port is unfinished and a reader deserves to know which
half they are looking at.

- **The mount armour overlay's hooks.** The arithmetic is in and tested - which limb a quad belongs to,
  where every face of every piece lands in a mount's skin, what each of the 32 effects does to a texel,
  which armour set and which effect an item carries - and nothing calls any of it yet.
- **Ambience**, WynnIris's per-region shader packs, which needs a way to change packs by name at
  runtime that this engine does not have.
- **The Wynncraft settings screens.** WynnIris's sixteen settings are constants here, at the values
  they ship at, and the single file switch above is the whole of what a player can change.

[CHANGELOG.md](CHANGELOG.md) says what each revision changed for a player, and it is the authority on
what is in a given jar rather than this list, which is a summary and will go stale.

## Licence and credits

LGPL-3.0-only, in [LICENSE](LICENSE), with [GPL-3.0.txt](GPL-3.0.txt) beside it because version 3 of
the Lesser GPL is written as permissions on top of the ordinary GPL rather than as a standalone
document.

Three projects are behind this one, in the order they were built:

| | |
| --- | --- |
| **[Vitrail](https://github.com/avpbynf/Vitrail-Shaders)**, by avpbynf | the engine. OptiFine-format packs on the Vulkan backend, and the whole of the translation, the target plan, the uniform catalogue and the frame. This is a fork of it with the same licence. |
| **[WynnIris](https://github.com/clpotvin/WynnIris)**, by clpotvin | the adaptation. Every threshold, table, formula and effect this fork's Wynncraft half is written from, read at the 26.3 tree and cited by file and line in the comments of the files that carry it. |
| **[Iris](https://github.com/IrisShaders/Iris)** | the reference both of them answer to, and the source of material Vitrail already credits. |

WynnIris is published with the Iris developers' permission and is not official Iris. Neither is this.
Do not report a bug in this fork to the Iris developers, or to Vitrail's author, or to WynnIris's.

---

**Everything below this line is Vitrail's own README**, kept in place so that what this fork came from
is stated by the project it came from rather than paraphrased here. Where it says 26.2, or names a
build command, it is describing upstream: this fork builds 26.3 by default and makes no promise about
26.2.

<h1 align="center">Vitrail Shaders</h1>

<p align="center">
  OptiFine-format shader packs, on Minecraft's native Vulkan renderer.
</p>

<p align="center">
  <a href="https://www.curseforge.com/minecraft/mc-mods/vitrail-shaders"><img src="https://img.shields.io/curseforge/dt/1649385?style=flat-square&logo=curseforge&logoColor=white&label=CurseForge&color=F16436" alt="Vitrail on CurseForge, with its download count"></a>
  <a href="https://modrinth.com/mod/vitrail-shaders"><img src="https://img.shields.io/modrinth/dt/oSIKhgz3?style=flat-square&logo=modrinth&logoColor=white&label=Modrinth&color=00AF5C" alt="Vitrail on Modrinth, with its download count"></a>
  <a href="https://ko-fi.com/B1H225VJC4"><img src="https://img.shields.io/badge/support-Ko--fi-FF5E5B?style=flat-square&logo=kofi&logoColor=white" alt="Support Vitrail on Ko-fi"></a>
</p>

---

<p align="center">
  <img src="docs/images/screenshot-mountains.jpg" alt="Snow-capped mountains over a cherry grove and a savanna, a mushroom island out at sea, under volumetric clouds, rendered on the Vulkan backend" width="830">
</p>
<p align="center">
  <sub>An OptiFine-format pack, running unmodified on the Vulkan backend.</sub>
</p>

<details>
<summary>More screenshots</summary>
<br>
<p align="center">
  <img src="docs/images/screenshot-savanna-sunset.jpg" alt="The sun setting over a savanna and a lake, fog lying on the water, rendered on the Vulkan backend" width="830">
</p>
<p align="center">
  <img src="docs/images/screenshot-ocean-ruins.jpg" alt="Sunken ruins on the sea floor, drowned walking through the light shafts, a school of tropical fish beside them, rendered on the Vulkan backend" width="830">
</p>
<p align="center">
  <img src="docs/images/screenshot-lush-cave.jpg" alt="A lush cave under a cliff, a shaft of sunlight falling through the opening onto glow berries and dripstone, rendered on the Vulkan backend" width="830">
</p>
</details>

Minecraft 26.2 ships a native Vulkan renderer alongside the OpenGL one. Every
shader pack that exists was written for OpenGL, and none of them run on it.

**Vitrail runs them anyway, unmodified.** It reads an OptiFine-format pack out
of `shaderpacks/`, translates its GLSL once when the pack loads, and hands it to
the compiler the game already embeds. Nothing translates while a frame is drawn.

It started as a question, whether packs written for OpenGL over more than a
decade could run untouched on the renderer that now ships with the game. It is
one person's side project, worked on every day since July, and an early one:
the backend it runs on is marked experimental by the game itself.

It is built the way a lot of software gets built now: AI tools do a real share
of the typing and the debugging, and a human decides, tests against real packs
and against Iris, reviews every line and carries the blame for every bug. What
was taken from Iris and from Kroppeb's stareval is credited file by file in
[NOTICE](NOTICE), under the same licence. If any of that matters to you, now you
know. If the mod is useful, there is a coffee link below, and the issues here
are where I answer.

## Quick start

- One jar for Fabric and NeoForge, on Minecraft 26.2, and one for 26.3 built
  from the same tree with `gradlew build -Pminecraft=26.3`. The 26.2 jar is on
  [CurseForge](https://www.curseforge.com/minecraft/mc-mods/vitrail-shaders), on
  [Modrinth](https://modrinth.com/mod/vitrail-shaders) and on every
  [release](https://github.com/avpbynf/Vitrail-Shaders/releases) here.
- Put it in `mods/` next to Sodium. Client only.
- Switch the game to Vulkan, in Options then Video Settings, and restart it.
- Packs go in `shaderpacks/` as they always have, and are picked from Vitrail's
  own settings screen.

[INSTALL.md](INSTALL.md) has the versions this needs, the Chloride settings that
decide what reaches your pack, and what a game that came up on the wrong backend
looks like.

## What goes through your pack

Terrain, moving blocks, water, shadows, sky, clouds, weather, particles, mobs, block entities,
the held hand, and the far terrain of Distant Horizons given a build of that mod
that draws on this backend, which [Other mods](INSTALL.md#other-mods) names. The
settings screen reads the pack's own menu layout, and a resource pack's normal
and specular maps are served beside the blocks they belong to.

The rest still comes from the game, and that set moves from one release to the
next: the engine logs which families do when a place first draws, and
[pack compatibility](docs/compatibility.md) starts from what you are seeing and
names the cause. If what you want today is a finished picture, use
[Iris](https://github.com/IrisShaders/Iris) on the OpenGL backend instead, which
is the reference this engine is checked against.

## Read more

| If you want to | Read |
| --- | --- |
| Know why the format is OptiFine's, and how this sits next to Iris, Sulkan and Aperture | [Why this exists](docs/why.md) |
| Work out why your pack looks wrong, starting from what you see | [Pack compatibility](docs/compatibility.md) |
| See what changed from one version to the next | [CHANGELOG.md](CHANGELOG.md) |
| Install it, and know what it refuses to run beside | [INSTALL.md](INSTALL.md) |
| Understand how any of this works | [The documentation](docs/README.md) |

## Support

<a href="https://ko-fi.com/B1H225VJC4"><img src="https://storage.ko-fi.com/cdn/kofi3.png?v=6" width="220" alt="Buy Me a Coffee at ko-fi.com"></a>

## Contributing

Open an issue before writing anything substantial. I order the work by risk, and
code that lands ahead of what can be verified is hard to accept however good it
is.

[CONTRIBUTING.md](CONTRIBUTING.md) opens on the short version, what refuses a first
push, and carries the rest.

## Licence

LGPL-3.0-only, in [LICENSE](LICENSE), with [GPL-3.0.txt](GPL-3.0.txt) beside it
because version 3 of the Lesser GPL is written as permissions on top of the
ordinary GPL rather than as a standalone document.

Parts of the pack loader and of the value catalogue are adapted from Iris, which
is LGPL-3.0 as well. What was taken and what was changed on the way is recorded
in [NOTICE](NOTICE).
