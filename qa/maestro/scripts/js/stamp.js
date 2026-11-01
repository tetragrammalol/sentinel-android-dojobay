// STAMP_OP = set|age, STAMP = name. For age: output.stampAge (seconds, -1 = never). Env: HELPER
var r = http.get(HELPER + '/stamp/' + STAMP_OP + '?' + STAMP);
if (r.status != 200) throw new Error('helper failed: ' + r.status);
if (STAMP_OP == 'age') output.stampAge = JSON.parse(r.body).age;
