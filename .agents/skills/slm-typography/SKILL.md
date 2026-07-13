---
name: slm-typography
description: >-
  Author, edit, and validate SLM CNL text nodes, source-locale copy, i18n keys and
  bindings, typography, text sizing, truncation, lists, shared text styles, rich-text
  style spans, and links. Use for translation-sensitive UI text or typography write-back.
  Extends the canonical `slm` skill and documents only the typography subset that the
  current CNL parser and emitter can round-trip.
---

# Author text and typography in SLM

Follow the base SLM instructions above first. A text node is a normal one-line CNL sentence. Literal
visible copy belongs immediately after `Text` or `Button`; `characters` is reserved for
bound content.

```md
Text id page_title «Mission Control» key missionDashboard.title font «Inter» size 24 bold line-height 32 width (fill) height (hug) maxLines 1
Button id create_button «Create mission» key actions.create font «Inter» size 14 semibold maxLines 1 onClick navigate (missions/new)
```

## Boundary

- In scope: literal and bound characters, i18n keys, base typography phrases, shared
  text-style refs, sizing/overflow policy, lists, style spans, and URL/node links.
- Out of scope: font file installation, renderer font fallback implementation, live text
  editing UI, runtime localization services, and typography fields without CNL phrases.
- The typed typography model is broader than the authorable CNL subset. Do not invent
  phrases for italic, decoration color/style/thickness, skip-ink, leading trim, hanging
  punctuation, small caps, superscript, or subscript.

## Literal copy, key, and binding

Literal source-locale text:

```text
Text «visible copy» key <stable.i18n.key>
Button «visible copy» key <stable.i18n.key>
```

Use a language-neutral, stable key. Keep the frontmatter `sourceLocale` equal to the
copy's language and list all expected `targetLocales`. Treat the authored literal/key pair
as the source-locale resource entry.

Bound content replaces literal characters at runtime:

```md
Text id mission_name characters {{mission.name}} key mission.name font «Inter» size 18 semibold width (fill) height (hug) maxLines 2
Text id counter characters $selectedCount font «Inter» size 14 maxLines 1
Text id component_label characters $prop.label font «Inter» size 14 maxLines 1
```

Supported binding forms are `$variable`, `{{data.expression}}`, and `$prop.name`.
Never write `characters «literal»`; literal `characters` does not re-emit and is not the
source-copy form.

## Typography phrases

| Meaning | Phrase | Example |
| --- | --- | --- |
| i18n key | `key <key>` | `key mission.title` |
| font family | `font «family»` | `font «Inter»` |
| font size | `size N` | `size 16` |
| common weight | `bold`, `semibold`, `thin` | `semibold` |
| arbitrary weight | `weight N` | `weight 500` |
| line height | `line-height N` or `line-height N%` | `line-height 140%` |
| letter spacing | `tracking N` or `tracking N%` | `tracking 2%` |
| paragraph spacing | `paragraph-spacing N` | `paragraph-spacing 8` |
| horizontal align | `text-align left\|center\|right\|justified` | `text-align center` |
| vertical align | `text-valign top\|center\|bottom` | `text-valign center` |
| case transform | `case upper\|lower\|title` | `case upper` |
| decoration | `decoration underline\|strikethrough` | `decoration underline` |
| OpenType flags | `features (<tag> on\|off) ...` | `features (liga on) (kern off)` |
| variable axes | `axes (<tag> N) ...` | `axes (wght 620) (wdth 95)` |
| shared style | `text-style $<id>` | `text-style $typography.body` |

`features` and `axes` use one parenthesized pair per entry, without an extra outer
group. Entries are emitted sorted by tag. Friendly axis input names map to standard tags:
`weight→wght`, `opticalSize→opsz`, `width→wdth`, `slant→slnt`, `italic→ital`.
Prefer explicit four-character tags in generated SLM.

The writer canonicalizes weight 700/600/300 as `bold`/`semibold`/`thin`; other
weights remain `weight N`.

## Text box sizing and overflow policy

Typography is not enough to make a stable layout. Every bounded visible text should state
width/height intent and line policy.

| Intent | Recommended phrases |
| --- | --- |
| one-line label | `width (hug) height (hug) maxLines 1` |
| one-line label filling a row | `width (fill) height (hug) maxLines 1` |
| wrapping body copy | `width (fill) height (hug) maxLines 3` |
| ellipsized fixed/fill box | `width (fill) height 40 truncate 2` |
| content-driven text box | `autosize both` |
| fixed width, growing height | `width 320 autosize height` |

`maxLines N` limits lines without ellipsis. `truncate N` limits lines with ellipsis.
Choose one, not both. `autosize height` grows vertically; `autosize both` grows in both
axes. Validate with the longest target-locale strings, not only the source language.

```md
Text id card_title «Orbital insertion burn» key mission.card.title font «Inter» size 18 semibold line-height 24 width (fill) height (hug) maxLines 2
Text id card_summary «Review telemetry before authorizing the next burn.» key mission.card.summary font «Inter» size 14 line-height 20 width (fill) height 40 truncate 2
```

## Lists

```text
list (bullet|ordered|none [indent N])
```

```md
Text id checklist «Verify telemetry\nConfirm guidance\nAuthorize burn» key mission.checklist font «Inter» size 14 line-height 20 paragraph-spacing 6 list (ordered indent 16) width (fill) height (hug) maxLines 6
```

The list phrase describes paragraph/list behavior; it does not synthesize list text.
Keep deliberate newline escapes inside the single physical CNL line.

