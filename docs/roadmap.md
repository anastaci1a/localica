# Roadmap

Released: [v0.34.2](https://github.com/anastaci1a/localica/releases/tag/v0.34.2). The milestones below describe planned outcomes, not claims of release or physical-device verification. Issues own acceptance criteria; [release gates](development/release.md) own distribution readiness.

## Milestones

| Milestone | Outcome |
| --- | --- |
| [M1 Stable base](https://github.com/anastaci1a/localica/milestone/12) | Current UI defects resolved, no open flakes, self-hosted runner available. |
| [M2 Apple-feel player & Queue](https://github.com/anastaci1a/localica/milestone/13) | Player and Queue use the measured motion model; A35 runner and Checkpoint A establish physical-device smoothness. |
| [M3 Surfaces & popups](https://github.com/anastaci1a/localica/milestone/14) | Persistent shared shapes, compositor and shadow planes, popup morphs; A35 Checkpoint B. |
| [M4 Lists & controls](https://github.com/anastaci1a/localica/milestone/15) | Shared controls, reveals, rows, selection, pinch and list motion; A35 Checkpoint C without list stutter. |
| [M5 Cleanup & final](https://github.com/anastaci1a/localica/milestone/16) | Delete dead code, shrink the codebase, complete final A35 measurements and visual review. |
| [M6 Liquid Glass to spec](https://github.com/anastaci1a/localica/milestone/17) | Match the liquid-glass geometry specifications. Optics changes are confined to this milestone unless priorities change. |

## Outstanding verification

- Startup and dense-library responsiveness: [#406](https://github.com/anastaci1a/localica/issues/406).
- Repeated pinching at Smallest: [#474](https://github.com/anastaci1a/localica/issues/474).
- Queue/player rendering performance and manual-scroll jitter: [#167](https://github.com/anastaci1a/localica/issues/167), [#150](https://github.com/anastaci1a/localica/issues/150).
- Selection appearance and reversal on physical devices: [#107](https://github.com/anastaci1a/localica/issues/107).
- AAC boundaries on physical audio output: [#28](https://github.com/anastaci1a/localica/issues/28).

Emulator timing and decoded-sample equality do not establish physical-device smoothness or seamless speaker playback. The [performance budget](development/performance.md) remains 8.3 ms per whole A35 frame.

## Deferred capabilities

These do not gate visual work. Issues own detailed acceptance criteria.

| Capability | Scope / dependency |
| --- | --- |
| Metadata parsing | Configurable title rules and separate Artist/Album Artist splitting, previews/exceptions, preserving raw tags; [#74](https://github.com/anastaci1a/localica/issues/74). |
| Personalized Home | Extend current typed/manual pins into configurable carousels: pinned mixed items, frequently played mixed items, recently added albums/songs. Default ten previews; customize sources/order/sort/presentation/count, overriding type defaults. Chevron titles open complete collections. |
| Lyric lookup | Opt-in lookup when local lyrics are absent, investigating Better Lyrics’ backend and protected `.lrc` sidecar saving; [#187](https://github.com/anastaci1a/localica/issues/187). |
| Lyric editor | Manual `.lrc` editing and playback-assisted line timestamp taps, with correction/undo, preview and recoverable explicit save/export. |
| Visualizers | Player button for Poweramp-compatible presets. Review [specification](https://github.com/maxmpz/powerampapi/blob/master/poweramp_vis_presets_example/readme.md): MilkDrop v1/v2, shaders/textures, extensions/packaging, licensing and device performance. |
| Other candidates | Crossfades, more shuffle modes, sleep timer, equalizer, system/light/dark themes, reduced transparency with solid fallback, multilingual UI, smart playlists, bookmarks/favorites, widgets, Android Auto and backup/export. Prioritize explicitly before implementation. |
