---
name: slm-vector-graphics
description: >-
  Author, edit, and validate SLM CNL for primitive shapes, reusable icons, SVG/path
  references, inline vector paths, editable vector networks, ellipse arcs, region fills,
  boolean operations, and masks. Use when a `*.layout.md` screen needs vector-native
  graphics or figure editing. Extends the canonical `slm` skill; it does not cover
  diagram graphs or raster image editing.
---

# Author vector graphics in SLM

Follow the base SLM instructions above first. Keep every shape or vector node on one physical CNL
line. This skill covers the figure payload of normal SLM nodes, not `## Diagram:` graph
nodes and not bitmap editing.

## Choose the least complex representation

1. Use a primitive noun for rectangle, ellipse, line, arrow, star, or polygon geometry.
2. Use `icon <ref>` or `svg <ref>` for reusable library/assets.
3. Use one or more `path «d»` phrases when the SLM file owns a small custom path.
4. Use `network (...)` only when vertices/segments/regions must remain structurally editable.
5. Use a boolean-operation heading when the output is defined by nested operand nodes.
6. Add a mask to a normal node when it clips named/following siblings.

Do not encode vector data as typed YAML. Do not invent an operand id list for boolean
operations; source-tree nesting is the operand list.

## Primitive shapes

```md
Rectangle id panel 240 by 120 color #FFFFFF radius 12
Ellipse id status_dot 10 by 10 color $color.status.success
Star id favorite 24 by 24 points 5 inner 0.5 color #F59E0B
Polygon id badge 48 by 48 points 6 color #2563EB
Line id divider 240 by 1 stroke #CBD5E1
Arrow id next_arrow 120 by 24 stroke (color #2563EB weight 3 cap round join round)
```

Supported shape controls:

| Phrase | Applies to | Meaning |
| --- | --- | --- |
| `points N` | star, polygon | point/vertex count |
| `inner N` | star, ellipse | star inner radius or ellipse donut fraction |
| `arc (start sweep)` | ellipse | arc start and sweep in degrees |
| `radius N` | node | corner radius, not vector vertex radius |

Ellipse angle 0 is at 3 o'clock and positive sweep is clockwise in screen coordinates.
An absent arc, or an absolute sweep of at least 360 degrees, represents a full ellipse.

```md
Ellipse id progress_ring 80 by 80 arc (-90 270) inner 0.5 color #F59E0B
```

## Reusable icons and paths

Use a vector noun (`Icon` or `Vector`) and an optional view box:

```md
Icon id alert_icon 24 by 24 icon ds/Icon/Alert viewbox (0 0 24 24) color $color.icon.warning
Vector id brand_logo 160 by 40 svg assets/logo.svg viewbox (0 0 640 160)
```

- `icon <ref>` is a reusable icon/library reference.
- `svg <ref>` is an SVG/path asset reference.
- `viewbox (x y width height)` defines vector coordinates.
- A reference is a bare token. Use ids/paths without spaces.

Use inline path data for source-owned geometry:

```md
Vector id triangle 24 by 24 viewbox (0 0 24 24) path «M12 2L22 20H2L12 2Z»
Vector id donut 100 by 100 viewbox (0 0 100 100) path «M50 6 A44 44 0 1 0 50 94 A44 44 0 1 0 50 6 Z M50 30 A20 20 0 1 0 50 70 A20 20 0 1 0 50 30 Z» evenodd
Vector id plus 24 by 24 viewbox (0 0 24 24) path «M4 12L20 12» path «M12 4L12 20» evenodd
```

Production:

```text
path «SVG path data» [nonzero|evenodd]
```

Repeat `path` for multiple paths. `nonzero` is default and is omitted by the emitter.
Escape a literal closing guillemet or newline inside path text using the base text-literal
escaping rules.

## Editable vector networks

Production:

```text
network (
  vertex (<x> <y> [in (<dx> <dy>)] [out (<dx> <dy>)]
          [mirror angle|angleAndLength] [corner] [radius N]) ...
  segment (<from-index> <to-index>) ...
  region [nonzero|evenodd] loops (<segment-index> ...) ... [fill <solid>] ...
)
```

Write the production on one physical line in the actual SLM file. Vertex and segment
indices are zero-based. Handle values are offsets from the vertex. `mirror angle` keeps
handle directions mirrored; `mirror angleAndLength` mirrors direction and length.
`corner` marks a corner vertex; `radius N` is its per-vertex corner radius.

A region contains one or more loop groups after `loops`. Loop entries are segment
indices. `evenodd` changes the winding rule. Region fills replace object-level fills for
that region; an unfilled region inherits the node's fills. For reliable CNL round-trip,
use only a plain solid `#hex` or `$token` region fill.

Canonical valid example:

