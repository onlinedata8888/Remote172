"""Page-1 TV keyboard: behaves like a normal TV remote keyboard.
Every typed character goes to the native bridge ONCE (imeType), every backspace -> imeDel (also when the box holds
nothing but the invisible guard, i.e. for text that was already on the TV), Enter -> imeEnter, pairing code still works."""
import os
from playwright.sync_api import sync_playwright

HTML = 'file://' + os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'app', 'assets', 'remote.html'))
FAKE = """
window.__calls = []; window.__ime = {ready:true};
window.TVNative = {
  ready(){}, key(c,d){}, keys(c,n){}, launch(l){}, toastMsg(m){}, manualIp(){}, connect(h){}, reconnect(){}, rescan(){},
  pairCode(c){ window.__calls.push('pair:'+c); }, cancelPairing(){ window.__calls.push('cancelPairing'); },
  imeType(t){ window.__calls.push('type:'+t); }, imeDel(n){ window.__calls.push('del:'+n); },
  imeBackspace(){ window.__calls.push('del:1'); }, imeEnter(){ window.__calls.push('enter'); },
  imeValue(){ return 'old text on tv'; }, imeReady(){ return window.__ime.ready; }
};
"""
res = []
def check(n, c, x=''):
    res.append(bool(c)); print(('PASS' if c else 'FAIL'), '-', n, x)

