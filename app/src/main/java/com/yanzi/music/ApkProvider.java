package com.yanzi.music;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 ContentProvider：只负责把应用缓存目录里的更新包以 content:// 形式交给系统安装器。
 *
 * 为什么不用 androidx 的 FileProvider：本工程 dependencies 是空的（纯 framework），
 * 为了一次安装去引 androidx.core 会把包体和构建依赖都拖大；这里只实现 openFile 一条链路，
 * 作用域限定在 cacheDir 下、文件名必须以 .apk 结尾，够用且可控。
 *
 * 安全：exported=false + grantUriPermissions=true，只有通过 Intent 的
 * FLAG_GRANT_READ_URI_PERMISSION 临时授权的安装器才能读。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.yanzi.music.updater";

    /** 给某个缓存 apk 生成 content:// uri（文件名做一次编码，防止特殊字符）。 */
    public static Uri uriFor(Context ctx, File apk) {
        String name = apk.getName();
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(name));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        // 只认 cacheDir 下的 .apk，且禁止任何路径穿越（%2F .. 都不放过）
        if (name == null || name.length() == 0
                || name.contains("/") || name.contains("\\") || name.contains("..")
                || !name.toLowerCase().endsWith(".apk")) {
            throw new FileNotFoundException("非法的安装包路径: " + name);
        }
        Context ctx = getContext();
        if (ctx == null) {
            throw new FileNotFoundException("provider 没有上下文");
        }
        File f = new File(ctx.getCacheDir(), name);
        if (!f.exists()) {
            throw new FileNotFoundException("安装包不存在: " + f.getAbsolutePath());
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
