import {test} from 'node:test';
import assert from 'node:assert/strict';
import {identities, csv, pin} from './pin.mjs';
test('implicit working HEAD is rejected', () => assert.throws(() => pin([]), /implicit HEAD/));
const row = {category:'1', revision:'a'.repeat(40), file:'app/src/main/F.kt', line:'1', pattern:'clock', normalized_column:'1'};
test('duplicate overlapping category identity is rejected', () => assert.throws(() => identities([row, row]), /Duplicate/));
test('category and column disambiguate overlaps', () => assert.equal(identities([row, {...row, category:'2'}, {...row, normalized_column:'2'}]).length, 3));
test('CSV quoting and truncation', () => {
    assert.deepEqual(csv('a,b\n"one,two","a""b"\n'), [{a:'one,two', b:'a"b'}]);
    assert.throws(() => csv('a\n"unfinished'), /Malformed/);
});
import {strictJson} from './strict-json.mjs';
test('receipt JSON rejects duplicate keys, truncation, unknown numeric values and malformed escapes',()=>{
    for(const s of ['{"a":1,"a":2}','{"x":{"a":1,"a":2}}','[1,]','{"x":1e999}','{"x":"bad\\q"}'])assert.throws(()=>strictJson(s));
    assert.deepEqual(strictJson('{"a":[null,true,-1.5e2,"x"]}'),{a:[null,true,-150,'x']});
});
