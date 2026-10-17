package com.example.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.widget.EditText;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Glue between the remote.html UI (JS object "TVNative") and the real Android TV Remote v2 protocol.
 * The HTML calls key()/keys()/text()/launch()/connect()...; we call back window.__tv.onXxx().
 */
public final class TvBridge implements Discovery.Callback {
    private static TvBridge inst;
    static TvBridge getInstance() { return inst; }

    public static void install(Activity a, WebView w) {
        if (inst != null) inst.dispose();
        inst = new TvBridge(a, w);
        w.addJavascriptInterface(inst, "TVNative");
        w.addJavascriptInterface(inst.hidMouse, "HidNative");   // Bluetooth HID mouse (PC mode)
        final TvBridge self = inst;
        // remote.html is a file:// page and fetches thumbnails from https://tvthumb.local/…
        // (answered locally by shouldInterceptRequest, never hits the network). Make sure the
        // WebView never blocks that as "mixed content".
        try {
            android.webkit.WebSettings ws = w.getSettings();
            ws.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            ws.setAllowContentAccess(true);
            ws.setSupportZoom(false);                 // no pinch-zoom of the whole remote on any page
            ws.setBuiltInZoomControls(false);
            ws.setDisplayZoomControls(false);
        } catch (Throwable ignored) {}
        w.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest req) {
                android.net.Uri u = req.getUrl();
                if (u != null && CastBridge.THUMB_HOST.equals(u.getHost())) return self.thumbResponse(u);
                return null;
            }
        });
        inst.begin();
        inst.hidMouse.warmUp();   // register as Bluetooth mouse in the background so the PC can connect instantly
    }

    public static void shutdown() {
        if (inst != null) {
            inst.dispose();
            inst = null;
        }
    }

    /**
     * Called from MainActivity.onBackPressed() (gesture-back AND the back button).
     * Asks the page to close its top-most layer (keyboard / dropdown / cast album / cast / mouse
     * page / 2nd page). If the page closed something it answers "1" and we stay in the app;
     * only when the main remote page is showing (answer "0") do we really leave.
     * Returns immediately; if anything goes wrong we fall back to leaving the app.
     */
    public static void handleBack(final Activity a, final WebView w) {
        if (w == null) { a.finish(); return; }
        try {
            w.evaluateJavascript(
                "(function(){try{var t=window.__tv;return (t&&t.handleBack&&t.handleBack())?'1':'0';}catch(e){return '0';}})()",
                new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        boolean consumed = v != null && v.indexOf('1') >= 0;
                        if (!consumed) a.finish();
                    }
                });
        } catch (Throwable t) {
            a.finish();
        }
    }

    private static final String CANCEL = "__cancel__";
    private static final int PORT_REMOTE = 6466;
    private static final int PORT_PAIR = 6467;

    private final Activity act;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Discovery disc;
    private final LinkedBlockingQueue<String> codeQ = new LinkedBlockingQueue<String>();
    private final ScheduledExecutorService ctl = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tv-control");
            t.setDaemon(true);
            return t;
        }
    });

    // --- state below is only touched on the ctl thread unless marked volatile ---
    private CertStore certs;

    /** Name shown on the TV's paired-devices list; unique per phone so a second phone doesn't replace the first. */
    private String clientLabel() {
        String model = Build.MODEL == null ? "Phone" : Build.MODEL;
        String id = certs != null ? certs.id : "";
        String suffix = id.length() > 4 ? id.substring(id.length() - 4) : id;
        return "TV Remote " + model + " " + suffix;
    }
    private Discovery.Device cur;
    private volatile RemoteSession sess;
    private volatile String state = "offline";
    private int gen;
    private int attempts;
    private boolean autoReconnect = true;
    private volatile boolean paused;
    private volatile boolean sleptClosed;   // we closed the TV link ourselves while the screen was off
    private java.util.concurrent.ScheduledFuture<?> sleepTask;
    private volatile long lastKick;
    private boolean disposed;
    private Application.ActivityLifecycleCallbacks lifecycle;
    private VoiceRecorder voice;
    private CastBridge cast;
    private HidMouse hidMouse;

    private TvBridge(Activity a, WebView w) {
        act = a;
        web = w;
        prefs = a.getSharedPreferences("tvremote", 0);
        disc = new Discovery(a, this);
        cast = new CastBridge(a, w, this);
        hidMouse = new HidMouse(a, w);
    }

    private void begin() {
        lifecycle = new Application.ActivityLifecycleCallbacks() {
            public void onActivityPaused(Activity a) {
                if (a != act) return;
                paused = true;
                RemoteSession s = sess;
                if (s != null) s.releaseHeld();
                goQuiet();
            }

            public void onActivityResumed(Activity a) {
                if (a != act) return;
                paused = false;
                wakeUp();
                kick();
            }

            public void onActivityCreated(Activity a, Bundle b) {
            }

            public void onActivityStarted(Activity a) {
            }

            public void onActivityStopped(Activity a) {
            }

            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            public void onActivityDestroyed(Activity a) {
            }
        };
        act.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        // generate the phone's identity in the background so the first pairing is not delayed
        Thread kg = new Thread(new Runnable() {
            public void run() {
                try {
                    ensureCerts();
                } catch (Throwable e) {
                    TvLog.d("identity: " + e);
                }
            }
        }, "tv-keygen");
        kg.setDaemon(true);
        kg.start();
    }

    private void dispose() {
        disposed = true;
        try {
            act.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        } catch (Throwable ignored) {
        }
        codeQ.offer(CANCEL);
        disc.stop();
        if (cast != null) cast.dispose();
        if (hidMouse != null) hidMouse.dispose();
        VoiceRecorder v = voice;
        if (v != null) v.requestStop();
        RemoteSession s = sess;
        sess = null;
        if (s != null) s.close("app closed");
        ctl.shutdownNow();
    }

    private synchronized void ensureCerts() throws Exception {
        if (certs == null) certs = CertStore.loadOrCreate(act.getFilesDir());
    }

    // ------------------------------------------------------------------ JS <- native
    private void js(final String call) {
        if (disposed) return;
        ui.post(new Runnable() {
            public void run() {
                try {
                    web.evaluateJavascript("(function(){var t=window.__tv;if(t){t." + call + ";}})()", null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private void toast(final String m) {
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(act, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setState(String s) {
        state = s;
        disc.setConnected("connected".equals(s));
        pushState();
    }

    private void pushState() {
        js("onState(" + q(state) + "," + q(cur == null ? "" : cur.name) + "," + q(cur == null ? "" : cur.host) + ")");
    }

    private void pushDevices() {
        JSONArray arr = new JSONArray();
        List<Discovery.Device> l = disc.snapshot();
        try {
            for (int i = 0; i < l.size(); i++) {
                JSONObject o = new JSONObject();
                o.put("id", l.get(i).host);
                o.put("name", l.get(i).name);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        js("onDevices(" + arr.toString() + ")");
    }

    // ------------------------------------------------------------------ discovery callback
    public void onChanged() {
        if (disposed) return;
        ctl.execute(new Runnable() {
            public void run() {
                pushDevices();
                List<Discovery.Device> l = disc.snapshot();
                // saved TV got a new IP address: follow it by name
                if (cur != null && "offline".equals(state)) {
                    for (int i = 0; i < l.size(); i++) {
                        Discovery.Device d = l.get(i);
                        if (d.name.equals(cur.name) && !d.host.equals(cur.host)) {
                            connectTo(d, true);
                            return;
                        }
                    }
                }
                // first run with exactly one TV around: select it for the user
                if (cur == null && !prefs.contains("host") && l.size() == 1) connectTo(l.get(0), true);
            }
        });
    }

    // ------------------------------------------------------------------ connection
    private final class SessionListener implements RemoteSession.Listener {
        private final int my;

        SessionListener(int my) {
            this.my = my;
        }

        public void onVolume(int level, int max, boolean muted) {
            if (my == gen) js("onVolume(" + level + "," + max + "," + muted + ")");
        }

        public void onImeField(boolean active, String value, String label) {
            if (my == gen) js("onImeField(" + active + "," + q(value) + "," + q(label) + ")");
        }

        @Override
        public void onPower(boolean on) {
            if (my == gen) js("onPower(" + on + ")");
        }

        public void onClosed(final RemoteSession s, final String reason) {
            TvLog.d("session closed: " + reason);
            if (disposed) return;
            ctl.execute(new Runnable() {
                public void run() {
                    if (my == gen && sess == s) {
                        sess = null;
                        setState("offline");
                        scheduleReconnect(my);
                    }
                }
            });
        }
    }

    /** Runs on the ctl thread. */
    private void connectTo(final Discovery.Device d, final boolean allowPair) {
        final int my = ++gen;
        RemoteSession old = sess;
        sess = null;
        if (old != null) old.close("switching");
        codeQ.offer(CANCEL); // stop any pairing dialog for another TV
        cur = d;
        if (cast != null) cast.setHost(d.host);
        setState("connecting");
        try {
            ensureCerts();
        } catch (Exception e) {
            TvLog.d("identity: " + e);
            setState("offline");
            toast("Could not create the phone's TV identity");
            return;
        }
        int result; // 0 ok, 1 unreachable, 2 not paired / rejected
        String err = "";
        RemoteSession s = null;
        try {
            s = RemoteSession.open(certs, d.host, PORT_REMOTE, new SessionListener(my));
            result = s.awaitReady(6000) ? 0 : (s.isClosed() ? 2 : 1);
            if (result == 1) err = "The TV did not respond";
        } catch (SSLException e) {
            result = 2;
            err = String.valueOf(e);
        } catch (Exception e) {
            result = 1;
            err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        if (my != gen) {
            if (s != null) s.close("superseded");
            return;
        }
        if (result == 0) {
            sess = s;
            attempts = 0;
            prefs.edit().putString("host", d.host).putString("name", d.name).apply();
            setState("connected");
            if (sleptClosed) sleptClosed = false; else toast("Connected: " + d.name);
            return;
        }
        if (s != null) s.close("connect failed");
        if (result == 2 && allowPair) {
            startPairing(d, my);
        } else {
            setState("offline");
            if (result == 2) {
                toast("TV did not accept the pairing. Tap the status dot to pair again.");
            } else {
                if (attempts == 0) toast("Could not connect to the TV (" + d.host + "): " + err);
                scheduleReconnect(my);
            }
        }
    }

    private void scheduleReconnect(final int my) {
        if (paused || cur == null || !autoReconnect || disposed) return;
        long delay = Math.min(10000L, 1000L + 1500L * attempts++);
        if (attempts >= 2) startDiscAsync();   // TV may have a new IP address: look for it (backs off by itself)
        try {
            ctl.schedule(new Runnable() {
                public void run() {
                    if (my == gen && sess == null && "offline".equals(state) && !paused && autoReconnect && cur != null)
                        connectTo(cur, true);
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    /** Called when the app is used while disconnected: try again right away (rate limited). */
    private void kick() {
        long now = System.currentTimeMillis();
        if (now - lastKick < 1500) return;
        lastKick = now;
        if (disposed) return;
        ctl.execute(new Runnable() {
            public void run() {
                if (cur != null && sess == null && "offline".equals(state)) {
                    autoReconnect = true;
                    connectTo(cur, true);
                }
            }
        });
    }


    // ------------------------------------------------------------------ battery: screen off / app in background
    private void startDiscAsync() {
        Thread th = new Thread(new Runnable() {
            public void run() { try { disc.start(); } catch (Throwable ignored) {} }
        }, "tv-disc-wake");
        th.setDaemon(true);
        th.start();
    }

    /** Called when the app leaves the screen (screen off, home, other app). Mic stops at once; after a short grace
     *  period the TV link is closed, discovery stops and the page's JS timers are frozen -> no background wake-ups. */
    private void goQuiet() {
        VoiceRecorder v = voice;
        if (v != null) v.requestStop();
        if (disposed) return;
        try {
            java.util.concurrent.ScheduledFuture<?> old = sleepTask;
            if (old != null) old.cancel(false);
            sleepTask = ctl.schedule(new Runnable() {
                public void run() {
                    if (!paused || disposed) return;
                    disc.stop();
                    RemoteSession s = sess;
                    if (s != null) { sleptClosed = true; s.close("screen off"); }
                    ui.post(new Runnable() {
                        public void run() {
                            if (!paused || disposed) return;
                            try { web.onPause(); web.pauseTimers(); } catch (Throwable ignored) {}
                        }
                    });
                }
            }, 20, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    /** Back on screen: undo goQuiet. The TV link is rebuilt by kick() right after this. */
    private void wakeUp() {
        try { web.onResume(); web.resumeTimers(); } catch (Throwable ignored) {}
        java.util.concurrent.ScheduledFuture<?> st = sleepTask;
        if (st != null) st.cancel(false);
        sleepTask = null;
        if (cur == null) startDiscAsync();   // nothing saved yet: keep looking for a TV
    }

    // ------------------------------------------------------------------ pairing
    private void startPairing(final Discovery.Device d, final int my) {
        setState("pairing");
        toast("Type the code shown on the TV into the app");
        codeQ.clear();
        Thread t = new Thread(new Runnable() {
            public void run() {
                runPairing(d, my);
            }
        }, "tv-pair");
        t.setDaemon(true);
        t.start();
    }

    private void runPairing(final Discovery.Device d, final int my) {
        PairingSession ps = null;
        try {
            boolean paired = false;
            while (!paired && my == gen) {
                ps = new PairingSession(certs, d.host, PORT_PAIR, clientLabel());
                ps.begin();
                js("onPairing(" + q(d.name) + ")");
                while (!paired && my == gen) {
                    String code = codeQ.poll(300, TimeUnit.SECONDS);
                    if (code == null || CANCEL.equals(code) || my != gen) return;
                    try {
                        ps.finish(code);
                        paired = true;
                    } catch (PairingSession.WrongCodeException e) {
                        js("onPairError(" + q(e.getMessage()) + ")");
                    } catch (PairingSession.RejectedException e) {
                        js("onPairError(" + q("TV rejected the code. Enter the new code now shown on the TV.") + ")");
                        break; // start a fresh pairing session
                    }
                }
                ps.close();
                ps = null;
            }
            if (paired && my == gen) {
                js("onPaired()");
                ctl.execute(new Runnable() {
                    public void run() {
                        if (my == gen) connectTo(d, false);
                    }
                });
            }
        } catch (final Exception e) {
            TvLog.d("pairing failed: " + e);
            if (my == gen) {
                js("onPairEnd()");
                toast("Pairing failed: " + e.getMessage());
                ctl.execute(new Runnable() {
                    public void run() {
                        if (my == gen) {
                            autoReconnect = false;
                            setState("offline");
                        }
                    }
                });
            }
        } finally {
            if (ps != null) ps.close();
        }
    }

    // ------------------------------------------------------------------ JS -> native
    /** Game pad screen: "sensor" = follow the phone's sensor (portrait + landscape), "landscape" = forced landscape, anything else = back to portrait lock. */
    @JavascriptInterface
    public void setOrientation(final String mode) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    int o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    if ("sensor".equals(mode)) o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
                    else if ("landscape".equals(mode)) o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                    act.setRequestedOrientation(o);
                } catch (Throwable ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void ready() {
        disc.start(); // not on the control thread: discovery must never wait for anything else
        ctl.execute(new Runnable() {
            public void run() {
                pushDevices();
                pushState();
                if (cur == null && prefs.contains("host")) {
                    connectTo(new Discovery.Device(prefs.getString("name", "TV"), prefs.getString("host", "")), true);
                }
            }
        });
        try {
            ctl.schedule(new Runnable() {
                public void run() {
                    if (cur == null && disc.snapshot().isEmpty()) {
                        toast("No TV found - you can enter the TV IP address");
                        showIpDialog();
                    }
                }
            }, 10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /** Native dialog to type the TV's IP address (bypasses discovery completely). */
    @JavascriptInterface
    public void manualIp() {
        showIpDialog();
    }

    private void showIpDialog() {
        ui.post(new Runnable() {
            public void run() {
                if (act.isFinishing()) return;
                final EditText et = new EditText(act);
                et.setInputType(InputType.TYPE_CLASS_TEXT);
                et.setHint("192.168.1.25");
                et.setText(prefs.getString("host", ""));
                et.setSelectAllOnFocus(true);
                new AlertDialog.Builder(act)
                        .setTitle("TV ka IP address")
                        .setMessage("TV: Settings > Network > Wi-Fi me IP address dekho. Phone aur TV same Wi-Fi par hone chahiye.")
                        .setView(et)
                        .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                connectManual(et.getText().toString().trim());
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    private void connectManual(final String ip) {
        if (!ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            toast("Sahi IP daalo (jaise 192.168.1.25)");
            return;
        }
        final Discovery.Device d = new Discovery.Device("TV " + ip, ip);
        disc.addManual(d);
        ctl.execute(new Runnable() {
            public void run() {
                autoReconnect = true;
                attempts = 0;
                connectTo(d, true);
            }
        });
    }

    @JavascriptInterface
    public void connect(final String host) {
        ctl.execute(new Runnable() {
            public void run() {
                List<Discovery.Device> l = disc.snapshot();
                for (int i = 0; i < l.size(); i++) {
                    if (l.get(i).host.equals(host)) {
                        autoReconnect = true;
                        attempts = 0;
                        connectTo(l.get(i), true);
                        return;
                    }
                }
            }
        });
    }

    /** Tap on the status dot: reconnect (or pair again). */
    @JavascriptInterface
    public void reconnect() {
        ctl.execute(new Runnable() {
            public void run() {
                autoReconnect = true;
                attempts = 0;
                if (cur != null) connectTo(cur, true);
                else disc.rescan();
            }
        });
    }

    @JavascriptInterface
    public void rescan() {
        disc.rescan();
    }

    // Number-pad delete uses Android KEYCODE_DEL (67); key() forwards arbitrary Android key codes.
    /** dir: 1 = key down, 2 = key up, 3 = tap. */
    @JavascriptInterface
    public void key(int code, int dir) {
        RemoteSession s = sess;
        if (s != null) s.key(code, dir);
        else if (dir != 2) kick();
    }

    @JavascriptInterface
    public void keys(int code, int count) {
        RemoteSession s = sess;
        if (s != null) s.keys(code, count);
        else kick();
    }

    // ---- TV keyboard: behaves like a normal remote / physical keyboard ----
    /** Types these characters on the TV, one key press each, exactly once. */
    @JavascriptInterface
    public void imeType(String text) {
        RemoteSession s = sess;
        if (s != null) s.imeType(text);
        else kick();
    }

    /** n Backspace presses on the TV (also removes text that was already in the TV field). */
    @JavascriptInterface
    public void imeDel(int n) {
        RemoteSession s = sess;
        if (s != null) s.imeDel(n);
        else kick();
    }

    @JavascriptInterface
    public void imeBackspace() {
        RemoteSession s = sess;
        if (s != null) s.imeBackspace();
        else kick();
    }

    @JavascriptInterface
    public void imeEnter() {
        RemoteSession s = sess;
        if (s != null) s.imeEnter();
        else kick();
    }

    /** Text currently in the TV text field ("" if none / not connected). */
    @JavascriptInterface
    public String imeValue() {
        RemoteSession s = sess;
        return s == null ? "" : s.imeValue();
    }

    @JavascriptInterface
    public boolean imeReady() {
        RemoteSession s = sess;
        return s != null && s.imeReady();
    }

    @JavascriptInterface
    public void launch(String link) {
        RemoteSession s = sess;
        if (s != null) s.launch(link);
        else kick();
    }

    @JavascriptInterface
    public void pairCode(String code) {
        codeQ.offer(code == null ? "" : code);
    }

    @JavascriptInterface
    public void cancelPairing() {
        ctl.execute(new Runnable() {
            public void run() {
                if ("pairing".equals(state)) {
                    gen++;
                    codeQ.offer(CANCEL);
                    autoReconnect = false;
                    setState("offline");
                }
            }
        });
    }

    /** Mic button tapped: start streaming the phone's microphone to the TV; tap again to stop. */
    @JavascriptInterface
    public void voiceToggle() {
        VoiceRecorder running;
        synchronized (this) {
            running = voice;
        }
        if (running != null) {
            running.requestStop();
            toast("Voice bhej rahe hain...");
            return;
        }
        final RemoteSession s = sess;
        if (s == null) {
            toast("Not connected to the TV");
            kick();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && act.checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            ui.post(new Runnable() {
                public void run() {
                    act.requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 71);
                }
            });
            toast("Allow the Mic permission, then tap the mic again");
            return;
        }
        if (!s.voiceSupported()) {
            s.key(84, 3);
            toast("This TV has no voice streaming, opened Search");
            return;
        }
        synchronized (this) {
            if (voice != null) return;
            voice = new VoiceRecorder(s, new VoiceRecorder.Sink() {
                public void toast(String m) {
                    TvBridge.this.toast(m);
                }

                public void started() {
                    // Fired only once the TV has actually opened its listening session --
                    // this is the real cue to speak, not the moment the button was tapped.
                    TvBridge.this.toast("Speak now... (it stops automatically when you finish)");
                }

                public void finished() {
                    synchronized (TvBridge.this) {
                        voice = null;
                    }
                }
            });
        }
        Thread t = new Thread(voice, "tv-voice");
        t.setDaemon(true);
        t.start();
        toast("Connecting to the TV...");
    }

    // ------------------------------------------------------------------ Cast / media sender
    @JavascriptInterface
    public void castPick(String kind) {
        if (cast != null) cast.pick(kind);
    }

    void castPicked(android.net.Uri uri, String kind) {
        if (cast != null) cast.handlePicked(uri, kind);
    }

    /** True once the runtime media permission for this kind is granted; otherwise asks for it. */
    @JavascriptInterface
    public boolean galleryReady(final String kind) {
        if (cast == null) return false;
        if (cast.hasMediaPermission(kind)) return true;
        ui.post(new Runnable() {
            public void run() { act.requestPermissions(CastBridge.mediaPermissionsFor(kind), 72); }
        });
        return false;
    }

    /** [{"id","name","count","cover"}] — albums / video folders / audio albums. Text only = instant. */
    @JavascriptInterface
    public String listGalleryAlbums(String kind) {
        return cast != null ? cast.listAlbums(kind) : "[]";
    }

    /** [{"id","title","sub"}] — items of one album. Text only = instant; thumbnails load lazily via tvthumb.local. */
    @JavascriptInterface
    public String listGalleryItems(String kind, String bucket) {
        return cast != null ? cast.listItems(kind, bucket) : "[]";
    }

    /** Serves https://tvthumb.local/<kind>/<id> thumbnails to the WebView on its own worker thread. */
    android.webkit.WebResourceResponse thumbResponse(android.net.Uri u) {
        try {
            java.util.List<String> seg = u.getPathSegments();
            if (seg.size() < 2 || cast == null) return null;
            java.io.InputStream in = cast.thumbStream(seg.get(0), Long.parseLong(seg.get(1)));
            if (in == null) {
                return new android.webkit.WebResourceResponse("image/gif", null, 404, "Not Found",
                        new java.util.HashMap<String, String>(), new java.io.ByteArrayInputStream(new byte[0]));
            }
            java.util.HashMap<String, String> h = new java.util.HashMap<String, String>();
            h.put("Cache-Control", "max-age=86400");
            h.put("Access-Control-Allow-Origin", "*");
            return new android.webkit.WebResourceResponse("image/jpeg", null, 200, "OK", h, in);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Casts a MediaStore item directly by its id/kind — no SAF picker Activity involved,
     * so the gallery grid in remote.html stays on screen and the user can tap the next
     * photo immediately after this one starts casting.
     */
    @JavascriptInterface
    public void castMediaStoreItem(String id, String kind) {
        if (cast != null) cast.castMediaStoreItem(id, kind);
    }

    @JavascriptInterface public void castPlay() { if (cast != null) cast.play(); }
    @JavascriptInterface public void castPause() { if (cast != null) cast.pause(); }
    @JavascriptInterface public void castStop() { if (cast != null) cast.stop(); }
    @JavascriptInterface public void castSeek(long ms) { if (cast != null) cast.seek(ms); }

    /** Opens the device Wi-Fi Display / Cast settings. This is the platform fallback for
     * devices whose vendor exposes Miracast/WFD as a system feature; it does not fake a
     * network mirror when the OS/TV does not expose a WFD receiver. */
    @JavascriptInterface
    public void mirrorStart() {
        try {
            android.content.Intent i = new android.content.Intent("android.settings.WIFI_DISPLAY_SETTINGS");
            act.startActivity(i);
            toast("Opening Wi-Fi Display / Screen Mirroring…");
        } catch (Throwable first) {
            try {
                android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS);
                act.startActivity(i);
                toast("Opening Cast / Screen Mirroring settings…");
            } catch (Throwable second) {
                toast("System Screen Mirroring is not available on this phone");
            }
        }
    }

    @JavascriptInterface
    public void toastMsg(String m) {
        toast(m);
    }

    // ---- Floating mini remote (switched from Settings) ----
    @JavascriptInterface public boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(act);
    }
    /** true = shown. If the permission is missing, opens the system screen and returns false (page re-calls once granted). */
    @JavascriptInterface public boolean floatSet(final boolean on) {
        if (!on) { FloatService.stop(act); return false; }
        if (!canOverlay()) {
            act.runOnUiThread(new Runnable() { public void run() { try {
                act.startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + act.getPackageName())));
            } catch (Throwable ignored) {} } });
            return false;
        }
        FloatService.start(act);
        return true;
    }
}
