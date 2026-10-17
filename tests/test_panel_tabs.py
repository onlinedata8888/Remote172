"""Gesture Remote <-> Bluetooth Remote Panel: same bottom bar on both (Back | Mobile icon | Bluetooth icon), icons fixed, active one highlighted."""
import os
from playwright.sync_api import sync_playwright
HTML = 'file://' + os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'app', 'assets', 'remote.html'))
FAKE = open(os.path.join(os.path.dirname(__file__), 'test_trackpad_page.py')).read().split('FAKE = """')[1].split('"""')[0]
HIDF = open(os.path.join(os.path.dirname(__file__), 'test_bt_pad.py')).read().split('HIDF = """')[1].split('"""')[0]
res = []
def check(n, c, extra=''):
    res.append(bool(c)); print(('PASS' if c else 'FAIL'), '-', n, extra)
with sync_playwright() as p:
    b = p.chromium.launch(); ctx = b.new_context(viewport={'width': 390, 'height': 844}, has_touch=True); pg = ctx.new_page(); errs = []
    pg.on('pageerror', lambda e: errs.append(str(e))); pg.add_init_script(FAKE); pg.add_init_script(HIDF); pg.goto(HTML); pg.wait_for_timeout(500)
    cls = lambda i: pg.locator(i).get_attribute('class')
    box = lambda i: pg.locator(i).bounding_box()
    pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(450)
    check('Gesture Remote title', pg.locator('.gt-title').inner_text() == 'Gesture Remote')
    g = [box(i) for i in ('#gtBack', '#gtMsTab', '#gtBtOpen')]
    check('Mobile page: Back | Mobile icon | Bluetooth icon in a row', g[0]['x'] < g[1]['x'] < g[2]['x'] and abs(g[0]['y'] - g[1]['y']) < 1 and abs(g[1]['y'] - g[2]['y']) < 1, g)
    check('Mobile icon highlighted, Bluetooth icon not', ' on' in cls('#gtMsTab') and ' on' not in cls('#gtBtOpen'))
    pg.locator('#gtMsTab').click(); pg.wait_for_timeout(300)
    check('tapping the active (Mobile) icon does nothing', 'open' in cls('#gtPage') and 'open' not in cls('#btpPage'))
    pg.locator('#gtBtOpen').click(); pg.wait_for_timeout(450)
    check('Bluetooth icon opens Bluetooth Remote Panel', 'open' in cls('#btpPage') and pg.locator('.btp-title').inner_text() == 'Bluetooth Remote Panel')
    h = [box(i) for i in ('#btpBack', '#btpMsTab', '#btpBtTab')]
    check('Bluetooth page: same 3 icons in the same row', h[0]['x'] < h[1]['x'] < h[2]['x'] and abs(h[0]['y'] - h[1]['y']) < 1, h)
    same = all(abs(a['x'] - c['x']) < 1 and abs(a['y'] - c['y']) < 1 and abs(a['width'] - c['width']) < 1 for a, c in zip(g, h))
    check('icons do NOT change position between the two panels', same, (g, h))
    check('Bluetooth icon highlighted, Mobile icon not', ' on' in cls('#btpBtTab') and ' on' not in cls('#btpMsTab'))
    pg.locator('#btpBtTab').click(); pg.wait_for_timeout(300)
    check('tapping the active (Bluetooth) icon does nothing', 'open' in cls('#btpPage'))
    pg.locator('#btpMsTab').click(); pg.wait_for_timeout(450)
    check('Mobile icon goes to Gesture Remote (highlighted)', 'open' not in cls('#btpPage') and 'open' in cls('#gtPage') and ' on' in cls('#gtMsTab') and ' on' not in cls('#gtBtOpen'))
    pg.locator('#gtBtOpen').click(); pg.wait_for_timeout(450)
    pg.locator('#btpBack').click(); pg.wait_for_timeout(450)
    check('Back (Bluetooth panel) returns to the remote', 'open' not in cls('#btpPage') and 'open' not in cls('#gtPage'))
    check('no JS errors', not errs, errs)
    print(sum(res), '/', len(res)); b.close()
