from flows_a import F, flow
from flows_b import MW, CS, RCV, SEND, ADDR, amount, TYPE_ADDR, COMPOSE, DISABLED, UTXO

flow('UTXO-01', ['p1','utxo'], 'Unspent outputs: Active list with amounts and addresses (Deposit)', UTXO + '''- assertVisible: "Active"
- assertVisible: { id: "utxo_item_amount", index: 0, text: "[0-9.]+ BTC" }
- assertVisible: { id: "utxo_item_address", index: 0, text: "tb1.*" }
- takeScreenshot: UTXO-01-list
- back
- back
''', known='The sum-vs-balance check stays manual (indices of scrolled rows are not stable in Maestro)')

flow('UTXO-05', ['p2','utxo','known'], 'UTXO detail toolbar shows a title and actions (known: empty)', UTXO + '''- tapOn: { id: "utxo_item", index: 0 }
- extendedWaitUntil: { visible: { id: "utxo_details_hash" }, timeout: 20000 }
- assertVisible: { id: "utxo_details_amount", text: "[0-9.]+ BTC" }
- assertVisible: { id: "utxo_details_spendable_status" }
- takeScreenshot: UTXO-05-detail
- assertVisible:
    text: "(?i)(unspent output|utxo|details).*"
    label: "Toolbar title present"
- back
- back
- back
''', expect='fail', known='Known (plan UTXO-05): the detail toolbar is empty')

flow('UTXO-07', ['p2','utxo'], 'UTXO filter sheet opens, Apply keeps the list (reset = no filter)', UTXO + '''- tapOn: { id: "action_utxo_filter" }
- extendedWaitUntil: { visible: { id: "utxo_filter_apply_btn" }, timeout: 10000 }
- assertVisible: { id: "spendable_checkBox_btn" }
- takeScreenshot: UTXO-07-filter
- tapOn: { id: "dsc_amount_radio_btn" }
- tapOn: { id: "utxo_filter_apply_btn" }
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible: { id: "utxo_item", index: 0 }
- tapOn: { id: "action_utxo_filter" }
- tapOn: { id: "asc_amount_radio_btn" }
- tapOn: { id: "utxo_filter_apply_btn" }
- back
- back
''')

flow('SYNC-04', ['p2','utxo','sync'], 'UTXO screen Refresh re-fetches without emptying the list', UTXO + '''- tapOn: "More options"
- tapOn: "(?i)refresh"
- waitForAnimationToEnd: { timeout: 5000 }
- extendedWaitUntil: { visible: { id: "utxo_item", index: 0 }, timeout: 120000 }
- takeScreenshot: SYNC-04-after-refresh
- back
- back
''')

MARK_FIRST = '''- longPressOn: { id: "utxo_item", index: 0 }
- tapOn: "More options"
- tapOn: "Mark as do not spend"
- extendedWaitUntil: { visible: { text: "Blocked" }, timeout: 20000 }
'''
UNMARK_ALL = '''- repeat:
    while: { visible: { text: "Blocked" } }
    times: 5
    commands:
      - scrollUntilVisible: { element: { text: "Blocked" }, direction: DOWN, timeout: 20000 }
      - longPressOn: { id: "utxo_item", below: { text: "Blocked" } }
      - tapOn: "More options"
      - tapOn: "Mark as Spendable"
      - waitForAnimationToEnd: { timeout: 5000 }
'''
flow('UTXO-02', ['p0','utxo'], 'Mark do-not-spend moves the UTXO to Blocked (restored afterwards)', UTXO + '''- copyTextFrom: { id: "utxo_item_amount", index: 0 }
- evalScript: { script: "${output.amt = maestro.copiedText}", label: "amount" }
''' + MARK_FIRST + '''- takeScreenshot: UTXO-02-blocked
- scrollUntilVisible: { element: { text: "Blocked" }, direction: DOWN, timeout: 20000 }
- assertVisible: { id: "utxo_item_amount", text: "${output.amt}", below: { text: "Blocked" } }
''' + UNMARK_ALL + '''- assertNotVisible: "Blocked"
- back
- back
''')

flow('UTXO-03', ['p1','utxo'], 'Mark Spendable returns a blocked UTXO to Active', UTXO + MARK_FIRST + UNMARK_ALL + '''- assertNotVisible: { text: "Blocked", label: "Blocked section gone" }
- assertVisible: "Active"
- takeScreenshot: UTXO-03-active
- back
- back
''')

