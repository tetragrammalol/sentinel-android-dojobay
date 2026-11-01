// Read clipboard length + 10-char prefix (never the full text). Sets output.clipLen / output.clipPrefix. Env: HELPER
var r = http.get(HELPER + '/clip/info');
if (r.status != 200) throw new Error('clipboard helper failed: ' + r.status);
var j = JSON.parse(r.body); output.clipLen = j.len; output.clipPrefix = j.prefix;
