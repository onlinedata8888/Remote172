"""Bluetooth Pad: every Mobile Style button / widget (scroll bars, volume bars, nav bar, extra touchpad, CH, Recent, Search, Settings, Power ...)
can be added in Edit and works over Bluetooth HID."""
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
    def calls(): return pg.evaluate('window.__h')
    def clear(): pg.evaluate('window.__h = []')
    pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(450); pg.locator('#gtBtOpen').click(); pg.wait_for_timeout(500)
    pg.evaluate("window.__hid.onState('connected','Living TV')")
    pg.locator('#btpEdit').click(); pg.locator('#btpReset').click(); pg.wait_for_timeout(50)
    n = pg.locator('#btpPalRow button').count()
    check('palette has every Mobile Style item + the Bluetooth extras', n >= 35, n)
    want = ['recent','search','chUp','chDown','rewind','ffwd','settings','power','menu','playPause','prev','next','ok','up','down','left','right','mute','vscroll','hscroll','volbar','hvolbar','navbar','touch2']
    for k in want:
        i = pg.evaluate("window.__btpTest.order.indexOf('%s')" % k); pg.locator('#btpPalRow button').nth(i).click(); pg.wait_for_timeout(20)
    check('all items added', all(pg.locator('.btp-key[data-k=%s]' % k).count() == 1 for k in want))
    i = pg.evaluate("window.__btpTest.order.indexOf('mute')"); pg.locator('#btpPalRow button').nth(i).click()
    check('same button can be added again (x2)', pg.locator('.btp-key[data-k=mute]').count() == 2)
    pg.locator('.btp-key[data-k=mute]').nth(1).locator('.btp-x').click(); pg.wait_for_timeout(30)
    cells = [k for k in want if k not in ('vscroll','hscroll','volbar','hvolbar','navbar','touch2')]
    for j, k in enumerate(cells): pg.evaluate("window.__btpTest.setGeo('%s',%f,%f,%f,%f)" % (k, (j % 6) * 0.165, 0.02 + (j // 6) * 0.11, 0.15, 0.1))
    G = {'vscroll':(0.0,0.5,0.14,0.4), 'volbar':(0.17,0.5,0.14,0.4), 'hscroll':(0.34,0.5,0.6,0.09), 'hvolbar':(0.34,0.62,0.6,0.09), 'navbar':(0.34,0.74,0.3,0.24), 'touch2':(0.66,0.74,0.32,0.24)}
    for k, g in G.items(): pg.evaluate("window.__btpTest.setGeo('%s',%f,%f,%f,%f)" % ((k,) + g))
    pg.locator('#btpEdit').click(); pg.wait_for_timeout(80)       # Done
    def box(sel): return pg.locator(sel).first.bounding_box()
    def mid(sel): bb = box(sel); return bb['x'] + bb['width'] / 2, bb['y'] + bb['height'] / 2
    def tap(sel):
        clear(); x, y = mid(sel); pg.mouse.click(x, y); pg.wait_for_timeout(70); return calls()
    for k, u in {'recent':0x1A0,'search':0x221,'chUp':0x9C,'chDown':0x9D,'rewind':0xB4,'ffwd':0xB3,'settings':0x183,'power':0x30,'menu':0x40,'playPause':0xCD,'prev':0xB6,'next':0xB5,'mute':0xE2}.items():
        c = [x[1] for x in tap('.btp-key[data-k=%s]' % k) if x[0] == 'consumer']
        check('%s -> Bluetooth consumer 0x%X' % (k, u), u in c, c)
    for k, u in {'ok':0x28,'up':0x52,'down':0x51,'left':0x50,'right':0x4F}.items():
        c = [x[1] for x in tap('.btp-key[data-k=%s]' % k) if x[0] == 'key']
        check('%s -> Bluetooth key %d' % (k, u), u in c, c)
    def drag(sel, dx, dy):
        clear(); x, y = mid(sel); pg.mouse.move(x, y); pg.mouse.down()
        for t in range(1, 9): pg.mouse.move(x + dx * t / 8, y + dy * t / 8); pg.wait_for_timeout(12)
        pg.mouse.up(); pg.wait_for_timeout(60); return calls()
    c = drag('.btp-key[data-k=vscroll]', 0, 90); check('scroll bar drag down = wheel scroll (Bluetooth mouse)', any(x[0] == 'scroll' and x[1] != 0 for x in c) and all(x[1] < 0 for x in c if x[0] == 'scroll'), c)
    c2 = drag('.btp-key[data-k=vscroll]', 0, -90); check('scroll bar drag up = opposite wheel direction', any(x[0] == 'scroll' and x[1] > 0 for x in c2), c2)
    c = drag('.btp-key[data-k=hscroll]', 100, 0); check('horizontal scroll bar = sideways wheel', any(x[0] == 'scroll' and x[1] == 0 and x[2] != 0 for x in c), c)
    c = drag('.btp-key[data-k=volbar]', 0, -90); check('volume bar drag up = Vol+ (consumer 0xE9)', [x[1] for x in c if x[0] == 'consumer'] and all(x[1] == 0xE9 for x in c if x[0] == 'consumer'), c)
    c = drag('.btp-key[data-k=volbar]', 0, 90); check('volume bar drag down = Vol- (0xEA)', [x[1] for x in c if x[0] == 'consumer'] and all(x[1] == 0xEA for x in c if x[0] == 'consumer'), c)
    c = drag('.btp-key[data-k=hvolbar]', 100, 0); check('horizontal volume bar drag right = Vol+', [x[1] for x in c if x[0] == 'consumer'] and all(x[1] == 0xE9 for x in c if x[0] == 'consumer'), c)
    bb = box('.btp-key[data-k=volbar]'); clear(); pg.mouse.click(bb['x'] + bb['width'] / 2, bb['y'] + 10); pg.wait_for_timeout(60)
    check('tap top half of volume bar = Vol+', [x for x in calls() if x[0] == 'consumer'] == [['consumer', 0xE9]], calls())
    for n_, u in {'up':0x52,'down':0x51,'left':0x50,'right':0x4F,'ok':0x28}.items():
        c = [x[1] for x in tap('.btp-key[data-k=navbar] [data-n=%s]' % n_) if x[0] == 'key']
        check('nav bar %s -> key %d' % (n_, u), u in c, c)
    clear(); x, y = mid('.btp-key[data-k=touch2]'); pg.mouse.move(x, y); pg.mouse.down()
    for t in range(1, 7): pg.mouse.move(x + t * 8, y + t * 3); pg.wait_for_timeout(16)
    pg.mouse.up(); pg.wait_for_timeout(50)
    mv = [m for m in calls() if m[0] == 'move']
    check('extra touchpad widget moves the Bluetooth cursor', mv and sum(m[1] for m in mv) > 0, mv)
    c = tap('.btp-key[data-k=touch2]'); check('extra touchpad tap = left click', ['click', 1] in c, c)
    st = box('#btpStage'); clear(); pg.mouse.click(st['x'] + st['width'] * 0.9, st['y'] + st['height'] * 0.47); pg.wait_for_timeout(80)
    check('main touchpad still works', ['click', 1] in calls(), calls())
    # saved + reload
    pg.reload(); pg.wait_for_timeout(500); pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(450); pg.locator('#gtBtOpen').click(); pg.wait_for_timeout(450)
    lay = pg.evaluate("window.__btpTest.state().layout")
    check('widgets are saved', all(k in lay for k in ['vscroll','volbar','navbar','touch2','recent','chUp']) and lay.count('mute') == 1, lay)
    # edit: widget can be moved + resized
    pg.locator('#btpEdit').click(); pg.wait_for_timeout(60)
    g0 = pg.evaluate("window.__btpTest.state().geo.keys.filter(function(k){return k.id==='navbar'})[0]")
    bb = box('.btp-key[data-k=navbar]'); pg.mouse.move(bb['x'] + bb['width'] / 2, bb['y'] + bb['height'] / 2); pg.mouse.down(); pg.mouse.move(bb['x'] + bb['width'] / 2 + 40, bb['y'] + bb['height'] / 2 - 60, steps=5); pg.mouse.up(); pg.wait_for_timeout(50)
    g1 = pg.evaluate("window.__btpTest.state().geo.keys.filter(function(k){return k.id==='navbar'})[0]")
    check('widget can be moved in edit', g1['y'] < g0['y'] - 0.03 and g1['x'] > g0['x'] + 0.03, (g0, g1))
    rb = box('#btpRz'); rx, ry = rb['x'] + rb['width'] / 2, rb['y'] + rb['height'] / 2
    pg.mouse.move(rx, ry); pg.mouse.down(); pg.mouse.move(rx + 30, ry + 30, steps=5); pg.mouse.up(); pg.wait_for_timeout(50)
    g2 = pg.evaluate("window.__btpTest.state().geo.keys.filter(function(k){return k.id==='navbar'})[0]")
    check('widget can be resized in edit', g2['w'] > g1['w'] + 0.03, (g1, g2))
    pg.locator('#btpReset').click(); pg.locator('#btpEdit').click(); pg.wait_for_timeout(60)
    check('reset = back to the 6 default keys', pg.locator('.btp-key').count() == 6)
    check('no JS errors', not errs, errs)
    print(sum(res), '/', len(res)); b.close()
