package com.yanzi.music;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.InflaterInputStream;

/**
 * 只下几十 KB 就能读到远端 APK 的 versionCode：
 * Range 取文件尾 → 解 zip 中央目录 → 定位 AndroidManifest.xml → Range 只取这一小段 → inflate → 解编译后的 AXML。
 * 拿不到（不支持 Range、解析失败）一律返回 -1，调用方按"未知"处理。
 *
 * 刻意不引 Android 依赖，方便在电脑上直接 javac 跑测试。
 */
final class ApkProbe {

    private static final String UA = "YanZiMusic-Updater/1.0";
    private static final int TAIL = 65536;

    private ApkProbe() {
    }

    /** 取远端文件的 versionCode；未知返回 -1。 */
    static long probe(String url) {
        HttpURLConnection conn = null;
        try {
            HttpURLConnection head = (HttpURLConnection) new URL(url).openConnection();
            head.setConnectTimeout(8000);
            head.setReadTimeout(12000);
            head.setRequestProperty("User-Agent", UA);
            head.setRequestProperty("Range", "bytes=0-1");
            if (head.getResponseCode() != 206) {
                head.disconnect();
                return -1;
            }
            String cr = head.getHeaderField("Content-Range");
            head.disconnect();
            if (cr == null) {
                return -1;
            }
            int slash = cr.lastIndexOf('/');
            if (slash < 0) {
                return -1;
            }
            long size = Long.parseLong(cr.substring(slash + 1).trim());
            if (size <= 0) {
                return -1;
            }

            long tailLen = Math.min(size, TAIL);
            byte[] tail = fetch(url, size - tailLen, size - 1);
            if (tail == null || tail.length < 22) {
                return -1;
            }
            long cdOffset = -1;
            long cdSize = -1;
            // EOCD: 从尾部向前找 0x06054b50
            for (int i = tail.length - 22; i >= 0; i--) {
                if (u4(tail, i) == 0x06054b50L) {
                    cdSize = u4(tail, i + 12);
                    cdOffset = u4(tail, i + 16);
                    break;
                }
            }
            if (cdOffset < 0 || cdSize <= 0) {
                return -1;
            }
            byte[] cd = fetch(url, cdOffset, cdOffset + cdSize - 1);
            if (cd == null) {
                return -1;
            }
            int p = 0;
            while (p + 46 <= cd.length) {
                if (u4(cd, p) != 0x02014b50L) {
                    break;
                }
                int method = u2(cd, p + 10);
                long compSize = u4(cd, p + 20);
                int nameLen = u2(cd, p + 28);
                int extraLen = u2(cd, p + 30);
                int commentLen = u2(cd, p + 32);
                long localOffset = u4(cd, p + 42);
                String name = new String(cd, p + 46, nameLen, "UTF-8");
                if (name.endsWith("AndroidManifest.xml")) {
                    return readVersionCode(url, localOffset, compSize, method);
                }
                p += 46 + nameLen + extraLen + commentLen;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static long readVersionCode(String url, long localOffset, long compSize, int method) {
        // 本地头 30 字节 + 文件名 + extra，头部长度事先不知道，多取 512 字节足够（文件名不会那么长）
        byte[] head = fetch(url, localOffset, Math.min(localOffset + compSize + 600, localOffset + compSize + 600));
        if (head == null || head.length < 30) {
            return -1;
        }
        if (u4(head, 0) != 0x04034b50L) {
            return -1;
        }
        int nameLen = u2(head, 26);
        int extraLen = u2(head, 28);
        int dataStart = 30 + nameLen + extraLen;
        if (dataStart + compSize > head.length) {
            return -1;
        }
        byte[] raw = new byte[(int) compSize];
        System.arraycopy(head, dataStart, raw, 0, (int) compSize);
        byte[] xml;
        try {
            if (method == 0) {
                xml = raw;
            } else if (method == 8) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(raw), new java.util.zip.Inflater(true));
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                in.close();
                xml = bos.toByteArray();
            } else {
                return -1;
            }
        } catch (Exception e) {
            return -1;
        }
        return parseVersionCode(xml);
    }

    private static byte[] fetch(String url, long from, long to) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Range", "bytes=" + from + "-" + to);
            if (c.getResponseCode() != 206) {
                return null;
            }
            in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** 测试用：整包读进内存后走同一套解析（与远端 Range 解析逻辑一致）。 */
    static long versionCodeOfBytes(byte[] apk) {
        byte[] xml = manifestFromBytes(apk);
        if (xml == null) {
            return -1;
        }
        return parseVersionCode(xml);
    }

    /** 取包里 AndroidManifest.xml 的原始字节（已解压）。 */
    static byte[] manifestFromBytes(byte[] apk) {
        try {
            int eocd = -1;
            for (int i = apk.length - 22; i >= 0; i--) {
                if (u4(apk, i) == 0x06054b50L) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                return null;
            }
            long cdSize = u4(apk, eocd + 12);
            long cdOffset = u4(apk, eocd + 16);
            int p = (int) cdOffset;
            int end = (int) Math.min(apk.length, cdOffset + cdSize);
            while (p + 46 <= end) {
                if (u4(apk, p) != 0x02014b50L) {
                    break;
                }
                int method = u2(apk, p + 10);
                long compSize = u4(apk, p + 20);
                int nameLen = u2(apk, p + 28);
                int extraLen = u2(apk, p + 30);
                int commentLen = u2(apk, p + 32);
                long localOffset = u4(apk, p + 42);
                String name = new String(apk, p + 46, nameLen, "UTF-8");
                if (name.endsWith("AndroidManifest.xml")) {
                    int lo = (int) localOffset;
                    int nLen = u2(apk, lo + 26);
                    int eLen = u2(apk, lo + 28);
                    int ds = lo + 30 + nLen + eLen;
                    byte[] raw = new byte[(int) compSize];
                    System.arraycopy(apk, ds, raw, 0, (int) compSize);
                    byte[] xml;
                    if (method == 0) {
                        xml = raw;
                    } else if (method == 8) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(raw), new java.util.zip.Inflater(true));
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                        }
                        in.close();
                        xml = bos.toByteArray();
                    } else {
                        return null;
                    }
                    return xml;
                }
                p += 46 + nameLen + extraLen + commentLen;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析编译后的 AndroidManifest.xml（AXML），只找 versionCode（资源 ID 0x0101021b）。 */
    static long parseVersionCode(byte[] axml) {
        if (axml == null || axml.length < 16) {
            return -1;
        }
        String[] pool = null;
        int[] resIds = null;
        int pos = 8;   // 跳过 RES_XML_TYPE 头
        while (pos + 8 <= axml.length) {
            int type = u2(axml, pos);
            int headerSize = u2(axml, pos + 2);
            long size = u4(axml, pos + 4);
            if (size < 8 || pos + size > axml.length) {
                break;
            }
            if (type == 0x0001) {
                pool = parseStringPool(axml, pos, headerSize, (int) size);
            } else if (type == 0x0180) {
                int count = (int) ((size - headerSize) / 4);
                resIds = new int[count];
                for (int i = 0; i < count; i++) {
                    resIds[i] = (int) u4(axml, pos + headerSize + i * 4);
                }
            } else if (type == 0x0102) {
                int off = pos + headerSize;
                if (off + 16 > axml.length) {
                    break;
                }
                int nameIdx = (int) u4(axml, off + 4);
                int attrStart = u2(axml, off + 8);
                int attrSize = u2(axml, off + 10);
                int attrCount = u2(axml, off + 12);
                if (pool != null && nameIdx >= 0 && nameIdx < pool.length && !"manifest".equals(pool[nameIdx])) {
                    pos += (int) size;
                    continue;
                }
                for (int i = 0; i < attrCount; i++) {
                    int a = off + attrStart + i * attrSize;
                    if (a + 20 > axml.length) {
                        break;
                    }
                    int aNameIdx = (int) u4(axml, a + 4);
                    int dataType = axml[a + 15] & 0xFF;
                    int data = (int) u4(axml, a + 16);
                    int rid = (aNameIdx >= 0 && resIds != null && aNameIdx < resIds.length) ? resIds[aNameIdx] : 0;
                    String aName = (pool != null && aNameIdx >= 0 && aNameIdx < pool.length) ? pool[aNameIdx] : "";
                    if (rid == 0x0101021b || "versionCode".equals(aName)) {
                        if (dataType == 0x10 || dataType == 0x11) {
                            return data;
                        }
                    }
                }
            }
            pos += (int) size;
        }
        return -1;
    }

    private static String[] parseStringPool(byte[] b, int pos, int headerSize, int size) {
        try {
            int count = (int) u4(b, pos + 8);
            int flags = (int) u4(b, pos + 16);
            int stringsStart = (int) u4(b, pos + 20);
            boolean utf8 = (flags & 0x100) != 0;
            String[] out = new String[count];
            for (int i = 0; i < count; i++) {
                int off = (int) u4(b, pos + headerSize + i * 4);
                int p = pos + stringsStart + off;
                if (utf8) {
                    int len = b[p] & 0xFF;
                    p++;
                    if ((len & 0x80) != 0) {
                        len = ((len & 0x7F) << 8) | (b[p] & 0xFF);
                        p++;
                    }
                    int len2 = b[p] & 0xFF;
                    p++;
                    if ((len2 & 0x80) != 0) {
                        len2 = ((len2 & 0x7F) << 8) | (b[p] & 0xFF);
                        p++;
                    }
                    out[i] = new String(b, p, len2, "UTF-8");
                } else {
                    int len = u2(b, p);
                    p += 2;
                    if ((len & 0x8000) != 0) {
                        len = ((len & 0x7FFF) << 16) | u2(b, p);
                        p += 2;
                    }
                    out[i] = new String(b, p, len * 2, "UTF-16LE");
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static int u2(byte[] b, int i) {
        if (i + 2 > b.length) {
            return 0;
        }
        return ((b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8));
    }

    private static long u4(byte[] b, int i) {
        if (i + 4 > b.length) {
            return 0xFFFFFFFFL;
        }
        return ((long) (b[i] & 0xFF))
                | ((long) (b[i + 1] & 0xFF) << 8)
                | ((long) (b[i + 2] & 0xFF) << 16)
                | ((long) (b[i + 3] & 0xFF) << 24);
    }
}
