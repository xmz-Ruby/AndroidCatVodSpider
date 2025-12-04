package com.github.catvod.spider;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.ProxyServer;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import com.github.catvod.utils.Path;

/**
 * 初始化入口类（精简版）
 * 使用模块化架构，职责更清晰
 *
 * 模块说明：
 * - DeviceInfoHelper: 设备信息管理（设备ID、系统信息、历史数据）
 * - HeartbeatManager: 心跳管理（心跳发送、定时调度）
 * - UpdateManager: 更新管理（版本检查、文件下载、域名测速）
 * - ConfigManager: 配置管理（本地配置、数据库更新）
 */
public class Init {

    private final ExecutorService executor;
    private final Handler handler;
    private Application app;

    private static class Loader {
        static volatile Init INSTANCE = new Init();
    }

    public static Init get() {
        return Loader.INSTANCE;
    }

    public static String getAppName() {
        String appName = "未知";
        try {
            Context ctx = context();
            PackageManager pm = ctx.getPackageManager();
            appName = pm.getApplicationLabel(ctx.getApplicationInfo()).toString();
        } catch (Exception e) {
            SpiderDebug.log("[Init] 获取App名称失败: " + e.getMessage());
        }
        return appName;
    }

    public Init() {
        this.handler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(5);
    }

    public static Application context() {
        return get().app;
    }

    /**
     * 初始化应用
     * @param context 应用上下文
     */
    public static void init(Context context) {
        get().app = ((Application) context);

        // 检查App限制：非"让我看看"且非TV设备时禁用
        checkAppRestriction();

        InitStatusTracker.reset();
        InitStatusTracker.markPending(InitStatusTracker.STEP_SECURE_HTTP, "等待安全控制");

        execute(() -> {
            ProxyServer.INSTANCE.stop();
            ProxyServer.INSTANCE.start();
        });

        // 初始化设备信息助手
        DeviceInfoHelper.init(get().app);

        // 异步执行downloadFile（使用UpdateManager）
        execute(() -> {
            InitStatusTracker.markPending(InitStatusTracker.STEP_DOWNLOAD, "更新流程执行中");
            try {
                UpdateManager.downloadFile();
                InitStatusTracker.markSuccess(InitStatusTracker.STEP_DOWNLOAD, "更新流程完成");
                File plusZipFile = new File(Path.root(), "TVBox.zip");
                if (!plusZipFile.exists()) {
                    // 尝试初始化 Chaquo Python Loader
                    if (!Objects.equals(getAppName(), "让我看看")){
                        initChaquoLoader();
                    }
                } else {
                    InitStatusTracker.markSkipped(InitStatusTracker.STEP_CHAQUO, "TVBox.zip 存在，跳过初始化");
                }

                // 【安全增强】HTTP服务器安全控制
                // 通过反射修改主App的Nano服务器，使其仅监听127.0.0.1
                if (!Objects.equals(getAppName(), "让我看看")) {
                    secureHttpServer(get().app);
                }

            } catch (Exception e) {
                InitStatusTracker.markError(InitStatusTracker.STEP_DOWNLOAD, "更新流程失败: " + e.getMessage());
                LogReportManager.logError("异步downloadFile执行失败: " + e.getMessage(), "Init", e);
                Notify.show("初始化失败: " + e.getMessage());
            }
        });
        // 启动心跳任务（使用HeartbeatManager）
        HeartbeatManager.startHeartbeat();

    }

    /**
     * 在后台线程池执行任务
     * @param runnable 要执行的任务
     */
    public static void execute(Runnable runnable) {
        get().executor.execute(runnable);
    }

    /**
     * 在主线程执行任务
     * @param runnable 要执行的任务
     */
    public static void run(Runnable runnable) {
        get().handler.post(runnable);
    }

    /**
     * 在主线程延迟执行任务
     * @param runnable 要执行的任务
     * @param delay 延迟时间（毫秒）
     */
    public static void run(Runnable runnable, int delay) {
        get().handler.postDelayed(runnable, delay);
    }

