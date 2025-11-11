package com.github.catvod.spider;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Map;

/**
 * 设备信息助手类
 * 负责设备ID生成管理、系统信息获取、平台类型检测等
 */
public class DeviceInfoHelper {

    private static String deviceId = ""; // 设备ID缓存
    private static Application context;

    private static final long DEVICE_AUTH_SUCCESS_CACHE_DURATION = 60 * 60 * 1000L; // 60分钟
    private static final long DEVICE_AUTH_FAILURE_CACHE_DURATION = 5 * 60 * 1000L; // 5分钟
    
    private static long lastDeviceAuthCheckTime = 0;
    private static boolean lastDeviceAuthResult = false;

    /**
     * 初始化设备信息助手
     * @param app 应用Context
     */
    public static void init(Application app) {
        context = app;
    }

    /**
     * 获取或生成设备ID
     * @return 设备ID
     */
    public static String getOrCreateDeviceId() {
        if (deviceId.isEmpty()) {
            deviceId = Prefers.getString("cache_device_id", "");
            if (deviceId.isEmpty()) {
                // 生成基于设备信息的唯一ID
                deviceId = generateDeviceId();
                Prefers.putString("cache_device_id", deviceId);
                Prefers.putString("cache_device_name", Build.DEVICE);
                SpiderDebug.log("生成新的设备ID: " + deviceId);
            }
        }
        return deviceId;
    }

