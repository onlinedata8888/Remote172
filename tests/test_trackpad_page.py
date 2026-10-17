"""Trackpad page (phone icon left of the cursor icon): open, pad arrows/hold/OK, edge gestures Back/Recent, edit mode."""
import os, sys, json, time
from playwright.sync_api import sync_playwright
HTML = 'file://' + os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'app', 'assets', 'remote.html'))
FAKE = """
window.__keys = [];
window.TVNative = { ready(){}, key(c,d){ window.__keys.push([c,d]); }, keys(c,n){}, text(t){}, launch(l){}, toastMsg(m){}, manualIp(){}, connect(h){},
  reconnect(){}, rescan(){}, pairCode(c){}, cancelPairing(){}, voiceToggle(){}, castPick(k){}, galleryReady(k){return true;},
  listGalleryAlbums(k){return '[]';}, listGalleryItems(k,b){return '[]';}, castMediaStoreItem(a,b){}, castPlay(){}, castPause(){}, castStop(){}, castSeek(m){} };
"""
res = []
def check(n, c, extra=''):
    res.append(bool(c)); print(('PASS' if c else 'FAIL'), '-', n, extra)

def keys(pg): return pg.evaluate("window.__keys")
def clear(pg): pg.evaluate("window.__keys=[]")
def drag(pg, x0, y0, x1, y1, steps=8, hold=0):
    pg.mouse.move(x0, y0); pg.mouse.down()
    for i in range(1, steps + 1):
        pg.mouse.move(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps); pg.wait_for_timeout(10)
    if hold: pg.wait_for_timeout(hold)
    pg.mouse.up()

