/** JSON.parse accepts duplicate keys; receipts must reject them before interpretation. */
export function strictJson(text) {
    let at=0;
    const fail=why=>{throw Error('Invalid receipt JSON: '+why);};
    const space=()=>{while(/\s/.test(text[at]||'') && at<text.length)at++;};
    function string() {
        const start=at++; let escaped=false;
        while(at<text.length) {
            const c=text[at++];
            if(c==='"' && !escaped)return JSON.parse(text.slice(start,at));
            escaped=c==='\\'&&!escaped;
        }
        fail('truncated string');
    }
    function value() {
        space();const c=text[at];
        if(c==='"'){string();return;}
        if(c==='{'||c==='[') {
            const object=c==='{', end=object?'}':']',keys=new Set();at++;space();
            if(text[at]===end){at++;return;}
            while(at<text.length) {
                if(object) {
                    if(text[at]!=='"')fail('object key'); const key=string();
                    if(keys.has(key))fail('duplicate key '+key);keys.add(key);space();
                    if(text[at++]!==':')fail('missing colon');
                }
                value();space();if(text[at]===end){at++;return;}
                if(text[at++]!==',')fail('missing comma');space();
            }
            fail('truncated collection');
        }
        const token=text.slice(at).match(/^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/)?.[0];
        if(!token)fail('value');if(!['true','false','null'].includes(token)&&!Number.isFinite(Number(token)))fail('nonfinite');at+=token.length;
    }
    value();space();if(at!==text.length)fail('trailing data');return JSON.parse(text);
}
