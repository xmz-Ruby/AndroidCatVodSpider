package com.github.catvod.spider;

import static com.github.catvod.spider.Init.getAppName;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.FileUtil;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.io.File;
import java.util.Objects;

/**
 * 配置管理器
 * 负责本地配置检查验证、SharedPreferences更新、数据库配置更新和WAL checkpoint
 */
public class ConfigManager {

    /**
     * 根据设备类型获取对应的配置文件URL
     * @return 配置文件URL (TV设备返回tv.json，其他设备返回local.json)
     */
    public static String getLocalConfigUrl() {
        String deviceType = DeviceInfoHelper.getDeviceType();
        boolean isTV = "tv".equals(deviceType);
        String configFileName = isTV ? "tv.json" : "local.json";
        if (!Objects.equals(getAppName(), "让我看看") && !Path.isInternalStorageMode()){
            configFileName = "ok.json";
        }

        String configUrl = "file://TVBoxOSC/tvbox/" + configFileName;
        SpiderDebug.log("设备类型: " + deviceType + ", 选择配置文件: " + configFileName + " (URL: " + configUrl + ")");
        return configUrl;
    }

    /**
     * 检查和处理本地配置文件
     * @return true 如果需要继续更新，false 如果不需要更新
     */
    public static boolean checkAndHandleLocalConfig() {
//        if (Path.isInternalStorageMode() && !Objects.equals(getAppName(), "让我看看")) {
//            return true;
//        }

        File plusZipFile = new File(Path.root(), "TVBox.zip");
        try {
            // 根据设备类型选择配置文件
            String localVodUrl = getLocalConfigUrl();
            String deviceType = DeviceInfoHelper.getDeviceType();
            boolean isTV = "tv".equals(deviceType);
            String configFileName = (!Objects.equals(getAppName(), "让我看看") && isTV) ? "tv.json" : "local.json";
            if (!Objects.equals(getAppName(), "让我看看") && !Path.isInternalStorageMode()){
                configFileName = "ok.json";
            }

            File localJsonFile = new File(Path.tvboxOscTvbox(), configFileName);
            SpiderDebug.log("检查 " + configFileName + " 文件: " + localJsonFile.getAbsolutePath());
            SpiderDebug.log(configFileName + " 是否存在: " + localJsonFile.exists());

            String currentVodUrl = Prefers.getString("config_0", "");

            if (localJsonFile.exists()) {
                SpiderDebug.log(configFileName + " 存在！");
                SpiderDebug.log("当前点播配置: " + currentVodUrl);
                SpiderDebug.log("目标本地配置: " + localVodUrl);

                // 验证目标本地配置是否有效
                if (isValidLocalConfig(localVodUrl)){
                    SpiderDebug.log("本地配置验证通过，开始更新配置");
                    if (plusZipFile.exists()) {
                        FileUtil.unzip(plusZipFile, Path.root());
                        FileUtil.unzip(plusZipFile, Path.internalStorageRoot());
                        // Verify all extracted files exist
                        if (!UpdateManager.verifyExtractedFiles()) {
                            if (plusZipFile.exists()) {
                                plusZipFile.delete();
                                SpiderDebug.log("已删除临时zip文件");
                            }
                            throw new Exception("文件验证失败：某些关键文件缺失");
                        }
                        Notify.show("🎉配置更新完成");
                        sleepQuietly(500);
                        plusZipFile.delete();
                        SpiderDebug.log("已删除临时zip文件");
                        // 更新数据库的当前Vod 配置和 Prefers config_0 为 localVodUrl
                        if ((Objects.equals(getAppName(), "让我看看") && currentVodUrl != localVodUrl) ||
                                (!Path.isInternalStorageMode() && currentVodUrl != localVodUrl) ||
                                (!Objects.equals(getAppName(), "让我看看")) && Path.isInternalStorageMode() && currentVodUrl.startsWith("file://")) {
                            updateConfigToLocal(localVodUrl);
                        }
                        SpiderDebug.log("Update completed, restarting app in 3 seconds");

                        // Restart app after 3 seconds
                        Init.run(() -> restartApp(), 3000);
                    } else {
                        SpiderDebug.log("当前已是本地配置，无需切换");
                    }
                } else {
                    SpiderDebug.log("本地配置验证失败，不更新配置");
                    Notify.show("❌ 本地配置文件无效，跳过更新");
                }
                return false; // 配置已存在，不需要继续更新
            } else {
                SpiderDebug.log(configFileName + " 不存在，显示当前配置");
                SpiderDebug.log("当前点播配置: " + currentVodUrl);
                Notify.show("当前点播配置:\n" + currentVodUrl);
                if (plusZipFile.exists()) {
                    plusZipFile.delete();
                    SpiderDebug.log("已删除临时zip文件");
                }
                sleepQuietly(2000);
                return true; // 配置不存在，需要更新
            }
        } catch (Exception e) {
            if (plusZipFile.exists()) {
                plusZipFile.delete();
                SpiderDebug.log("已删除临时zip文件");
            }
            SpiderDebug.log("检查本地配置失败: " + e.getMessage());
            return true; // 发生异常，继续更新流程
        }
    }

