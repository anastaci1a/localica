# Roadmap

## Current checkpoint

Profile shared rendering and interaction costs against the completed feature build, preserving materials and intended motion timing. Review the [Liquid Glass specification](liquid-glass/specification.md) on the user’s phone: low-damping depth response, rapid interrupted gestures, large collections and material handoffs. Emulator checks do not establish physical-device frame rates. Revisit selection entrance motion separately ([#107](https://github.com/anastaci1a/localica/issues/107)). Preserve accessibility, playback continuity and older-Android support.

## Deferred capabilities

These do not gate visual work. Issues own detailed acceptance criteria.

| Capability | Scope / dependency |
| --- | --- |
| Metadata parsing | Configurable title rules and separate Artist/Album Artist splitting, previews/exceptions, preserving raw tags; [#74](https://github.com/anastaci1a/localica/issues/74). |
| Personalized Home | Extend current typed/manual pins into configurable carousels: pinned mixed items, frequently played mixed items, recently added albums/songs. Default ten previews; customize sources/order/sort/presentation/count, overriding type defaults. Chevron titles open complete collections. |
| Last.fm | Off by default; explicit connection/scrobbling, durable pending submissions, disconnect/status controls, reviewed/resettable track/artist matching. No automatic upload of past history or rewriting local tags; local counts remain independent. Verify [protocol eligibility](https://www.last.fm/api/scrobbling) separately from local counting (track >30s, half duration or four minutes listened). Retain actual listening duration/start timestamps. |
| Lyric editor | Manual `.lrc` editing and playback-assisted line timestamp taps, with correction/undo, preview and recoverable explicit save/export. |
| Visualizers | Player button for Poweramp-compatible presets. Review [specification](https://github.com/maxmpz/powerampapi/blob/master/poweramp_vis_presets_example/readme.md): MilkDrop v1/v2, shaders/textures, extensions/packaging, licensing and device performance. |
| Other candidates | Crossfades, more shuffle modes, sleep timer, equalizer, system/light/dark themes, reduced transparency with solid fallback, multilingual UI, smart playlists, bookmarks/favorites, widgets, Android Auto and backup/export. Prioritize explicitly before implementation. |
