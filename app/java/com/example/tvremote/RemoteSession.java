package com.example.tvremote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** A live remote-control connection (port 6466). Sends are queued on one thread so key order is preserved. */
final class RemoteSession {
    interface Listener {
        void onVolume(int level, int max, boolean muted);

        void onPower(boolean on);

        void onClosed(RemoteSession s, String reason);

        /** TV text field state: active = a text box is focused on the TV, value = its current text. */
        void onImeField(boolean active, String value, String label);
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tv-writer");
            t.setDaemon(true);
            return t;
        }
    });
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile int active = Msgs.F_PING | Msgs.F_KEY | Msgs.F_IME | Msgs.F_VOICE | Msgs.F_POWER | Msgs.F_VOLUME | Msgs.F_APP_LINK;
    // ---- TV text field (IME) state: only what the TV tells us; typing itself is stateless (see keyboard section) ----
    private volatile int imeCounter;
    private volatile int fieldCounter;
    private volatile boolean batchSeen;      // TV sent a RemoteImeBatchEdit -> its counters are authoritative
    private volatile boolean imeSeen;        // TV has told us about a text field in this session
    private volatile String imeValue = "";   // text the TV last reported for its focused field (info only)
    private volatile int statusCounter = -1; // counter_field from the TV's text_field_status
    private final java.util.concurrent.ScheduledExecutorService imeSched =
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tv-ime");
                t.setDaemon(true);
                return t;
            }
        });
    private final AtomicInteger voiceSessionCounter = new AtomicInteger(0);
    private final AtomicInteger voiceSessionActive = new AtomicInteger(0);
    private volatile long voiceId;
    private volatile boolean voiceEnded;
    private final java.util.Set<Integer> held = java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    private final String deviceId;

    private RemoteSession(Socket s, Listener l, String deviceId) throws IOException {
        this.deviceId = deviceId;
        this.socket = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();
        this.listener = l;
    }

    static RemoteSession open(CertStore cs, String host, int port, Listener l) throws Exception {
        Socket s = Tls.connect(cs, host, port, 3000);
        s.setSoTimeout(25000); // TV pings every ~5 s; silence for 25 s means the link is dead
        final RemoteSession r = new RemoteSession(s, l, cs.id);
        Thread t = new Thread(new Runnable() {
            public void run() {
                r.readLoop();
            }
        }, "tv-reader");
        t.setDaemon(true);
        t.start();
        return r;
    }

    boolean isClosed() {
        return closed;
    }

    boolean awaitReady(long ms) {
        try {
            return ready.await(ms, TimeUnit.MILLISECONDS) && !closed;
        } catch (InterruptedException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ reading
    private void readLoop() {
        String why = "closed";
        try {
            while (!closed) {
                byte[] f = Msgs.readFrame(in);
                handle(f);
            }
        } catch (Exception e) {
            why = String.valueOf(e);
        }
        close(why);
    }

    private void handle(byte[] raw) {
        Pb.Reader r = new Pb.Reader(raw);
        while (r.next()) {
            if (r.wire != 2) continue;
            switch (r.field) {
                case 1: { // remote_configure: TV tells which features it supports
                    int code1 = 0;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) if (s.field == 1) code1 = (int) s.num;
                    active &= code1;
                    send(Msgs.remoteConfigure(active, deviceId));
                    break;
                }
                case 2: // remote_set_active
                    send(Msgs.remoteSetActive(active));
                    break;
                case 3:
                    TvLog.d("remote_error from TV");
                    break;
                case 8: { // ping request -> ping response
                    int v = 0;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) if (s.field == 1) v = (int) s.num;
                    send(Msgs.pingResponse(v));
                    break;
                }
                case 20: // remote_ime_key_inject: app_info (1) + text_field_status (2)
                case 22: { // remote_ime_show_request: text_field_status (2)
                    imeSeen = true;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) {
                        if (s.field == 2 && s.wire == 2) readTextFieldStatus(s.data);
                    }
                    break;
                }
                case 21: { // remote_ime_batch_edit from TV: its ime/field counters
                    imeSeen = true;
                    batchSeen = true;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) {
                        if (s.field == 1) imeCounter = (int) s.num;
                        else if (s.field == 2) fieldCounter = (int) s.num;
                    }
                    break;
                }
                case 30: { // remote_voice_begin: TV acknowledged the voice UI.
                    long id = 0;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) if (s.field == 1) id = s.num;
                    voiceId = id;
                    break;
                }
                case 32: // TV finished listening (it understood the command or gave up)
                    voiceEnded = true;
                    break;
                case 40: { // remote_start: ready for commands
                    boolean on = false;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) if (s.field == 1) on = s.num != 0;
                    ready.countDown();
                    listener.onPower(on);
                    break;
                }
                case 50: { // volume info
                    int max = 0, level = 0;
                    boolean muted = false;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) {
                        if (s.field == 6) max = (int) s.num;
                        else if (s.field == 7) level = (int) s.num;
                        else if (s.field == 8) muted = s.num != 0;
                    }
                    listener.onVolume(level, max, muted);
                    break;
                }
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ writing
    private void writeNow(byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            close("write failed: " + e);
        }
    }

    private void write(final byte[] bytes) {
        if (closed) return;
        try {
            writer.execute(new Runnable() {
                public void run() {
                    if (!closed) writeNow(bytes);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void send(byte[] payload) {
        write(Msgs.frame(payload));
    }

    /** dir: 1 = key down (START_LONG), 2 = key up (END_LONG), 3 = full press (SHORT). */
    void key(int code, int dir) {
        if (dir == Msgs.DIR_START_LONG) held.add(code);
        else if (dir == Msgs.DIR_END_LONG) held.remove(code);
        send(Msgs.keyInject(code, dir));
    }

    /** n quick presses in a single network write. */
    void keys(int code, int n) {
        if (n <= 0) return;
        if (n > 60) n = 60;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] one = Msgs.frame(Msgs.keyInject(code, Msgs.DIR_SHORT));
        for (int i = 0; i < n; i++) b.write(one, 0, one.length);
        write(b.toByteArray());
    }

    void releaseHeld() {
        Object[] codes = held.toArray();
        held.clear();
        for (int i = 0; i < codes.length; i++) send(Msgs.keyInject((Integer) codes[i], Msgs.DIR_END_LONG));
    }

    // ---------------------------------------------------------------- keyboard (like a physical TV keyboard)
    //
    // Typing: when the TV has told us about a focused text box, each typed chunk goes out as ONE RemoteImeBatchEdit
    // (the same message the reference v2 client androidtvremote2 sends for send_text(): counters = the last ones the
    // TV sent in a RemoteImeBatchEdit, value = the new text, insert=1). The TV INSERTS that text at its cursor, so it
    // is sent exactly once and is never verified / re-sent (re-sending is what typed everything 4 times before).
    // Backspace: every one is a KEYCODE_DEL press, so it also removes text that was already in the TV field.
    // Enter is KEYCODE_ENTER. If the TV reported no text box at all, characters go as plain key presses instead.
    // Everything runs on one thread, so the order is always kept.

    private static final int KEY_DEL = 67, KEY_ENTER = 66, KEY_SHIFT = 59;

    /** RemoteTextFieldStatus: counter_field(1) value(2) start(3) end(4) label(6). Info only (hint + counters). */
    private void readTextFieldStatus(byte[] d) {
        int counter = -1;
        String value = "", label = "";
        Pb.Reader t = new Pb.Reader(d);
        while (t.next()) {
            if (t.field == 1 && t.wire == 0) counter = (int) t.num;
            else if (t.field == 2 && t.wire == 2) value = t.str();
            else if (t.field == 6 && t.wire == 2) label = t.str();
        }
        if (counter >= 0) statusCounter = counter;
        imeValue = value;
        listener.onImeField(true, value, label);
    }

    /** Text of the TV text field as last reported by the TV ("" if unknown). */
    String imeValue() {
        return imeValue;
    }

    boolean imeReady() {
        return imeSeen && (active & Msgs.F_IME) != 0;
    }

    private void ime(Runnable job) {
        if (closed) return;
        try {
            imeSched.execute(job);
        } catch (Exception ignored) {
        }
    }

    /** Types `text` on the TV: one key press per character (exactly once). */
    void imeType(final String text) {
        if (text == null || text.length() == 0) return;
        ime(new Runnable() {
            public void run() {
                typeNow(text);
            }
        });
    }

    /** n Backspace presses on the TV (deletes n characters before the TV cursor, whatever they are). */
    void imeDel(final int n) {
        if (n <= 0) return;
        ime(new Runnable() {
            public void run() {
                int left = Math.min(n, 400);
                while (left > 0) {
                    int c = Math.min(left, 60);
                    keys(KEY_DEL, c);
                    left -= c;
                }
            }
        });
    }

    void imeBackspace() {
        imeDel(1);
    }

    /** Enter / search / go on the TV text field (KEYCODE_ENTER). Ordered after any typing still queued. */
    void imeEnter() {
        ime(new Runnable() {
            public void run() {
                send(Msgs.keyInject(KEY_ENTER, Msgs.DIR_SHORT));
            }
        });
    }

    private void typeNow(String text) {
        if (imeReady()) {                                  // text box on the TV: IME insert, exactly once per chunk
            int from = 0;
            while (from <= text.length()) {
                int nl = text.indexOf('\n', from);
                String part = nl < 0 ? text.substring(from) : text.substring(from, nl);
                if (part.length() > 0) sendImeText(part);
                if (nl < 0) break;
                send(Msgs.keyInject(KEY_ENTER, Msgs.DIR_SHORT));   // a typed newline = Enter
                from = nl + 1;
            }
            return;
        }
        ByteArrayOutputStream keysOut = new ByteArrayOutputStream();   // no text box reported: plain key presses
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int[] st = strokeFor(cp);
            if (st != null) appendStroke(keysOut, st);          // characters without a key cannot be typed this way
        }
        if (keysOut.size() > 0) write(keysOut.toByteArray());
    }

    /** ONE batch edit that inserts exactly this text; never repeated. */
    private void sendImeText(String run) {
        send(Msgs.imeBatchEdit(imeCounter, fieldCounter, run));
    }

    private static void appendStroke(ByteArrayOutputStream b, int[] st) {
        byte[] press = Msgs.frame(Msgs.keyInject(st[0], Msgs.DIR_SHORT));
        if (st[1] == 0) {
            b.write(press, 0, press.length);
            return;
        }
        byte[] down = Msgs.frame(Msgs.keyInject(KEY_SHIFT, Msgs.DIR_START_LONG));
        byte[] up = Msgs.frame(Msgs.keyInject(KEY_SHIFT, Msgs.DIR_END_LONG));
        b.write(down, 0, down.length);
        b.write(press, 0, press.length);
        b.write(up, 0, up.length);
    }

    /** {Android keycode, shift 0/1} for a character a US keyboard can type, or null if it has no key. */
    static int[] strokeFor(int c) {
        if (c >= 'a' && c <= 'z') return new int[]{29 + (c - 'a'), 0};
        if (c >= 'A' && c <= 'Z') return new int[]{29 + (c - 'A'), 1};
        if (c >= '0' && c <= '9') return new int[]{7 + (c - '0'), 0};
        switch (c) {
            case ' ': return new int[]{62, 0};
            case '\n': return new int[]{66, 0};
            case '.': return new int[]{56, 0};
            case ',': return new int[]{55, 0};
            case '-': return new int[]{69, 0};
            case '=': return new int[]{70, 0};
            case '[': return new int[]{71, 0};
            case ']': return new int[]{72, 0};
            case '\\': return new int[]{73, 0};
            case ';': return new int[]{74, 0};
            case '\'': return new int[]{75, 0};
            case '/': return new int[]{76, 0};
            case '`': return new int[]{68, 0};
            case '@': return new int[]{77, 0};
            case '+': return new int[]{81, 0};
            case '*': return new int[]{17, 0};
            case '#': return new int[]{18, 0};
            case '!': return new int[]{8, 1};
            case '$': return new int[]{11, 1};
            case '%': return new int[]{12, 1};
            case '^': return new int[]{13, 1};
            case '&': return new int[]{14, 1};
            case '(': return new int[]{16, 1};
            case ')': return new int[]{7, 1};
            case '_': return new int[]{69, 1};
            case '{': return new int[]{71, 1};
            case '}': return new int[]{72, 1};
            case '|': return new int[]{73, 1};
            case ':': return new int[]{74, 1};
            case '"': return new int[]{75, 1};
            case '<': return new int[]{55, 1};
            case '>': return new int[]{56, 1};
            case '?': return new int[]{76, 1};
            case '~': return new int[]{68, 1};
            default: return null;
        }
    }

    // ---------------------------------------------------------------- voice (tested Cast_Remote flow)

    static final long VOICE_FAILED = Long.MIN_VALUE;
    private static final int VOICE_CHUNK_MAX = 20 * 1024;

    boolean voiceSupported() {
        return (active & Msgs.F_VOICE) != 0;
    }

    /**
     * Starts RemoteVoiceBegin using a locally generated session id.
     * The caller sends KEYCODE_SEARCH before calling this method, matching
     * the tested Cast_Remote implementation.
     */
    long startVoice() {
        if (closed) return VOICE_FAILED;

        if (voiceSessionActive.get() != 0) {
            return voiceId;
        }

        int id = voiceSessionCounter.incrementAndGet();
        if (id <= 0) {
            voiceSessionCounter.set(1);
            id = 1;
        }

        voiceId = id;
        voiceEnded = false;

        write(Msgs.frame(Msgs.voiceBegin(id)));
        voiceSessionActive.set(1);
        return id;
    }

    /** Streams raw 16-bit PCM mono 8 kHz audio, split at 20 KB. */
    void sendVoiceChunk(byte[] pcmChunk, int len) {
        if (voiceSessionActive.get() == 0 || pcmChunk == null || len <= 0) return;

        int offset = 0;
        while (offset < len && !closed) {
            int n = Math.min(VOICE_CHUNK_MAX, len - offset);
            byte[] samples = new byte[n];
            System.arraycopy(pcmChunk, offset, samples, 0, n);

            write(Msgs.frame(Msgs.voicePayload(voiceId, samples)));
            offset += n;
        }
    }

    /** Ends the current voice session. */
    void stopVoice() {
        if (voiceSessionActive.getAndSet(0) == 0) return;

        long id = voiceId;
        voiceId = VOICE_FAILED;
        write(Msgs.frame(Msgs.voiceEnd(id)));
    }

    // Compatibility helpers retained for the existing voice code/tests.
    long voiceBegin(long timeoutMs) {
        key(84, Msgs.DIR_SHORT);
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return VOICE_FAILED;
        }
        return startVoice();
    }

    void voiceChunk(long id, byte[] pcm, int len) {
        if (voiceSessionActive.get() == 0) {
            voiceId = id;
            voiceSessionActive.set(1);
        }
        sendVoiceChunk(pcm, len);
    }

    boolean voiceEndedByTv() {
        return voiceEnded;
    }

    void voiceEnd(long id) {
        stopVoice();
    }

    void launch(String link) {
        send(Msgs.appLink(link));
    }

    void close(String reason) {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        writer.shutdownNow();
        imeSched.shutdownNow();
        ready.countDown();
        try {
            listener.onClosed(this, reason);
        } catch (Exception ignored) {
        }
    }
}
