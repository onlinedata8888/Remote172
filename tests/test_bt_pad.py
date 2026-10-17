"""Bluetooth Pad page (Bluetooth icon next to the back arrow on the Mobile Style page): trackpad, Home/Back/Mic/Keyboard/Volume keys over
Bluetooth HID, edit (remove / add keys), keyboard typing, back button."""
import os
from playwright.sync_api import sync_playwright
HTML = 'file://' + os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'app', 'assets', 'remote.html'))
FAKE = open(os.path.join(os.path.dirname(__file__), 'test_trackpad_page.py')).read().split('FAKE = """')[1].split('"""')[0]
HIDF = """
window.__h = [];
window.HidNative = {
  start(){}, startUser(){ window.__h.push('startUser'); }, reconnect(){}, status(){ return JSON.stringify({state:'connected', name:'Living TV', profile:0}); },
  devices(){ return JSON.stringify([{name:'Living TV', addr:'AA:BB', connected:true, last:true}]); }, connect(a){}, discoverable(){}, btSettings(){}, forget(a){ return true; },
  getProfile(){ return 0; }, setProfile(p){ window.__h.push('profile:'+p); },
  move(x,y){ window.__h.push(['move', Math.round(x), Math.round(y)]); }, click(m){ window.__h.push(['click', m]); }, buttons(m){ window.__h.push(['buttons', m]); },
  scroll(v,h){ window.__h.push(['scroll', v, h]); }, key(u,m){ window.__h.push(['key', u, m]); }, consumer(u){ window.__h.push(['consumer', u]); }, zoom(n){}
};
"""
res = []
def check(n, c, extra=''):
    res.append(bool(c)); print(('PASS' if c else 'FAIL'), '-', n, extra)
