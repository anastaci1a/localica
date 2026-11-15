// #949 / #971 synthetic content, built against the checked-out Room schemas.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
process.umask(0o002);
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const out = path.resolve(process.argv[2]);
if (fs.existsSync(out)) throw new Error('Use a fresh fixture directory');
for (const dir of ['databases', 'files/949-scale', 'shared_prefs']) fs.mkdirSync(path.join(out, dir), {recursive: true});
function database(name, type) {
    const dir = path.join(repo, 'app/schemas/io.github.anastaci1a.localica.' + type);
    const version = Math.max(...fs.readdirSync(dir).map(file => Number.parseInt(file)));
    const schema = JSON.parse(fs.readFileSync(path.join(dir, version + '.json'))).database;
    const db = new DatabaseSync(path.join(out, 'databases', name));
    db.exec('PRAGMA foreign_keys=ON; BEGIN');
    for (const entity of schema.entities) {
        db.exec(entity.createSql.replaceAll('${TABLE_NAME}', entity.tableName));
        for (const index of entity.indices || []) db.exec(index.createSql.replaceAll('${TABLE_NAME}', entity.tableName));
    }
    for (const sql of schema.setupQueries) db.exec(sql);
    db.exec('PRAGMA user_version=' + schema.version);
    return db;
}
const library = database('library.db', 'library.LibraryDatabase');
const queues = database('queues.db', 'queue.QueueDatabase');
const root = 'file:///data/user/0/io.github.anastaci1a.localica.debug/files/949-scale';
const uri = root + '/tone.mp3';
library.prepare('INSERT INTO folders(uri,name) VALUES (?,?)').run(root, '949 operator scale');
const insertTrack = library.prepare('INSERT INTO tracks(id,title,artist,album,durationMs,fileName) VALUES (?,?,?,?,?,?)');
const insertLocation = library.prepare('INSERT INTO folder_tracks(folderUri,trackId,uri,relativePath,metadataFailed) VALUES (?,?,?,?,0)');
queues.prepare('INSERT INTO queues(id,name,createdAt,currentEntryId,positionMs,shuffle,repeat,ordinal,lastActivatedAt,orderState) VALUES (?,?,?,?,0,0,?,0,?,?)')
    .run('949-scale', 'Queue 949 scale', 1794672000000, '949-occurrence-13390', 'Off', 1794672000000, '');
const entry = queues.prepare('INSERT INTO queue_entries(id,queueId,ordinal,track) VALUES (?,?,?,?)');
for (let i = 0; i < 13400; i++) {
    const id = '949-track-' + i, title = 'Scale song ' + String(i).padStart(5, '0');
    const file = i + '.mp3', artist = '949 artist ' + (i % 200), album = '949 album ' + (i % 1100);
    insertTrack.run(id, title, artist, album, 20000, file);
    insertLocation.run(root, id, uri, file);
    entry.run('949-occurrence-' + i, '949-scale', i, JSON.stringify({id, uri, title, file, path: file,
        duration: 20000, artist, album, locations: [{root, uri, path: file}]}));
}
queues.prepare('INSERT INTO queue_state(id,queueId) VALUES (1,?)').run('949-scale');
for (const db of [library, queues]) { db.exec('COMMIT'); db.close(); }
const tone = fs.readFileSync(path.join(repo, 'app/src/androidTest/assets/queue-tone.mp3'));
fs.writeFileSync(path.join(out, 'files/949-scale/tone.mp3'), tone);
const preferences = {
    scanner: '<boolean name="on-launch" value="false"/><boolean name="full-on-launch" value="false"/>',
    interface: '<string name="tabs">Songs,Library,Queues</string>',
    'queue-settings': '<boolean name="shuffle" value="false"/>',
    'browse-style': '<int name="catalog.Song.columns" value="2"/><string name="catalog.Song.list-size">Normal</string>'
};
for (const [name, values] of Object.entries(preferences)) fs.writeFileSync(path.join(out, 'shared_prefs', name + '.xml'),
    '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>' + values + '</map>\n');
console.log(JSON.stringify({tracks: 13400, queueEntries: 13400, toneSha256: createHash('sha256').update(tone).digest('hex'),
    artwork: 'absent', lyrics: 'empty', schemaSource: 'checked-out Room exports'}));
