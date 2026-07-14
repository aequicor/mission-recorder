---
name: vision
description: Analyze Mission Recorder storyboards and compatible contact sheets supplied as a single PNG file. Use when an agent needs to reconstruct a timestamped action sequence, recognize keyboard and mouse input, interpret the cursor's comet trail, identify interaction targets, track object state changes, and determine observed or inferred side effects.
---

# Vision: Storyboard Analysis

## Input Contract

Receive one PNG file containing a storyboard. Treat it as a contact sheet made of separate frames, not as one continuous screenshot.

In the current Mission Recorder format, frames are arranged vertically and separated by dark gaps. A dark strip below each frame contains a centered timestamp in the `HH:MM:SS.mmm` format. Associate each timestamp with the image directly above it. For a compatible contact sheet with a different layout, pair each frame with its nearest timestamp label.

Treat the storyboard as a sparse sample of a video. Unseen actions may have occurred between two frames. Do not reconstruct those actions as facts, and do not treat the absence of an intermediate frame as evidence that no event occurred.

## Analysis Workflow

1. Identify every frame boundary and read its timestamp. Verify chronological order. Preserve visual order for duplicate timestamps; report non-monotonic timestamps as an uncertainty.
2. Record the visible windows, panels, controls, documents, notifications, and other relevant objects in each frame. Track an object's identity across frames using the combined evidence of its text, icon, shape, position, and state.
3. Compare each frame with the previous frame. Describe transitions as `before -> after`: appearance, disappearance, movement, text or value changes, selection, enabled state, toggle state, navigation, or focus.
4. Locate the cursor, cursor halo, input label, and comet trail. Distinguish hover from click, drag, and keyboard actions.
5. Identify the probable interaction target using combined evidence: cursor position, input label, trail endpoint, and the subsequent state change. Do not attribute an action to an object solely because the cursor is nearby.
6. Identify immediate side effects in the same frame and delayed side effects in later frames. Describe invisible external consequences only as hypotheses.
7. Separate direct observations from causal inferences for every claim. Assign `high`, `medium`, or `low` confidence.

## Mouse Input and Movement

- Read the label next to the cursor literally. `ЛКМ`, `ПКМ`, and `СКМ` mean left, right, and middle mouse button; `Mouse 4` and `Mouse 5` mean additional mouse buttons.
- Preserve complete keyboard and mixed input chords, such as `Ctrl + Shift + B` or `Ctrl + ЛКМ`.
- Treat `drag` as evidence of pointer movement while a mouse button is held. Identify the dragged object by comparing the path origin, path endpoint, and any change in the object's position or container.
- Treat the input label as the pressed state sampled for that frame. A released key or mouse button is removed from the next sampled label; do not extend the state to later frames unless their labels repeat it.
- Interpret the comet trail on an important frame as the mouse path from the previous important marker, or from the start of the clip for the first marker, to the cursor's current position. Do not guess the direction if the current cursor or trail direction is unclear.
- Do not treat a trail passing over a control as interaction with that control. Look for input, a pause or endpoint on the control, and a corresponding state change.
- Do not infer that no input or movement occurred merely because a label or trail is absent. Recording may have been disabled, the label may have expired, or the frame may not be an important frame.

## Objects, Changes, and Side Effects

Use these evidence levels:

- `Fact`: a directly visible cursor, input label, object state, notification, or difference between frames.
- `Hypothesis`: a probable action or cause reconstructed from multiple visible clues.
- `Unknown`: a relationship that cannot be established reliably from the available frames.

Assign `high` confidence when the input label, cursor position, and expected object change agree. Assign `medium` confidence when two of these signals agree. Assign `low` confidence when the inference relies only on temporal proximity or an indirect change.

Separate a side effect from the immediate change to the interaction target. Side effects include opening or closing a window, navigation, a notification, a change to another object, starting a background process, saving, sending, or another consequence. Treat saving, sending, and other external operations as facts only when there is visible confirmation; otherwise label them as hypotheses.

Do not confuse ordinary animation, a blinking text caret, clock changes, or other background changes with the result of an action without additional evidence.

## Uncertainty and Safety

- If a timestamp or input label is unreadable, write `unreadable`; do not reconstruct exact text by guessing.
- If frame boundaries cannot be identified, report the limitation and request a higher-quality PNG instead of inventing a structure.
- If two frames have a large time gap, list only the confirmed end states and explicitly report the gap.
- If the same change has several plausible causes, list the alternatives and do not select one without sufficient evidence.
- Do not reproduce passwords, tokens, keys, personal data, or other visible secrets. Name the data type and use `[redacted]` when its value is not required for the analysis.

## Output Format

Follow the user's requested output format when one is provided. Otherwise, produce one row for every frame in this table:

| Timestamp | Observation | Input and movement | Interaction target | Change and side effect | Status and confidence |
|---|---|---|---|---|---|
| `HH:MM:SS.mmm` | Visible objects and state | Cursor, trail, keys, and buttons | Target object or `not established` | `before -> after` transition and consequences | `Fact` or `Hypothesis`, plus confidence |

After the table, add the following sections. Use the user's language for the report and translate these headings when needed.

## Brief Scenario

Summarize the sequence of confirmed actions and keep probable transitions distinct.

## Changed Objects

List the objects and their final states.

## Cumulative Side Effects

List immediate and delayed consequences while preserving fact and hypothesis labels.

## Uncertainties and Gaps

List unreadable data, time gaps, ambiguous causal relationships, and alternative explanations. Write `None identified` when there are no uncertainties.
