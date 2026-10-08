# Project structure

The repository currently contains planning files only. The layout below is a provisional shape for implementation, not directories to create before they are needed.

```text
/
├── README.md                 short public overview and screenshots
├── AGENTS.md                 pointer to agent instructions
├── agents/README.md          working and compaction rules
├── docs/                     current product, structure, and roadmap
├── app/                      Android entry point, navigation, app wiring
├── core/                     shared playback, library, storage, and design code
└── feature/                  focused UI features if separation becomes useful
```

Start with one buildable Android app module. Extract `core/` or `feature/` modules only when a real boundary reduces coupling or build cost; directory names are not an architecture requirement. Keep playback state, persistent library data, and UI state separately owned so a screen cannot become the authority for what is playing. Put Android platform access behind small interfaces where it helps testing and device compatibility.

Before implementation, choose the minimum supported Android version, playback and UI libraries, and local data approach based on current platform guidance. Record the resulting choice and short reason here, replacing provisional text. Keep generated output, local SDK paths, signing keys, and private media out of Git.
