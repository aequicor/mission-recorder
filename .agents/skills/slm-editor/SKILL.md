---
name: slm-editor
description: >-
  Implement, reason about, and validate editor persistence for CNL-authored SLM: source
  ownership, source-map anchors, surgical value replacement, phrase append,
  whole-sentence re-emission, stable structural create/delete/reorder/reparent, explicit
  ids, and the anti-corruption fidelity veto. Use for `CnlWriter`, `SlmPatcher`,
  structural write-back, or an edit that changes the canvas but not source. Extends the
  canonical `slm` skill; it does not define new CNL grammar.
---

# Preserve SLM through editor write-back

Follow the base SLM instructions above first. The editor must keep CNL as the sole authored surface.
An edit may update source only when a static source/model equivalence audit proves that the
candidate faithfully represents the intended change. Otherwise retain the edit in memory
and leave source byte-identical.

## Boundary

- In scope: ownership/indexing, source anchors, `CnlWriter` tiers, stable ids,
  structural section relocation, fidelity checks, and in-memory fallback.
- Out of scope: inventing new grammar, writing typed YAML, silently replacing a whole
  document, cross-file transactions without explicit support, and annotation Markdown
  patching, which follows the separate annotation-sidecar grammar.
- Diagram graph edits use their registered diagram reader/writer inside the owning
  container; vector and typography edits still pass the same outer fidelity gate.

## Core persistence invariant

For intended document `D'`, original source `S`, and candidate patch `S'`:

```text
S' expresses D' with known CNL and preserves ids/topology  -> propose S'
that correspondence cannot be proved from source/model    -> keep S, keep D' in memory
```

“Faithfully matches” includes the edited fields, stable id set, parent/child topology,
and any operation-specific invariants. In a tool-free environment, prove those properties
by reconstructing the affected source/model with the checklist below. A lossy write is
worse than an explicit in-memory-only edit.

## Source ownership and anchors

The SLM model records source spans and `SlmEditIndex.cnlOwners`. A node is CNL-owned when
its source location belongs to a parsed CNL sentence or typed container heading. Use that
ownership to route edits to `CnlWriter`; do not search by visible text.

Stable explicit ids are the durable identity anchor:

```md
## AutoLayout: Mission Card id mission_card column gap 12 padding 16

Text id mission_title «Mission Control» key mission.title size 20 bold maxLines 1
```

- Keep existing ids byte-for-byte.
- Mint an explicit unique id for every newly persisted structural subtree.
- Never use translated copy or generated content-derived ids as a long-lived edit target.
- Treat paragraph/line source spans as ephemeral addresses; re-read the changed source and
  rebuild your node/section inventory after every accepted patch.
- A heading is the structural anchor for a container subtree. An inline sentence is
  addressable for property edits but cannot own nested sections.

Layout `anchor (...)` is a UI positioning property, not a source anchor. Annotation
`@node` is a sidecar review anchor, also separate from source ownership.

## CNL write-back tiers

Try the cheapest faithful tier first.

### Tier 1: replace a value span

Replace only the token span already owned by the changed property. Preserve every other
byte in the sentence.

```diff
- Rectangle id badge 80 by 24 color #DCFCE7 radius 12 opacity 0.6
+ Rectangle id badge 80 by 24 color #DCFCE7 radius 12 opacity 0.8
```

Typical surgical values are numbers, `#hex`, coordinates in `position X Y`, literal
text content, and other property-owned scalar spans. Do not use a stale span after source
has changed; locate the phrase again in the current sentence first.

### Tier 2: append a missing phrase

When the node owns a CNL sentence but the property has no authored span, append a valid
phrase to the sentence.

```diff
- ## AutoLayout: Mission Card id mission_card column gap 12 padding 16
+ ## AutoLayout: Mission Card id mission_card column gap 12 padding 16 radius 12
```

Parser phrase order is flexible. The deterministic emitter has canonical descriptor order,
but an appended phrase may remain at the end until a later full re-emission.

### Tier 3: re-emit the whole sentence or heading

Use `CnlEmitter.emitSentence` or the stable heading emitter when the first two tiers cannot
represent the change (for example a compound feature/axis map or a property removal).
Include the explicit id and all faithfully renderable existing fields.

```md
Text id status «Nominal» key status.nominal font «Inter» size 12 semibold features (liga on) axes (wght 620) maxLines 1
```

Before accepting whole-sentence output, compare it phrase-by-phrase with the intended node
and the original sentence. If any existing field has no CNL rendering or cannot be accounted
for, veto the candidate. Never substitute a typed YAML block as a fallback.

## Fidelity veto

Apply the veto to every tier, including apparently surgical changes:

1. Draft a candidate source separately; do not mutate the original.
2. Locate the exact owning sentence/section by explicit id and current source span.
3. Parse the candidate mentally with the base/specialist productions: identify its noun,
   each property phrase, parent heading, and extension scope.
4. Compare stable-id inventories and parent topology; reject drift, disappearance,
   duplication, depth overflow, or a changed unrelated sibling.
5. Find the edited node by id in the candidate model and compare every intended field.
6. Apply extra source-level checks for ordered collections, text ranges, instances, media,
   vector networks, interactions, and diagram payloads.
7. Accept the candidate only when every source/model fact is explicitly accounted for.
8. Otherwise leave source exactly unchanged, record no source undo entry, and keep the
   intended working document in memory.

