---
name: slm-diagrams
description: >-
  Author, edit, validate, and explain diagram CNL inside an SLM `## Diagram:` container.
  Use for UML class, sequence, state, activity, use-case, component, and deployment
  diagrams; flowcharts; ER models; BPMN; swimlanes; table nodes; routed connectors;
  layers; and groups. Extends the canonical `slm` skill and covers only the
  diagram-scoped grammar implemented by `DiagramCnlReader` and `DiagramCnlWriter`.
---

# Author SLM diagram containers

Follow the base SLM instructions above first. This section extends them only for a `Diagram:` heading and
the graph sentences in that heading's body.

## Boundary

- In scope: diagram nodes, ports, edges, labels, relations, layers, groups, graph-local
  coordinates, canonical graph write-back, and static diagram error rules.
- Out of scope: the surrounding screen tree, normal `Rectangle`/`Text` children,
  general screen layout, foreign `.drawio`/VSDX import, and image export.
- The grammar activates only when the host registers the diagram container extension.
  In this repository the editor does so through `EditorSlmExtensions`.

Never write `diagram:` YAML. A diagram is always a typed heading plus one physical CNL
line per graph element:

```md
## Diagram: Class Model id class_model 720 by 480 position 40 40

Node class shape «Shape» abstract 180 by 120 position 190 24 field (+ «origin: Point») method (+ abstract «area(): Double»)
Node class circle «Circle» 180 by 100 position 60 220 field (- «radius: Double») method (+ «area(): Double»)
Edge circle_extends_shape from circle to shape relation generalization
```

The heading owns the screen node's id, box size, and position. Body coordinates are local
to that box, with origin at its top-left. Within the body, only `Layer`, `Node`, `Edge`,
and `Group` sentences are active; global SLM nouns are not child nodes.

## Shared lexical rules

- Use one element per line and stable, unique ids.
- Use plain decimal numbers; the writer drops trailing `.0`.
- Put display text in `«…»` or `"…"`.
- Use lowercase hyphenated enum words; `_` is accepted on input but not emitted.
- Use `#RRGGBB` or `#RRGGBBAA`; the last byte is alpha and `FF` is omitted.
- Flags accept boolean synonyms on input; canonical output uses `... yes` or `... no`
  only when the non-default value must be stated.
- Forward references may appear before definitions, but check them only after inventorying
  the complete body.

Canonical body order is `Layer*`, `Node*`, `Edge*`, `Group*`. Parsing tolerates
interleaving, but the first editor rewrite emits canonical order.

## Layers

```text
Layer <id> [«name»] [visible no] [locked yes]
```

Layer sentence order is bottom to top. The name defaults to the id.

```md
Layer wiring «Wiring» visible no
Layer base locked yes
```

## Nodes

General production:

```text
Node <type> <id> <payload-head>
     <width> by <height> position <x> <y> [rotate <degrees>]
     <payload-items>
     {port (...)} [style (...)] {label ...}
     [parent <id>] [layer <id>] [locked yes] [visible no]
```

Size and position are required. Earlier node sentences paint behind later ones within a
layer. `parent` must name a container node. `label «text»` is repeatable; use
`label («text» markdown)` when the label is Markdown.

### Basic shapes

The basic type words are `rectangle`, `rounded-rectangle`, `ellipse`, `text`,
`rhombus`, `triangle`, `hexagon`, `parallelogram`, `trapezoid`, `cylinder`, and
`cloud`. They have no payload fields; use `label` for the caption.

```md
Node rounded-rectangle card 220 by 120 position 40 40 style (fill #F4F7FB corners rounded) label «Draft card»
Node flowchart valid decision 140 by 80 position 320 60 label «Valid?»
```

### Payload types

| Type | Required/optional head after id | Repeatable items after geometry |
| --- | --- | --- |
| `container` | `[title «…»] [collapsed]` | none |
| `swimlane` | `[vertical] [title «…»]` | `lane («Title» [size])` or `lane <size>`; default size 120 |
| `flowchart` | required `process\|decision\|input-output\|terminator` | none |
| `entity` | required `«name»` | `attribute («name» [type «…»] [pk] [fk])` |
| `bpmn` | required `task\|event\|gateway` | none |
| `table` | none | `row`, `col`, then `cell` groups |
| `class` | required `«name»`, optional `stereotype «…»`, `abstract` | `field (...)`, `method (...)` |
| `lifeline` | required `«name»`, optional `actor` | `activation (start end)` in 0..1 |
| `state` | optional `«name»`, `initial\|final\|composite` | none |
| `activity` | required `action\|decision\|fork\|join\|start\|end`, optional `«name»` | none |
| `actor`, `use-case`, `package` | required `«name»` | none |
| `component`, `deployment` | required `«name»`, optional `stereotype «…»` | none |
| `note` | required `«text»` | none |

