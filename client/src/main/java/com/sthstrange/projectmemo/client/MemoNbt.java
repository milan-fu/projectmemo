package com.sthstrange.projectmemo.client;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** 最小 NBT 读取器（gzip NBT，.litematic 用）。值：Map/List/long[]/int[]/byte[]/String/Number。 */
public final class MemoNbt {

    private static final int MAX_DEPTH = 512;

    private final DataInputStream in;

    private MemoNbt(DataInputStream in) { this.in = in; }

    public static Map<String, Object> readGzip(File file) throws IOException {
        try (InputStream fin = new FileInputStream(file);
             GZIPInputStream gz = new GZIPInputStream(fin);
             DataInputStream dis = new DataInputStream(gz)) {
            MemoNbt r = new MemoNbt(dis);
            int type = dis.readUnsignedByte();
            if (type != 10) throw new IOException(L10n.get("projectmemo.nbt.badRoot", type));
            r.readString();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) r.readPayload(10, 0);
            return root;
        }
    }

    private String readString() throws IOException {
        int len = in.readUnsignedShort();
        if (len < 0 || len > 65535) throw new IOException(L10n.get("projectmemo.nbt.badStringLen", len));
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Object readPayload(int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException(L10n.get("projectmemo.nbt.depth"));
        switch (type) {
            case 1: return (byte) in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                int n = in.readInt();
                checkLen(n);
                byte[] b = new byte[n];
                in.readFully(b);
                return b;
            }
            case 8: return readString();
            case 9: {
                int elemType = in.readUnsignedByte();
                int n = in.readInt();
                checkLen(n);
                List<Object> list = new ArrayList<>(Math.min(n, 1_000_000));
                for (int i = 0; i < n; i++) list.add(readPayload(elemType, depth + 1));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String name = readString();
                    map.put(name, readPayload(t, depth + 1));
                }
                return map;
            }
            case 11: { // TAG_Int_Array
                int n = in.readInt();
                checkLen(n);
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                return a;
            }
            case 12: { // TAG_Long_Array
                int n = in.readInt();
                checkLen(n);
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            default:
                throw new IOException(L10n.get("projectmemo.nbt.unknownType", type));
        }
    }

    private static void checkLen(int n) throws IOException {
        if (n < 0 || n > 64_000_000) throw new IOException(L10n.get("projectmemo.nbt.badArrayLen", n));
    }
}
