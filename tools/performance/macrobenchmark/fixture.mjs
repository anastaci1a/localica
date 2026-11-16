// #949 / #971 synthetic content, built against the checked-out Room schemas.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
import seedScale from './seed-scale.cjs';
process.umask(0o002);
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const out = path.resolve(process.argv[2]);
if (fs.existsSync(out)) throw new Error('Use a fresh fixture directory');
for (const dir of ['databases', 'files', 'shared_prefs']) fs.mkdirSync(path.join(out, 'original', dir), {recursive: true});
function database(name, type) {
    const dir = path.join(repo, 'app/schemas/io.github.anastaci1a.localica.' + type);
    const version = Math.max(...fs.readdirSync(dir).map(file => Number.parseInt(file)));
    const schema = JSON.parse(fs.readFileSync(path.join(dir, version + '.json'))).database;
    const db = new DatabaseSync(path.join(out, 'original/databases', name));
    db.exec('PRAGMA foreign_keys=ON;');
    for (const entity of schema.entities) {
        db.exec(entity.createSql.replaceAll('${TABLE_NAME}', entity.tableName));
        for (const index of entity.indices || []) db.exec(index.createSql.replaceAll('${TABLE_NAME}', entity.tableName));
    }
    for (const sql of schema.setupQueries) db.exec(sql);
    db.exec('PRAGMA user_version=' + schema.version);
    db.close();
}
database('library.db', 'library.LibraryDatabase');
database('queues.db', 'queue.QueueDatabase');
seedScale(out);
fs.rmSync(path.join(out, 'original'), {recursive: true});
const tone = fs.readFileSync(path.join(repo, 'app/src/androidTest/assets/tone.mp3'));
fs.writeFileSync(path.join(out, 'scale/files/949-scale/tone.mp3'), tone);
fs.writeFileSync(path.join(out, 'scale/shared_prefs/queue-settings.xml'),
    '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map><boolean name="shuffle" value="false"/></map>\n');
console.log(JSON.stringify({tracks: 13400, queueEntries: 13400, toneSha256: createHash('sha256').update(tone).digest('hex'),
    artwork: 'absent', lyrics: 'empty', schemaSource: 'checked-out Room exports'}));
