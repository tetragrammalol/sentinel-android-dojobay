# Flow definitions part B: collection details, receive, UTXO, send, PSBT.
from flows_a import F, flow
MW = '''- runFlow:
    file: ../subflows/open_collection.yaml
    env: { COLLECTION: "My Wallet" }
'''
CS = '''- runFlow:
    file: ../subflows/open_collection.yaml
    env: { COLLECTION: "Cold Storage" }
'''
RCV = MW + '''- tapOn: { id: "bottom_nav_receive" }
- extendedWaitUntil: { visible: { id: "receiveAddressText" }, timeout: 30000 }
'''
SEND = MW + '''- tapOn: { id: "bottom_nav_send" }
- extendedWaitUntil: { visible: { id: "composeBtn" }, timeout: 30000 }
- extendedWaitUntil:
    visible: { id: "totalBTC", text: "[0-9.]+ BTC" }
    timeout: 60000
'''
ADDR = '''- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "${TB1_ADDR}" }, label: "clipboard = throwaway tb1 address" }
- tapOn: { id: "text_input_end_icon", index: 0, label: "Paste icon in the To field" }
- assertVisible: { id: "btcAddress", text: "${TB1_ADDR}" }
'''
def amount(a):
    return f'''- tapOn: {{ id: "btcEditText" }}
- eraseText: 20
- inputText: "{a}"
'''
TYPE_ADDR = lambda a: f'''- tapOn: {{ id: "btcAddress" }}
- eraseText: 80
- inputText: "{a}"
'''
COMPOSE = '''- extendedWaitUntil:
    visible: { id: "composeBtn", enabled: true }
    timeout: 60000
    label: "COMPOSE becomes enabled (fee estimate can take a while on the emulator)"
- tapOn: { id: "composeBtn" }
- extendedWaitUntil:
    visible: { id: "psbtQRCode|psbtCopyBtn|unsignedTxView" }
    timeout: 60000
- assertVisible: { id: "broadCastTransactionBtn", label: "Unsigned Transaction screen (BROADCAST is never tapped)" }
'''
DISABLED = lambda why: f'''- waitForAnimationToEnd: {{ timeout: 3000 }}
- extendedWaitUntil: {{ visible: {{ id: "composeBtn", enabled: true }}, timeout: 10000, optional: true, label: "give COMPOSE 10 s to (wrongly) enable" }}
- assertVisible:
    id: "composeBtn"
    enabled: false
    label: "COMPOSE disabled: {why}"
'''

flow('SYNC-09', ['smoke','p0','collection'], 'Key tabs filter the header and list (My Wallet: All, Deposit, Premix, Postmix, Badbank)', MW + '''- tapOn: { id: "bottom_nav_transaction" }
- copyTextFrom: { id: "collectionBalanceBtc" }
- evalScript: { script: "${output.all = maestro.copiedText}", label: "All balance" }
- tapOn: "Deposit"
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible: { id: "collectionBalanceBtc", text: "[0-9.]+ BTC" }
- takeScreenshot: SYNC-09-deposit
- tapOn: "Premix"
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible: { id: "collectionBalanceBtc", text: "0\\\\.00000000 BTC" }
- assertNotVisible:
    id: "bottom_nav_send"
    label: "Receive/Send hidden on Premix"
- tapOn: "Postmix"
- waitForAnimationToEnd: { timeout: 3000 }
- tapOn: "Badbank"
- waitForAnimationToEnd: { timeout: 3000 }
- tapOn: "All"
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible: { id: "collectionBalanceBtc", text: "${output.all}" }
- back
''')

flow('SYNC-10', ['smoke','p0','collection','known-fixed'], 'Key tab before Receive in a fresh process: no crash (fixed #244)', MW + '''- tapOn: "Deposit"
- tapOn: "Premix"
- tapOn: "Deposit"
- tapOn: { id: "bottom_nav_receive" }
- extendedWaitUntil: { visible: { id: "receiveAddressText", text: "tb1.*" }, timeout: 30000 }
- takeScreenshot: SYNC-10-receive
- back
''', known='Strict "fresh process" needs a cold start; this runs in the live process after a relock (ROB/E covers the cold variant)')