    /**
     * 尝试初始化 Chaquo Python
     * 使用反射机制调用，避免编译时依赖
     * 提前启动 Python 环境，避免首次使用时延迟
     */
    private static void initChaquoLoader() {
        try {
            InitStatusTracker.markPending(InitStatusTracker.STEP_CHAQUO, "检查Chaquo Python环境");
            // 使用反射加载 Python 类
            Class<?> pythonClass = Class.forName("com.chaquo.python.Python");

            // 检查 Python 是否已启动: Python.isStarted()
            boolean isStarted = (boolean) pythonClass.getMethod("isStarted").invoke(null);

            if (isStarted) {
                InitStatusTracker.markSuccess(InitStatusTracker.STEP_CHAQUO, "Chaquo Python 已经启动");
                // LogReportManager.log("info", "Chaquo Python 已经启动", "Init", "chaquo");
                // SpiderDebug.log("[Init] Chaquo Python 已经启动");
                return;
            }

            // 启动 Python 环境: Python.start(new AndroidPlatform(context()))
            Class<?> platformClass = Class.forName("com.chaquo.python.android.AndroidPlatform");
            Object platform = platformClass.getConstructor(Context.class).newInstance(context());
            pythonClass.getMethod("start", Class.forName("com.chaquo.python.Python$Platform")).invoke(null, platform);

            InitStatusTracker.markSuccess(InitStatusTracker.STEP_CHAQUO, "Chaquo Python 启动成功");
            // LogReportManager.log("info", "Chaquo Python 启动成功", "Init", "chaquo");
            // SpiderDebug.log("[Init] Chaquo Python 启动成功");

            // 尝试预加载 app 模块（可选，加快首次使用速度）
            try {
                // Python.getInstance().getModule("app")
                Object pythonInstance = pythonClass.getMethod("getInstance").invoke(null);
                Class<?> pyObjectClass = Class.forName("com.chaquo.python.PyObject");
                pythonClass.getMethod("getModule", String.class).invoke(pythonInstance, "app");

                // LogReportManager.log("info", "Chaquo Python app 模块预加载成功", "Init", "chaquo");
                SpiderDebug.log("[Init] Chaquo Python app 模块预加载成功");
            } catch (Exception e) {
                // app 模块可能不存在，这是正常的
                LogReportManager.log("info", "[Init] Chaquo Python app 模块预加载失败（可能尚未部署）", "Init", "chaquo");
                SpiderDebug.log("[Init] Chaquo Python app 模块预加载失败（可能尚未部署）: " + e.getMessage());
            }

        } catch (ClassNotFoundException e) {
            // Chaquo Python 类不存在，可能主 app 没有集成
            SpiderDebug.log("[Init] Chaquo Python 类不存在，跳过初始化（主app可能未集成Chaquo）");
            LogReportManager.log("info", "[Init] Chaquo Python 类不存在，跳过初始化（主app可能未集成Chaquo）", "Init", "chaquo");
            InitStatusTracker.markSkipped(InitStatusTracker.STEP_CHAQUO, "Chaquo Python 组件不存在");
        } catch (Exception e) {
            // 其他异常记录但不影响应用运行
            InitStatusTracker.markError(InitStatusTracker.STEP_CHAQUO, "Chaquo Python 启动失败: " + e.getMessage());
            LogReportManager.logError("Chaquo Python 启动失败: " + e.getMessage(), "Init", e);
            SpiderDebug.log("[Init] Chaquo Python 启动异常: " + e.getMessage());
        }
    }

    /**
     * 检查存储权限
     */
    public static void checkPermission() {
//        try {
//            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
//            if (context().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return;
//            Notify.show("請允許儲存權限");
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
    }