flow('UTXO-04', ['p0','utxo','send'], 'A blocked UTXO is excluded from Send (available drops by its value); restored afterwards', MW + '''- tapOn: "Deposit"
- tapOn: { id: "bottom_nav_send" }
- extendedWaitUntil: { visible: { id: "totalBTC", text: "[0-9.]+ BTC" }, timeout: 60000 }
- copyTextFrom: { id: "totalBTC" }
- evalScript: { script: "${output.before = Math.round(parseFloat(maestro.copiedText.replace(/[^0-9.]/g,''))*1e8)}", label: "available before" }
- back
- tapOn: "More options"
- tapOn: "View Unspent Outputs"
- extendedWaitUntil: { visible: { id: "utxo_item" }, timeout: 30000 }
- copyTextFrom: { id: "utxo_item_amount", index: 0 }
- evalScript: { script: "${output.u = Math.round(parseFloat(maestro.copiedText.replace(/[^0-9.]/g,''))*1e8)}", label: "utxo value" }
''' + MARK_FIRST + '''- back
- tapOn: { id: "bottom_nav_send" }
- extendedWaitUntil: { visible: { id: "totalBTC", text: "[0-9.]+ BTC" }, timeout: 60000 }
- copyTextFrom: { id: "totalBTC" }
- evalScript: { script: "${output.after = Math.round(parseFloat(maestro.copiedText.replace(/[^0-9.]/g,''))*1e8)}", label: "available after" }
- takeScreenshot: UTXO-04-send-after-block
- back
- tapOn: "More options"
- tapOn: "View Unspent Outputs"
- extendedWaitUntil: { visible: { id: "utxo_item" }, timeout: 30000 }
''' + UNMARK_ALL + '''- assertNotVisible: "Blocked"
- back
- back
- assertTrue: { condition: "${output.before - output.after === output.u}", label: "Available dropped by exactly the blocked UTXO" }
''', known='PSBT-level check (blocked coin not an input) stays manual: decode needed')

flow('SEND-01', ['smoke','p0','send'], 'Compose a normal send to the Unsigned Transaction screen (no broadcast)', SEND + ADDR + amount('0.001') + COMPOSE + '''- takeScreenshot: SEND-01-unsigned
- back
- assertVisible: { id: "composeBtn" }
- back
''')

flow('SEND-02', ['p1','send','clipboard'], 'Paste icon fills the full address', SEND + ADDR + '''- takeScreenshot: SEND-02-pasted
- back
''')

flow('SEND-04', ['smoke','p0','send'], 'Invalid addresses keep COMPOSE disabled (garbage, mainnet, bad checksum); valid control enables it', SEND + amount('0.001') + TYPE_ADDR('${GARBAGE}') + DISABLED('garbage') + TYPE_ADDR('${MAIN_ADDR}') + DISABLED('mainnet address on testnet') + TYPE_ADDR('${TB1_BADSUM}') + DISABLED('bad checksum') + '''- takeScreenshot: SEND-04-badsum
''' + TYPE_ADDR('${TB1_ADDR}') + '''- waitForAnimationToEnd: { timeout: 3000 }
- extendedWaitUntil: { visible: { id: "composeBtn", enabled: true }, timeout: 60000, label: "control: valid tb1 address enables COMPOSE" }
- back
''', known='N6: no "Invalid address" text is shown, only the disabled button')

flow('SEND-05', ['p0','send'], 'Amount above balance keeps COMPOSE disabled', SEND + ADDR + amount('5') + DISABLED('5 BTC > available') + '''- takeScreenshot: SEND-05
- back
''', known='N6: no "insufficient funds" text')

flow('SEND-06', ['p1','send'], 'Zero, empty and 9-decimal amounts are blocked or limited to 8 decimals', SEND + ADDR + amount('0') + DISABLED('amount 0') + '''- tapOn: { id: "btcEditText" }
- eraseText: 20
''' + DISABLED('empty amount') + amount('0.123456789') + '''- waitForAnimationToEnd: { timeout: 2000 }
- takeScreenshot: SEND-06-9-decimals
- assertNotVisible: { id: "btcEditText", text: "0\\\\.123456789", label: "No 9th decimal accepted" }
- back
''')

flow('SEND-07', ['p1','send'], 'Dust output (300 sat) is refused or warned', SEND + ADDR + amount('0.00000300') + '''- waitForAnimationToEnd: { timeout: 3000 }
- takeScreenshot: SEND-07-dust
- assertVisible: { id: "composeBtn", enabled: false, label: "COMPOSE disabled for a 300-sat output" }
- back
''', known='Threshold not confirmed in the plan; a fail here means a 300-sat output can be composed (no dust guard)')

