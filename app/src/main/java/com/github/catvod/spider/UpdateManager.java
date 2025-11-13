package com.github.catvod.spider;

import static com.github.catvod.spider.Init.getAppName;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Environment;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.FileUtil;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 更新管理器
 * 负责版本检查、文件下载、ZIP解压、域名测速和配置管理
 */
public class UpdateManager {

    // 域名测速相关变量
    private static final String[] DOMAIN_CANDIDATES = {
        "https://gitee.com/mang_zhe_xue_xi/letmeseesee/raw/backup",
        "http://ctv.mangzhexuexi.com",
        "https://itv.mangzhexuexi.com",
        "https://tv.mangzhexuexi.com"
    };
    private static final long DEFAULT_SPEED_TEST_CACHE_DURATION = 1 * 60 * 1000; // 默认1分钟缓存测速结果
    private static long lastSpeedTestTime = 0;
    private static String fastestDomain = DOMAIN_CANDIDATES[0]; // 默认使用第一个域名
    private static final Map<String, Long> domainLatencies = new HashMap<>();

    
    public static boolean ensureFileAccessPermission() {
        return ensureFileAccessPermission("1");
    }

    /**
     * 确认应用是否具备访问外部存储的权限，并使用日志上报
     * @return true 表示已具备访问权限
     */
    public static boolean ensureFileAccessPermission(String quietly) {
        try {
            Context context = Init.context();
            if (context == null) {
                if (quietly != "1"){
                    LogReportManager.log("warning", "无法获取应用上下文，判定文件访问权限不足", "UpdateManager", "permission");
                }
                SpiderDebug.log("无法获取应用上下文，判定文件访问权限不足");
                return false;
            }

            boolean hasPermission = true;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                hasPermission = Environment.isExternalStorageManager() || hasStorageRuntimePermission(context);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                hasPermission = hasStorageRuntimePermission(context);
            }

            if (hasPermission) {
                if (quietly != "1"){
                    LogReportManager.log("info", "文件访问权限检查通过", "UpdateManager", "permission");
                }
                SpiderDebug.log("文件访问权限检查通过");
                return true;
            }

            String message = "当前应用缺少文件访问权限，无法执行更新";
            if (quietly != "1"){
                LogReportManager.log("warning", message, "UpdateManager", "permission");
            }
            SpiderDebug.log(message);
            return false;
        } catch (Exception e) {
            if (quietly != "1"){
                LogReportManager.logError("检测文件访问权限时发生异常: " + e.getMessage(), "UpdateManager", e);
            }
            SpiderDebug.log("检测文件访问权限时发生异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查是否具备外部存储运行时权限
     */
    private static boolean hasStorageRuntimePermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean hasMediaPermission =
                    context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;

            if (hasMediaPermission) {
                return true;
            }
        }

        boolean readGranted = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean writeGranted = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        return readGranted || writeGranted;
    }

    /**
     * 安全地显示Toast通知（避免DeadObjectException）
     * @param message 通知消息
     */
    private static void safeNotify(String message) {
        try {
            Notify.show(message);
        } catch (Exception e) {
            // 在低性能设备上，Toast可能会因为DeadObjectException失败
            // 记录到日志中，不影响主流程
            SpiderDebug.log("通知显示失败: " + e.getMessage());
        }
    }