    /**
     * 验证本地配置URL是否指向有效的JSON文件
     * @param localVodUrl 本地配置URL
     * @return true 如果是有效的JSON配置
     */
    private static boolean isValidLocalConfig(String localVodUrl) {
        try {
            if (!Objects.equals(getAppName(), "让我看看") && Path.isInternalStorageMode()){
                return true;
            }
            SpiderDebug.log("开始验证本地配置URL: " + localVodUrl);

            // 检查URL格式
            if (!localVodUrl.startsWith("file://")) {
                SpiderDebug.log("URL格式不正确，不是file://协议: " + localVodUrl);
                return false;
            }

            // 转换URL为文件路径
            String filePath = localVodUrl.substring(6); // 移除 "file://" 前缀
            File configFile = new File(Path.root(), filePath);

            // 检查文件是否存在
            if (!configFile.exists()) {
                SpiderDebug.log("配置文件不存在: " + configFile.getAbsolutePath());
                return false;
            }

            // 检查文件是否可读
            if (!configFile.canRead()) {
                SpiderDebug.log("配置文件不可读: " + configFile.getAbsolutePath());
                return false;
            }

            // 读取文件内容并验证JSON格式
            String fileContent = Path.read(configFile);
            if (fileContent == null || fileContent.trim().isEmpty()) {
                SpiderDebug.log("配置文件内容为空: " + configFile.getAbsolutePath());
                return false;
            }

            // 验证JSON格式
            try {
                JSONObject jsonObject = new JSONObject(fileContent);

                // 检查必要的JSON字段
                if (!jsonObject.has("sites") && !jsonObject.has("spider")) {
                    SpiderDebug.log("配置文件缺少必要的字段(sites或spider): " + configFile.getAbsolutePath());
                    return false;
                }

                SpiderDebug.log("配置文件验证通过: " + configFile.getAbsolutePath());
                SpiderDebug.log("JSON内容预览: " + fileContent.substring(0, Math.min(100, fileContent.length())) + "...");
                return true;

            } catch (org.json.JSONException e) {
                SpiderDebug.log("配置文件不是有效的JSON格式: " + e.getMessage());
                return false;
            }

        } catch (Exception e) {
            SpiderDebug.log("验证本地配置时发生异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 更新配置到本地配置
     * @param localVodUrl 本地配置URL
     */
    public static void updateConfigToLocal(String localVodUrl) {
        try {
            SpiderDebug.log("开始更新配置到本地: " + localVodUrl);

            // 1. 更新 SharedPreferences 中的 config_0
            updateSharedPreferencesConfig(localVodUrl);

            // 2. 更新 SQLite 数据库
            updateDatabaseConfig(localVodUrl);

            // 3. 强制执行 WAL checkpoint 确保数据写入
            forceWALCheckpoint();

            Notify.show("✅ 点播和直播配置已切换到本地版本,即将重启");
            SpiderDebug.log("点播和直播配置更新完成");
            sleepQuietly(1000);
        } catch (Exception e) {
            SpiderDebug.log("更新配置失败: " + e.getMessage());
            Notify.show("❌ 点播和直播配置更新失败: " + e.getMessage());
        }
    }

    /**
     * 更新 SharedPreferences 中的配置
     */
    private static void updateSharedPreferencesConfig(String localVodUrl) {
        try {
            // 获取App的SharedPreferences
            String packageName = Init.context().getPackageName();
            SharedPreferences preferences = Init.context().getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE);

            // 更新 config_0 (点播配置)
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString("config_0", localVodUrl);

            // 同时更新 config_1 (直播配置)
            editor.putString("config_1", localVodUrl);

            editor.apply();

        } catch (Exception e) {
            SpiderDebug.log("更新 SharedPreferences 失败: " + e.getMessage());
            throw new RuntimeException("SharedPreferences 更新失败", e);
        }
    }

    /**
     * 更新 SQLite 数据库中的配置
     */
    private static void updateDatabaseConfig(String localVodUrl) {
        SQLiteDatabase db = null;
        try {
            // 获取数据库文件路径
            File databaseFile = new File(Path.databases(), "tv");
            if (!databaseFile.exists()) {
                throw new Exception("数据库文件不存在: " + databaseFile.getAbsolutePath());
            }

            SpiderDebug.log("数据库文件路径: " + databaseFile.getAbsolutePath());
            SpiderDebug.log("数据库文件存在: " + databaseFile.exists());
            SpiderDebug.log("数据库文件大小: " + databaseFile.length() + " bytes");

            // 打开数据库
            db = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);

            long currentTime = System.currentTimeMillis();

            // 更新点播配置 (type=0)
            updateConfigByType(db, 0, localVodUrl, "本地点播配置", currentTime);

            // 更新直播配置 (type=1)
            updateConfigByType(db, 1, localVodUrl, "本地直播配置", currentTime);

            SpiderDebug.log("数据库配置已更新 (点播和直播)");

        } catch (Exception e) {
            SpiderDebug.log("更新数据库配置失败: " + e.getMessage());
            throw new RuntimeException("数据库更新失败", e);
        } finally {
            if (db != null) {
                db.close();
                SpiderDebug.log("数据库连接已关闭");
            }
        }
    }

    /**
     * 根据类型更新配置
     * @param db 数据库连接
     * @param type 配置类型 (0=点播, 1=直播)
     * @param url 配置URL
     * @param name 配置名称
     * @param time 时间戳
     */
    private static void updateConfigByType(SQLiteDatabase db, int type, String url, String name, long time) {
        try {
            String typeName = type == 0 ? "点播" : "直播";
            SpiderDebug.log("更新" + typeName + "配置 (type=" + type + ")");

            // 查询当前配置
            Cursor cursor = db.rawQuery("SELECT id FROM Config WHERE type = ? and url = ? ORDER BY time DESC LIMIT 1", new String[]{String.valueOf(type), String.valueOf(url)});

            if (cursor.moveToFirst()) {
                // 更新现有配置
                int id = cursor.getInt(0);

                SpiderDebug.log("更新现有" + typeName + "配置 ID: " + id);

                db.execSQL("UPDATE Config SET time = ? WHERE id = ?",
                    new Object[]{time, id});

                SpiderDebug.log(typeName + "配置已更新");

            } else {
                // 插入新配置
                SpiderDebug.log("未找到现有" + typeName + "配置，插入新配置");

                db.execSQL("INSERT INTO Config (type, url, name, time) VALUES (?, ?, ?, ?)",
                    new Object[]{type, url, name, time});

                SpiderDebug.log("新" + typeName + "配置已插入到数据库");
            }

            cursor.close();

        } catch (Exception e) {
            String typeName = type == 0 ? "点播" : "直播";
            SpiderDebug.log("更新" + typeName + "配置失败: " + e.getMessage());
            throw new RuntimeException(typeName + "配置更新失败", e);
        }
    }

    /**
     * 强制执行 WAL checkpoint 确保数据写入
     */
    private static void forceWALCheckpoint() {
        SQLiteDatabase db = null;
        try {
            File databaseFile = new File(Path.databases(), "tv");
            db = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);

            // 执行 WAL checkpoint
            db.execSQL("PRAGMA wal_checkpoint(FULL)");

            SpiderDebug.log("WAL checkpoint 执行完成");

        } catch (Exception e) {
            SpiderDebug.log("WAL checkpoint 执行失败: " + e.getMessage());
            // WAL checkpoint 失败不是致命错误，继续执行
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    /**
     * 安静地睡眠指定毫秒数
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            SpiderDebug.log("睡眠被中断: " + e.getMessage());
        }
    }

    /**
     * 重启应用
     */
    private static void restartApp() {
        try {
            Intent intent = Init.context().getPackageManager().getLaunchIntentForPackage(Init.context().getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                Init.context().startActivity(intent);
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }
        } catch (Exception e) {
            // 使用 Android Log 而不是 SpiderDebug，避免触发已关闭的线程池
            android.util.Log.e("ConfigManager", "重启应用失败: " + e.getMessage());
        }
    }
}
