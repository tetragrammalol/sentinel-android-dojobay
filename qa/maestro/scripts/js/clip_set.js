// Set the device clipboard through scripts/helper.py. Env: HELPER, TEXT
var r = http.post(HELPER + '/clip/set', { body: String(TEXT) });
if (r.status != 200) throw new Error('clipboard helper failed: ' + r.status);
output.clipSetLen = JSON.parse(r.body).len;