    /**
     * 检查网络权限
     */
    public static void checkNetworkPermission() {
        try {
            if (context().checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
                Notify.show("請允許網路權限");
                SpiderDebug.log("[Init] 缺少网络权限");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 打印App关键信息
     * 用于后续根据不同App做差异化处理
     */
    private static void printAppInfo() {
        try {
            SpiderDebug.log("[Init] ========== App关键信息 ==========");
            LogReportManager.log("info", "========== App关键信息 ==========", "Init", "printAppInfo");

            Context ctx = context();
            if (ctx == null) {
                SpiderDebug.log("[Init] ⚠ Context为null，无法获取App信息");
                return;
            }

            // 1. App名称
            String appName = "未知";
            try {
                PackageManager pm = ctx.getPackageManager();
                appName = pm.getApplicationLabel(ctx.getApplicationInfo()).toString();
            } catch (Exception e) {
                SpiderDebug.log("[Init] 获取App名称失败: " + e.getMessage());
            }
            SpiderDebug.log("[Init] 📛 App名称: " + appName);
            LogReportManager.log("info", "App名称: " + appName, "Init", "printAppInfo");

            // 2. 包名
            String packageName = ctx.getPackageName();
            SpiderDebug.log("[Init] 📦 包名: " + packageName);
            LogReportManager.log("info", "包名: " + packageName, "Init", "printAppInfo");

            // 3. 版本信息
            String appVersion = DeviceInfoHelper.getAppVersion();
            String versionCode = "未知";
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    versionCode = String.valueOf(ctx.getPackageManager().getPackageInfo(packageName, 0).getLongVersionCode());
                } else {
                    versionCode = String.valueOf(ctx.getPackageManager().getPackageInfo(packageName, 0).versionCode);
                }
            } catch (Exception e) {
                SpiderDebug.log("[Init] 获取versionCode失败: " + e.getMessage());
            }
            SpiderDebug.log("[Init] 📱 版本: " + appVersion + " (versionCode: " + versionCode + ")");
            LogReportManager.log("info", "版本: " + appVersion + " (versionCode: " + versionCode + ")", "Init", "printAppInfo");

            // 4. 构建类型（mobile/leanback）
            String buildType = getBuildConfigType();
            SpiderDebug.log("[Init] 🏗 构建类型: " + (buildType != null ? buildType : "未知"));
            LogReportManager.log("info", "构建类型: " + (buildType != null ? buildType : "未知"), "Init", "printAppInfo");

            // 5. Android版本
            SpiderDebug.log("[Init] 🤖 Android版本: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            LogReportManager.log("info", "Android版本: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", "Init", "printAppInfo");

            // 6. 存储权限状态
            boolean hasStoragePermission = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                hasStoragePermission = (ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
            } else {
                hasStoragePermission = true; // Android 6.0以下默认有权限
            }
            SpiderDebug.log("[Init] 🔐 存储权限: " + (hasStoragePermission ? "已授予" : "未授予"));
            LogReportManager.log("info", "存储权限: " + (hasStoragePermission ? "已授予" : "未授予"), "Init", "printAppInfo");

            // 7. 内部存储路径
            File internalFilesDir = ctx.getFilesDir();
            String internalPath = internalFilesDir != null ? internalFilesDir.getAbsolutePath() : "null";
            SpiderDebug.log("[Init] 📁 内部存储: " + internalPath);
            LogReportManager.log("info", "内部存储: " + internalPath, "Init", "printAppInfo");

            // 8. 外部存储路径（如果可用）
            File externalFilesDir = ctx.getExternalFilesDir(null);
            String externalPath = externalFilesDir != null ? externalFilesDir.getAbsolutePath() : "不可用";
            boolean externalAvailable = externalFilesDir != null && externalFilesDir.exists();
            SpiderDebug.log("[Init] 💾 外部存储: " + externalPath + (externalAvailable ? " (可用)" : " (不可用)"));
            LogReportManager.log("info", "外部存储: " + externalPath + (externalAvailable ? " (可用)" : " (不可用)"), "Init", "printAppInfo");

            // 9. 当前使用的存储路径（来自Path.root()）
            File rootFile = Path.root();
            String rootPath = rootFile != null ? rootFile.getAbsolutePath() : "null";
            SpiderDebug.log("[Init] 🎯 当前存储路径: " + rootPath);
            LogReportManager.log("info", "当前存储路径: " + rootPath, "Init", "printAppInfo");

            // 10. 判断是否使用内部存储
            boolean usingInternal = rootPath != null && (rootPath.contains("/data/user/") ||
                                   (internalPath != null && rootPath.startsWith(internalPath)));
            SpiderDebug.log("[Init] 🏠 使用内部存储: " + (usingInternal ? "是" : "否"));
            LogReportManager.log("info", "使用内部存储: " + (usingInternal ? "是" : "否"), "Init", "printAppInfo");

            // 11. 设备信息
            String deviceInfo = Build.BRAND + " " + Build.MODEL;
            SpiderDebug.log("[Init] 📱 设备: " + deviceInfo);
            LogReportManager.log("info", "设备: " + deviceInfo, "Init", "printAppInfo");

            SpiderDebug.log("[Init] ========================================");
            LogReportManager.log("info", "========================================", "Init", "printAppInfo");

        } catch (Exception e) {
            SpiderDebug.log("[Init] 打印App信息异常: " + e.getMessage());
            LogReportManager.logError("打印App信息异常: " + e.getMessage(), "Init", e);
            e.printStackTrace();
        }
    }

    /**
     * 获取当前Activity
     * @return 当前的Activity实例
     * @throws Exception 如果无法获取Activity
     */
    public static Activity getActivity() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
        Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
        activitiesField.setAccessible(true);
        Map<?, ?> activities = (Map<?, ?>) activitiesField.get(activityThread);
        for (Object o : activities.values()) {
            Class<?> clz = o.getClass();
            Field pausedField = clz.getDeclaredField("paused");
            pausedField.setAccessible(true);
            if (!pausedField.getBoolean(o)) {
                Field activityField = clz.getDeclaredField("activity");
                activityField.setAccessible(true);
                return (Activity) activityField.get(o);
            }
        }
        return null;
    }

    /**
     * 检查App使用限制
     * 当App不是"让我看看"且设备不是TV时，显示禁用提示并退出
     */
    private static void checkAppRestriction() {
        try {
            String appName = getAppName();
            String deviceType = DeviceInfoHelper.getDeviceType();

            SpiderDebug.log("[Init] 检查App限制 - App名称: " + appName + ", 设备类型: " + deviceType);

            // 如果是"让我看看"或者是TV设备，则允许使用
            if (Objects.equals(appName, "让我看看") || !"tv".equals(deviceType)) {
                SpiderDebug.log("[Init] App检查通过，允许使用");
                return;
            }

            // 非"让我看看"且非TV设备，显示禁用提示
            SpiderDebug.log("[Init] App被限制使用，准备显示提示对话框");

            run(() -> {
                try {
                    Activity activity = getActivity();
                    if (activity == null) {
                        SpiderDebug.log("[Init] 无法获取Activity，直接退出");
                        exitApp();
                        return;
                    }

                    // 创建AlertDialog
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity);
                    builder.setTitle("应用限制");
                    builder.setMessage("当前App被禁用，需要继续使用请下载《让我看看》");
                    builder.setCancelable(false);
                    builder.setPositiveButton("确认", (dialog, which) -> {
                        dialog.dismiss();
                        exitApp();
                    });

                    android.app.AlertDialog dialog = builder.create();
                    dialog.show();

                    // 5秒后自动关闭对话框并退出
                    run(() -> {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        exitApp();
                    }, 5000);

                } catch (Exception e) {
                    SpiderDebug.log("[Init] 显示限制对话框失败: " + e.getMessage());
                    exitApp();
                }
            });

        } catch (Exception e) {
            SpiderDebug.log("[Init] 检查App限制异常: " + e.getMessage());
        }
    }

