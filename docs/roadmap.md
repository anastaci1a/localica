# Roadmap

## Current checkpoint

Review the v0.15.1 fixes and v0.16.0 Liquid Glass controls on device before further visual changes. Current contracts live in [Product](product.md) and [Interface](interface.md); release history belongs in [GitHub Releases](https://github.com/anastaci1a/localica/releases).

## Next outcome

Tune the shared Liquid Glass controls, frosted Queue drawer and player gestures from device feedback before expanding the material to other surfaces. Preserve legibility, accessibility, performance and older-device fallbacks. Keep the design system in this repository until demonstrated reuse justifies extraction.

## Deferred capabilities

These do not gate visual work. Issues own detailed acceptance criteria.

| Capability | Scope / dependency |
| --- | --- |
| Metadata parsing | Configurable title rules and separate Artist/Album Artist splitting, previews/exceptions, preserving raw tags; [#74](https://github.com/anastaci1a/localica/issues/74). |
| Pinch views | Position-anchored list/gallery transitions, exactly one adjacent step per pinch, excluding queues; [#75](https://github.com/anastaci1a/localica/issues/75). |
| Personalized Home | Extend current typed/manual pins into configurable carousels: pinned mixed items, frequently played mixed items, recently added albums/songs. Default ten previews; customize sources/order/sort/presentation/count, overriding type defaults. Chevron titles open complete collections. |
| Last.fm | Off by default; explicit connection/scrobbling, durable pending submissions, disconnect/status controls, reviewed/resettable track/artist matching. No automatic upload of past history or rewriting local tags; local counts remain independent. Verify [protocol eligibility](https://www.last.fm/api/scrobbling) separately from local counting (track >30s, half duration or four minutes listened). Retain actual listening duration/start timestamps. |
| Lyric editor | Manual `.lrc` editing and playback-assisted line timestamp taps, with correction/undo, preview and recoverable explicit save/export. |
| Visualizers | Player button for Poweramp-compatible presets. Review [specification](https://github.com/maxmpz/powerampapi/blob/master/poweramp_vis_presets_example/readme.md): MilkDrop v1/v2, shaders/textures, extensions/packaging, licensing and device performance. |
| Other candidates | Crossfades, more shuffle modes, sleep timer, equalizer, system/light/dark themes, reduced transparency with solid fallback, multilingual UI, custom playlist covers, smart playlists, bookmarks/favorites, widgets, Android Auto and backup/export. Prioritize explicitly before implementation. |

Deliver agreed outcomes in small verified APK releases; remove completed roadmap items rather than adding historical summaries.
