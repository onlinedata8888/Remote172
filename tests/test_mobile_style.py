"""Mobile Style page: title, typed size values, same button added many times, new bar widgets."""
import os
from playwright.sync_api import sync_playwright
HTML = 'file://' + os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'app', 'assets', 'remote.html'))
FAKE = open(os.path.join(os.path.dirname(__file__), 'test_trackpad_page.py')).read().split('FAKE = """')[1].split('"""')[0]
res = []
def check(n, c, extra=''):
    res.append(bool(c)); print(('PASS' if c else 'FAIL'), '-', n, extra)
def keys(pg): return pg.evaluate("window.__keys")
def clear(pg): pg.evaluate("window.__keys=[]")
def drag(pg, x0, y0, x1, y1, steps=8):
    pg.mouse.move(x0, y0); pg.mouse.down()
    for i in range(1, steps + 1):
        pg.mouse.move(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps); pg.wait_for_timeout(10)
    pg.mouse.up()
with sync_playwright() as p:
    b = p.chromium.launch(); ctx = b.new_context(viewport={'width': 390, 'height': 844}); pg = ctx.new_page(); errs = []
    pg.on('pageerror', lambda e: errs.append(str(e))); pg.add_init_script(FAKE); pg.goto(HTML); pg.wait_for_timeout(500)
    pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(450)
    check('page title is Gesture Remote', pg.locator('.gt-title').inner_text() == 'Gesture Remote')
    pg.locator('#gtEdit').click(); pg.wait_for_timeout(100)
    # typed pad size
    w0 = pg.locator('#gtPad').bounding_box()['width']
    pg.locator('#gtWV').fill('70'); pg.keyboard.press('Enter'); pg.wait_for_timeout(100)
    w1 = pg.locator('#gtPad').bounding_box()['width']
    check('typing Width number changes pad + slider', w1 > w0 and pg.locator('#gtW').input_value() == '70', f'{w0}->{w1}')
    pg.locator('#gtHV').fill('30'); pg.keyboard.press('Enter'); pg.wait_for_timeout(100)
    check('typing Height number works', pg.locator('#gtH').input_value() == '30')
    # same button 3 times
    pal = pg.locator('#gtPal button')
    for _ in range(3): pg.locator('#gtPal button', has_text='Home').first.click()
    check('Home button added 3 times', pg.locator('.gt-btn').count() == 3, pg.locator('.gt-btn').count())
    # typed size of selected button
    pg.locator('#gtBWV').fill('90'); pg.keyboard.press('Enter'); pg.wait_for_timeout(100)
    check('typing Btn W number resizes selected button', abs(pg.locator('.gt-btn.sel').bounding_box()['width'] - 90) < 2, pg.locator('.gt-btn.sel').bounding_box())
    pg.locator('#gtBHV').fill('70'); pg.keyboard.press('Enter'); pg.wait_for_timeout(100)
    check('typing Btn H number resizes selected button', abs(pg.locator('.gt-btn.sel').bounding_box()['height'] - 70) < 2)
    # widgets
    for t in ['Scroll ↕', 'Scroll ↔', 'Volume bar ↕', 'Volume bar ↔', 'Nav bar', 'Touchpad']:
        pg.locator('#gtPal button', has_text=t).first.click()
    check('6 widgets added (9 items)', pg.locator('.gt-btn').count() == 9, pg.locator('.gt-btn').count())
    pg.locator('#gtDone').click(); pg.wait_for_timeout(100)
    def widget(cls):
        return pg.locator('.gt-btn.' + cls).first
    for k in range(9):
        pass
    # vertical scroll widget: drag down => down key
    ws = pg.locator('.gt-wd.wd-v:not(.wd-vol)').first; r = ws.bounding_box()
    clear(pg); drag(pg, r['x'] + r['width'] / 2, r['y'] + 20, r['x'] + r['width'] / 2, r['y'] + 100)
    ks = keys(pg); check('vertical scroll bar drag down = DOWN keys', len(ks) >= 2 and all(k == [20, 3] for k in ks), ks)
    wv = pg.locator('.gt-wd.wd-vol').first; r = wv.bounding_box()
    clear(pg); drag(pg, r['x'] + r['width'] / 2, r['y'] + 110, r['x'] + r['width'] / 2, r['y'] + 30)
    ks = keys(pg); check('volume bar drag up = VOL UP', len(ks) >= 2 and all(k == [24, 3] for k in ks), ks)
    wh = pg.locator('.gt-wd.wd-h:not(.wd-hvol)').first; r = wh.bounding_box()
    clear(pg); drag(pg, r['x'] + 20, r['y'] + r['height'] / 2, r['x'] + 110, r['y'] + r['height'] / 2)
    ks = keys(pg); check('horizontal scroll bar drag right = RIGHT keys', len(ks) >= 2 and all(k == [22, 3] for k in ks), ks)
    nb = pg.locator('.wd-nav .n-u').first; r = nb.bounding_box(); clear(pg); pg.mouse.click(r['x'] + r['width'] / 2, r['y'] + r['height'] / 2)
    check('nav bar up arrow', keys(pg) == [[19, 1], [19, 2]], keys(pg))
    t2 = pg.locator('.gt-wd.wd-pad2').first; r = t2.bounding_box(); clear(pg); pg.mouse.click(r['x'] + r['width'] / 2, r['y'] + r['height'] / 2)
    check('extra touchpad tap = OK', keys(pg) == [[23, 3]], keys(pg))
    pg.screenshot(path='/home/claude/work/shot_mobile_style.png')
    check('no JS errors', errs == [], errs)
    b.close()
print(sum(res), '/', len(res))
raise SystemExit(0 if all(res) else 1)
