# Product

Localica plays music stored on an Android device. Core playback, editing, lyrics and listening statistics work offline without an account. Optional online metadata requires explicit consent. See [architecture](architecture/overview.md) and the [interface map](README.md#interface).

## Priorities

- Fast access to large libraries and reliable background playback.
- Independent saved queues with stable occurrence identities, including duplicate songs.
- Accessible shared controls, interruptible motion and meaningful customization.
- Explicit file grants, reviewed edits and recoverable failures.
- Durable local data across restarts and upgrades.

## Boundaries

Localica organizes music the user already has. It does not fetch remote audio or silently replace file tags with app-only metadata. Unsupported formats and providers remain playable where possible, with editing limitations explained.

Visual changes preserve data and listening intent. Contracts: [Library](architecture/library.md), [Queues](architecture/queues.md), [Playback](architecture/playback.md) and [File editing](architecture/file-editing.md).

## Privacy

Online features default off. Artist photos and Last.fm are optional, separately explained integrations. No library audio or local listening history is uploaded. Disabling consent stops new requests and retains cached data until explicit deletion.

Last.fm sync is read-only and starts explicitly with a username. It requires no password or account login. Provider requests, retention and deletion rules live in [Online](architecture/online.md).

## Direction

The interface combines Musicolet-inspired organization with Apple Music-inspired presentation. Shared UI owners keep controls consistent across screens. The [roadmap](roadmap.md) separates proposed work from released behavior.