UML class member visibility is `+` public, `-` private, `#` protected, or `~`
package. Put optional `static` and `abstract` before the quoted member text.

```md
Node class registry «Registry» stereotype «singleton» 200 by 140 position 320 220 field (- static «instance: Registry») method (+ static «get(): Registry»)
Node lifeline client «Client» actor 100 by 360 position 40 40 activation (0.2 0.6)
Node state idle «Idle» 140 by 64 position 40 40
Node state start initial 24 by 24 position 20 60
Node activity approve action «Approve» 140 by 64 position 240 40
Node actor operator «Operator» 100 by 120 position 40 220
Node use-case submit «Submit mission» 180 by 80 position 260 220
Node component frontend «frontend» stereotype «engine» 170 by 56 position 60 380
Node deployment cluster «Mission cluster» stereotype «node» 220 by 120 position 300 360
```

Swimlane and ER examples:

```md
Node swimlane fulfilment vertical title «Fulfilment» 640 by 360 position 20 20 lane («Intake» 140) lane («Review») lane 100
Node entity customer «Customer» 200 by 140 position 40 420 attribute («id» type «UUID» pk) attribute («name» type «String»)
Node entity order «Order» 200 by 140 position 340 420 attribute («id» type «UUID» pk) attribute («customerId» type «UUID» fk)
```

BPMN/flowchart examples:

```md
Node bpmn receive task 140 by 64 position 40 40 label «Receive request»
Node bpmn decision gateway 64 by 64 position 280 40 label «Valid?»
Node bpmn accepted event 56 by 56 position 480 40 label «Accepted»
Node flowchart input input-output 160 by 64 position 40 160 label «Read payload»
Node flowchart process process 160 by 64 position 280 160 label «Validate»
```

### Table payload

Rows and columns are zero-based in cells:

```text
row <height> | row (<height> header)
col <width>  | col (<width> header)
cell (<row> <column> [span <rows> by <columns>] [«label»] [style (...)])
```

Define all rows and columns before cells. Spans must stay in-grid and cells may not overlap.

```md
Node table pricing 360 by 128 position 40 40 row (32 header) row 32 col (160 header) col 100 cell (0 0 «Plans») cell (0 1 «Price») cell (1 0 «Basic») cell (1 1 «9 €»)
```

### Ports

Use a fixed port only when an edge must stay attached to a specific side or point:

```text
port (<id> top|right|bottom|left [offset])
port (<id> at <relative-x> <relative-y>)
```

The default side offset is `0.5` and is omitted. Relative values are normally 0..1.

```md
Node bpmn gateway gateway 64 by 64 position 40 40 port (out right) port (in left 0.25)
```

### Node/edge/cell style

```text
style ([fill #hex] [stroke #hex] [weight N]
       [pattern solid|dashed|dotted] [opacity N]
       [corners sharp|rounded|curved] [sketch] [shadow])
```

Default weight 1, solid pattern, opacity 1, and sharp corners are omitted. A fully
default style group is omitted.

## Edges

```text
Edge <id> from <endpoint> to <endpoint>
     [relation <relation>] [routing <routing>] {via (x y)} {label <label>}
     [style (...)] [arrow source <arrowhead>] [arrow target <arrowhead>]
     [jumps arc|gap|sharp] [mode link|arrow] [animated yes] [layer <id>]
```

Endpoint forms:

| Form | Meaning |
| --- | --- |
| `nodeId` | floating connection on the node perimeter |
| `nodeId.portId` | fixed declared port |
| `(x y)` | free diagram-local point |
| `(node <id> [port <id>])` | explicit form; required if an id itself contains `.` |

Prefer a semantic relation and let it choose notation:

| Relation phrase | Meaning/default notation |
| --- | --- |
| `association [directed]` | plain line; directed adds open target arrow |
| `aggregation` | hollow diamond at source/whole end |
| `composition` | filled diamond at source/whole end |
| `generalization` | hollow target triangle |
| `realization` | dashed line and hollow target triangle |
| `dependency` | dashed line and open target arrow |
| `transition` | state/activity transition |
| `include`, `extend` | use-case relations |
| `message sync\|async\|return\|create\|destroy` | sequence message |
| `er [cardinality to cardinality]` | crow's-foot relation |

ER cardinalities are `one`, `zero-or-one`, `many`, `one-or-many`, and
`zero-or-many`; default is `one to many`. Routing is `straight`, `orthogonal`,
`simple`, `isometric`, `curved`, or `entity-relation`; default orthogonal is omitted.
Each `via (x y)` is a mandatory waypoint.

