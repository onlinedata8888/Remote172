"""Mobile Style page: PowerPoint-like smart guides. Moving a button next to another snaps it into one line (pink line shown),
resizing snaps to the same size as the other button."""
import os
from playwright.sync_api import sync_playwright
HTML = 'file://' + os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'app', 'assets', 'remote.html'))
FAKE = open(os.path.join(os.path.dirname(__file__), 'test_trackpad_page.py')).read().split('FAKE = "\"\"')[1].split('"\"\"')[0]
res = []
def check(n, c, extra=''):
    res.append(bool(c)); print(('PASS' if c else 'FAIL'), '-', n, extra)
with sync_playwright() as p:
    b = p.chromium.launch(); ctx = b.new_context(viewport={'width': 390, 'height': 844}); pg = ctx.new_page(); errs = []
    pg.on('pageerror', lambda e: errs.append(str(e))); pg.add_init_script(FAKE); pg.goto(HTML); pg.wait_for_timeout(500)
    pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(450)
    pg.locator('#gtEdit').click(); pg.wait_for_timeout(100)
    pg.locator('#gtPal button', has_text='Home').first.click()
    pg.locator('#gtPal button', has_text='Back').first.click()
    pg.wait_for_timeout(100)
    bt = pg.locator('.gt-btn')
    check('two buttons placed', bt.count() == 2, bt.count())
    # make Home (first) bigger so a resize-match can be tested
    pg.locator('.gt-btn').nth(0).click(); pg.locator('#gtBWV').fill('80'); pg.keyboard.press('Enter'); pg.locator('#gtBHV').fill('80'); pg.keyboard.press('Enter')
    a = bt.nth(0).bounding_box(); c = bt.nth(1).bounding_box()
    # drag button 2 so that its centre-y is ~3px off button 1's centre-y -> must snap to the same centre line
    cx1, cy1 = a['x'] + a['width'] / 2, a['y'] + a['height'] / 2
    cx2, cy2 = c['x'] + c['width'] / 2, c['y'] + c['height'] / 2
    tx, ty = cx1 + 120, cy1 + 3
    pg.mouse.move(cx2, cy2); pg.mouse.down()
    for i in range(1, 9): pg.mouse.move(cx2 + (tx - cx2) * i / 8, cy2 + (ty - cy2) * i / 8); pg.wait_for_timeout(10)
    lines = pg.locator('.gt-gl').count()
    check('guide line visible while dragging', lines >= 1, lines)
    pg.mouse.up(); pg.wait_for_timeout(50)
    c2 = bt.nth(1).bounding_box(); cy2n = c2['y'] + c2['height'] / 2
    check('moved button snapped onto the same centre line', abs(cy2n - cy1) < 0.8, (cy1, cy2n))
    check('guide lines removed after release', pg.locator('.gt-gl').count() == 0)
    # resize button 2 with the corner handle towards 80 px (button 1 is 80x80): snap to same size
    bt.nth(1).click(); pg.wait_for_timeout(50)
    h = bt.nth(1).locator('.gt-bh').bounding_box(); hx, hy = h['x'] + h['width'] / 2, h['y'] + h['height'] / 2
    cur = bt.nth(1).bounding_box()['width']
    d = (80 - cur) / 2 + 2          # finger travel that gives ~82 px (2 px past equal)
    pg.mouse.move(hx, hy); pg.mouse.down()
    for i in range(1, 7): pg.mouse.move(hx + d * i / 6, hy + d * i / 6); pg.wait_for_timeout(10)
    check('matching button outlined while resizing', pg.locator('.gt-btn.gt-match').count() >= 1)
    pg.mouse.up(); pg.wait_for_timeout(50)
    nb = bt.nth(1).bounding_box()
    check('resized button snapped to the same size', abs(nb['width'] - 80) < 0.8 and abs(nb['height'] - 80) < 0.8, nb)
    check('no JS errors', not errs, errs)
    print(sum(res), '/', len(res)); b.close()