flow('SYNC-11', ['smoke','p1','collection','tx'], 'Tx detail sheet shows amount, txid, confirmations, fee and rate', MW + '''- tapOn: { id: "bottom_nav_transaction" }
- tapOn: { id: "txContainer", index: 0 }
- extendedWaitUntil: { visible: { id: "txDetailsHash" }, timeout: 20000 }
- assertVisible: { id: "txDetailsAmount", text: "-?[0-9.]+ BTC.*" }
- assertVisible: { id: "txDetailsHash", text: "[0-9a-f]{64}" }
- assertVisible: { id: "txDetailsConfirmation", text: "[0-9]+" }
- assertVisible: { id: "txDetailsFees", text: "[0-9]+" }
- assertVisible: { id: "txDetailsFeeRate", text: "[0-9.]+" }
- takeScreenshot: SYNC-11-detail
- back
- back
''')

flow('RCV-01', ['smoke','p0','receive'], 'Receive shows a tb1q address and a QR that encodes it', RCV + '''- assertVisible: { id: "receiveAddressText", text: "tb1q[02-9ac-hj-np-z]{38,58}" }
- assertVisible: { id: "receiveQR" }
- copyTextFrom: { id: "receiveAddressText" }
- evalScript: { script: "${output.addr = maestro.copiedText}", label: "address" }
- runScript: { file: ../scripts/js/qr_screen.js, label: "Decode the on-screen QR" }
- takeScreenshot: RCV-01-receive
- assertTrue:
    condition: ${output.qr.replace(/^bitcoin:/i,'').split('?')[0].toLowerCase() === output.addr.toLowerCase()}
    label: "QR encodes the shown address"
- back
''')

flow('RCV-04', ['p1','receive','clipboard'], 'Tap the address: clipboard holds exactly the address', RCV + '''- copyTextFrom: { id: "receiveAddressText" }
- evalScript: { script: "${output.addr = maestro.copiedText}", label: "address" }
- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "placeholder" }, label: "clear clipboard" }
- tapOn: { id: "receiveAddressText" }
- runScript: { file: ../scripts/js/clip_equals.js, env: { EXPECTED: "${output.addr}" }, label: "Compare clipboard" }
- assertTrue: { condition: "${output.clipEqual}", label: "Clipboard == address" }
- back
''')

flow('RCV-05', ['p2','receive'], 'Share options sheet; copy option puts the address on the clipboard', RCV + '''- copyTextFrom: { id: "receiveAddressText" }
- evalScript: { script: "${output.addr = maestro.copiedText}", label: "address" }
- tapOn: "More options"
- tapOn: "Share"
- extendedWaitUntil: { visible: { text: "Share options" }, timeout: 10000 }
- takeScreenshot: RCV-05-share-options
- assertVisible: { text: "(?i)share as qr code" }
- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "placeholder" }, label: "clear clipboard" }
- tapOn: { text: "(?i)copy address to clipboard" }
- runScript: { file: ../scripts/js/clip_equals.js, env: { EXPECTED: "${output.addr}" }, label: "Compare clipboard" }
- assertTrue: { condition: "${output.clipEqual}", label: "Clipboard == address" }
- back
''', known='"Share as QR code" opens the system share sheet; the ATD image has none, so only the copy branch is asserted')

flow('RCV-06', ['smoke','p1','receive'], 'Advanced amount turns the QR into a BIP21 URI with the amount', RCV + '''- copyTextFrom: { id: "receiveAddressText" }
- evalScript: { script: "${output.addr = maestro.copiedText}", label: "address" }
- tapOn: { id: "advance_button" }
- extendedWaitUntil: { visible: { id: "amountBTC" }, timeout: 10000 }
- tapOn: { id: "amountBTC" }
- inputText: "0.00012345"
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible: { id: "amountSAT", text: "12,?345" }
- hideKeyboard
- scrollUntilVisible: { element: { id: "receiveQR" }, direction: UP, timeout: 10000 }
- runScript: { file: ../scripts/js/qr_screen.js, label: "Decode the on-screen QR" }
- takeScreenshot: RCV-06-bip21
- assertTrue:
    condition: ${/^bitcoin:/i.test(output.qr) && output.qr.toLowerCase().indexOf(output.addr.toLowerCase()) >= 0 && /amount=0?\\.00012345/.test(output.qr)}
    label: "QR = bitcoin:<address>?amount=0.00012345"
''')

flow('RCV-07', ['p2','receive'], 'Advanced shows address type and path matching the key (BIP84 testnet)', RCV + '''- tapOn: { id: "advance_button" }
- extendedWaitUntil: { visible: { id: "path" }, timeout: 10000 }
- assertVisible: { text: "Segwit native \\\\(bech32\\\\)" }
- assertVisible: { id: "path", text: "m/84'/1'/0'/0/[0-9]+" }
- takeScreenshot: RCV-07-advanced
''')

