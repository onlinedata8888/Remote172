package com.example.tvremote;
import java.io.*; import java.net.*; import java.lang.reflect.*; import java.util.*;

/** Fake TV on a real socket: records EXACTLY what the phone sends for typing / backspace / enter. */
public class ImeTest {
    static int fails = 0;
    static void check(String n, boolean c){ System.out.println((c?"PASS":"FAIL")+" - "+n); if(!c) fails++; }

    static byte[] readFrame(InputStream in) throws IOException {
        int len = 0, shift = 0, b;
        while ((b = in.read()) >= 0) { len |= (b & 0x7F) << shift; if ((b & 0x80)==0) break; shift += 7; }
        if (b < 0) throw new EOFException();
        byte[] d = new byte[len]; int o = 0; while (o < len) { int r = in.read(d, o, len-o); if (r<0) throw new EOFException(); o += r; }
        return d;
    }
    /** "K<code>/<dir>" for a key inject, "IME<ime>,<field>:<text>" for a batch edit */
    static String decode(byte[] raw){
        Pb.Reader r = new Pb.Reader(raw);
        while (r.next()) {
            if (r.field == 21) {
                int ic=0, fc=0; String text="";
                Pb.Reader s = new Pb.Reader(r.data);
                while (s.next()) {
                    if (s.field==1) ic=(int)s.num; else if (s.field==2) fc=(int)s.num;
                    else if (s.field==3) { Pb.Reader e=new Pb.Reader(s.data); while(e.next()) if(e.field==2){ Pb.Reader o=new Pb.Reader(e.data); while(o.next()) if(o.field==3) text=o.str(); } }
                }
                return "IME"+ic+","+fc+":"+text;
            }
            if (r.field == 10) { Pb.Reader s=new Pb.Reader(r.data); int k=0, d=0; while(s.next()){ if(s.field==1) k=(int)s.num; else if(s.field==2) d=(int)s.num; } return "K"+k+"/"+d; }
        }
        return "other";
    }
    static byte[] status(int counter, String value){   // TV -> phone: remote_ime_key_inject{ text_field_status{counter, value} }
        Pb.Writer st = new Pb.Writer().uint(1, counter).str(2, value).uint(3, value.length()).uint(4, value.length());
        return new Pb.Writer().msg(20, new Pb.Writer().msg(2, st)).toByteArray();
    }
    static byte[] batch(int ic, int fc){ return new Pb.Writer().msg(21, new Pb.Writer().uint(1, ic).uint(2, fc)).toByteArray(); }

    static final class Rig {
        ServerSocket ss; Socket phone, tv; RemoteSession s; Method handle;
        final List<String> got = Collections.synchronizedList(new ArrayList<String>());
        Rig(boolean tvHasTextBox) throws Exception {
            ss = new ServerSocket(0);
            phone = new Socket("127.0.0.1", ss.getLocalPort()); tv = ss.accept();
            Constructor<RemoteSession> c = RemoteSession.class.getDeclaredConstructor(Socket.class, RemoteSession.Listener.class, String.class);
            c.setAccessible(true);
            s = c.newInstance(phone, new RemoteSession.Listener(){
                public void onVolume(int l,int m,boolean u){} public void onPower(boolean on){}
                public void onClosed(RemoteSession x,String r){} public void onImeField(boolean act,String v,String l){}
            }, "dev");
            handle = RemoteSession.class.getDeclaredMethod("handle", byte[].class); handle.setAccessible(true);
            if (tvHasTextBox) { handle.invoke(s, (Object) batch(5, 7)); handle.invoke(s, (Object) status(7, "old text on tv")); }
            final InputStream tin = tv.getInputStream();
            Thread t = new Thread(new Runnable(){ public void run(){ try { while(true) got.add(decode(readFrame(tin))); } catch(Exception e){} }});
            t.setDaemon(true); t.start();
        }
        void tvSays(byte[] m) throws Exception { handle.invoke(s, (Object) m); }
        List<String> take(int ms) throws Exception { Thread.sleep(ms); List<String> r; synchronized(got){ r = new ArrayList<String>(got); got.clear(); } return r; }
        void close() throws Exception { phone.close(); tv.close(); ss.close(); }
    }
    static List<String> L(String... a){ return Arrays.asList(a); }