    /**
     * 生成设备ID
     * 优先使用Android ID（设备串码），如果获取失败则使用设备信息的哈希值
     * @return 设备ID
     */
    private static String generateDeviceId() {
        try {
            // 方案1: 优先使用 Android ID（设备唯一标识符）
            String androidId = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID
            );

            if (androidId != null && !androidId.isEmpty() && !androidId.equals("9774d56d682e549c")) {
                // 9774d56d682e549c 是某些设备/模拟器的默认值，需要排除
                SpiderDebug.log("使用 Android ID 作为设备ID: " + androidId);
                return androidId;
            }

            // 方案2: 如果 Android ID 不可用，使用设备信息生成唯一ID
            SpiderDebug.log("Android ID 不可用，使用设备信息生成ID");
            String raw = Build.BRAND + Build.MODEL + Build.DEVICE + Build.FINGERPRINT + Build.SERIAL;
            String hash = String.valueOf(raw.hashCode()).replace("-", "");
            SpiderDebug.log("生成设备ID (基于设备信息): " + hash);
            return hash;

        } catch (Exception e) {
            SpiderDebug.log("生成设备ID失败，使用时间戳: " + e.getMessage());
            return String.valueOf(System.currentTimeMillis());
        }
    }

    /**
     * 获取设备平台类型
     * @return "tv" 或 "phone"
     */
    public static String getDeviceType() {
        try {
            int uiMode = context.getResources().getConfiguration().uiMode;
            boolean isTv = (uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK)
                         == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
            return isTv ? "tv" : "phone";
        } catch (Exception e) {
            SpiderDebug.log("获取设备类型失败: " + e.getMessage());
            return "unknown";
        }
    }

    /**
     * 获取应用版本号
     * @return 版本号
     */
    public static String getAppVersion() {
        try {
            return context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            SpiderDebug.log("获取应用版本失败: " + e.getMessage());
            return "unknown";
        }
    }

    /**
     * 获取CPU架构
     * @return CPU架构
     */
    public static String getCpuArchitecture() {
        try {
            return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        } catch (Exception e) {
            SpiderDebug.log("获取CPU架构失败: " + e.getMessage());
            return "unknown";
        }
    }

    /**
     * 获取宿主App的原生库架构
     * 通过检查宿主App加载的原生库来判断其构建架构
     *
     * @return "armeabi-v7a", "arm64-v8a", "x86", "x86_64" 或 "unknown"
     */
    public static String getHostAppAbi() {
        try {
            // 方法1：通过ApplicationInfo获取原生库路径
            android.content.pm.ApplicationInfo appInfo = context.getApplicationInfo();
            String nativeLibraryDir = appInfo.nativeLibraryDir;

            SpiderDebug.log("[DeviceInfo] 宿主App原生库目录: " + nativeLibraryDir);

            if (nativeLibraryDir != null) {
                if (nativeLibraryDir.contains("arm64")) {
                    return "arm64-v8a";
                } else if (nativeLibraryDir.contains("arm")) {
                    return "armeabi-v7a";
                } else if (nativeLibraryDir.contains("x86_64")) {
                    return "x86_64";
                } else if (nativeLibraryDir.contains("x86")) {
                    return "x86";
                }
            }

            // 方法2：检查lib目录下的文件
            File libDir = new File(nativeLibraryDir);
            if (libDir.exists() && libDir.isDirectory()) {
                String[] files = libDir.list();
                if (files != null && files.length > 0) {
                    SpiderDebug.log("[DeviceInfo] 宿主App原生库文件数量: " + files.length);
                    // 通过文件名或父目录判断架构
                    return detectAbiFromPath(nativeLibraryDir);
                }
            }

            // 方法3：通过系统属性判断（备选）
            String primaryAbi = Build.SUPPORTED_ABIS[0];
            SpiderDebug.log("[DeviceInfo] 使用系统主架构作为备选: " + primaryAbi);
            return primaryAbi;

        } catch (Exception e) {
            SpiderDebug.log("[DeviceInfo] 获取宿主App架构失败: " + e.getMessage());
            return "unknown";
        }
    }

    /**
     * 从路径中检测ABI类型
     */
    private static String detectAbiFromPath(String path) {
        if (path == null) return "unknown";

        if (path.contains("arm64-v8a") || path.contains("arm64")) {
            return "arm64-v8a";
        } else if (path.contains("armeabi-v7a") || path.contains("armeabi")) {
            return "armeabi-v7a";
        } else if (path.contains("x86_64")) {
            return "x86_64";
        } else if (path.contains("x86")) {
            return "x86";
        }

        return "unknown";
    }

    /**
     * 根据宿主App的ABI判断构建类型
     *
     * 规则（基于FongMi TV的构建配置）：
     * - armeabi-v7a (32位) -> leanback 版本（电视优化）
     * - arm64-v8a (64位) -> mobile 版本（手机/平板优化）
     *
     * @return "mobile" 或 "leanback" 或 null（无法判断）
     */
    public static String getBuildTypeFromAbi() {
        try {
            String abi = getHostAppAbi();
            SpiderDebug.log("[DeviceInfo] 宿主App ABI: " + abi);

            // 根据ABI判断构建类型
            if (abi.equals("armeabi-v7a")) {
                SpiderDebug.log("[DeviceInfo] 根据ABI判断为 mobile 版本（32位）");
                return "leanback";
            } else if (abi.equals("arm64-v8a")) {
                SpiderDebug.log("[DeviceInfo] 根据ABI判断为 leanback 版本（64位）");
                return "mobile";
            } else if (abi.equals("x86")) {
                // x86 通常是模拟器或某些平板，倾向于 mobile
                SpiderDebug.log("[DeviceInfo] 根据ABI判断为 mobile 版本（x86模拟器）");
                return "mobile";
            } else if (abi.equals("x86_64")) {
                // x86_64 可能是TV模拟器
                SpiderDebug.log("[DeviceInfo] 根据ABI判断为 leanback 版本（x86_64）");
                return "mobile";
            }

            SpiderDebug.log("[DeviceInfo] 无法根据ABI判断构建类型: " + abi);
            return null;

        } catch (Exception e) {
            SpiderDebug.log("[DeviceInfo] getBuildTypeFromAbi 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取Android系统架构信息
     */
    public static String getSystemArchitecture() {
        try {
            // 获取CPU架构
            String abi1 = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
            String abi2 = Build.SUPPORTED_ABIS.length > 1 ? Build.SUPPORTED_ABIS[1] : "";
            String archInfo = "主要架构: " + abi1;
            if (!abi2.isEmpty()) {
                archInfo += ", 次要架构: " + abi2;
            }

            // 获取CPU信息
            String cpuInfo = "CPU核心数: " + Runtime.getRuntime().availableProcessors();

            return archInfo + " | " + cpuInfo;
        } catch (Exception e) {
            return "获取架构信息失败: " + e.getMessage();
        }
    }

    /**
     * 获取设备平台信息（手机/TV）
     */
    public static String getDevicePlatform() {
        try {
            // 通过UI模式判断设备类型
            int uiMode = context.getResources().getConfiguration().uiMode;
            boolean isTv = (uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK)
                         == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;

            String deviceType = isTv ? "tv" : "phone";

            // 获取屏幕尺寸信息
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            String screenInfo = "屏幕: " + metrics.widthPixels + "x" + metrics.heightPixels
                              + " (" + metrics.densityDpi + "dpi)";

            // 获取设备型号
            String model = "型号: " + Build.MODEL;

            return deviceType + " | " + model + " | " + screenInfo;
        } catch (Exception e) {
            return "获取平台信息失败: " + e.getMessage();
        }
    }

    /**
     * 获取整个SharedPreferences的内容为JSON字符串
     * @return SharedPreferences内容的JSON字符串
     */
    public static String getAllSharedPreferences() {
        try {
            String packageName = context.getPackageName();
            SharedPreferences preferences = context.getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE);
            Map<String, ?> allPrefs = preferences.getAll();

            JSONObject prefsJson = new JSONObject();
            for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // 将不同类型的值转换为JSON
                if (value instanceof String) {
                    prefsJson.put(key, value);
                } else if (value instanceof Integer) {
                    prefsJson.put(key, value);
                } else if (value instanceof Long) {
                    prefsJson.put(key, value);
                } else if (value instanceof Float) {
                    prefsJson.put(key, value);
                } else if (value instanceof Boolean) {
                    prefsJson.put(key, value);
                } else if (value != null) {
                    prefsJson.put(key, value.toString());
                }
            }

            return prefsJson.toString();
        } catch (Exception e) {
            SpiderDebug.log("获取SharedPreferences失败: " + e.getMessage());
            return "{}";
        }
    }

    /**
     * 获取播放历史记录（最近100条）
     * @return 播放历史的JSON字符串
     */
    public static String getPlayHistory() {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            // 获取数据库文件路径
            File databaseFile = new File(Path.databases(), "tv");
            if (!databaseFile.exists()) {
                SpiderDebug.log("数据库文件不存在，无法获取播放历史");
                return "[]";
            }

            // 打开数据库
            db = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

            // 查询最近100条播放历史记录，按createTime降序排列
            cursor = db.rawQuery(
                "SELECT key, vodPic, vodName, vodFlag, vodRemarks, episodeUrl, revSort, revPlay, " +
                "createTime, opening, ending, position, duration, speed, scale, cid " +
                "FROM History ORDER BY createTime DESC LIMIT 100",
                null
            );

            JSONArray historyArray = new JSONArray();
            while (cursor.moveToNext()) {
                JSONObject record = new JSONObject();
                record.put("key", cursor.getString(0));
                record.put("vodPic", cursor.getString(1));
                record.put("vodName", cursor.getString(2));
                record.put("vodFlag", cursor.getString(3));
                record.put("vodRemarks", cursor.getString(4));
                record.put("episodeUrl", cursor.getString(5));
                record.put("revSort", cursor.getInt(6));
                record.put("revPlay", cursor.getInt(7));
                record.put("createTime", cursor.getLong(8));
                record.put("opening", cursor.getLong(9));
                record.put("ending", cursor.getLong(10));
                record.put("position", cursor.getLong(11));
                record.put("duration", cursor.getLong(12));
                record.put("speed", cursor.getFloat(13));
                record.put("player", cursor.getInt(14));
                record.put("scale", cursor.getInt(15));
                record.put("cid", cursor.getInt(16));

                historyArray.put(record);
            }

            SpiderDebug.log("成功获取播放历史记录: " + historyArray.length() + " 条");
            return historyArray.toString();

        } catch (Exception e) {
            SpiderDebug.log("获取播放历史失败: " + e.getMessage());
            return "[]";
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            if (db != null) {
                db.close();
            }
        }
    }

    /**
     * 打印系统信息
     */
    public static void printSystemInfo() {
        try {
            String architecture = getSystemArchitecture();
            String platform = getDevicePlatform();

            // 获取Android系统版本信息
            String androidVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
            String securityPatch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? Build.VERSION.SECURITY_PATCH : "N/A";

            // 获取设备信息
            String deviceManufacturer = Build.MANUFACTURER;
            String deviceModel = Build.MODEL;
            String deviceName = Build.DEVICE;
            String deviceProduct = Build.PRODUCT;
            String deviceBrand = Build.BRAND;
            String deviceBoard = Build.BOARD;

            // 获取系统构建信息
            String buildFingerprint = Build.FINGERPRINT;
            String buildDisplay = Build.DISPLAY;
            String buildId = Build.ID;
            String buildTags = Build.TAGS;
            String buildType = Build.TYPE;

            // 获取硬件信息
            String hardware = Build.HARDWARE;
            String bootloader = Build.BOOTLOADER;
            String radioVersion = Build.getRadioVersion();

            String systemInfo = "=== 系统信息 ===";
            SpiderDebug.log(systemInfo);
            SpiderDebug.log("Android版本: " + androidVersion);
            SpiderDebug.log("安全补丁: " + securityPatch);
            SpiderDebug.log("设备制造商: " + deviceManufacturer);
            SpiderDebug.log("设备品牌: " + deviceBrand);
            SpiderDebug.log("设备型号: " + deviceModel);
            SpiderDebug.log("设备名称: " + deviceName);
            SpiderDebug.log("产品名称: " + deviceProduct);
            SpiderDebug.log("主板型号: " + deviceBoard);
            SpiderDebug.log("硬件平台: " + hardware);
            SpiderDebug.log("架构: " + architecture);
            SpiderDebug.log("平台: " + platform);
            SpiderDebug.log("构建ID: " + buildId);
            SpiderDebug.log("构建显示: " + buildDisplay);
            SpiderDebug.log("构建标签: " + buildTags);
            SpiderDebug.log("构建类型: " + buildType);
            SpiderDebug.log("引导加载程序: " + bootloader);
            SpiderDebug.log("基带版本: " + radioVersion);
            SpiderDebug.log("系统指纹: " + buildFingerprint);
            SpiderDebug.log("================");

        } catch (Exception e) {
            SpiderDebug.log("打印系统信息失败: " + e.getMessage());
        }
    }
}
