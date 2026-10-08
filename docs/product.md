# Product

## Goal

Build a dependable Android player for audio files already on the device. It should make listening and library navigation simple while offering meaningful control over playback, organization, and appearance. Localica takes functional inspiration from [Musicolet's published feature list](https://play.google.com/store/apps/details?id=in.krosbits.musicolet), without copying its branding or interface.

## Experience principles

- The common path stays short: find music, start playback, see and change the current queue.
- Advanced controls remain available in context or settings rather than crowding the primary player.
- The device's music belongs to the user. File access and edits must be explicit, reversible where possible, and respectful of Android storage rules.
- Listening should work without an account or network connection. Streaming, music discovery, and online lyrics are outside the current goal.
- Accessibility, large libraries, and interrupted or background playback are core quality requirements.

## Capability targets

| Area | Target behavior |
| --- | --- |
| Library | Browse and search local tracks by song, album, artist, playlist, and folder; handle metadata and artwork. |
| Playback | Background playback, media controls, resume, shuffle, repeat, queue editing, and audio focus behavior. |
| Multiple queues | Keep separate listening contexts and restore their order and position. |
| Organization | Create and edit playlists; support favorites and useful sort/filter choices. |
| Listening tools | Consider gapless playback, sleep timer, lyrics, equalizer, bookmarks, and output-specific preferences as later capabilities. |
| Customization | Clear light and dark modes plus adjustable behavior and layout where it improves use. |
| Integration | Notifications, headset controls, widgets, and Android Auto as the platform and release scope allow. |

These are product targets, not a promise that every item ships in the first release. The roadmap identifies the first usable slice. File operations and tag editing need a separate design for permissions, safety, and rollback before implementation.