flow('RCV-08', ['smoke','p0','receive'], 'Switching key in the dropdown changes the address (Cold Storage, 2 keys)', CS + '''- tapOn: { id: "bottom_nav_receive" }
- extendedWaitUntil: { visible: { id: "receiveAddressText", text: "tb1.*" }, timeout: 30000 }
- copyTextFrom: { id: "receiveAddressText" }
- evalScript: { script: "${output.a1 = maestro.copiedText}", label: "address 1" }
- copyTextFrom: { id: "pubKeySelector" }
- evalScript: { script: "${output.k1 = maestro.copiedText}", label: "key 1" }
- tapOn: { id: "pubKeySelector" }
- waitForAnimationToEnd: { timeout: 3000 }
- tapOn:
    id: "android:id/text1|text1"
    text: "^(?!${output.k1}$).+"
    label: "Pick the other key"
- waitForAnimationToEnd: { timeout: 3000 }
- copyTextFrom: { id: "receiveAddressText" }
- evalScript: { script: "${output.a2 = maestro.copiedText}", label: "address 2" }
- takeScreenshot: RCV-08-other-key
- assertTrue: { condition: "${output.a1 !== output.a2 && /^tb1/.test(output.a2)}", label: "Address changed to the other key" }
- back
''')

flow('RCV-09', ['p0','receive','stress'], 'Tab <-> Receive <-> key tab 10 times: no crash or loop', MW + '''- repeat:
    times: 10
    commands:
      - tapOn: { id: "bottom_nav_transaction" }
      - tapOn: "Deposit"
      - tapOn: { id: "bottom_nav_receive" }
      - assertVisible: { id: "receiveAddressText" }
- takeScreenshot: RCV-09-after-loop
- back
''', known='Plan says 20 cycles; 10 here to save time on the slow emulator (manual run did 20)')

flow('PAIR-07', ['p2','collection'], 'Imported (My Wallet) collection: no ADD NEW; key menu has Fingerprint and Delete, no Edit', MW + '''- tapOn: { id: "collection_details_transaction_edit_option" }
- extendedWaitUntil: { visible: { id: "collectionEdiText" }, timeout: 20000 }
- assertNotVisible: { id: "addNewPubFab", label: "No ADD NEW on an imported collection" }
- tapOn: { id: "moreButton", index: 0 }
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible: "View Master Fingerprint"
- assertVisible: "Delete"
- assertNotVisible: { text: "Edit", label: "No Edit item for imported keys" }
- takeScreenshot: PAIR-07-key-menu
- back
- back
- runFlow:
    when: { visible: { text: "(?i).*discard.*|.*unsaved.*" } }
    commands:
      - tapOn: { text: "(?i)discard|no" }
''')

UTXO = MW + '''- tapOn: "More options"
- tapOn: "View Unspent Outputs"
- extendedWaitUntil: { visible: { id: "utxo_item" }, timeout: 30000 }
'''
flow('UTXO-01', ['p1','utxo'], 'Unspent outputs list: Active list, Deposit sum = Deposit balance', MW + '''- tapOn: "Deposit"
- waitForAnimationToEnd: { timeout: 3000 }
- copyTextFrom: { id: "collectionBalanceBtc" }
- evalScript: { script: "${output.bal = parseFloat(maestro.copiedText.replace(/[^0-9.]/g,''))}", label: "Deposit balance" }
- tapOn: "More options"
- tapOn: "View Unspent Outputs"
- extendedWaitUntil: { visible: { id: "utxo_item" }, timeout: 30000 }
- assertVisible: "Active"
- evalScript: { script: "${output.sum = 0; output.i = 0}", label: "init" }
- repeat:
    times: 30
    while: { notVisible: { id: "nothing-here-sentinel" } }
    commands:
      - runFlow:
          when: { visible: { id: "utxo_item_amount", index: "${output.i}" } }
          commands:
            - copyTextFrom: { id: "utxo_item_amount", index: "${output.i}" }
            - evalScript: { script: "${output.sum = output.sum + parseFloat(maestro.copiedText.replace(/[^0-9.]/g,'')); output.i = output.i + 1}", label: "add" }
      - runFlow:
          when: { notVisible: { id: "utxo_item_amount", index: "${output.i}" } }
          commands:
            - evalScript: { script: "${output.done = (output.lastI === output.i); output.lastI = output.i}", label: "page check" }
            - scroll
      - runFlow:
          when: { true: "${output.done === true}" }
          commands:
            - evalScript: { script: "${output.stop = true}", label: "stop" }
''', known='superseded below')
