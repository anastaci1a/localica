Offline, synthetic protocol fixtures; no live requests run during tests.

Constructed 2026-11-08 from these public API schemas. IDs, artist names,
release titles, image paths and attribution are invented, not downloaded
artist records or licensed photographs. Image byte fixtures are generated in
JVM tests. These fixtures do not claim that the invented image URLs exist.

Deezer search/artist/top tracks (data, id, name, album.title, picture_xl/picture_big):
https://developers.deezer.com/api/search/artist
https://developers.deezer.com/api/artist
https://developers.deezer.com/api/artist/top
MusicBrainz JSON search, release-group browse, artist lookup with url-rels:
https://musicbrainz.org/doc/MusicBrainz_API
Wikidata EntityData JSON, P18 image claim:
https://www.wikidata.org/wiki/Special:EntityData
Commons imageinfo url, thumburl, descriptionurl and extmetadata:
https://www.mediawiki.org/wiki/API:Imageinfo

These constructed fixtures exercise parser contracts and provider sequencing.
They do not establish live service availability, ranking quality or licence
validity for any actual photograph. No private libraries/accounts or keys.
