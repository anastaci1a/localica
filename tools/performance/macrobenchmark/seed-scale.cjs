// Promoted from #949 seed-scale.cjs; shared by hosted and physical #971 fixtures.
const fs=require('fs'), {DatabaseSync}=require('node:sqlite');
module.exports=function seedScale(root, queueCount=13400, timestamp=1794672000000){
fs.cpSync(root+'/original',root+'/scale',{recursive:true});
const dir=root+'/scale', lib=new DatabaseSync(dir+'/databases/library.db'), q=new DatabaseSync(dir+'/databases/queues.db');
const folder='file:///data/user/0/io.github.anastaci1a.localica.debug/files/949-scale';
const uri=folder+'/tone.mp3';
const snapshots=[];
lib.exec('PRAGMA journal_mode=DELETE; PRAGMA foreign_keys=ON; BEGIN');
lib.prepare('INSERT INTO folders(uri,name) VALUES (?,?)').run(folder,'949 operator scale');
const track=lib.prepare('INSERT INTO tracks (id,title,artist,album,durationMs,fileName) VALUES (?,?,?,?,?,?)');
const place=lib.prepare('INSERT INTO folder_tracks (folderUri,trackId,uri,relativePath,metadataFailed) VALUES (?,?,?,?,0)');
for(let i=0;i<13400;i++){
 const id='949-track-'+i,title='Scale song '+String(i).padStart(5,'0'),file=i+'.mp3';
 track.run(id,title,'949 artist '+(i%200),'949 album '+(i%1100),20000,file);place.run(folder,id,uri,file);
 snapshots.push(JSON.stringify({id,uri,title,file,path:file,duration:20000,artist:'949 artist '+(i%200),album:'949 album '+(i%1100),locations:[{root:folder,uri,path:file}]}));
}
lib.exec('COMMIT');
q.exec('PRAGMA journal_mode=DELETE; PRAGMA foreign_keys=ON; BEGIN');
q.prepare('INSERT INTO queues(id,name,createdAt,currentEntryId,positionMs,shuffle,repeat,ordinal,lastActivatedAt,orderState) VALUES (?,?,?,?,0,0,?,0,?,?)').run('949-scale','Queue 949 scale',timestamp,`949-occurrence-${queueCount-10}`,'Off',timestamp,'');
const entry=q.prepare('INSERT INTO queue_entries(id,queueId,ordinal,track) VALUES (?, ?, ?, ?)');
for(let i=0;i<queueCount;i++)entry.run('949-occurrence-'+i,'949-scale',i,snapshots[i%13400]);
q.prepare('INSERT OR REPLACE INTO queue_state(id,queueId) VALUES (1,?)').run('949-scale');q.exec('COMMIT');
console.log(JSON.stringify({tracks:lib.prepare('SELECT COUNT(*) AS n FROM tracks').get().n,queue:q.prepare('SELECT COUNT(*) AS n,COUNT(DISTINCT id) AS uniqueIds FROM queue_entries').get(),current:`949-occurrence-${queueCount-10}`}));
lib.close();q.close();
fs.mkdirSync(dir+'/files/949-scale',{recursive:true});
const xml=contents=>'<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n'+contents+'\n</map>\n';
fs.writeFileSync(dir+'/shared_prefs/scanner.xml',xml('<boolean name="on-launch" value="false"/><boolean name="full-on-launch" value="false"/>'));
fs.writeFileSync(dir+'/shared_prefs/interface.xml',xml('<string name="tabs">Songs,Library,Queues</string>'));
fs.writeFileSync(dir+'/shared_prefs/browse-style.xml',xml('<string name="contents.Queue.list-size">Compact</string><string name="contents.Playlist.list-size">Compact</string>'));

};