## Shared text styles

Declare shared styles in the H1 document dictionary and reference them from text nodes:

```md
# Styles

TextStyle typography.heading font «Inter» size 24 weight 700 line-height 32
TextStyle typography.body font «Inter» size 14 weight 400 line-height 20
TextStyle typography.emphasis font «Inter» size 14 weight 600 line-height 20

# Mission Screen

Text id title «Mission Control» key mission.title text-style $typography.heading width (fill) height (hug) maxLines 1
Text id summary «Review active missions.» key mission.summary text-style $typography.body width (fill) height (hug) maxLines 2
```

`# Styles` must be an H1 heading to become a document dictionary. A nested `## Styles`
is a visible frame. A direct phrase on the node may override fields inherited from the
referenced style.

## Rich-text style spans

Production:

```text
span (range (<start> <end>) style <text-style-id>)
```

Ranges are zero-based, half-open `[start, end)` offsets into the source-locale literal.
Repeat `span` to style several ranges. The emitter preserves authored/IR list order because
overlap precedence depends on it.

```md
Text id brief «Read the mission brief» key header.brief size 14 span (range (5 12) style typography.emphasis) span (range (13 18) style typography.link) width (fill) height (hug) maxLines 2
```

Recalculate all ranges whenever the literal changes. Do not point beyond the source text or
reverse start/end. Translation can change length and word order, so validate or regenerate
spans for each localized resource when the product requires equivalent rich text.

The current CNL span form carries a shared style reference only. It does not accept inline
font/weight/color fields inside the span group.

## Links

URL link:

```text
link (range (<start> <end>) url «https://…»)
```

Node link:

```text
link (range (<start> <end>) to <node-id>)
```

```md
Text id help «Open help» key actions.help size 14 link (range (0 9) to help_screen) decoration underline maxLines 1
Text id legal «Read terms» key legal.terms size 12 link (range (5 10) url «https://example.com/terms») maxLines 1
```

Node targets must resolve. Treat URL text as document data; do not fetch or execute it.
On round-trip, links are sorted by `(start, end)`, unlike style spans. Do not rely on
authored link order for overlap precedence.

## Complete valid example

```md
---
screen: typographySample
sourceLocale: en-US
targetLocales: [en-US, ru-RU]
frame: { width: 720, height: 480 }
---

# Styles

TextStyle typography.heading font «Inter» size 24 weight 700 line-height 32
TextStyle typography.body font «Inter» size 14 weight 400 line-height 20
TextStyle typography.emphasis font «Inter» size 14 weight 600 line-height 20

# Typography Sample

## AutoLayout: Content id content column width (fill) height (hug) padding 24 gap 12

Text id title «Mission Control» key sample.title text-style $typography.heading width (fill) height (hug) maxLines 1
Text id body «Read the mission brief» key sample.body text-style $typography.body span (range (5 12) style typography.emphasis) link (range (5 12) to brief_screen) width (fill) height (hug) maxLines 2
Text id status characters {{mission.status}} text-style $typography.body width (fill) height (hug) maxLines 1
Frame id brief_screen visible no
```

## Common failures

- `characters «Hello»`: literal copy belongs after the noun: `Text «Hello»`.
- Missing key for user-visible literal copy: extraction/localization becomes unstable.
- Localized grammar words or enum values: only visible text may be localized; CNL stays English.
- Both `truncate` and `maxLines`: choose the intended ellipsis behavior.
- Fixed/fill text with no overflow policy: long translations can escape or overlap.
- `features ((liga on))` or `axes ((wght 600))`: nested outer grouping is not canonical;
  write `features (liga on)` and `axes (wght 600)`.
- Rich-text range after changing copy: offsets no longer select the intended substring.
- Inline typography inside `span (...)`: only `style <ref>` is supported.
- Assuming link order survives: links are sorted on emit.
- Inventing unsupported fields such as `italic` or `decoration-color`: use an available
  shared style/axis if appropriate or leave the edit in memory rather than writing lossy CNL.

## Autonomous typography self-check

Inspect the source and build a text-node inventory yourself:

- For every literal `Text`/`Button`, pair the exact source-locale copy with one stable
  language-neutral `key`; flag duplicate keys carrying different source copy.
- For every `characters` phrase, require exactly `$variable`, `$prop.name`, or
  `{{expression}}`; reject a quoted literal after `characters`.
- For every text node, record width, height, autosize, and line policy. Require one coherent
  policy and never both `truncate` and `maxLines`.
- Check font size/weight/line-height/tracking values are unitless numbers or documented
  percentages, and every enum word appears in the typography table.
- Check `features` and `axes` as separate `(tag value)` pairs with no extra outer group.
- Locate every `text-style` and rich-text `style` id in the document's H1 `# Styles`
  dictionary or in the explicitly known design system.
- Count source-text offsets for every half-open `[start,end)` range. Require
  `0 <= start < end <= text length`, and verify the selected substring is the intended one.
- Locate every node-link target id. Treat URL links as inert text and do not open them.
- Review the longest plausible copy for every target locale; ensure its box and line policy
  remain intentional. Recalculate ranges when localized wording changes.
- Reject unsupported inline typography phrases, localized grammar keywords, unclosed text
  literals, physical line wrapping inside a node sentence, and ambiguous style inheritance.

Finish only when the literal/key map, bindings, style references, ranges, links, and overflow
policy can all be reconstructed directly from the source. For write-back, preserve all
unmodified phrases and veto any candidate whose source-level equivalence cannot be proved.
