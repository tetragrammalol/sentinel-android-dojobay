// Compare the device clipboard with EXPECTED. Sets output.clipEqual / output.clipLen. Env: HELPER, EXPECTED
var r = http.post(HELPER + '/clip/equals', { body: String(EXPECTED) });
if (r.status != 200) throw new Error('clipboard helper failed: ' + r.status);
var j = JSON.parse(r.body); output.clipEqual = j.equal; output.clipLen = j.len;
