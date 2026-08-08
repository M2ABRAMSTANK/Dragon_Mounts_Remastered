# Changelog

All notable changes to this fork are documented here. Upstream DMR did not maintain a
changelog; this file starts at the fork's first release. See [`readme.md`](readme.md) for
the fork's status and scope, and [`.fork-notes/`](.fork-notes/) for the full technical
investigation (file:line references, upstream issue/commit history, advisor rulings).

## [1.9.2-community.1] — 2026-08-07

First release of the community fork, picking up DMR 1.9.2 after upstream archival
(2026-01-26). Fixes every open, reproducible defect class in the upstream tracker that
affects dedicated-server multiplayer, plus a security hardening pass on serverbound
packet handling. Full detail and file:line references in the six fork commits
(`c8895ee`..`acf64fa`) and `.fork-notes/fix-plan.md`.

### Fixed

- **Dragon deletion on dimension change / relog / distance** (#123, #41, #64, #124, #90).
  The whistle's stored record of *where* a dragon is went stale on dimension transit
  (the update was gated on an owner lookup scoped to the dragon's old level, which fails
  exactly when riding through a portal or returning Nether→Overworld). A summon then
  couldn't find the dragon — there was no cross-dimension lookup at all — so it silently
  **cloned** the dragon from a stored NBT snapshot with a fresh entity UUID, and a
  join-time duplicate check keyed on entity UUID later deleted whichever copy loaded
  second (usually the original, with all its progression). Cross-dimension summons now
  resolve and **teleport the real, live dragon** (via `changeDimension` /
  `DimensionTransition`) instead of reconstructing it from an NBT snapshot; the old
  entity-UUID mismatch check is replaced by a duplicate-detection guard that never deletes
  automatically (see **Added config** below). A `.orElse(0)` fallback that silently
  collapsed any owned-but-unbound dragon onto whistle slot 0 — deleting it on the next
  duplicate check — is also removed (`getDragonSummonIndex` now returns `OptionalInt`, and
  every call site bails out on an empty result instead of guessing slot 0).
- **Duplicate dragons from whistle-summon** (#125). Root cause was the same
  snapshot-cloning path above: summoning materialized a *new* dragon from stored capability
  NBT while the original entity still existed in an unloaded far chunk. With real teleports
  in place, there is nothing left to clone from and no duplicate to create.
- **Whistle cross-talk / capability sync corruption in multiplayer** (#113, #88). The
  client-side sync handler (`CompleteDataSync`) applied *every* received payload to the
  local player regardless of which player it was addressed to, so another player's
  dimension change or login could push their dragon-ownership capability onto your client,
  making your whistle appear to control their dragon (or vice versa). Payloads are now
  addressee-checked (`payloadPlayerId == localPlayerId`, by entity id) and silently dropped
  if foreign. The mechanism that broadcast a player's capability to every nearby client on
  entity-tracking start (`ModCapabilities.onTrackingStart`) — the deterministic source of
  the corruption — is deleted outright; capability sync now targets only the owning player,
  matching every other sync packet in the mod.
- **Dragon stats pinned to minimum / rerolled** (#127, #128). Reading a dragon's stat
  attributes from a snapshot missing one of the four attribute tags (legacy data, a
  partial/hand-trimmed clone, or the snapshot-respawn path this fork removes) silently
  defaulted to `0.0f`, which mapped to the *minimum* possible stat roll. Missing tags now
  leave the entity's existing rolled value untouched instead of pinning it to the floor.
  `[behavior] enable_random_stats = false` (previously declared but never actually
  consulted) now also actively strips an already-applied random-stats modifier instead of
  merely skipping future rolls.
- **Equipment (saddle / armor / chest) vanishes while the dragon still renders/behaves as
  equipped** (#98). Root-caused to per-dimension inventory storage going out of sync with
  the equipped-state flags during dimension travel. Dragon inventories are now globalized
  into a single overworld-backed store (see **Internal**), removing the split-brain that
  caused it; as a backstop, equipment flags are reconciled against actual inventory
  contents on load and corrected (with a one-time log) if they disagree.
- **World-save crash on dirty-inventory check.** `DragonWorldData.isDirty()` streamed over
  a map that could legitimately hold `null` values and threw an NPE on every world save
  once a null entry existed; both code paths that could write a null entry are also closed.
- **Crash on first whistle-summon for players with legacy save data.** Legacy migration
  code serialized a dimension key via `ResourceKey.toString()` (yielding
  `"ResourceKey[minecraft:dimension / minecraft:overworld]"`), which
  `ResourceLocation.parse` cannot parse and threw on first use; fixed to use
  `.location().toString()`. A related stale-entry bug (legacy `lastSummons` data
  surviving a capability reload when the other three legacy maps were cleared) is fixed
  alongside it.
- **Dead-dragon respawn timers reset on every server restart.** `DragonWorldData.save`
  wrote per-entry fields onto the wrong (outer) tag and appended empty compounds, so
  `deadDragons` / `deathDelay` / `deathMessages` never actually survived a save/load
  round-trip; a related count field used the wrong source list. Respawn countdowns now
  persist correctly across restarts.

### Security

- **Serverbound forgery of clientbound-only sync packets.** Every mod packet was
  registered bidirectional, so a modified client could send `CompleteDataSync` (and eight
  other clientbound-only packet types) to the server, injecting arbitrary dragon-ownership
  capability data — including full dragon NBT — which the server would then rebroadcast to
  nearby clients. Packets now declare a `clientboundOnly` flag; the server rejects and logs
  (sender name/UUID) any serverbound delivery of a clientbound-only packet.
- **Unauthorized state changes via serverbound packets.** Several serverbound handlers
  acted on any entity id the client supplied without checking it belonged to the sending
  player or that the sender owned/tamed the target dragon: a modified client could force
  another player to dismount, or trigger attack/breath/sit-follow-wander state changes on
  any dragon. All four handlers (`DismountDragonPacket`, `DragonAttackPacket`,
  `DragonBreathPacket`, `DragonStatePacket`) now verify ownership (`isTamedFor`) or identity
  (`entityId == player.getId()`) before applying the requested change.
- **Serverbound packet handlers ran on network I/O threads.** Serverbound packet handling
  now runs inside `context.enqueueWork()` (the main server thread) instead of directly on
  the Netty event loop, matching vanilla/NeoForge convention and closing a class of
  thread-safety hazards in handlers that touch world/entity state.

### Added config

Both are additive — **existing config files are untouched**; new keys are written with
their defaults on first load of the updated mod.

- `[whistle] duplicate_resolution = OFF | LOG | AGGRESSIVE` (**default: `LOG`**). Controls
  what happens when the same dragon (by `dragonUUID`) is detected loaded more than once.
  `OFF` never acts. `LOG` (the default) never cancels or removes anything — it only logs
  each detection (dragonUUID, owner name/UUID, both entities' UUID/dimension/position) so
  operators can investigate. `AGGRESSIVE` restores the upstream 1.9.2 behavior of canceling
  the join event for the newer entity. `LOG` is the safe default for this release because
  real teleports mean new duplicates should no longer be created — legacy duplicates from
  worlds played on older versions are surfaced via the new `/dmr duplicates` admin command
  (lists every live same-`dragonUUID` set with owner/dimension/position) for the operator to
  resolve manually, rather than risking automatic deletion of the wrong copy in a world with
  pre-existing duplicates.
- `[behavior] persist_hatched_dragons = true` (**default: `true`**). When enabled, a dragon
  is marked persistence-required (vanilla `PersistenceRequired`) at the moment it hatches,
  so freshly-hatched dragons are exempt from the far-away despawn check that could
  previously delete them before they were ever bound to a whistle.

### Internal

- Dragon inventories (saddle/armor/chest contents) are now stored in a single
  overworld-backed `DragonWorldData` store with lazy per-entry migration on first access,
  instead of separate per-dimension storage with a manual hand-off block run on every
  dimension change. This is the structural fix behind the #98 equipment-loss fix above —
  there is no longer a per-dimension copy that can go stale or get clobbered by a
  duplicated hand-off.
- `DragonInstance` gains an additive, presence-guarded `lastPos` field so a dragon in an
  unloaded chunk can be region-ticketed and re-checked before being treated as gone;
  snapshot-based respawn is now strictly the last resort, gated on both the chunk's block
  status and entity data actually being loaded (these load through separate async
  pipelines — either alone was previously enough to wrongly green-light cloning a dragon
  that was still on disk).
- `ConfigProcessor` gained enum-config support (`defineEnum`), previously only primitives
  were supported.
- Rebuilt the executable regression-test spec: seven new gametests reproducing each fixed
  defect class, committed `required = false` and flipped to `required = true` in the same
  commit as each fix (see `.fork-notes/fix-plan.md` Wave 0). CI now floor-asserts at least
  50 registered gametests run, and rethrows test-framework registration failures instead of
  silently reporting a green build with zero tests executed.
- `spotlessCheck` re-enabled in the build (previously the build silently reformatted via
  `spotlessApply`, hiding formatting drift from diffs).

[1.9.2-community.1]: https://github.com/M2ABRAMSTANK/Dragon_Mounts_Remastered/releases/tag/1.9.2-community.1