Known veto-provoking cases include an unsupported interaction/easing, unrenderable instance
override, media/vector payload gap, reordered rich-text precedence, a malformed extension
payload, or a structural edit that cannot be anchored safely.

## Structural edits

Structural changes operate on complete heading sections/subtrees rather than scalar spans.
Persist emitted sections with explicit ids and preserve unaffected bytes.

### Create and duplicate

- Emit a stable CNL subtree with an explicit id on every created node.
- Insert under an addressable heading parent at depth `parentDepth + 1`.
- Fail to memory when the resulting ATX heading depth would exceed 6.
- For duplicate, mint new ids for the entire duplicated subtree and rewrite internal
  references that intentionally point inside that subtree.
- Rebuild the id/parent inventory and compare it with the intended new subtree.

Valid inserted subtree:

```md
### AutoLayout: Status Row id status_row row width (fill) height (hug) gap 8

Ellipse id status_dot 8 by 8 color #22C55E
Text id status_label «Nominal» key status.nominal size 12 semibold maxLines 1
```

### Delete

- Locate the exact owning sentence or full heading section by source ownership.
- Remove only that footprint and its structural separator.
- Reject a multi-source or ambiguous delete rather than matching text heuristically.
- Recount surviving ids and trace their nearest shallower headings; relationships must
  remain stable.

### Reorder

- Move the complete source footprint as a unit.
- Anchor insertion relative to addressable sibling sections in the same owning source.
- Preserve relative order of a multi-node block.
- If the required before/after sibling is prose, an unowned splice, or otherwise
  unaddressable, keep the reorder in memory.

### Reparent

- Persist only when old and new parents are addressable and belong to the same source/page
  transaction supported by the writer.
- Adjust heading depth for the whole moved subtree without exceeding level 6.
- Reject moves into self/descendants and any resulting parent cycle.
- Cross-page/cross-source reparent is a multi-source transaction; without an atomic writer,
  keep it in memory and leave every source unchanged.

## Extension-specific persistence

- Diagram: patch the body in canonical `Layer*`, `Node*`, `Edge*`, `Group*` order, then
  rebuild the graph inventory and compare nodes, edges, groups, layers, and references.
- Vector: require paths/network topology, region fills, boolean child order, and masks to
  round-trip; unsupported payloads trigger veto.
- Typography: simple scalar fields may be surgical; `features`/`axes` usually need full
  re-emission. Preserve style-span order and account for canonical link sorting.
- Annotations: patch the sibling sidecar by explicit annotation id; do not route it through
  `CnlWriter` or treat it as SLM.

## Working document and undo caveat

The editor's working document can contain valid in-memory edits that source could not
faithfully encode. Make this state visible to callers; do not claim persistence succeeded.

In the current editor contract, ordinary `undo()`/`redo()` swaps the in-memory document but
does not roll source text back. After accepted write-back, source remains at the last persisted
state even if the canvas is undone. UI messaging must not imply source-history undo exists
until a source-transaction history is implemented.

## Common failures

- Falling back to `node:`/`style:` YAML: typed blocks are not an authoring surface and are
  ignored as node properties.
- Editing by node name or visible text: names/copy are not unique or stable; use id + source ownership.
- Applying a stale token span after another patch: re-read the current sentence and locate
  the phrase again.
- Full-sentence re-emission without preserving id: identity and external anchors can drift.
- Accepting a patch because it looks syntactically plausible: compare every intended and
  preserved field explicitly.
- Reordering around prose/unowned nodes: there is no safe structural anchor.
- Cross-source reparent written as two independent edits: partial success corrupts topology.
- Forcing depth 7: Markdown ATX headings stop at 6; keep the move in memory.
- Treating in-memory fallback as failure of the canvas edit: the edit remains valid; only
  persistence was vetoed.
- Recording source undo for a vetoed candidate: source did not change and must remain byte-identical.

## Autonomous write-back self-check

For every proposed edit, perform this source/model comparison yourself:

- Save the original sentence/section and list its id, parent, ordered children, and all
  authored phrases before drafting the candidate.
- Mark exactly one intended semantic delta. Every other listed field and unrelated byte
  must remain unchanged unless whole-sentence canonicalization is explicitly necessary.
- Confirm the candidate uses a known noun and only documented phrases; close every literal
  and parenthesized group.
- Rebuild the full source id inventory. Require uniqueness, preserve all surviving ids, and
  explicitly account for every minted/deleted id.
- Derive parents from heading depth and nearest shallower heading. Reject cycles, depth above
  6, ambiguous prose anchors, and unsupported cross-source moves.
- For create/duplicate, enumerate all new subtree ids and rewrite only intentional internal
  references. For delete, enumerate the exact removed footprint. For reorder/reparent,
  compare source order and parent maps before/after.
- For typography, check literal/ranges/style order; for vectors, paths/topology/boolean child
  order/masks; for diagrams, all graph inventories and endpoints; for annotations, the
  separate sidecar grammar.
- Check the original source is byte-identical whenever the candidate is vetoed and that no
  source undo entry is claimed.

Finish only when the single intended delta and every preservation invariant can be proven
from the two source texts and their reconstructed models. If proof depends on unavailable
runtime behavior, choose the in-memory-only fallback and say why.
