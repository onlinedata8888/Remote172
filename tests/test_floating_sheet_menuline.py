"""Gesture Remote: edit sheet floats (draggable, stage size unchanged) + vertical faded Menu line (swipe in from the right = TV menu)."""
import os
from playwright.sync_api import sync_playwright
HTML = 'file://' + os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'app', 'assets', 'remote.html'))
FAKE = open(os.path.join(os.path.dirname(__file__), 'test_gt_gestures.py'), encoding='utf-8').read().split('FAKE = """')[1].split('"""')[0]
res = []
def check(n, c, extra=''):
    res.append(bool(c)); print(('PASS' if c else 'FAIL'), '-', n, extra)
def drag(pg, x0, y0, x1, y1, steps=8):
    pg.mouse.move(x0, y0); pg.mouse.down()
    for i in range(1, steps + 1):
        pg.mouse.move(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps); pg.wait_for_timeout(10)
    pg.mouse.up()
with sync_playwright() as p:
    b = p.chromium.launch(); errs = []
    pg = b.new_context(viewport={'width': 390, 'height': 844}).new_page()
    pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.add_init_script(FAKE); pg.goto(HTML); pg.wait_for_timeout(500)
    pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(400)
    s0 = pg.locator('#gtStage').bounding_box(); p0 = pg.locator('#gtPad').bounding_box()
    pg.locator('#gtEdit').click(); pg.wait_for_timeout(200)
    s1 = pg.locator('#gtStage').bounding_box(); p1 = pg.locator('#gtPad').bounding_box()
    check('edit on: stage size unchanged', s0 == s1, (s0, s1))
    check('edit on: pad position/size unchanged', p0 == p1, (p0, p1))
    sh = pg.locator('#gtSheet'); check('sheet visible', sh.is_visible())
    check('sheet floats (position:absolute)', pg.evaluate("getComputedStyle(document.getElementById('gtSheet')).position") == 'absolute')
    a = sh.bounding_box(); h = pg.locator('#gtSheetH').bounding_box()
    drag(pg, h['x'] + 60, h['y'] + 12, h['x'] + 60, h['y'] - 300)
    a2 = sh.bounding_box()
    check('sheet dragged up', a2['y'] < a['y'] - 200, (a['y'], a2['y']))
    check('stage still same size after drag', pg.locator('#gtStage').bounding_box() == s0)
    rz = pg.locator('#gtSheetRz').bounding_box(); a3 = sh.bounding_box()
    drag(pg, rz['x'] + 20, rz['y'] + 20, rz['x'] - 100, rz['y'] - 80)
    a4 = sh.bounding_box()
    check('sheet resizes smaller (w and h)', a4['width'] < a3['width'] - 60 and a4['height'] < a3['height'] - 40, (a3, a4))
    check('sheet top-left stays while resizing', abs(a4['x'] - a3['x']) < 2 and abs(a4['y'] - a3['y']) < 2)
    check('stage still same size after resize', pg.locator('#gtStage').bounding_box() == s0)
    pg.locator('#gtSheetRz').scroll_into_view_if_needed()
    pg.locator('#gtSheetMin').click(); pg.wait_for_timeout(100)
    check('collapse works', sh.bounding_box()['height'] < 60, sh.bounding_box())
    pg.locator('#gtSheetMin').click(); pg.wait_for_timeout(100)
    # add menu line
    pg.locator('#gtPal button', has_text='Menu line').click(); pg.wait_for_timeout(150)
    bb = pg.evaluate("(function(){var e=document.querySelector('.gt-btn.wd-mline');var r=e.getBoundingClientRect();return [r.width,r.height];})()")
    check('menu line default is vertical (taller than wide)', bb[1] > bb[0] * 5, bb)
    pg.locator('#gtDone').click(); pg.wait_for_timeout(200)
    r = pg.evaluate("(function(){var e=document.querySelector('.gt-btn.wd-mline');var r=e.getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height};})()")
    cy = r['y'] + r['h'] / 2; lx = r['x'] + r['w'] / 2
    pg.evaluate("window.__keys=[]")
    drag(pg, lx + 36, cy, lx - 20, cy)   # start 36px to the RIGHT of the line, swipe towards / past it
    k = pg.evaluate("window.__keys")
    check('swipe in from right side = menu key (82)', any(x[0] == 82 for x in k), k)
    pg.evaluate("window.__keys=[]")
    drag(pg, lx - 60, cy, lx + 20, cy)   # wrong direction
    check('left -> right = nothing', pg.evaluate("window.__keys") == [], pg.evaluate("window.__keys"))
    check('no JS errors', not errs, errs)
    b.close()
print('%d/%d passed' % (sum(res), len(res)))
raise SystemExit(0 if all(res) else 1)