    public static void main(String[] a) throws Exception {
        Rig r = new Rig(true);
        check("ime ready after TV reported a field", r.s.imeReady());

        // ---- the "types 4 times" bug: ONE character reaches the TV exactly once and is never re-sent
        r.s.imeType("a");
        check("typing 'a' = exactly ONE batch edit with the counters the TV reported", r.take(300).equals(L("IME5,7:a")));
        r.tvSays(status(7, "old text on tvA")); r.tvSays(status(9, "something else")); r.tvSays(batch(6, 8));
        check("nothing is re-sent later, whatever the TV reports (3 s)", r.take(3000).isEmpty());

        r.s.imeType("b");
        check("next key uses the counters the TV sent last (6,8)", r.take(300).equals(L("IME6,8:b")));

        r.s.imeType("hello");
        check("a typed chunk 'hello' = ONE batch edit (value hello)", r.take(300).equals(L("IME6,8:hello")));
        r.s.imeType("Hi there!");
        check("capitals, space, symbols go as they are in ONE edit", r.take(300).equals(L("IME6,8:Hi there!")));
        r.s.imeType(" ");
        check("space alone is typed", r.take(300).equals(L("IME6,8: ")));

        // ---- the "cannot delete text already on the TV" fix: DEL is always sent
        r.s.imeDel(1);
        check("1 backspace = 1 DEL", r.take(200).equals(L("K67/3")));
        r.s.imeDel(25);
        List<String> d = r.take(300); boolean allDel = d.size() == 25; for (String x : d) if (!x.equals("K67/3")) allDel = false;
        check("25 backspaces = exactly 25 DEL (more than anything typed on the phone)", allDel);
        r.s.imeBackspace();
        check("imeBackspace = 1 DEL", r.take(200).equals(L("K67/3")));

        // ---- order is kept across calls
        r.s.imeType("ab"); r.s.imeDel(1); r.s.imeEnter();
        check("order kept: edit(ab) DEL ENTER", r.take(300).equals(L("IME6,8:ab","K67/3","K66/3")));
        r.s.imeType("x\ny");
        check("typed newline = ENTER key between two edits", r.take(300).equals(L("IME6,8:x","K66/3","IME6,8:y")));

        // ---- Hindi / any text: same single edit
        r.s.imeType("\u0928\u092e\u0938\u094d\u0924\u0947");
        check("a Hindi word = ONE batch edit, never repeated", r.take(3000).equals(L("IME6,8:\u0928\u092e\u0938\u094d\u0924\u0947")));
        r.close();

        // ---- no counters ever sent by the TV: still exactly one edit (counters 0,0 like the reference client)
        Rig z = new Rig(false);
        z.tvSays(status(4, ""));              // TV only reported the field status, never a batch edit
        check("ready after a field status alone", z.s.imeReady());
        z.s.imeType("q");
        check("one edit with counters 0,0", z.take(300).equals(L("IME0,0:q")));
        check("and no retry", z.take(2500).isEmpty());
        z.close();

        // ---- TV that never reported a text box: plain keys; characters without a key are skipped
        Rig q = new Rig(false);
        check("not ready when TV reported nothing", !q.s.imeReady());
        q.s.imeType("ok"); check("plain keys typed", q.take(300).equals(L("K43/3","K39/3")));
        q.s.imeType("Ok"); check("capital via SHIFT", q.take(300).equals(L("K59/1","K43/3","K59/2","K39/3")));
        q.s.imeDel(3);     check("DEL still sent", q.take(300).equals(L("K67/3","K67/3","K67/3")));
        q.s.imeType("a\u0939b"); check("unsupported char skipped when no text box", q.take(400).equals(L("K29/3","K30/3")));
        q.close();

        check("strokeFor basics", RemoteSession.strokeFor('a')[0]==29 && RemoteSession.strokeFor('9')[0]==16 && RemoteSession.strokeFor('Z')[1]==1
              && RemoteSession.strokeFor('@')[0]==77 && RemoteSession.strokeFor(0x939)==null);

        System.out.println(fails==0 ? "ALL OK" : fails+" FAILED"); System.exit(fails==0?0:1);
    }
}
