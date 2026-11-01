// Decode QR codes on the current screen (public data only). Sets output.qrTexts (array) / output.qr (first). Env: HELPER
var r = http.get(HELPER + '/qr/screen');
if (r.status != 200) throw new Error('helper failed: ' + r.status);
var j = JSON.parse(r.body); output.qrTexts = j.texts; output.qr = j.texts.length ? j.texts[0] : '';