def calls(pg): return pg.evaluate('window.__h')
def clear(pg): pg.evaluate('window.__h = []')
with sync_playwright() as p:
    b = p.chromium.launch(); ctx = b.new_context(viewport={'width': 390, 'height': 844}, has_touch=True); pg = ctx.new_page(); errs = []
    pg.on('pageerror', lambda e: errs.append(str(e))); pg.add_init_script(FAKE); pg.add_init_script(HIDF); pg.goto(HTML); pg.wait_for_timeout(500)
    pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(450)
    bb, bt = pg.locator('#gtBack').bounding_box(), pg.locator('#gtBtOpen').bounding_box()
    check('Bluetooth icon sits next to the back arrow (bottom)', bt['y'] > 700 and bt['x'] > bb['x'] + bb['width'] - 1, (bb, bt))
    pg.locator('#gtBtOpen').click(); pg.wait_for_timeout(500)
    check('Bluetooth Pad page opens', 'open' in pg.locator('#btpPage').get_attribute('class'))
    check('title is Bluetooth Remote Panel', pg.locator('.btp-title').inner_text() == 'Bluetooth Remote Panel')
    check('default keys: back home mic kb vol- vol+', [pg.locator('.btp-key').nth(i).get_attribute('data-k') for i in range(pg.locator('.btp-key').count())] == ['back','home','mic','kb','volDown','volUp'])
    check('status shows connected device', 'Living TV' in pg.locator('#btpStat').inner_text(), pg.locator('#btpStat').inner_text())
    pg.evaluate("window.__hid.onState('connected','Living TV')"); clear(pg)
    for k in ['back', 'home', 'mic', 'volUp', 'volDown']:
        box = pg.locator('.btp-key[data-k=%s]' % k).bounding_box(); pg.mouse.click(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2); pg.wait_for_timeout(40)
    c = [x[1] for x in calls(pg) if x[0] == 'consumer']
    check('Back/Home/Mic/Vol+/Vol- go as Bluetooth consumer keys', c == [0x224, 0x223, 0xCF, 0xE9, 0xEA], [hex(x) for x in c])
    # trackpad
    pad = pg.locator('#btpPad').bounding_box(); cx, cy = pad['x'] + pad['width'] / 2, pad['y'] + pad['height'] / 2
    clear(pg); pg.mouse.move(cx, cy); pg.mouse.down()
    for i in range(1, 7): pg.mouse.move(cx + i * 8, cy + i * 3); pg.wait_for_timeout(16)
    pg.mouse.up(); pg.wait_for_timeout(50)
    mv = [x for x in calls(pg) if x[0] == 'move']
    check('swipe on the pad moves the cursor (right + down)', mv and sum(m[1] for m in mv) > 0 and sum(m[2] for m in mv) > 0, mv)
    clear(pg); pg.mouse.click(cx, cy); pg.wait_for_timeout(80)
    check('tap on the pad = left click', ['click', 1] in calls(pg), calls(pg))
    clear(pg); pg.mouse.move(cx, cy); pg.mouse.down(); pg.wait_for_timeout(600)
    for i in range(1, 4): pg.mouse.move(cx + i * 10, cy); pg.wait_for_timeout(16)
    pg.mouse.up(); pg.wait_for_timeout(50)
    cl = calls(pg)
    check('hold + move = drag (button down, moves, button up)', ['buttons', 1] in cl and ['buttons', 0] in cl and any(x[0] == 'move' for x in cl), cl)
    # keyboard
    pg.locator('.btp-key[data-k=kb]').click(); pg.wait_for_timeout(120)
    check('keyboard key opens the typing box', 'open' in pg.locator('#btpKb').get_attribute('class'))
    clear(pg); pg.locator('#btpKbInput').press_sequentially('Hi 5!', delay=20); pg.wait_for_timeout(100)
    k = [(x[1], x[2]) for x in calls(pg) if x[0] == 'key']
    check('typing sends HID keys (H=shift+11, i=12, space, 5, !)', k == [(11, 2), (12, 0), (44, 0), (34, 0), (30, 2)], k)
    clear(pg); pg.keyboard.press('Backspace'); pg.wait_for_timeout(60)
    check('backspace = key 42', ('key', 42, 0) in [tuple(x) for x in calls(pg)], calls(pg))
    clear(pg); pg.locator('#btpKbEnter').click(); pg.wait_for_timeout(40)
    check('Enter button = key 40', ['key', 40, 0] in calls(pg))
    pg.locator('#btpKbDone').click(); pg.wait_for_timeout(100)
    check('Done closes the typing box', 'open' not in pg.locator('#btpKb').get_attribute('class'))
    # edit
    pg.locator('#btpEdit').click(); pg.wait_for_timeout(100)
    check('edit mode shows add-palette', pg.locator('#btpPalRow button').count() >= 5, pg.locator('#btpPalRow button').count())
    pg.locator('.btp-key[data-k=mic] .btp-x').click(); pg.wait_for_timeout(80)
    check('x removes the Mic key', pg.locator('.btp-key[data-k=mic]').count() == 0 and pg.locator('.btp-key').count() == 5)
    pg.locator('#btpPalRow button', has_text='Mute').first.click(); pg.wait_for_timeout(80)
    check('palette adds Mute key', pg.locator('.btp-key[data-k=mute]').count() == 1)
    clear(pg); box = pg.locator('.btp-key[data-k=back]').bounding_box(); pg.mouse.click(box['x'] + 20, box['y'] + 30); pg.wait_for_timeout(40)
    check('keys do nothing while editing', calls(pg) == [], calls(pg))
    pg.locator('#btpEdit').click(); pg.wait_for_timeout(60)
    pg.reload(); pg.wait_for_timeout(500)
    pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(450); pg.locator('#gtBtOpen').click(); pg.wait_for_timeout(450)
    lay = pg.evaluate("window.__btpTest.state().layout")
    check('layout is saved', 'mute' in lay and 'mic' not in lay, lay)
    pg.locator('#btpEdit').click(); pg.locator('#btpReset').click(); pg.locator('#btpEdit').click()
    check('reset restores default keys', pg.locator('.btp-key').count() == 6)
    # free move / resize (Edit mode)
    pg.locator('#btpEdit').click(); pg.wait_for_timeout(80)
    def geo(i): return pg.evaluate("(function(i){var g=window.__btpTest.state().geo; return i==='pad'?g.pad:g.keys.filter(function(k){return k.id===i})[0];})('%s')" % i)
    g0 = geo('back'); bx = pg.locator('.btp-key[data-k=back]').bounding_box()
    pg.mouse.move(bx['x'] + bx['width'] / 2, bx['y'] + bx['height'] / 2); pg.mouse.down(); pg.mouse.move(bx['x'] + bx['width'] / 2 + 60, bx['y'] + bx['height'] / 2 - 120, steps=6); pg.mouse.up(); pg.wait_for_timeout(60)
    g1 = geo('back')
    check('drag moves a key', g1['x'] > g0['x'] + 0.05 and g1['y'] < g0['y'] - 0.1, (g0, g1))
    check('moved key is selected and handle shows', 'sel' in pg.locator('.btp-key[data-k=back]').get_attribute('class') and pg.locator('#btpRz.show').count() == 1)
    rb = pg.locator('#btpRz').bounding_box(); rx, ry = rb['x'] + rb['width'] / 2, rb['y'] + rb['height'] / 2
    pg.mouse.move(rx, ry); pg.mouse.down(); pg.mouse.move(rx + 40, ry + 30, steps=6); pg.mouse.up(); pg.wait_for_timeout(60)
    g2 = geo('back')
    check('corner handle resizes a key', g2['w'] > g1['w'] + 0.05 and g2['h'] > g1['h'] + 0.03, (g1, g2))
    pg.evaluate("var r=document.getElementById('btpW'); r.value=30; r.dispatchEvent(new Event('input')); r.dispatchEvent(new Event('change'));")
    check('W slider sets exact key width', abs(geo('back')['w'] - 0.30) < 0.011, geo('back'))
    p0 = geo('pad'); pb = pg.locator('#btpPad').bounding_box()
    pg.mouse.move(pb['x'] + 40, pb['y'] + 40); pg.mouse.down(); pg.mouse.move(pb['x'] + 40, pb['y'] + 40 + 50, steps=5); pg.mouse.up(); pg.wait_for_timeout(60)
    check('touchpad can be selected and moved', 'sel' in pg.locator('#btpPad').get_attribute('class') and geo('pad')['y'] > p0['y'] + 0.02, (p0, geo('pad')))
    pb = pg.locator('#btpPad').bounding_box(); clear(pg)
    pg.mouse.click(pb['x'] + pb['width'] / 2, pb['y'] + pb['height'] / 2); pg.wait_for_timeout(80)
    check('pad does not click/move while editing', calls(pg) == [], calls(pg))
    rb = pg.locator('#btpRz').bounding_box(); rx, ry = rb['x'] + rb['width'] / 2, rb['y'] + rb['height'] / 2
    pg.mouse.move(rx, ry); pg.mouse.down(); pg.mouse.move(rx - 80, ry - 100, steps=6); pg.mouse.up(); pg.wait_for_timeout(60)
    check('touchpad can be resized', geo('pad')['w'] < p0['w'] - 0.1 and geo('pad')['h'] < p0['h'] - 0.1, geo('pad'))
    check('stays inside the stage', all(0 <= k['x'] and k['x'] + k['w'] <= 1.0001 and 0 <= k['y'] and k['y'] + k['h'] <= 1.0001 for k in pg.evaluate("window.__btpTest.state().geo.keys")))
    pg.locator('#btpEdit').click(); pg.wait_for_timeout(60)
    sv = pg.evaluate("JSON.stringify((function(g){return {pad:g.pad,keys:g.keys.map(function(k){return {id:k.id,x:k.x,y:k.y,w:k.w,h:k.h};})};})(window.__btpTest.state().geo))")
    pg.reload(); pg.wait_for_timeout(500); pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(450); pg.locator('#gtBtOpen').click(); pg.wait_for_timeout(450)
    check('moved / resized layout survives reload', pg.evaluate("JSON.stringify((function(g){return {pad:g.pad,keys:g.keys.map(function(k){return {id:k.id,x:k.x,y:k.y,w:k.w,h:k.h};})};})(window.__btpTest.state().geo))") == sv)
    # normal use after the new layout: moved key still works, pad (resized) still clicks
    pg.evaluate("window.__hid.onState('connected','Living TV')"); clear(pg)
    bx = pg.locator('.btp-key[data-k=back]').bounding_box(); pg.mouse.click(bx['x'] + bx['width'] / 2, bx['y'] + bx['height'] / 2); pg.wait_for_timeout(50)
    check('moved key still sends its Bluetooth key', ['consumer', 0x224] in calls(pg), calls(pg))
    pb = pg.locator('#btpPad').bounding_box(); clear(pg); pg.mouse.click(pb['x'] + 15, pb['y'] + 15); pg.wait_for_timeout(80)
    check('resized pad still clicks', ['click', 1] in calls(pg), calls(pg))
    pg.locator('#btpEdit').click(); pg.locator('#btpReset').click(); pg.locator('#btpEdit').click(); pg.wait_for_timeout(50)
    check('reset restores default positions', abs(geo('back')['x']) < 0.001 and abs(geo('pad')['w'] - 1) < 0.001)
    # old saved layout (list of ids) still loads
    pg.evaluate("localStorage.removeItem('remote13_btp_layout2'); localStorage.setItem('remote13_btp_layout', JSON.stringify(['ok','esc','up']))")
    pg.reload(); pg.wait_for_timeout(500); pg.locator('#gtOpenBtn').click(); pg.wait_for_timeout(450); pg.locator('#gtBtOpen').click(); pg.wait_for_timeout(450)
    check('old id-list layout migrates', pg.evaluate("window.__btpTest.state().layout") == ['ok', 'esc', 'up'])
    pg.locator('#btpEdit').click(); pg.locator('#btpReset').click(); pg.locator('#btpEdit').click()
    # not connected
    pg.evaluate("window.__hid.onState('ready','')"); clear(pg)
    box = pg.locator('.btp-key[data-k=home]').bounding_box(); pg.mouse.click(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2); pg.wait_for_timeout(40)
    check('not connected: key sends nothing and tells to connect', calls(pg) == [] and 'connect' in pg.locator('#btpStat').inner_text().lower(), pg.locator('#btpStat').inner_text())
    # back
    pg.locator('#btpBack').click(); pg.wait_for_timeout(450)
    check('back arrow returns to the remote', 'open' not in pg.locator('#btpPage').get_attribute('class') and 'open' not in pg.locator('#gtPage').get_attribute('class'))
    check('no JS errors', not errs, errs)
    print(sum(res), '/', len(res)); b.close()