    /**
     * 退出应用
     */
    private static void exitApp() {
        try {
            SpiderDebug.log("[Init] 正在退出应用...");
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } catch (Exception e) {
            SpiderDebug.log("[Init] 退出应用失败: " + e.getMessage());
        }
    }

    /**
     * 关闭所有后台任务和资源
     * 应在应用退出或重启前调用
     */
    public static void shutdown() {
        try {
            SpiderDebug.log("[Init] 开始清理Init资源");

            // 1. 关闭心跳任务
            try {
                get().handler.removeCallbacksAndMessages(null);
                SpiderDebug.log("[Init] 已清理Handler所有任务");
            } catch (Exception e) {
                SpiderDebug.log("[Init] 清理Handler任务失败: " + e.getMessage());
            }

            // 2. 关闭线程池
            try {
                get().executor.shutdownNow();
                SpiderDebug.log("[Init] 已关闭线程池");
            } catch (Exception e) {
                SpiderDebug.log("[Init] 关闭线程池失败: " + e.getMessage());
            }

            // 3. 短暂等待，确保所有清理操作完成
            Thread.sleep(200);

            // 4. 最后关闭日志上报器（在所有其他操作之后）
            try {
                LogReportManager.shutdown();
                // 关闭后不再使用LogReportManager，改用SpiderDebug
                SpiderDebug.log("[Init] 已关闭日志上报器");
            } catch (Exception e) {
                SpiderDebug.log("[Init] 关闭日志上报器失败: " + e.getMessage());
            }

        } catch (Exception e) {
            // 使用原生Log，避免使用任何可能已关闭的服务
            android.util.Log.e("Init", "shutdown失败: " + e.getMessage());
        }
    }

