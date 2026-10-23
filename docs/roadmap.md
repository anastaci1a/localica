# Roadmap

Status: proposed work. These outcomes require implementation or verification. They are not claims about released builds.

## Current checkpoint

- Stabilize current development candidates against [release gates](build.md#release-preparation). A passing component or local test is not confirmation of a cumulative APK. Preserve unreleased experiments separately.
- Improve startup/dense-library responsiveness and modal gesture ownership ([#406](https://github.com/anastaci1a/localica/issues/406), [#407](https://github.com/anastaci1a/localica/issues/407)). Optional new-file-only launch scanning remains a separate [pending change](https://github.com/anastaci1a/localica/pull/408), not current full-scan behavior.
- Require reproducible optical comparisons before changing glass defaults. Preserve immediate selection appearance until the separate [entrance-motion review](https://github.com/anastaci1a/localica/issues/107).
- Validate [frame performance](https://github.com/anastaci1a/localica/issues/167) and [manual-scroll jitter](https://github.com/anastaci1a/localica/issues/150) on representative physical devices, especially queues, tab travel and player expansion. Emulator tests do not establish physical-device frame rates.

## Deferred capabilities

These do not gate visual work. Issues own detailed acceptance criteria.

| Capability | Scope / dependency |
| --- | --- |
| Metadata parsing | Configurable title rules and separate Artist/Album Artist splitting, previews/exceptions, preserving raw tags; [#74](https://github.com/anastaci1a/localica/issues/74). |
| Personalized Home | Extend current typed/manual pins into configurable carousels: pinned mixed items, frequently played mixed items, recently added albums/songs. Default ten previews. Customize sources/order/sort/presentation/count, overriding type defaults. Chevron titles open complete collections. |
| Last.fm | Off by default. Explicit connection/scrobbling, durable pending submissions, disconnect/status controls, reviewed/resettable track/artist matching. No automatic upload of past history or rewriting local tags. Local counts remain independent. Verify [protocol eligibility](https://www.last.fm/api/scrobbling) separately from local counting (tracks longer than 30 seconds, half duration or four minutes listened). Retain actual listening duration/start timestamps. |
| Lyric lookup | Opt-in lookup when local lyrics are absent, investigating Better Lyrics’ backend and protected `.lrc` sidecar saving; [#187](https://github.com/anastaci1a/localica/issues/187). |
| Lyric editor | Manual `.lrc` editing and playback-assisted line timestamp taps, with correction/undo, preview and recoverable explicit save/export. |
| Visualizers | Player button for Poweramp-compatible presets. Review [specification](https://github.com/maxmpz/powerampapi/blob/master/poweramp_vis_presets_example/readme.md): MilkDrop v1/v2, shaders/textures, extensions/packaging, licensing and device performance. |
| Other candidates | Crossfades, more shuffle modes, sleep timer, equalizer, system/light/dark themes, reduced transparency with solid fallback, multilingual UI, smart playlists, bookmarks/favorites, widgets, Android Auto and backup/export. Prioritize explicitly before implementation. |