flow('SEND-08', ['smoke','p0','send'], 'Tap the available amount fills the max and composes (no broadcast)', SEND + ADDR + '''- copyTextFrom: { id: "totalBTC" }
- evalScript: { script: "${output.avail = maestro.copiedText.replace(/[^0-9.]/g,'')}", label: "available" }
- tapOn: { id: "totalBTC" }
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible: { id: "btcEditText", text: "${output.avail}", label: "Amount = available" }
''' + COMPOSE + '''- takeScreenshot: SEND-08-unsigned
- back
- back
''', known='N1/#257: the max-send PSBT has an extra output map (PSBT-level, not checked here)')

flow('SEND-09', ['p2','send'], 'BTC and fiat fields convert into each other', SEND + '''- tapOn: { id: "fiatEditText" }
- eraseText: 20
- inputText: "10"
- waitForAnimationToEnd: { timeout: 3000 }
- copyTextFrom: { id: "btcEditText" }
- evalScript: { script: "${output.b = parseFloat(maestro.copiedText.replace(/[^0-9.]/g,''))}", label: "btc from fiat" }
- assertTrue: { condition: "${output.b > 0 && output.b < 1}", label: "10 fiat -> a small BTC amount" }
''' + amount('0.001') + '''- waitForAnimationToEnd: { timeout: 3000 }
- copyTextFrom: { id: "fiatEditText" }
- evalScript: { script: "${output.f = parseFloat(maestro.copiedText.replace(/[^0-9.]/g,''))}", label: "fiat from btc" }
- takeScreenshot: SEND-09
- assertTrue: { condition: "${output.f > 0}", label: "0.001 BTC -> non-zero fiat" }
- assertTrue: { condition: "${Math.abs((output.f/0.001) - (10/output.b)) / (10/output.b) < 0.02}", label: "Both directions use the same rate (2%)" }
- back
''')

flow('SEND-10', ['p1','send','fee'], 'Fee priority: Low at the left end, higher label/rate at the right end; total fee shown', SEND + ADDR + amount('0.001') + '''- assertVisible: { id: "selectedFeeRateLayman", text: "Low" }
- assertVisible: { id: "selectedFeeRate", text: "[0-9.]+ sat/vB" }
- copyTextFrom: { id: "totalMinerFee" }
- evalScript: { script: "${output.f1 = maestro.copiedText}", label: "fee at Low" }
- swipe: { start: "10%, 43%", end: "75%, 43%", duration: 1500 }
- waitForAnimationToEnd: { timeout: 3000 }
- takeScreenshot: SEND-10-max
- assertNotVisible: { id: "selectedFeeRateLayman", text: "Low", label: "Label moved off Low" }
- assertVisible: { id: "totalMinerFee", text: "[0-9.]+ BTC" }
- back
''', known='Testnet4 estimates are ~1-2 sat/vB, so Normal vs High ordering (#305) is not testable here')

flow('SEND-11', ['p1','send','fee'], 'Fee slider: min = 1 sat/vB, max raises the rate and fee; compose still works', SEND + ADDR + amount('0.001') + '''- assertVisible: { id: "selectedFeeRate", text: "1 sat/vB" }
- copyTextFrom: { id: "totalMinerFee" }
- evalScript: { script: "${output.f1 = parseFloat(maestro.copiedText.replace(/[^0-9.]/g,''))}", label: "fee at min" }
- swipe: { start: "10%, 43%", end: "75%, 43%", duration: 1500 }
- waitForAnimationToEnd: { timeout: 3000 }
- copyTextFrom: { id: "totalMinerFee" }
- evalScript: { script: "${output.f2 = parseFloat(maestro.copiedText.replace(/[^0-9.]/g,''))}", label: "fee at max" }
- copyTextFrom: { id: "selectedFeeRate" }
- evalScript: { script: "${output.r2 = parseFloat(maestro.copiedText)}", label: "rate at max" }
- takeScreenshot: SEND-11-max
- assertTrue: { condition: "${output.r2 > 1 && output.f2 > output.f1}", label: "Max rate > 1 sat/vB and fee rose" }
''' + COMPOSE + '''- back
- back
''', known='Max is 1.5x the Dojo High estimate (about 2 sat/vB on testnet4); no custom fee (#77). PSBT rate not decoded here')