    /**
     * 创建支持TLS 1.2的SSLSocketFactory（兼容Android 6.0.1）
     */
    private static SSLSocketFactory createSSLSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, new TrustManager[]{createTrustAllManager()}, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            SpiderDebug.log("创建TLSv1.2 SSLSocketFactory失败，降级到TLS: " + e.getMessage());
            // 降级到默认TLS版本
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{createTrustAllManager()}, new SecureRandom());
                return sslContext.getSocketFactory();
            } catch (Exception ex) {
                throw new RuntimeException("无法创建SSL上下文", ex);
            }
        }
    }

    /**
     * 创建信任所有证书的TrustManager
     */
    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    private static X509TrustManager createTrustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /**
     * 创建兼容Android 6.0.1的OkHttpClient
     */
    private static okhttp3.OkHttpClient createCompatibleOkHttpClient(int timeoutMs) {
        try {
            return new okhttp3.OkHttpClient.Builder()
                .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .sslSocketFactory(createSSLSocketFactory(), createTrustAllManager())
                .hostnameVerifier((hostname, session) -> true)
                .build();
        } catch (Exception e) {
            SpiderDebug.log("创建OkHttpClient失败: " + e.getMessage());
            throw new RuntimeException("无法创建HTTP客户端", e);
        }
    }

    /**
     * 下载文件主逻辑
     * 直接从plusZipFile中检查版本，不依赖单独的version.txt判断
     * 保留单线路.zip下载功能
     */
    public static void downloadFile() {
        try {
            File plusZipFile = new File(Path.root(), "TVBox_test.zip");
            File localVersionFile = new File(Path.tvbox(), "local_version.txt");
            String localVersion = "";

            // 读取本地版本号
            if (localVersionFile.exists()) {
                localVersion = Path.read(localVersionFile);
                if (localVersion == null) localVersion = "";
            }
            
            Prefers.putString("cache_current_version", localVersion);

            if (!plusZipFile.exists()) {
                // plusZipFile不存在，需要下载并检查版本
                boolean updateCompleted = false;
                String selectedDomain = null;
                String onlineVersion = null;

                // 遍历所有域名进行版本检测
                for (String domain : DOMAIN_CANDIDATES) {
                    try {
                        SpiderDebug.log("尝试域名: " + domain);
                        safeNotify("检测可用服务...");
                        sleepQuietly(500);

                        // 下载plusZipFile到临时文件
                        String plusUrl = domain + "/TVBox_test.zip";
                        File tempZipFile = new File(Path.root(), "TVBox_temp.zip");

                        safeNotify("检查更新...");
                        // LogReportManager.log("info", "检查更新", "UpdateManager", "downloadFile");
                        downloadFileFromUrl(plusUrl, tempZipFile);
                        SpiderDebug.log("成功下载更新包: " + domain);

                        // 从下载的zip文件中提取版本号
                        onlineVersion = extractVersionFromZip(tempZipFile);

                        if (!isValidVersion(onlineVersion)) {
                            SpiderDebug.log("从zip文件提取的版本号无效: " + onlineVersion + "，尝试下一个域名");
                            LogReportManager.log("warn", "从zip文件提取的版本号无效: " + onlineVersion + "，尝试下一个域名", "UpdateManager", "downloadFile");
                            if (tempZipFile.exists()) {
                                tempZipFile.delete(); 
                            }
                            continue;
                        }

                        SpiderDebug.log("在线版本号: " + onlineVersion + "，本地版本号: " + localVersion);
                        LogReportManager.log("info", "在线版本号: " + onlineVersion + "，本地版本号: " + localVersion, "UpdateManager", "downloadFile");

                        // 比较版本号
                        if (!localVersion.isEmpty() && localVersion.equals(onlineVersion)) {
                            // 版本一致，不需要更新
                            if (tempZipFile.exists()) {
                                tempZipFile.delete();
                            }
                            safeNotify("🎉已是最新版本");
                            updateCompleted = true;
                            break;
                        }

                        // 版本不一致或本地无版本，需要更新
                        // 将临时文件重命名为正式文件
                        if (plusZipFile.exists()) {
                            plusZipFile.delete();
                        }
                        tempZipFile.renameTo(plusZipFile);

                        selectedDomain = domain;
                        SpiderDebug.log("版本检查通过，使用域名: " + domain);
                        updateCompleted = true;
                        break;

                    } catch (Exception e) {
                        SpiderDebug.log("域名 " + domain + " 处理失败: " + e.getMessage());
                        continue;
                    }
                }

                if (!updateCompleted) {
                    SpiderDebug.log("所有域名都未能成功获取有效版本号");
                    LogReportManager.log("warning", "所有域名都未能成功获取有效版本号，跳过更新", "UpdateManager", "version_check");
                    safeNotify("无法获取更新信息，请稍后重试");
                    return;
                }

                // 如果版本检查通过，下载单线路.zip文件
                if (selectedDomain != null && onlineVersion != null && !localVersion.equals(onlineVersion)) {
                    // 下载单线路.zip文件
                    safeNotify("正在下载资源包...");
                    String zipUrl = selectedDomain + "/%E5%8D%95%E7%BA%BF%E8%B7%AF_test.zip";
                    File zipFile = new File(Path.root(), "单线路.zip");

                    downloadFileFromUrl(zipUrl, zipFile);
                    SpiderDebug.log("Downloaded 单线路.zip file successfully");
                    safeNotify("资源包下载完成，正在解压...");

                    // 解压到根目录
                    FileUtil.unzip(zipFile, Path.root());
                    SpiderDebug.log("Unzipped 单线路.zip file successfully to: " + Path.tvboxOsc().getAbsolutePath());

                    // 验证关键文件
                    File apiJsonFile = new File(Path.tvboxOscTvbox(), "api.json");
                    if (!apiJsonFile.exists()) {
                        throw new Exception("解压失败：关键文件 api.json 不存在于 " + Path.tvboxOscTvbox().getAbsolutePath());
                    }
                    SpiderDebug.log("验证成功：api.json 文件存在，大小: " + apiJsonFile.length() + " bytes");

                    // 删除单线路.zip文件
                    if (zipFile.exists()) {
                        zipFile.delete();
                        SpiderDebug.log("已删除单线路.zip文件");
                    }

                    // 解压plusZipFile
                    safeNotify("正在解压更新包...");
                    FileUtil.unzip(plusZipFile, Path.root());
                    SpiderDebug.log("解压完成: " + Path.tvboxOsc().getAbsolutePath());

                    // 验证关键文件
                    if (!verifyExtractedFiles()) {
                        // 删除zip文件
                        plusZipFile.delete();
                        SpiderDebug.log("已删除临时zip文件");
                        throw new Exception("文件验证失败：某些关键文件缺失");
                    }
                    SpiderDebug.log("所有文件验证通过");

                    safeNotify("更新文件下载完成，正在应用配置...");
                    SpiderDebug.log("Update completed, restarting app in 3 seconds");

                    // 重启应用
//                    Init.run(() -> restartApp(), 3000);
                    ConfigManager.checkAndHandleLocalConfig();
                }
            }else{
                ConfigManager.checkAndHandleLocalConfig();
            }
        } catch (Exception e) {
            SpiderDebug.log("Error downloading/unzipping file: " + e.getMessage());
            safeNotify("更新失败: " + e.getMessage());
            LogReportManager.logError("更新流程失败: " + e.getMessage(), "UpdateManager", e);
            e.printStackTrace();
        }
    }


    /**
     * 测试域名延迟并返回最快的域名（同步版本）
     * @param testUrl 测试用的URL路径
     * @return 最快的域名
     */
    public static String getFastestDomain(String testUrl) {
        return getFastestDomain(testUrl, null, false);
    }

    /**
     * 测试域名延迟并返回最快的域名（同步版本）
     * @param testUrl 测试用的URL路径
     * @param quietly 是否静默模式（不显示通知）
     * @return 最快的域名
     */
    public static String getFastestDomain(String testUrl, String quietly) {
        return getFastestDomain(testUrl, quietly, false);
    }

    /**
     * 测试域名延迟并返回最快的域名（同步版本）
     * @param testUrl 测试用的URL路径
     * @param quietly 是否静默模式（不显示通知）
     * @param validateAsVersion 是否验证返回内容为版本号格式
     * @return 最快的域名
     */
    public static String getFastestDomain(String testUrl, String quietly, boolean validateAsVersion) {
        if (quietly == null) {
            safeNotify("检测可用服务...");
        }
        long currentTime = System.currentTimeMillis();

        // 从 Prefers 获取缓存时间设置
        long speedTestCacheDuration = getSpeedTestCacheDuration();

        // 从 Prefers 获取缓存的最快域名
        String cachedFastestDomain = Prefers.getString("fastest_domain", "");
        long cachedFastestTime = Prefers.getLong("fastest_domain_time", 0);

        // 如果缓存未过期且存在缓存的最快域名，直接返回
        if (!cachedFastestDomain.isEmpty() && (currentTime - cachedFastestTime < speedTestCacheDuration)) {
            SpiderDebug.log("使用 Prefers 缓存的 fastestDomain: " + cachedFastestDomain);
            sleepQuietly(500);
            return cachedFastestDomain;
        }

        SpiderDebug.log("开始域名测速...");
        lastSpeedTestTime = currentTime;

        // 设置测速超时时间（10秒）
        final int TIMEOUT_MS = 10000;

        // 测试所有候选域名
        for (String domain : DOMAIN_CANDIDATES) {
            long startTime = System.currentTimeMillis();
            try {
                String testFullUrl = domain + testUrl;
                Map<String, String> headers = getTestHeader();

                // 使用兼容Android 6.0.1的OkHttpClient
                okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(testFullUrl)
                    .headers(okhttp3.Headers.of(headers))
                    .build();

                // 创建支持TLS 1.2的客户端（兼容Android 6.0.1）
                okhttp3.OkHttpClient client = createCompatibleOkHttpClient(TIMEOUT_MS);

                // 执行请求
                okhttp3.Response response = client.newCall(request).execute();

                // 获取响应内容
                String responseBody = response.body().string();
                response.close(); // 关闭响应，释放资源

                long latency = System.currentTimeMillis() - startTime;

                // 如果需要验证版本号格式
                if (validateAsVersion) {
                    // 检查响应内容是否为有效的版本号
                    if (!isValidVersion(responseBody)) {
                        domainLatencies.put(domain, 9999L);
                        SpiderDebug.log("域名 " + domain + " 返回无效版本号: " + responseBody + "，跳过该域名");
                        continue;
                    }
                    SpiderDebug.log("域名 " + domain + " 延迟: " + latency + "ms，版本号: " + responseBody);
                } else {
                    // 不验证内容格式，只要能成功获取即可
                    SpiderDebug.log("域名 " + domain + " 延迟: " + latency + "ms");
                }

                domainLatencies.put(domain, latency);

                // 找到第一个可用且内容有效的域名就直接返回
                Prefers.putString("fastest_domain", domain);
                Prefers.putLong("fastest_domain_time", currentTime);
                SpiderDebug.log("选择可用域名: " + domain + " (延迟: " + latency + "ms)");
                return domain;

            } catch (java.net.SocketTimeoutException e) {
                domainLatencies.put(domain, 9999L);
                SpiderDebug.log("域名 " + domain + " 测试超时(>" + TIMEOUT_MS + "ms)");
            } catch (Exception e) {
                domainLatencies.put(domain, 9999L);
                SpiderDebug.log("域名 " + domain + " 测试失败: " + e.getMessage());
            }
        }

        // 如果所有域名都失败，使用默认域名
        SpiderDebug.log("所有域名测试失败，使用默认域名: " + DOMAIN_CANDIDATES[0]);
        if (quietly == null) {
            safeNotify("所有域名测试失败，使用默认域名: " + DOMAIN_CANDIDATES[0]);
        }
        sleepQuietly(1000);
        return DOMAIN_CANDIDATES[0];
    }

    /**
     * 从 Prefers 获取域名测速缓存时间设置
     * @return 缓存时间（毫秒）
     */
    private static long getSpeedTestCacheDuration() {
        try {
            // 从 Prefers 获取缓存时间设置，单位：分钟
            int cacheMinutes = Prefers.getInt("speed_test_cache_minutes", 1); // 默认1分钟
            long cacheDuration = cacheMinutes * 60 * 1000L;
            SpiderDebug.log("从 Prefers 获取缓存时间设置: " + cacheMinutes + " 分钟 (" + cacheDuration + "ms)");
            return cacheDuration;
        } catch (Exception e) {
            SpiderDebug.log("获取缓存时间设置失败，使用默认值: " + e.getMessage());
            return DEFAULT_SPEED_TEST_CACHE_DURATION;
        }
    }

    /**
     * 获取测速用的请求头
     */
    private static Map<String, String> getTestHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        header.put("Connection", "close");
        return header;
    }

    /**
     * 下载文件到指定位置
     * @param url 文件URL
     * @param targetFile 目标文件
     * @throws Exception 下载失败时抛出异常
     */
    private static void downloadFileFromUrl(String url, File targetFile) throws Exception {
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = new BufferedInputStream(OkHttp.newCall(url).body().byteStream());
            output = new FileOutputStream(targetFile);

            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (Exception e) {
                    SpiderDebug.log("关闭输出流失败: " + e.getMessage());
                }
            }
            if (input != null) {
                try {
                    input.close();
                } catch (Exception e) {
                    SpiderDebug.log("关闭输入流失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 验证关键文件是否存在
     * @return true 如果所有关键文件都存在
     */
    public static boolean verifyExtractedFiles() {
        boolean allFilesExist = true;
        String[] criticalFiles = {
            "local.json",
            "tv.json",
            "custom_spider.jar",
            "douban.json"
        };

        for (String fileName : criticalFiles) {
            File file = new File(Path.tvboxOscTvbox(), fileName);
            if (!file.exists()) {
                SpiderDebug.log("关键文件不存在: " + file.getAbsolutePath());
                allFilesExist = false;
            } else {
                SpiderDebug.log("文件验证通过: " + file.getAbsolutePath() + " (大小: " + file.length() + " bytes)");
            }
        }

        // 额外验证 api.json（来自解压的zip文件）
//        File apiJsonFile = new File(Path.tvboxOscTvbox(), "api.json");
//        if (!apiJsonFile.exists()) {
//            SpiderDebug.log("关键文件不存在: " + apiJsonFile.getAbsolutePath());
//            allFilesExist = false;
//        } else {
//            SpiderDebug.log("文件验证通过: " + apiJsonFile.getAbsolutePath() + " (大小: " + apiJsonFile.length() + " bytes)");
//        }

        return allFilesExist;
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
            SpiderDebug.log("重启应用失败: " + e.getMessage());
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
     * 从zip文件中提取版本号
     * @param zipFile zip文件
     * @return 版本号字符串，如果提取失败返回null
     */
    private static String extractVersionFromZip(File zipFile) {
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(zipFile);
            java.util.zip.ZipEntry versionEntry = zip.getEntry("TVBox/local_version.txt");

            if (versionEntry == null) {
                SpiderDebug.log("zip文件中未找到TVBox/local_version.txt");
                return null;
            }

            InputStream is = zip.getInputStream(versionEntry);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
            String version = reader.readLine();
            reader.close();
            is.close();

            if (version != null) {
                version = version.trim();
            }
            SpiderDebug.log("从zip文件提取版本号: " + version);
            return version;

        } catch (Exception e) {
            SpiderDebug.log("从zip文件提取版本号失败: " + e.getMessage());
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception e) {
                    SpiderDebug.log("关闭zip文件失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 校验版本号是否为有效数值
     * @param version 版本号字符串
     * @return true 如果版本号是有效数值
     */
    private static boolean isValidVersion(String version) {
        if (version == null || version.trim().isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(version.trim());
            return true;
        } catch (NumberFormatException e) {
            SpiderDebug.log("版本号不是有效数值: " + version);
            return false;
        }
    }

    /**
     * 检查是否有新版本可用（统一的版本检查方法）
     * 此方法用于定期检查更新，不执行实际下载
     * @return 检查结果：0=已是最新版本, 1=发现新版本, -1=检查失败
     */
    public static int checkForUpdate() {
        try {
            File localVersionFile = new File(Path.tvbox(), "local_version.txt");
            String localVersion = "";
            String onlineVersion = null;

            // 读取本地版本号
            if (localVersionFile.exists()) {
                localVersion = Path.read(localVersionFile);
                if (localVersion == null) localVersion = "";
            }

            // 如果本地没有版本文件，说明是首次安装或需要更新
            if (localVersion.isEmpty()) {
                SpiderDebug.log("[UpdateCheck] 本地版本文件不存在，需要更新");
                return 1;
            }

            // 遍历所有域名检查在线版本
            for (String domain : DOMAIN_CANDIDATES) {
                try {
                    // 下载plusZipFile到临时文件
                    String plusUrl = domain + "/TVBox_test.zip";
                    File tempZipFile = new File(Path.root(), "TVBox_temp.zip");

                    // safeNotify("检查更新...");
                    LogReportManager.log("info", "检查更新", "UpdateManager", "downloadFile");
                    downloadFileFromUrl(plusUrl, tempZipFile);
                    SpiderDebug.log("成功下载更新包: " + domain);

                    // 从下载的zip文件中提取版本号
                    onlineVersion = extractVersionFromZip(tempZipFile);

                    if (!isValidVersion(onlineVersion)) {
                        SpiderDebug.log("从zip文件提取的版本号无效: " + onlineVersion + "，尝试下一个域名");
                        LogReportManager.log("warn", "从zip文件提取的版本号无效: " + onlineVersion + "，尝试下一个域名", "UpdateManager", "downloadFile");
                        if (tempZipFile.exists()) {
                            tempZipFile.delete(); 
                        }
                        continue;
                    }

                    SpiderDebug.log("在线版本号: " + onlineVersion + "，本地版本号: " + localVersion);
                    LogReportManager.log("info", "在线版本号: " + onlineVersion + "，本地版本号: " + localVersion, "UpdateManager", "downloadFile");

                    // 比较版本号
                    if (localVersion.equals(onlineVersion)) {
                        SpiderDebug.log("[UpdateCheck] 已是最新版本");
                        return 0;
                    } else {
                        SpiderDebug.log("[UpdateCheck] 发现新版本: " + onlineVersion);
                        return 1;
                    }

                } catch (Exception e) {
                    SpiderDebug.log("[UpdateCheck] 域名 " + domain + " 检查失败: " + e.getMessage());
                    continue;
                }
            }

            // 所有域名都失败
            SpiderDebug.log("[UpdateCheck] 所有域名检查失败");
            return -1;

        } catch (Exception e) {
            SpiderDebug.log("[UpdateCheck] 版本检查异常: " + e.getMessage());
            return -1;
        }
    }
}
