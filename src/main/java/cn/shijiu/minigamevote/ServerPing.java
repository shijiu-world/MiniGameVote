package cn.shijiu.minigamevote;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft 服务器的 Server List Ping（1.7+ 协议），只用来读「当前在线人数」。
 *
 * <p>为什么自己写：Bukkit 端没有"问另一个服务器有多少人"的接口，而插件要判断
 * 「那个还在跑的小游戏里到底有没有人」—— 有人就不能关。自己发两个包（握手 + 状态请求）
 * 拿到的 JSON 里就有 {@code players.online}，不需要任何第三方依赖。
 *
 * <p>⚠ 这是个<b>阻塞</b>调用（连不上要等超时），所以只能在异步线程里用，
 * 绝不能在主线程调。
 *
 * <p>⚠ 地址要填<b>子服自己的端口</b>，不是代理的端口：
 * 填代理的话拿到的是全服总人数（Velocity 会把所有后端的人数加起来），
 * 判断就完全错了。
 */
public final class ServerPing {

    /** 地址解析结果 */
    public static final class Address {
        public final String host;
        public final int port;

        Address(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private ServerPing() {
    }

    /**
     * 解析 {@code host:port}。
     *
     * @return 解析结果；没写端口或格式不对返回 null
     */
    public static Address parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            return null;
        }
        String host = s.substring(0, colon).trim();
        int port;
        try {
            port = Integer.parseInt(s.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (host.isEmpty() || port <= 0 || port > 65535) {
            return null;
        }
        return new Address(host, port);
    }

    /**
     * 查在线人数。
     *
     * @return 在线人数；<b>null = 没查到</b>（连不上 / 超时 / 响应不对）。
     *         调用方必须把 null 当成"不知道"处理 —— 别当成 0 人，
     *         否则一个网络抖动就会把还有人在玩的小游戏关掉。
     */
    public static Integer online(String raw, long timeoutMs) {
        Address addr = parse(raw);
        if (addr == null) {
            return null;
        }
        return ping(addr.host, addr.port, timeoutMs);
    }

    public static Integer ping(String host, int port, long timeoutMs) {
        long timeout = Math.max(200L, Math.min(timeoutMs <= 0 ? 1500L : timeoutMs, 10000L));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout);
            socket.setSoTimeout((int) timeout);
            OutputStream out = socket.getOutputStream();

            // 握手包：协议号传 -1，服务端不在乎（我们只要 players.online）
            byte[] handshake = concat(
                    varint(0x00),
                    varint(-1),
                    string(host),
                    new byte[]{(byte) ((port >> 8) & 0xFF), (byte) (port & 0xFF)},
                    varint(1));
            out.write(concat(varint(handshake.length), handshake));

            // 状态请求包
            out.write(new byte[]{0x01, 0x00});
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int len = readVarInt(in);
            if (len <= 0 || len > 1 << 20) {
                return null;
            }
            byte[] payload = new byte[len];
            in.readFully(payload);
            int id = readVarInt(new DataInputStream(new java.io.ByteArrayInputStream(payload)));
            if (id != 0x00) {
                return null;
            }
            DataInputStream p = new DataInputStream(new java.io.ByteArrayInputStream(payload));
            readVarInt(p);                       // 包 id 再读一次，把指针推过去
            String json = readString(p);
            Object root = Json.parse(json);
            Integer n = Json.integer(Json.at(root, "players.online"));
            return n;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------

    private static byte[] string(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return concat(varint(bytes.length), bytes);
    }

    private static byte[] varint(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(5);
        int v = value;
        while (true) {
            if ((v & 0xFFFFFF80) == 0) {
                out.write(v);
                break;
            }
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        return out.toByteArray();
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = read & 0x7F;
            result |= value << (7 * numRead);
            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt 太长");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > 1 << 20) {
            throw new IOException("字符串长度不对: " + len);
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