flow('SEND-13', ['p0','send'], 'Amount above the largest UTXO composes (several inputs)', SEND + ADDR + amount('0.12') + COMPOSE + '''- takeScreenshot: SEND-13-unsigned
- back
- back
''', known='Input count/sums need a PSBT decode (manual); here only "composes without error"')

flow('SEND-16', ['p1','send'], 'From dropdown switches the available balance (Cold Storage: 2 keys)', CS + '''- tapOn: { id: "bottom_nav_send" }
- extendedWaitUntil: { visible: { id: "totalBTC", text: "[0-9.]+ BTC" }, timeout: 60000 }
- copyTextFrom: { id: "totalBTC" }
- evalScript: { script: "${output.t1 = maestro.copiedText}", label: "available key 1" }
- copyTextFrom: { id: "pubKeySelector" }
- evalScript: { script: "${output.k1 = maestro.copiedText}", label: "key 1" }
- tapOn: { id: "pubKeySelector" }
- waitForAnimationToEnd: { timeout: 3000 }
- takeScreenshot: SEND-16-dropdown
- tapOn:
    text: "^(?!${output.k1}$).+"
    below: { id: "pubKeySelector" }
    label: "Pick the other key"
- waitForAnimationToEnd: { timeout: 3000 }
- copyTextFrom: { id: "totalBTC" }
- evalScript: { script: "${output.t2 = maestro.copiedText}", label: "available key 2" }
- assertTrue: { condition: "${output.t1 !== output.t2}", label: "Balance switched with the key" }
- back
''')

flow('SEND-17', ['p2','send'], 'Back from the PSBT screen returns to a usable Send form', SEND + ADDR + amount('0.001') + COMPOSE + '''- back
- assertVisible: { id: "composeBtn" }
- assertVisible: { id: "btcAddress" }
- takeScreenshot: SEND-17-after-back
- back
''')

flow('SEND-18', ['p1','send','rotation'], 'Rotate on a filled Send form: values kept, no crash', SEND + ADDR + amount('0.001') + '''- setOrientation: LANDSCAPE_LEFT
- waitForAnimationToEnd: { timeout: 5000 }
- takeScreenshot: SEND-18-landscape
- setOrientation: PORTRAIT
- waitForAnimationToEnd: { timeout: 5000 }
- assertVisible: { id: "btcAddress", text: "${TB1_ADDR}" }
- assertVisible: { id: "btcEditText", text: "0\\\\.001" }
- back
''')

flow('PSBT-02', ['p1','psbt','clipboard'], 'COPY puts the PSBT on the clipboard', SEND + ADDR + amount('0.001') + COMPOSE + '''- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "placeholder" }, label: "clear clipboard" }
- tapOn: { id: "psbtCopyBtn" }
- runScript: { file: ../scripts/js/clip_info.js, label: "clipboard length + prefix" }
- assertTrue: { condition: "${output.clipLen > 200 && (output.clipPrefix.indexOf('70736274ff') === 0 || output.clipPrefix.indexOf('cHNidP') === 0)}", label: "Clipboard holds a PSBT (hex or base64)" }
- back
- back
''')

flow('PSBT-03', ['p2','psbt'], 'Share menu lists the 4 export options; Share as text does not crash', SEND + ADDR + amount('0.001') + COMPOSE + '''- tapOn: { id: "psbtShareBtn" }
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible: "Share as text"
- assertVisible: "Save as text file"
- assertVisible: "Share as psbt"
- assertVisible: "Save as psbt file"
- takeScreenshot: PSBT-03-menu
- tapOn: "Share as text"
- waitForAnimationToEnd: { timeout: 5000 }
- takeScreenshot: PSBT-03-share
- back
- runFlow: ../subflows/home_basic.yaml
''', known='ATD has no share targets; this checks the menu and that the share intent does not crash the app')

flow('ROB-11', ['p1','send','robustness'], 'Double-tap COMPOSE opens one Unsigned Transaction screen', SEND + ADDR + amount('0.001') + '''- doubleTapOn: { id: "composeBtn" }
- extendedWaitUntil: { visible: { id: "broadCastTransactionBtn" }, timeout: 60000 }
- back
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible: { id: "composeBtn", label: "One Back returns to the form: only one PSBT screen was opened" }
- assertNotVisible: { id: "broadCastTransactionBtn" }
- back
''', known='Only COMPOSE is double-tapped (BROADCAST is never tapped by this suite)')
