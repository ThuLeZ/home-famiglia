package com.cosyhome.tv;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough FTP for a few small files on the home network: login, passive mode, binary transfers,
 * list, store, fetch, rename, make directories. The FRITZ!Box NAS speaks it out of the box, and it
 * needs no library. Plain FTP is unencrypted: fine inside the house, not across the internet.
 */
final class Ftp implements Closeable {

    private static final int TIMEOUT_MS = 6000;
    private static final int MAX_FILE = 256 * 1024;
    private static final Pattern PASV = Pattern.compile("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)");

    private final String host;
    private final Socket ctrl;
    private final BufferedReader in;
    private final Writer out;

    /** The last reply's code and text. */
    private int code;
    private String text = "";

    Ftp(String host, int port, String user, String password) throws IOException {
        this.host = host;
        ctrl = new Socket();
        ctrl.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        ctrl.setSoTimeout(TIMEOUT_MS);
        in = new BufferedReader(new InputStreamReader(ctrl.getInputStream(), StandardCharsets.UTF_8));
        out = new OutputStreamWriter(ctrl.getOutputStream(), StandardCharsets.UTF_8);
        try {
            expect(reply(), 2);
            int c = send("USER " + user);
            if (c == 331) c = send("PASS " + password);
            expect(c, 2);
            send("OPTS UTF8 ON");   // file names in UTF-8; servers that don't know it just say no
            expect(send("TYPE I"), 2);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** Creates every directory along {@code path}, ignoring the ones that already exist. */
    void mkdirs(String path) throws IOException {
        StringBuilder at = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            if (at.length() > 0 || path.startsWith("/")) at.append('/');
            at.append(part);
            send("MKD " + at);
        }
    }

    /** Names in a directory; empty when it is empty or missing. */
    List<String> list(String dir) throws IOException {
        List<String> names = new ArrayList<>();
        Socket data = passive();
        try {
            int c = send("NLST " + dir);
            if (c / 100 != 1) return names;   // 450/550: nothing there
            BufferedReader r = new BufferedReader(new InputStreamReader(data.getInputStream(), StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // Some servers answer with full paths, some with bare names.
                names.add(line.substring(line.lastIndexOf('/') + 1));
            }
        } finally {
            data.close();
        }
        expect(reply(), 2);
        return names;
    }

    /** A small file's bytes, or null if it isn't there. */
    byte[] get(String path) throws IOException {
        Socket data = passive();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            int c = send("RETR " + path);
            if (c / 100 != 1) return null;
            InputStream is = data.getInputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = is.read(chunk)) > 0; ) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_FILE) throw new IOException("file too large: " + path);
            }
        } finally {
            data.close();
        }
        expect(reply(), 2);
        return buf.toByteArray();
    }

    /** Writes a file whole: to a temporary name first, then renamed, so readers never see half of it. */
    void put(String path, byte[] bytes) throws IOException {
        String tmp = path + ".part";
        Socket data = passive();
        try {
            expect(send("STOR " + tmp), 1);
            OutputStream os = data.getOutputStream();
            os.write(bytes);
            os.flush();
        } finally {
            data.close();
        }
        expect(reply(), 2);
        send("DELE " + path);   // some servers won't rename over an existing file
        expect(send("RNFR " + tmp), 3);
        expect(send("RNTO " + path), 2);
    }

    @Override
    public void close() {
        try {
            out.write("QUIT\r\n");
            out.flush();
        } catch (IOException ignored) {
        }
        try {
            ctrl.close();
        } catch (IOException ignored) {
        }
    }

    // ---- the protocol -----------------------------------------------------------------------------

    private Socket passive() throws IOException {
        expect(send("PASV"), 2);
        Matcher m = PASV.matcher(text);
        if (!m.find()) throw new IOException("bad PASV reply: " + text);
        String ip = m.group(1) + "." + m.group(2) + "." + m.group(3) + "." + m.group(4);
        int port = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
        // A server behind NAT may offer an address we can't reach; the one we dialled always works.
        if (ip.startsWith("0.") || ip.startsWith("127.")) ip = host;
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, port), TIMEOUT_MS);
        s.setSoTimeout(TIMEOUT_MS);
        return s;
    }

    private int send(String command) throws IOException {
        out.write(command + "\r\n");
        out.flush();
        return reply();
    }

    /** Reads one reply, multi-line ones included ("123-..." up to "123 ..."). */
    private int reply() throws IOException {
        String line = in.readLine();
        if (line == null || line.length() < 3) throw new IOException("connection closed");
        StringBuilder all = new StringBuilder(line);
        if (line.length() > 3 && line.charAt(3) == '-') {
            String end = line.substring(0, 3) + " ";
            while ((line = in.readLine()) != null) {
                all.append('\n').append(line);
                if (line.startsWith(end)) break;
            }
        }
        try {
            code = Integer.parseInt(all.substring(0, 3));
        } catch (NumberFormatException e) {
            throw new IOException("bad reply: " + all);
        }
        text = all.toString();
        return code;
    }

    private void expect(int c, int klass) throws IOException {
        if (c / 100 != klass) throw new IOException("FTP " + text);
    }
}
