---
name: mission-visualization-mcp
description: Use the Mission Visualization MCP server to author, validate, render, and visually review Semantic Layout Markdown (*.layout.md) files.
metadata:
  version: "2"
---

# Mission Visualization MCP

Use the MCP server named `mission_visualization` for every task that creates or changes
`*.layout.md`. The server is read-only: edit project files with the agent's normal file tools.

## Tools

- `get_mcp_skill`: returns this canonical root skill and its version.
- `get_slm_skills`: returns the canonical base SLM skill plus requested subsystem skills.
  Call it with `skill: "all"` during initial setup. Supported selectors are `all`, `slm`,
  `diagrams`, `vector_graphics`, `typography`, `annotations`, and `editor`.
- `validate_project_setup`: proves that this client can call the MCP server and that skill setup
  is complete. Pass `agent_project_path` (where project-scoped skills are installed) separately
  from `layouts_path` (the MCP content root). Call it after installing the root and SLM skills.
- `check_layout`: compiles one `*.layout.md` file and returns `valid`, diagnostics, and the
  source fingerprint. Fix errors until `valid: true`.
- `render_layout`: compiles and renders a screen, placed component, or group. It returns PNG,
  diagnostics, dimensions, and the source fingerprint.

## Required workflow

1. Load the relevant installed SLM skill before editing.
2. Create or edit the `*.layout.md` source with normal project file tools.
3. Call `check_layout` and fix every error.
4. Call `render_layout` and inspect the returned PNG, not only the diagnostics.
5. Compare the render fingerprint with the last check fingerprint so a stale PNG is never accepted.
6. Fix layout, clipping, overflow, text, spacing, contrast, and responsive issues, then repeat.
7. Finish only when the source is valid and the latest PNG has been visually reviewed.

The layouts root can be a subfolder of the agent project or a separate folder. Never install
project-scoped skills into the layouts root merely because MCP exposes it there. Install them at
the actual agent project root. Never ask the MCP server to edit project sources and never access
a layout outside its allowed layouts root.
