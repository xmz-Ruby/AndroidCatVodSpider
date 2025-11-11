package com.github.catvod.utils;

import static com.github.catvod.spider.Init.getAppName;

import android.os.Environment;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.spider.Init;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Path {

    private static File mkdir(File file) {
        if (!file.exists()) file.mkdirs();
        return file;
    }

    public static File download() {
        if (isInternalStorageMode()) {
            return mkdir(new File(Init.context().getFilesDir(), "downloads"));
        }
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    public static File root() {
        if (isInternalStorageMode()) {
            return Init.context().getFilesDir();
        }
        return Environment.getExternalStorageDirectory();
    }

    private static boolean isInternalStorageMode() {
        try {
            return Objects.equals(getAppName(), "让我看看");
        } catch (Exception e) {
            return false;
        }
    }

    public static File cache() {
        return Init.context().getCacheDir();
    }

    public static File files() {
        return Init.context().getFilesDir();
    }

    public static File databases() {
        // Try getDatabasesDir() for API 24+
        try {
            return (File) Init.context().getClass().getMethod("getDatabasesDir").invoke(Init.context());
        } catch (Exception e) {
            // Fallback for older APIs: construct path from data directory
            return new File(Init.context().getApplicationInfo().dataDir + File.separator + "databases");
        }
    }

    public static File tv() {
        return mkdir(new File(root() + File.separator + "TV"));
    }

    public static File tvbox() {
        return mkdir(new File(root() + File.separator + "TVBox"));
    }

    public static File tvboxOsc() {
        return mkdir(new File(root() + File.separator + "TVBoxOSC"));
    }

    public static File tvboxOscTvbox() {
        return mkdir(new File(root() + File.separator + "TVBoxOSC" + File.separator + "tvbox"));
    }

    public static File databases(String path) {
        return mkdir(new File(databases(), path));
    }

    public static File cache(String path) {
        return mkdir(new File(cache(), path));
    }

    public static File tv(String name) {
        if (!name.startsWith(".")) name = "." + name;
        return new File(tv(), name);
    }

    public static File tvbox(String name) {
        if (!name.startsWith(".")) name = "." + name;
        return new File(tvbox(), name);
    }

    public static File tvboxOsc(String name) {
        if (!name.startsWith(".")) name = "." + name;
        return new File(tvboxOsc(), name);
    }

    public static File tvboxOscTvbox(String name) {
        if (!name.startsWith(".")) name = "." + name;
        return new File(tvboxOscTvbox(), name);
    }

    public static String read(File file) {
        try {
            return read(new FileInputStream(file));
        } catch (Exception e) {
            return "";
        }
    }

    public static String read(InputStream is) {
        try {
            byte[] data = new byte[is.available()];
            is.read(data);
            is.close();
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    public static File write(File file, String data) {
        return write(file, data.getBytes());
    }

    public static File write(File file, byte[] data) {
        try {
            FileOutputStream fos = new FileOutputStream(create(file));
            fos.write(data);
            fos.flush();
            fos.close();
            return file;
        } catch (Exception ignored) {
            return file;
        }
    }

    public static void copy(File in, File out) {
        try {
            copy(new FileInputStream(in), out);
        } catch (Exception ignored) {
        }
    }

    public static void copy(InputStream in, File out) {
        try {
            int read;
            byte[] buffer = new byte[8192];
            FileOutputStream fos = new FileOutputStream(create(out));
            while ((read = in.read(buffer)) != -1) fos.write(buffer, 0, read);
            fos.close();
            in.close();
        } catch (Exception ignored) {
        }
    }

    public static void move(File in, File out) {
        copy(in, out);
        clear(in);
    }

    public static void clear(File dir) {
        if (dir == null) return;
        if (dir.isDirectory()) for (File file : list(dir)) clear(file);
        if (dir.delete()) SpiderDebug.log("Deleted:" + dir.getAbsolutePath());
    }

    public static List<File> list(File dir) {
        File[] files = dir.listFiles();
        return files == null ? Collections.emptyList() : Arrays.asList(files);
    }

    public static File create(File file) throws Exception {
        try {
            if (file.getParentFile() != null) mkdir(file.getParentFile());
            if (!file.canWrite()) file.setWritable(true);
            if (!file.exists()) file.createNewFile();
            Shell.exec("chmod 777 " + file);
            return file;
        } catch (Exception e) {
            e.printStackTrace();
            return file;
        }
    }
}
