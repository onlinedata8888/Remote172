"""Gesture Remote: Home / Recent gesture switches (edit mode), Recent = swipe up + hold, thin 'Menu line' widget (swipe right->left = TV menu)."""
import os
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
    pg = b.new_context(viewport={'width': 390, 'height': 844}).new_page(); errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.add_init_script(FAKE); pg.goto(HTML); pg.wait_for_timeout(500)
    pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(400)
    pg.evaluate("window.__y=0; document.querySelector('#panePage [data-key=\"yellow\"]').addEventListener('click',function(){window.__y++;});")
    sg = pg.locator('#gtStage').bounding_box(); pb = pg.locator('#gtPad').bounding_box()
    mx = sg['x'] + sg['width'] / 2; by = pb['y'] + pb['height'] + 25

    # ---- defaults: both gestures on
    clear(pg); drag(pg, mx, by, mx, by - 90)
    check('Home: swipe up + release = Home', keys(pg) == [[3, 3]] and pg.evaluate("window.__y") == 0, keys(pg))
    clear(pg); drag(pg, mx, by, mx, by - 90, hold=700)
    check('Recent: swipe up + hold = Recent (no Home)', pg.evaluate("window.__y") == 1 and [3, 3] not in keys(pg), keys(pg))
    pg.evaluate("window.__y=0")

    # ---- edit mode switches
    pg.locator('#gtEdit').click(); pg.wait_for_timeout(200)
    check('both switches visible and ON by default', pg.locator('#gtGHome').is_checked() and pg.locator('#gtGRecent').is_checked())
    pg.locator('#gtGRecent').uncheck()
    pg.locator('#gtEdit').click(); pg.wait_for_timeout(200)
    pb = pg.locator('#gtPad').bounding_box(); by = pb['y'] + pb['height'] + 25
    clear(pg); drag(pg, mx, by, mx, by - 90)
    check('Recent OFF: Home still works (immediately)', keys(pg) == [[3, 3]], keys(pg))
    clear(pg); drag(pg, mx, by, mx, by - 90, hold=700)
    check('Recent OFF: hold does NOT open Recent', pg.evaluate("window.__y") == 0 and keys(pg) == [[3, 3]], keys(pg))
    pg.locator('#gtEdit').click(); pg.locator('#gtGRecent').check(); pg.locator('#gtGHome').uncheck(); pg.locator('#gtEdit').click(); pg.wait_for_timeout(200)
    clear(pg); drag(pg, mx, by, mx, by - 90)
    check('Home OFF: quick swipe up does nothing', keys(pg) == [] and pg.evaluate("window.__y") == 0, keys(pg))
    clear(pg); drag(pg, mx, by, mx, by - 90, hold=700)
    check('Home OFF: hold still gives Recent', pg.evaluate("window.__y") == 1 and [3, 3] not in keys(pg), keys(pg))
    pg.evaluate("window.__y=0")
    pg.locator('#gtEdit').click(); pg.locator('#gtGRecent').uncheck(); pg.locator('#gtEdit').click(); pg.wait_for_timeout(200)
    clear(pg); drag(pg, mx, by, mx, by - 90, hold=700)
    check('both OFF: nothing happens', keys(pg) == [] and pg.evaluate("window.__y") == 0, keys(pg))
    pg.reload(); pg.wait_for_timeout(500); pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(400); pg.locator('#gtEdit').click()
    check('switch state saved after reload (both OFF)', not pg.locator('#gtGHome').is_checked() and not pg.locator('#gtGRecent').is_checked())
    pg.locator('#gtReset').click(); pg.wait_for_timeout(100)
    check('Reset turns both back ON', pg.locator('#gtGHome').is_checked() and pg.locator('#gtGRecent').is_checked())
    pg.locator('#gtEdit').click()

    # ---- Menu line widget
    pg.locator('#gtEdit').click(); pg.wait_for_timeout(100)
    pg.locator('#gtPal button', has_text='Menu line').click(); pg.wait_for_timeout(150)
    ml = pg.locator('.gt-btn.wd-mline'); check('Menu line added', ml.count() == 1)
    bb = ml.bounding_box(); check('Menu line is thin + vertical', bb['width'] <= 8 and bb['height'] >= 100, bb)
    pg.locator('#gtEdit').click(); pg.wait_for_timeout(100)
    bb = ml.bounding_box(); cx, cy = bb['x'] + bb['width'] / 2, bb['y'] + bb['height'] / 2
    clear(pg); drag(pg, cx + 40, cy, cx - 40, cy)
    check('swipe right -> left on the line = TV menu (82)', keys(pg) == [[82, 3]], keys(pg))
    clear(pg); drag(pg, cx - 40, cy, cx + 40, cy)
    check('swipe left -> right = nothing', keys(pg) == [], keys(pg))
    clear(pg); pg.mouse.click(cx, cy); pg.wait_for_timeout(80)
    check('tap = nothing', keys(pg) == [], keys(pg))
    clear(pg); drag(pg, cx + 40, cy + 12, cx - 40, cy + 12)           # a bit off the 6px line: invisible hit padding still catches it
    check('swipe slightly off the thin line still works (hit padding)', keys(pg) == [[82, 3]], keys(pg))
    clear(pg); drag(pg, cx + 8, cy, cx - 10, cy)
    check('tiny swipe = nothing', keys(pg) == [], keys(pg))
    # movable + saved
    pg.locator('#gtEdit').click(); pg.wait_for_timeout(100)
    ml = pg.locator('.gt-btn.wd-mline'); bb = ml.bounding_box()
    drag(pg, bb['x'] + bb['width'] / 2, bb['y'] + bb['height'] / 2, bb['x'] + bb['width'] / 2 + 30, bb['y'] + bb['height'] / 2 - 60)
    bb2 = ml.bounding_box(); check('Menu line can be moved in edit mode', abs(bb2['y'] - bb['y']) > 40, f"{bb['y']} -> {bb2['y']}")
    pg.evaluate("var s=document.getElementById('gtBH'); s.value=10; s.dispatchEvent(new Event('input'))")
    check('height slider reaches thin values', abs(ml.bounding_box()['height'] - 10) < 1.5, ml.bounding_box()['height'])
    pg.locator('#gtEdit').click(); pg.reload(); pg.wait_for_timeout(500); pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(400)
    check('Menu line saved after reload', pg.locator('.gt-btn.wd-mline').count() == 1)
    check('no JS errors', not errs, errs)
    b.close()
print(sum(res), '/', len(res))