    /**
     * HTTP服务器安全控制
     * 通过反射找到并修改主App的Nano服务器，使其仅监听127.0.0.1
     * 支持多版本自动适配
     *
     * @param app 应用上下文
     */
    private static void secureHttpServer(Application app) {
        ServerVersionConfig config = null;
        try {
            InitStatusTracker.markPending(InitStatusTracker.STEP_SECURE_HTTP, "执行HTTP服务器安全控制");
            SpiderDebug.log("[Init-Security] ========== HTTP服务器安全控制 ==========");
            // LogReportManager.log("info", "开始执行HTTP服务器安全控制", "Init", "secureHttpServer");

            // 0. 获取App版本和构建类型（mobile/leanback）
            String appVersion = DeviceInfoHelper.getAppVersion();
            String buildType = getBuildConfigType();
            SpiderDebug.log("[Init-Security] 当前App版本: " + appVersion + ", 构建类型: " + buildType);
            // LogReportManager.log("info", "当前App版本: " + appVersion + ", 构建类型: " + buildType, "Init", "secureHttpServer");

            // 组合版本号和构建类型（例如：2.8.4|leanback）
            String fullVersion = buildType != null ? appVersion + "|" + buildType : appVersion;

            // 先尝试使用完整版本号（包含构建类型），如果没有则回退到普通版本号
            config = ServerVersionConfig.getConfig(fullVersion);
            if (config == null || !config.isValid()) {
                SpiderDebug.log("[Init-Security] 未找到完整版本 " + fullVersion + " 的配置，尝试使用基础版本");
                config = ServerVersionConfig.getConfig(appVersion);
            }

            if (config == null || !config.isValid()) {
                SpiderDebug.log("[Init-Security] 未找到版本 " + appVersion + " 的配置或配置不完整，放弃安全控制");
                LogReportManager.log("warning", "未找到版本配置，放弃HTTP安全控制", "Init", "secureHttpServer");
                InitStatusTracker.markSkipped(InitStatusTracker.STEP_SECURE_HTTP, "缺少版本配置");
                return;
            }

            // 打印配置信息（便于调试）
            // config.printConfig();
            // LogReportManager.log("info", "使用版本 " + config.version + " 的配置进行安全控制", "Init", "secureHttpServer");

            ClassLoader classLoader = app.getClassLoader();

            // 1. 加载Server类、Nano类和ServerHolder类（使用版本配置）
            SpiderDebug.log("[Init-Security] 开始加载Server类: " + config.serverClassName);
            Class<?> serverClass = classLoader.loadClass(config.serverClassName);
            SpiderDebug.log("[Init-Security] ✓ Server类加载成功");

            SpiderDebug.log("[Init-Security] 开始加载Nano类: " + config.nanoClassName);
            Class<?> nanoClass = classLoader.loadClass(config.nanoClassName);
            SpiderDebug.log("[Init-Security] ✓ Nano类加载成功");

            SpiderDebug.log("[Init-Security] 开始加载ServerHolder类: " + config.serverHolderClassName);
            Class<?> serverHolderClass = classLoader.loadClass(config.serverHolderClassName);
            SpiderDebug.log("[Init-Security] ✓ ServerHolder类加载成功");

            SpiderDebug.log("[Init-Security] 成功加载所有类");

            // 2. 获取Server单例和Nano字段（使用版本配置）
            Field serverInstanceField = serverHolderClass.getDeclaredField(config.serverHolderFieldName);
            serverInstanceField.setAccessible(true);
            Object serverInstance = serverInstanceField.get(null);

            Field nanoField;
            try {
                nanoField = serverClass.getDeclaredField(config.serverNanoFieldName);
            } catch (NoSuchFieldException nsfe) {
                SpiderDebug.log("[Init-Security] 未找到字段 " + config.serverNanoFieldName + "，Server类字段列表如下：");
                for (Field field : serverClass.getDeclaredFields()) {
                    SpiderDebug.log("[Init-Security]   字段: " + field.getName() + "，类型: " + field.getType().getName());
                }
                throw nsfe;
            }
            nanoField.setAccessible(true);
            Object oldNano = nanoField.get(serverInstance);

            // 3. 停止旧Nano
            if (oldNano != null) {
                SpiderDebug.log("[Init-Security] 停止旧Nano服务器");
                try {
                    oldNano.getClass().getMethod("stop").invoke(oldNano);
                    SpiderDebug.log("[Init-Security] 旧Nano已停止");
                } catch (Exception e) {
                    SpiderDebug.log("[Init-Security] 停止旧Nano失败: " + e.getMessage());
                }
            }

            // 4. 获取端口号
            int port = 9978;
            if (oldNano != null) {
                try {
                    port = (int) oldNano.getClass().getMethod("getListeningPort").invoke(oldNano);
                    SpiderDebug.log("[Init-Security] 获取端口: " + port);
                } catch (Exception e) {
                    SpiderDebug.log("[Init-Security] 使用默认端口: " + port);
                }
            }

            // 5. 创建新的安全Nano实例
            Object newNano = nanoClass.getDeclaredConstructor(int.class).newInstance(port);
            SpiderDebug.log("[Init-Security] 创建新Nano实例");

            // 6. 修改hostname为127.0.0.1
            boolean hostnameSet = false;
            Class<?> currentClass = nanoClass;
            while (currentClass != null && !hostnameSet) {
                try {
                    Field hostnameField = currentClass.getDeclaredField("hostname");
                    hostnameField.setAccessible(true);
                    hostnameField.set(newNano, "127.0.0.1");
                    hostnameSet = true;
                    SpiderDebug.log("[Init-Security] 成功设置hostname为127.0.0.1");
                    LogReportManager.log("info", "成功设置Nano hostname为127.0.0.1", "Init", "createSecureNano");
                } catch (NoSuchFieldException e) {
                    currentClass = currentClass.getSuperclass();
                }
            }

            if (!hostnameSet) {
                SpiderDebug.log("[Init-Security] 警告：未找到hostname字段");
                LogReportManager.log("warning", "未找到hostname字段", "Init", "createSecureNano");
            }

            // 7. 替换并启动新Nano
            nanoField.set(serverInstance, newNano);
            newNano.getClass().getMethod("start").invoke(newNano);

            SpiderDebug.log("[Init-Security] ✓ 安全Nano已启动（仅监听127.0.0.1:" + port + "）");
            LogReportManager.log("info", "安全Nano已启动，端口: " + port, "Init", "secureHttpServer");
            InitStatusTracker.markSuccess(InitStatusTracker.STEP_SECURE_HTTP, "HTTP服务器安全控制完成");

        } catch (ClassNotFoundException e) {
            SpiderDebug.log("[Init-Security] 未找到类：" + e.getMessage());
            if (config != null) {
                SpiderDebug.log("[Init-Security] 配置信息：");
                SpiderDebug.log("[Init-Security]   Server类: " + config.serverClassName);
                SpiderDebug.log("[Init-Security]   Nano类: " + config.nanoClassName);
                SpiderDebug.log("[Init-Security]   ServerHolder类: " + config.serverHolderClassName);
            } else {
                SpiderDebug.log("[Init-Security] 配置为空，无法显示详细信息");
            }

            LogReportManager.log("warning", "未找到类: " + e.getMessage(), "Init", "secureHttpServer");
            InitStatusTracker.markError(InitStatusTracker.STEP_SECURE_HTTP, "安全控制失败，缺少类: " + e.getMessage());
        } catch (Exception e) {
            InitStatusTracker.markError(InitStatusTracker.STEP_SECURE_HTTP, "HTTP服务器安全控制失败: " + e.getMessage());
            LogReportManager.logError("HTTP服务器安全控制失败: " + e.getMessage(), "Init", e);
            SpiderDebug.log("[Init-Security] 安全控制异常: " + e.getMessage());
            SpiderDebug.log("[Init-Security] 异常类型: " + e.getClass().getName());
            e.printStackTrace();
        }
    }

    /**
     * 获取主App的BuildConfig类型（mobile或leanback）
     * 宿主App的ABI架构（根据构建架构推断）
     *
     * @return 构建类型，如果无法确定则返回null
     */
    private static String getBuildConfigType() {
        // 方法：通过宿主App的ABI架构判断（新增，更可靠）
        try {
            String buildType = DeviceInfoHelper.getBuildTypeFromAbi();
            if (buildType != null) {
                SpiderDebug.log("[Init-Security] 成功：根据ABI判断为 " + buildType);
                return buildType;
            }
        } catch (Exception e) {
            SpiderDebug.log("[Init-Security] 失败：ABI判断异常: " + e.getMessage());
        }
        SpiderDebug.log("[Init-Security] 所有判断方法均失败，无法确定构建类型");
        return null;
    }
}