A short label is `label «text»`. The grouped form is
`label («text» [markdown] [at source|target] [dx N] [dy N])`; use at most one label
per source/middle/target position.

Arrowheads are `none`, `open`, `block`, `block-filled`, `diamond`,
`diamond-filled`, `triangle`, `triangle-filled`, `oval`, `oval-filled`, `cross`,
`dash`, `er-one`, `er-many`, `er-one-or-many`, `er-zero-or-one`, or
`er-zero-or-many`. Override size/inset with `(kind [size N] [inset N])`.

```md
Edge extends from circle to shape relation generalization
Edge owns from drawing to circle relation composition label «owns»
Edge places from customer to order relation er one to zero-or-many label («places» at source dx 4 dy -6)
Edge fixed from gateway.out to service.in routing straight via (420 160) via (420 240)
Edge flow from intake to review relation transition jumps arc mode link animated yes layer wiring
```

## Groups

```text
Group <id> [«name»] members (<id> ...)
```

At least one unique member is required.

```md
Group engine «Engine cluster» members (frontend ir backend_compose)
```

## Complete valid examples

Class diagram:

```md
## Diagram: Shapes Model id class_diagram 560 by 400 position 48 48

Node class shape «Shape» abstract 180 by 120 position 190 24 field (+ «origin: Point») method (+ abstract «area(): Double»)
Node class circle «Circle» 180 by 100 position 60 220 field (- «radius: Double») method (+ «area(): Double»)
Node class registry «Registry» stereotype «singleton» 200 by 140 position 320 220 field (- static «instance: Registry») method (+ static «get(): Registry»)
Node note note_radius «Circle owns its radius.» 160 by 64 position 320 40
Edge extends from circle to shape relation generalization
Edge uses from registry to shape relation dependency
Edge caches from registry to circle relation association directed label «caches»
```

ER diagram:

```md
## Diagram: Orders id orders_er 760 by 360 position 40 40

Node entity customer «Customer» 220 by 140 position 40 80 attribute («id» type «UUID» pk) attribute («name» type «String»)
Node entity order «Order» 220 by 140 position 460 80 attribute («id» type «UUID» pk) attribute («customerId» type «UUID» fk)
Edge places from customer to order relation er one to zero-or-many label «places»
```

## Common failures

- Unknown type or required kind missing: the entire node sentence is dropped.
- Duplicate id: the first element wins and the duplicate is dropped with an error.
- Missing node/port/layer/parent reference or parent cycle: the graph is inconsistent.
- `a.b` used when `a.b` is the node id rather than `node.port`: use `(node «a.b»)` or
  another explicit endpoint form.
- Cell span leaves the table or overlaps another cell: the table is invalid.
- Global `Rectangle`/`Text` sentence placed in the diagram body: it is prose, not a node.
- Raw `diagram:` YAML: it is ignored as graph authoring.
- Trusting tolerant interpretation while a malformed sentence is silently dropped.

Readers may be intentionally tolerant and drop malformed graph elements. Treat every
grammar/reference violation found by the checklist below as a defect.

## Autonomous graph self-check

Reconstruct the graph from the source text before finishing:

- Confirm the `## Diagram:` heading has a display name, unique stable id, positive width
  and height, and an intentional position.
- Scan only until the next same-or-higher heading. Every non-blank body line must begin
  with exactly `Layer`, `Node`, `Edge`, or `Group` and stay on one physical line.
- Build four ordered inventories for layers, nodes, edges, and groups. Require globally
  unique element ids and prefer canonical body order `Layer* Node* Edge* Group*`.
- For every node, identify a documented type, all required payload-head words, positive
  size, and two position coordinates. Check type-specific items against its table.
- For each table, number rows/columns from zero, keep every cell/span inside the grid, and
  reject overlapping cells.
- For each port, require a unique id within its node, a documented side/point form, and a
  sensible 0..1 relative offset.
- For each `parent` and `layer`, locate the target id; follow parent chains to prove there
  is no cycle and that parents are container-capable.
- For every edge, resolve both endpoints. A fixed endpoint must name an existing port;
  an explicit/free endpoint must match its exact grouped form.
- Check each relation, routing, label position, arrowhead, jump, and mode against the
  enumerated words. Preserve `via` waypoint order.
- For every group, require at least one unique member and locate each member id.
- Omit documented defaults, use unitless numbers and `#RRGGBB[AA]`, close every text
  literal/group, and reject raw YAML or global SLM nouns in the body.

Finish by comparing the inventories with the intended picture: every intended box appears
once, every intended connector has two resolved ends, and no authored element is left
unreferenced because of an id spelling mismatch. If the graph cannot be reconstructed
unambiguously, correct the source instead of assuming a host will repair it.
