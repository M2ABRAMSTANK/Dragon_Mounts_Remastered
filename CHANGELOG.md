# Changelog

All notable changes to this fork are documented here. Upstream DMR did not maintain a
changelog; this file starts at the fork's first release. See [`readme.md`](readme.md) for
the fork's status and scope, and [`.fork-notes/`](.fork-notes/) for the full technical
investigation (file:line references, upstream issue/commit history, advisor rulings).

## [1.9.2-community.2] — 2026-08-09

Two operator bug reports against the `community.1` RC: summoning with insufficient room
failed with no feedback, and cross-dimension summons could still mint a duplicate dragon
under a chunk-loading race. Full detail in `.fork-notes/wave5-spec.md`.

### Fixed

- **Silent summon failure.** `DragonWhistleHandler.summonDragon` returned `void`, so every
  failure reason (no space, no dragon, riding, on cooldown, dead/respawning, not found,
  teleport blocked) was computed and then discarded. Worse, the radial whistle-menu path
  (`DragonCommandPacket`) sent an unconditional "whistle" action-bar on every attempt,
  which — landing in the same tick as any failure message — always won the client's
  last-message-wins action-bar rendering and silently swallowed it. `summonDragon` now
  returns whether the summon actually succeeded, and the unconditional action-bar is only
  sent on success; several previously-bare `return`s (a whistle-less player, a desynced
  binding, a snapshot respawn that can't be confirmed) now message the player instead of
  failing silently, reusing the existing `dmr.dragon_call.*` lang keys.
- **Vacuous entity-residency gate could still mint a snapshot clone** (a `community.1`-era
  regression of #125). The snapshot-respawn "is the dragon really gone" check only probed
  the single chunk the summon path itself had just force-loaded via its region ticket —
  proving the ticket worked, not that the dragon was absent from the wider area the rescue
  scan actually reads. The gate now requires EVERY chunk in that scanned area (up to 9x9
  chunks around the dragon's last known position) to have positively reached entity-loaded
  status before an empty read anywhere in it is trusted; the decision logic is extracted
  into a pure, unit-tested helper (`decideSnapshotRespawn`). The region ticket radius that
  holds those chunks open is widened (2 -> 4 chunks) to actually cover the area the gate and
  rescue scan both read, and the deferred-summon timeout that used to green-light a clone on
  expiry is both widened (20 -> 60 ticks, a cold chunk load can exceed 1 second) and now
  fails safe on expiry (a message, never a clone).
- **Malformed-dimension crash/clone hole in three more readers.** Wave 1 fixed the one
  *writer* that produced legacy `ResourceKey#toString()` dimension strings
  (`"ResourceKey[minecraft:dimension / minecraft:overworld]"`); three summon-path *readers*
  (plus a fourth introduced with Wave 2's cross-dimension work) still parsed that string
  unguarded and would throw. All four now go through one `resolveStoredLevel` helper that
  treats a malformed or unknown dimension as "unknown" — logged once, never thrown, never
  treated as a reason to clone.
- **`/dmr recall` was a second clone-minting vector.** The recall command rebuilt a dragon
  from its history snapshot without checking whether a live entity with that `dragonUUID`
  already existed somewhere. It now sweeps all loaded levels first and refuses (naming the
  live entity's dimension and position) instead of minting a copy.
- **Snapshot-clone self-healing.** On the rare occasion a clone is minted anyway (a race the
  gate above narrows but cannot fully close — see `reclaim_snapshot_clones` below), it no
  longer has to persist forever: the entity minted by the snapshot-respawn path (and by
  `/dmr recall`'s rebuild path) is now flagged with clone provenance at creation. If the
  join-time duplicate check ever finds two live same-`dragonUUID` entities where EXACTLY
  ONE carries that flag, it can PROVE which one is the clone (never guess from binding
  staleness, the destructive pre-Wave-2 `AGGRESSIVE` failure mode) and reclaim it: the
  flagged clone is discarded (skipped, log-only, if it currently has a passenger) and the
  whistle binding is re-pointed at the proven original — on the next server tick, never
  synchronously inside the join event.

### Added config

Additive — existing config files are untouched; the new key is written with its default on
first load of the updated mod.

- `[whistle] reclaim_snapshot_clones = true` (**default: `true`**). Enables the self-healing
  reclaim described above. Entirely separate from `duplicate_resolution` (unchanged this
  release): it never runs when `duplicate_resolution = AGGRESSIVE` is active (that mode's
  existing join-cancel behavior is left alone to avoid a double-removal race), and even when
  it does run it only ever discards an entity PROVEN to be a snapshot clone, never guesses,
  and never removes a dragon carrying a passenger.

### Internal

- Wave 5 regression tests added to `CommunityRegressionTests` (return-value plumbing,
  reclaim + its passenger-guard variant, the honest-gate decision table as a plain JUnit
  unit test, and the malformed-dimension guard).

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