with sync_playwright() as p:
    b = p.chromium.launch()
    page = b.new_context(viewport={'width': 400, 'height': 820}, has_touch=True).new_page()
    errs = []; page.on('pageerror', lambda e: errs.append(str(e)))
    page.add_init_script(FAKE); page.goto(HTML); page.wait_for_timeout(500)
    calls = lambda: page.evaluate('window.__calls')
    typed = lambda: ''.join(c[5:] for c in calls() if c.startswith('type:'))
    isopen = lambda: page.evaluate("document.getElementById('kbOverlay').classList.contains('open')")

    page.click('#keyboardBtn'); page.wait_for_timeout(200)
    check('overlay opens', isopen())
    check('only ONE keyboard: no custom keys, no toggle', page.query_selector('.kb-key') is None and page.query_selector('#kbPhone') is None)
    check('input is editable and focused (phone keyboard opens)', page.evaluate("document.activeElement.id") == 'kbInput' and
          not page.evaluate("document.getElementById('kbInput').hasAttribute('readonly')"))
    check('box is NOT seeded with the TV text (nothing mirrored)', 'old text' not in page.input_value('#kbInput'))
    check('hint says text box is live', 'Typing into the TV' in page.inner_text('#kbHint'))
    check('opening sends nothing to the TV', calls() == [], str(calls()))

    # ---- typing: every character exactly once
    page.keyboard.type('hello', delay=15); page.wait_for_timeout(100)
    check('typing "hello" sends hello exactly once', typed() == 'hello' and not [c for c in calls() if c.startswith('del')], str(calls()))
    page.keyboard.type(' World', delay=15); page.wait_for_timeout(100)
    check('keeps going, no repeats: "hello World" once', typed() == 'hello World', typed())
    check('no duplicate calls (11 chars = 11 sends)', len([c for c in calls() if c.startswith('type:')]) == 11, str(calls()))

    # ---- backspace deletes one character on the TV each time
    n = len(calls()); page.keyboard.press('Backspace'); page.wait_for_timeout(100)
    check('backspace -> one DEL', calls()[n:] == ['del:1'], str(calls()[n:]))
    n = len(calls())
    for _ in range(5): page.keyboard.press('Backspace')
    page.wait_for_timeout(100)
    check('5 backspaces -> 5 DEL total', sum(int(c[4:]) for c in calls()[n:] if c.startswith('del:')) == 5, str(calls()[n:]))

    # ---- THE BUG: text that was already on the TV must be deletable (box holds only the guard now)
    for _ in range(5): page.keyboard.press('Backspace')          # removes the rest of what we typed ("hello")
    page.wait_for_timeout(100)
    check('typed text is gone from the box (only the guard is left)', page.input_value('#kbInput') == '\u200b', repr(page.input_value('#kbInput')))
    n = len(calls())
    for _ in range(4): page.keyboard.press('Backspace')
    page.wait_for_timeout(100)
    check('backspace on an EMPTY box still deletes on the TV (pre-existing text)',
          sum(int(c[4:]) for c in calls()[n:] if c.startswith('del:')) == 4 and not [c for c in calls()[n:] if c.startswith('type:')], str(calls()[n:]))
    check('guard is put back so backspace keeps working', page.input_value('#kbInput') == '\u200b', repr(page.input_value('#kbInput')))

    # ---- autocorrect-like replacement / paste / select-all delete
    page.evaluate("kbPrev='\u200b'; document.getElementById('kbInput').value='\u200b'")
    page.keyboard.type('helo ', delay=10); page.wait_for_timeout(60)
    n = len(calls())
    page.evaluate("var i=document.getElementById('kbInput'); i.value='\u200bhello '; i.dispatchEvent(new Event('input',{bubbles:true}))")
    page.wait_for_timeout(60)
    check('autocorrect "helo " -> "hello ": 2 DEL ("o ") then "lo " once', calls()[n:] == ['del:2', 'type:lo '], str(calls()[n:]))
    n = len(calls())
    page.evaluate("var i=document.getElementById('kbInput'); i.value=''; i.dispatchEvent(new Event('input',{bubbles:true}))")
    page.wait_for_timeout(60)
    check('select-all delete removes exactly the typed chars (guard not counted)', calls()[n:] == ['del:6'], str(calls()[n:]))
    n = len(calls())
    page.evaluate("var i=document.getElementById('kbInput'); i.value='\u200bpasted'; i.dispatchEvent(new Event('input',{bubbles:true}))")
    page.wait_for_timeout(60)
    check('paste sends the pasted text once', calls()[n:] == ['type:pasted'], str(calls()[n:]))
    n = len(calls())
    page.evaluate("var i=document.getElementById('kbInput'); i.value='\u200bpasted\U0001F600'; i.dispatchEvent(new Event('input',{bubbles:true}))")
    page.evaluate("var i=document.getElementById('kbInput'); i.value='\u200bpasted'; i.dispatchEvent(new Event('input',{bubbles:true}))")
    page.wait_for_timeout(60)
    check('emoji (2 UTF-16 units) is ONE backspace', calls()[n:] == ['type:\U0001F600', 'del:1'], str(calls()[n:]))

    # ---- Enter
    n = len(calls()); page.click('#kbEnter'); page.wait_for_timeout(100)
    check('Enter button -> imeEnter only', calls()[n:] == ['enter'], str(calls()[n:]))
    n = len(calls()); page.keyboard.press('Enter'); page.wait_for_timeout(100)
    check('Enter key -> imeEnter only', calls()[n:] == ['enter'], str(calls()[n:]))

    # ---- TV reports its field: must not overwrite / echo anything
    before = page.input_value('#kbInput'); n = len(calls())
    page.evaluate("window.__tv.onImeField(true,'something else','Search')"); page.wait_for_timeout(100)
    check('TV report does not touch the box and sends nothing', page.input_value('#kbInput') == before and calls()[n:] == [])

    n = len(calls()); page.click('#kbDone'); page.wait_for_timeout(200)
    check('Done submits (Enter) and closes overlay', not isopen() and calls()[n:] == ['enter'], str(calls()[n:]))
    page.evaluate("window.__ime.ready=false"); page.click('#keyboardBtn'); page.wait_for_timeout(100)
    check('hint when no TV text box', 'Open a search' in page.inner_text('#kbHint'))
    n = len(calls()); page.keyboard.type('a'); page.wait_for_timeout(60)
    check('typing still works with no text box reported (plain key presses)', calls()[n:] == ['type:a'], str(calls()[n:]))
    page.click('#kbClose')

    page.evaluate("window.__tv.onPairing('Living Room TV')"); page.wait_for_timeout(150)
    check('pairing opens overlay', isopen())
    n = len(calls())
    page.keyboard.type('1aF9z023', delay=5); page.wait_for_timeout(100)
    check('pairing accepts only hex, max 6', page.input_value('#kbInput') == '1aF902', page.input_value('#kbInput'))
    check('pairing sends nothing to TV text field', not [c for c in calls()[n:] if c.startswith('type:') or c.startswith('del')])
    page.click('#kbDone')
    check('Done submits the code', 'pair:1aF902' in calls(), str(calls()))
    page.evaluate("window.__tv.onPaired()"); page.wait_for_timeout(100)
    check('overlay closes after pairing', not isopen())
    page.click('#keyboardBtn'); page.wait_for_timeout(100)
    check('after pairing the box has the guard again', page.input_value('#kbInput') == '\u200b', repr(page.input_value('#kbInput')))
    check('no JS errors', not errs, str(errs))
    b.close()
print('%d/%d passed' % (sum(res), len(res))); raise SystemExit(0 if all(res) else 1)
