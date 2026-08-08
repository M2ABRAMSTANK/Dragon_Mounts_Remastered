# Dragon Mounts Remastered — Community Fork

> **This is a community-maintained continuation of DMR 1.9.2**, published after the upstream
> [Wyrmheart-Team/Dragon_Mounts_Remastered](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered)
> repository was archived on 2026-01-26. Upstream is read-only; no new fixes will land there.
> This fork picks up exactly where 1.9.2 left off and fixes the multiplayer/dedicated-server
> bugs — dragon deletion on dimension change, whistle cross-talk, duplicate dragons, stat
> corruption, and equipment loss — that were open and unresolved at archival time.

## Status

Maintained. Actively fixing the dedicated-server-multiplayer defect classes documented in
the upstream issue tracker (frozen but still readable). Not a rewrite or a fork-and-forget —
every change is scoped to a specific upstream issue and ships with a regression test.

## Fixed in this fork (1.9.2-community.1)

| Upstream issue(s) | Symptom | Status |
|---|---|---|
| [#123](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/123), [#41](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/41), [#64](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/64), [#124](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/124) | Tamed/untamed dragons deleted on dimension change, relog, or distance ("last entity id mismatch") | Fixed — dragons are now teleported, not cloned-and-orphaned |
| [#125](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/125) | Whistle-summon creates a duplicate dragon while the original still exists | Fixed — real teleport instead of NBT-snapshot cloning |
| [#113](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/113), [#88](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/88) | Whistle cross-talk between players; capability sync corruption in multiplayer | Fixed — sync payloads are now addressee-checked and dropped if foreign |
| [#127](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/127), [#128](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/128) | Dragon stats pinned to minimum / reroll when bred, hatched, or re-entering the Nether | Fixed — missing attribute tags no longer pin stats to the minimum roll |
| [#98](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/98) | Saddle/armor/chest items vanish while the dragon still renders/behaves as equipped | Fixed — root causes closed (inventory globalized off level-scoped storage; equipment flags reconciled against actual inventory contents on load) |
| Security hardening | Serverbound packets applied unauthorized state changes (dismount/attack/breath/state on any dragon or player; clientbound-only sync packets accepted from clients) | Fixed — ownership/addressee checks added on every serverbound handler; clientbound-only packets are rejected server-side |

Full technical writeup with file:line references and upstream commit history lives in
[`.fork-notes/`](.fork-notes/) and [`CHANGELOG.md`](CHANGELOG.md).

## Installing this fork

- **Drop-in replacement** for DMR 1.9.2 — same modId (`dmr`), same data format. No world or
  quest-progress migration needed.
- Use the **identical jar on both client and server** — this is a server-authoritative
  multiplayer fork; running mismatched client/server jars is unsupported.
- Version string: `1.9.2-community.N` (N increments per fork release; distinguishes fork
  builds from upstream `1.9.2` in logs and crash reports).

## Distribution

**GitHub Releases only** — this fork is **not** published to CurseForge or Modrinth. Always
download from this repository's [Releases](../../releases) page.

## License

Inherited from upstream: **PolyForm Noncommercial License 1.0.0**. Noncommercial use only —
see [`LICENSE.md`](LICENSE.md) for the full terms. This fork does not relicense or add
additional restrictions.

## Known limitations

- **[#112](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/112) — invisible
  hatching eggs with Continuity: OUT OF SCOPE.** Not reproducible with DMR + Continuity alone;
  requires an unidentified third mod present in Better MC 5. The Continuity developer has
  acknowledged the interaction but declined to treat it as a Continuity-side issue. Cosmetic
  (client-render only); no server-side fix is possible without identifying the third factor.
- **[#111](https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/111) — invisible
  dragon after a long-range whistle summon + relog: expected fixed, pending live-server
  verification.** The `teleportTo`-routed arrival positioning in this fork's cross-dimension
  summon rework addresses the known cause (tracker desync after a snapshot-cloned respawn);
  this is believed resolved now that summons teleport the real entity, but has not yet been
  confirmed against a live long-range repro on a dedicated server.
- **Legacy duplicate dragons in existing worlds are LOGGED, not auto-deleted.** Worlds
  created under older DMR/fork versions may already contain duplicate dragon entities from
  the historical bug. This fork ships with `duplicate_resolution = LOG` (never removes
  entities automatically — see [`CHANGELOG.md`](CHANGELOG.md)). Operators should run
  `/dmr duplicates` to list live duplicate sets (owner, entity UUIDs, dimensions, positions)
  and resolve them manually.

---

Dragon Mounts Remastered (DMR) is a Minecraft mod that allows players to tame, ride, and breed dragons. It builds upon the foundation of the original Dragon Mounts mod by BarracudaATA, adding new features, mechanics, and enhanced visuals.

## Table of Contents

- [Features](#features)
- [Installation](#installation)
  - [Requirements](#requirements)
  - [Setup](#setup)
- [Usage](#usage)
- [Contributing](#contributing)
- [License](#license)
- [Contact](#contact)
- [Acknowledgements](#acknowledgements)

## Features

- **Enhanced Dragon Models**: Improved models and animations for a more immersive experience.
- **Summon Dragons**: Use a crafted whistle to summon your dragon, even across dimensions.
- **Dragon Commands**: Control your dragon's behavior—sit, follow, or wander.
- **Unique Dragon Types**: Discover elemental dragons with distinct abilities.
- **Dragon Armor**: Equip dragons with armor for better defense.

## Installation

### Requirements

- **Minecraft Version**: 1.20.4 or 1.21.1
- **Mod Loader**: NeoForge
- **Dependency**: [GeckoLib](https://www.curseforge.com/minecraft/mc-mods/geckolib)

### Setup

1. **Install NeoForge**: Download and install NeoForge for your Minecraft version.
2. **Install GeckoLib**: Download the GeckoLib mod and place it in your `mods` folder.
3. **Download DMR**: Get the latest version of Dragon Mounts Remastered from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/dmr) or [Modrinth](https://modrinth.com/mod/dmr).
4. **Add to Mods Folder**: Move the downloaded DMR `.jar` file into the `mods` folder.
5. **Launch Minecraft**: Start Minecraft with the NeoForge profile to load the mod.

## Usage

- **Finding Eggs**: Dragon eggs can be found in various structures around the world, encouraging exploration. 
- **Hatching Dragons**: When you have found a dragon egg, you can place it down and interact with it to start hatching.
- **Taming Dragons**: Feed baby dragons with fish. Some community made dragon breeds may have other taming items.
- **Riding Dragons**: Equip adult dragons with a saddle, then right-click to ride and control them.
- **Binding Whistle**: The dragon whistle can be bound to a dragon by holding it in your hand and shift right-clicking the dragon.
- **Summon Dragon**: Once you have bound a dragon whistle to your dragon you can use it to summon your dragon.

## Contributing

We welcome contributions! To contribute:

1. **Fork the Repository**: Click the **Fork** button on GitHub.
2. **Create a Branch**: Create a feature branch for your changes.
3. **Submit a Pull Request**: Push your changes and submit a PR for review.

Please follow coding conventions and include clear commit messages.

## License

This project is licensed under the PolyForm Noncommercial License 1.0.0.

You may use, modify, and share this code for non-commercial purposes only.
See the [LICENSE](LICENSE.md) file for details.

## Contact
- **Discord**: Join our [Discord Server](https://discord.gg/3XknsXtKYR) for community support.

## Acknowledgements

This mod is based on the original **Dragon Mounts** mod by [BarracudaATA](https://www.minecraftforum.net/members/BarracudaATA).
