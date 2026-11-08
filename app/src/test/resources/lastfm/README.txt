Offline response fixtures for Last.fm user.getTopTracks.

The documented response envelope and Dream Theater / Learning to Live sample
(42 plays and the documented artist MBID) come from the official endpoint docs:
https://www.last.fm/api/show/user.getTopTracks

Pagination, singleton-track JSON, album, numeric values, synthetic recording MBID,
missing-match and rate-limit cases are sanitized constructed variants of that
published response. They are not recordings from a private account. No API key,
real account, live server, authentication or scrobbling is used in tests.