```md
Vector id editable_glyph 160 by 160 color #2F9E44 viewbox (0 0 24 24) network (vertex (12 2 in (-7 -3) out (7 3) mirror angleAndLength) vertex (22 20 corner radius 2) vertex (2 20 corner radius 2) segment (0 1) segment (1 2) segment (2 0) region loops (0 1 2) fill #2F9E44)
```

Even-odd/token-fill example:

```md
Vector id token_region 24 by 24 viewbox (0 0 24 24) network (vertex (0 0) vertex (24 0) vertex (24 24) segment (0 1) segment (1 2) segment (2 0) region evenodd loops (0 1 2) fill $color.accent)
```

Readers may tolerate malformed vertices, segments, or regions by dropping them while the
node still exists. Prevent that outcome yourself: count the authored vertices, confirm every
segment index is within that count, and confirm every region loop names existing segments.

## Boolean operations

The operation phrase is:

```text
boolean union|subtract|intersect|exclude
```

The operands are the boolean node's nested child subtree. Because a leaf sentence cannot
own children, author the operation as a heading. Do not add `children (...)`, `operands
(...)`, or any id-list phrase.

```md
## Vector: Combined Mark id combined_mark 160 by 160 color #E64980 boolean union

Ellipse id union_left 90 by 90 position 12 40 absolute
Ellipse id union_right 90 by 90 position 58 40 absolute
```

For subtraction, the first child is the base and later children subtract from it. Preserve
child source order and ids: they are required both for operation semantics and write-back.

## Masks

Production and canonical phrase order:

```text
mask alpha|vector|luminance [clips (<target-id> ...)] [from <source-id>]
```

`clips (...)` must precede `from ...`. `clips` names explicit targets; when targets are
omitted the mask applies according to sibling mask semantics. `from` names an external
mask-source node.

```md
Rectangle id avatar_mask 96 by 96 radius 48 mask alpha clips (avatar_image)
Image id avatar_image 96 by 96 media (asset assets/avatar.png crop)
Rectangle id luminance_source 120 by 80 gradient (linear stops (#000000 at 0) (#FFFFFF at 1))
Image id photo 120 by 80 media (asset assets/photo.png crop)
Rectangle id mask_controller 120 by 80 mask luminance clips (photo) from luminance_source
```

Keep target/source ids stable and locate every referenced id in the same SLM document.
Mask references do not create or move nodes.

## Paint and stroke considerations

- Object `color`/`gradient`/`image` fills apply to the complete primitive/path/network
  unless a network region has its own fills.
- Open paths normally need `stroke`; a fill alone may render nothing useful.
- Use `stroke (... join miter|round|bevel cap ... dash (...))` for explicit path joins.
- `evenodd` belongs after each `path` or after `region`, not after the node.
- Flatten and outline-stroke are editor operations. Persist their result as ordinary
  `path` or `network` phrases; there is no `flatten`/`outline` CNL property.

## Common failures

- Using a diagram `Node ...` sentence for a screen vector: diagram grammar is scoped to
  `## Diagram:` and is unrelated to normal `Vector` nodes.
- Using `Image` for an SVG that must remain editable: use `Vector svg ...` or a network.
- Adding units to view-box/network numbers: use unitless numbers.
- Splitting a long network across physical lines: one sentence must remain one line.
- Invalid vertex/segment indices or region loops: elements are dropped or render incorrectly.
- Using a gradient/image/prop-bearing region fill: the canonical emitter cannot faithfully
  re-emit it; use object-level paint or a plain solid region fill.
- Writing `mask alpha from source clips (target)`: order is wrong; write `clips` before `from`.
- Listing boolean operands in a phrase: operands must be nested children.
- Editing a vector subtree that the emitter cannot round-trip: editor persistence should
  leave source byte-identical under the fidelity veto, not write lossy CNL.

## Autonomous vector self-check

Inspect the authored source without assuming a renderer or parser is available:

- Confirm every vector/shape is one physical line and begins with a base SLM noun.
- For each `viewbox`, count exactly four unitless numbers and require positive width/height.
- For every `path`, find a closed `«…»` literal and allow only the optional trailing
  `nonzero` or `evenodd` winding word.
- Number network vertices from zero. Check each segment's two endpoints against that range;
  then check every loop index against the segment range.
- Require at least one vertex before accepting a network. Preserve vertex, segment, region,
  and loop order exactly.
- Allow network region fills only as plain `#hex` or `$token` solids.
- For every boolean heading, list its nested child operands in source order and require at
  least two; reject any invented operand-id phrase.
- For every mask, require the order `mask <type> [clips (...)] [from ...]`, then locate
  every target/source id in the document.
- Check that all explicit ids are unique and that every referenced asset/token is named
  consistently.
- Reject typed YAML, split sentences, units on numbers, unsupported region paints, and
  any phrase not defined in this guide or the base SLM skill.

Finish only when you can reconstruct the complete figure model from the source inventory:
primitive kind and geometry, paths or network topology, ordered boolean children, paints,
and mask references. If any field would be guessed, simplify or correct the source.