with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={'width': 390, 'height': 844}, has_touch=False)
    pg = ctx.new_page(); errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.add_init_script(FAKE); pg.goto(HTML); pg.wait_for_timeout(500)
    ob = pg.locator('#gtOpenBtn'); mb = pg.locator('#mouseBtn')
    bo, bm = ob.bounding_box(), mb.bounding_box()
    check('phone icon in left corner, mirrors navigation icon', bo["x"] + bo["width"] <= bm["x"] and abs(bo["y"] - bm["y"]) < 1 and abs((bo["x"] - 14) - (390 - 14 - (pg.locator("#pageBtn").bounding_box()["x"] + pg.locator("#pageBtn").bounding_box()["width"]))) < 6, f"{bo} {bm}")
    pg.screenshot(path='/home/claude/work/shot_main.png')
    ob.click(); pg.wait_for_timeout(500)
    check('page opens', pg.evaluate("document.getElementById('gtPage').classList.contains('open')"))
    pg.screenshot(path='/home/claude/work/shot_open.png')
    r = pg.locator('#gtPad').bounding_box(); cx, cy = r['x'] + r['width'] / 2, r['y'] + r['height'] / 2
    clear(pg); pg.mouse.click(cx, cy); pg.wait_for_timeout(100)
    check('tap = OK', keys(pg) == [[23, 3]], keys(pg))
    clear(pg); drag(pg, cx - 60, cy, cx + 60, cy)
    k = keys(pg); check('swipe right = right arrows', k and all(x == [22, 3] for x in k), k)
    clear(pg); drag(pg, cx, cy + 60, cx, cy - 60)
    k = keys(pg); check('swipe up = up arrows', k and all(x == [19, 3] for x in k), k)
    clear(pg); drag(pg, cx + 40, cy, cx - 40, cy)
    k = keys(pg); check('swipe left = left arrows', k and all(x == [21, 3] for x in k), k)
    clear(pg); drag(pg, cx, cy - 40, cx, cy + 40)
    k = keys(pg); check('swipe down = down arrows', k and all(x == [20, 3] for x in k), k)
    clear(pg); drag(pg, cx - 40, cy, cx + 40, cy, hold=900)
    k = keys(pg); check('hold after moving right = continuous right', len(k) >= 6 and all(x == [22, 3] for x in k), len(k))
    n0 = len(k); pg.wait_for_timeout(300); check('stops after release', len(keys(pg)) == n0)
    st = pg.locator('#gtPad').bounding_box()      # gestures now live INSIDE the pad (the pad = small phone)
    ym = st['y'] + st['height'] * 0.4
    clear(pg); drag(pg, st['x'] + 6, ym, st['x'] + 80, ym)
    check('swipe in from pad LEFT edge = NO Back (plain touchpad move)', [4, 3] not in keys(pg), keys(pg))
    clear(pg); drag(pg, st['x'] + st['width'] - 6, ym, st['x'] + st['width'] - 80, ym)
    check('swipe in from pad RIGHT edge = NO Back (plain touchpad move)', [4, 3] not in keys(pg), keys(pg))
    pg.evaluate("window.__y=0; var y=document.querySelector('#panePage [data-key=\\\"yellow\\\"]'); y.addEventListener('click',function(){window.__y++;});")
    clear(pg); pg.evaluate("window.__y=0")
    sg = pg.locator('#gtStage').bounding_box()
    drag(pg, sg['x'] + sg['width'] / 2, sg['y'] + sg['height'] - 6, sg['x'] + sg['width'] / 2, sg['y'] + sg['height'] - 130)
    check('outside the pad: bottom swipe up = Home', keys(pg) == [[3, 3]] and pg.evaluate("window.__y") == 0, keys(pg))
    clear(pg); drag(pg, sg['x'] + sg['width'] / 2, sg['y'] + sg['height'] - 6, sg['x'] + sg['width'] / 2, sg['y'] + sg['height'] - 130, hold=700)
    check('outside the pad: swipe up + hold = Recent', pg.evaluate("window.__y") == 1 and [3, 3] not in keys(pg), keys(pg))
    clear(pg)
    drag(pg, sg['x'] + 8, sg['y'] + sg['height'] / 2, sg['x'] + 120, sg['y'] + sg['height'] / 2)
    check('outside the pad: side swipe in = Back (as Remote5 1)', keys(pg) == [[4, 3]], keys(pg))
    pb = pg.locator('#gtPad').bounding_box()
    clear(pg); drag(pg, sg['x'] + 8, pb['y'] - 20, sg['x'] + 120, pb['y'] - 20)
    check('side swipe ABOVE the box = no Back', keys(pg) == [], keys(pg))
    clear(pg); drag(pg, sg['x'] + 8, pb['y'] + pb['height'] + 20, sg['x'] + 120, pb['y'] + pb['height'] + 20)
    check('side swipe BELOW the box = no Back', keys(pg) == [], keys(pg))
    clear(pg); drag(pg, sg['x'] + 8, pb['y'] + 10, sg['x'] + 120, pb['y'] + 10)
    check('side swipe level with box top = Back', keys(pg) == [[4, 3]], keys(pg))
    by2 = pb['y'] + pb['height'] + 25
    clear(pg); drag(pg, sg['x'] + sg['width'] / 2, by2, sg['x'] + sg['width'] / 2, by2 - 90)
    check('swipe up from area below the box = Home', keys(pg) == [[3, 3]], keys(pg))
    clear(pg); drag(pg, sg['x'] + sg['width'] / 2, by2, sg['x'] + sg['width'] / 2, by2 - 15)
    check('tiny swipe up below box = nothing', keys(pg) == [], keys(pg))
    clear(pg); drag(pg, sg['x'] + sg['width'] / 2, pb['y'] - 20, sg['x'] + sg['width'] / 2, pb['y'] - 110)
    check('swipe up above the box = nothing', keys(pg) == [], keys(pg))
    clear(pg); drag(pg, st['x'] + 6, ym, st['x'] + 18, ym)
    check('tiny edge move does nothing', keys(pg) == [])
    # edit mode
    pg.locator('#gtEdit').click()
    w0 = pg.locator('#gtPad').bounding_box()['width']
    pg.evaluate("var s=document.getElementById('gtW'); s.value=80; s.dispatchEvent(new Event('input'))")
    w1 = pg.locator('#gtPad').bounding_box()['width']; check('width slider makes pad bigger', w1 > w0 + 40, f'{w0}->{w1}')
    pg.evaluate("var s=document.getElementById('gtH'); s.value=25; s.dispatchEvent(new Event('input'))")
    check('height slider makes pad smaller', pg.locator('#gtPad').bounding_box()['height'] < r['height'])
    pg.locator('#gtSheetMin').click()   # the edit sheet now floats over the stage: collapse it so it does not cover the resize corner
    rr = pg.locator('#gtRz').bounding_box(); hb = pg.locator('#gtPad').bounding_box()
    drag(pg, rr['x'] + 15, rr['y'] + 15, rr['x'] - 40, rr['y'] - 5)
    check('corner drag resizes', pg.locator('#gtPad').bounding_box()['width'] < hb['width'] - 20)
    pg.locator('#gtSheetMin').click()   # expand the sheet again
    pg.locator('#gtPal button', has_text='Home').click(); pg.locator('#gtPal button', has_text='Vol').first.click()
    check('buttons added from palette', pg.locator('.gt-btn').count() == 2)
    pg.screenshot(path='/home/claude/work/shot_edit.png')
    xb = pg.locator('.gt-btn .gt-x').first.bounding_box(); pg.mouse.click(xb['x'] + xb['width'] / 2, xb['y'] + xb['height'] / 2); pg.wait_for_timeout(100)
    check('X click deletes the button', pg.locator('.gt-btn').count() == 1)
    pg.locator('#gtPal button', has_text='Home').click()
    check('button can be re-added after delete', pg.locator('.gt-btn').count() == 2)
    bt = pg.locator('.gt-btn').first.bounding_box(); x0, y0 = bt['x'] + 26, bt['y'] + 26
    drag(pg, x0, y0, x0 + 30, y0 + 40)
    check('dragging a button in edit mode does not delete it', pg.locator('.gt-btn').count() == 2)
    # per-button size: tap to select, sliders + corner handle
    pg.locator('.gt-btn').first.click(); pg.wait_for_timeout(80)
    check('tap selects a button, size sliders appear', pg.locator('.gt-btn.sel').count() == 1 and pg.locator('#gtBW').is_visible())
    b0 = pg.locator('.gt-btn.sel').bounding_box()
    pg.evaluate("var s=document.getElementById('gtBW'); s.value=110; s.dispatchEvent(new Event('input'))")
    b1 = pg.locator('.gt-btn.sel').bounding_box()
    check('button width slider widens only this button', b1['width'] > b0['width'] + 40 and abs(b1['height'] - b0['height']) < 2, f"{b0['width']}->{b1['width']}")
    pg.evaluate("var s=document.getElementById('gtBH'); s.value=90; s.dispatchEvent(new Event('input'))")
    b2 = pg.locator('.gt-btn.sel').bounding_box(); check('button height slider works', b2['height'] > b1['height'] + 25, f"{b1['height']}->{b2['height']}")
    check('other button unchanged', abs(pg.locator('.gt-btn:not(.sel)').first.bounding_box()['width'] - 52) < 2)
    hh = pg.locator('.gt-btn.sel .gt-bh').bounding_box(); cx0, cy0 = hh['x'] + 13, hh['y'] + 13
    drag(pg, cx0, cy0, cx0 - 30, cy0 - 25)
    b3 = pg.locator('.gt-btn.sel').bounding_box(); check('corner handle shrinks the button', b3['width'] < b2['width'] - 20 and b3['height'] < b2['height'] - 15, f"{b2['width']}x{b2['height']} -> {b3['width']}x{b3['height']}")
    pg.screenshot(path='/home/claude/work/shot_btnsize.png')
    pg.locator('#gtDone').click()
    clear(pg); hb2 = pg.locator('.gt-btn').first.bounding_box(); pg.mouse.click(hb2['x'] + 26, hb2['y'] + 26); pg.wait_for_timeout(80)
    check('placed Back/Home button sends key', keys(pg) and keys(pg)[0][1] == 1 and keys(pg)[-1][1] == 2, keys(pg))
    pg.reload(); pg.wait_for_timeout(400); pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(400)
    check('layout saved after reload', pg.locator('.gt-btn').count() == 2)
    pg.screenshot(path='/home/claude/work/shot_saved.png')
    check('android back closes the page', pg.evaluate("window.__tv.handleBack()") is True and not pg.evaluate("document.getElementById('gtPage').classList.contains('open')"))
    check('then back on main page exits (false)', pg.evaluate("window.__tv.handleBack()") is False)
    check('no JS errors', not errs, errs)
    b.close()
print(sum(res), '/', len(res)); sys.exit(0 if all(res) else 1)
