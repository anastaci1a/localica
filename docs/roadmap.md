# Roadmap

Status: proposed work. These outcomes require implementation or verification. They do not establish released behavior.

## Current checkpoint

Released v0.30.0 delivered gesture ownership, queue reuse and the shared glass controls. The next checkpoint is physical-device confidence in responsiveness and stability, judged against [release gates](build.md#release-preparation).

Improve startup and dense-library responsiveness ([#406](https://github.com/anastaci1a/localica/issues/406)).

Investigate the open stability reports: repeated pinching at Smallest ([#474](https://github.com/anastaci1a/localica/issues/474)) and Legacy renderer observer failures during navigation ([#475](https://github.com/anastaci1a/localica/issues/475)).

Resolve the post-release defects [#491](https://github.com/anastaci1a/localica/issues/491) to [#496](https://github.com/anastaci1a/localica/issues/496) and the test-reliability reports [#498](https://github.com/anastaci1a/localica/issues/498) and [#499](https://github.com/anastaci1a/localica/issues/499). Clean up inert CI workflows ([#497](https://github.com/anastaci1a/localica/issues/497)).

Require reproducible optical comparisons before changing glass defaults.

Review the [material and control contracts](interface.md#materials-and-controls) on representative physical devices: depth response, interrupted gestures, large collections and material handoffs.

Validate [rendering performance](https://github.com/anastaci1a/localica/issues/167), especially queue scrolling and player expansion. Emulator measurements do not establish physical-device frame rates. Reproduce [intermittent manual-scroll jitter](https://github.com/anastaci1a/localica/issues/150).

Validate [selection appearance and reversal](https://github.com/anastaci1a/localica/issues/107) on physical devices, preserving accessibility and reduced motion.

Verify [AAC track boundaries](https://github.com/anastaci1a/localica/issues/28) on physical output. Decoded sample equality does not establish seamless speaker playback.

## Deferred capabilities

These do not gate visual work. Issues own detailed acceptance criteria.

| Capability | Scope / dependency |
| --- | --- |
| Metadata parsing | Configurable title rules and separate Artist/Album Artist splitting, previews/exceptions, preserving raw tags; [#74](https://github.com/anastaci1a/localica/issues/74). |
| Personalized Home | Extend current typed/manual pins into configurable carousels: pinned mixed items, frequently played mixed items, recently added albums/songs. Default ten previews; customize sources/order/sort/presentation/count, overriding type defaults. Chevron titles open complete collections. |
| Last.fm | Off by default; explicit connection/scrobbling, durable pending submissions, disconnect/status controls, reviewed/resettable track/artist matching. No automatic upload of past history or rewriting local tags; local counts remain independent. Verify [protocol eligibility](https://www.last.fm/api/scrobbling) separately from local counting (track >30s, half duration or four minutes listened). Retain actual listening duration/start timestamps. |
| Lyric lookup | Opt-in lookup when local lyrics are absent, investigating Better Lyrics’ backend and protected `.lrc` sidecar saving; [#187](https://github.com/anastaci1a/localica/issues/187). |
| Lyric editor | Manual `.lrc` editing and playback-assisted line timestamp taps, with correction/undo, preview and recoverable explicit save/export. |
| Visualizers | Player button for Poweramp-compatible presets. Review [specification](https://github.com/maxmpz/powerampapi/blob/master/poweramp_vis_presets_example/readme.md): MilkDrop v1/v2, shaders/textures, extensions/packaging, licensing and device performance. |
| Other candidates | Crossfades, more shuffle modes, sleep timer, equalizer, system/light/dark themes, reduced transparency with solid fallback, multilingual UI, smart playlists, bookmarks/favorites, widgets, Android Auto and backup/export. Prioritize explicitly before implementation. |
