package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import org.json.JSONObject;

import android.os.Build;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 日志上报管理器
 * 负责将日志异步发送到远程服务器，同时支持本地日志输出
 *
 * 使用方法：
 * - LogReportManager.log("消息内容");  // 简单日志，默认 info 级别
 * - LogReportManager.log("info", "消息内容", "ClassName", "category");  // 完整参数
 * - LogReportManager.logError("错误消息", "ClassName", exception);  // 错误日志
 */
public class LogReportManager {

    private static final String LOG_REPORT_URL = "https://itv.mangzhexuexi.com/api/logs";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static volatile boolean isShutdown = false;

    /**
     * 简单日志方法（兼容 SpiderDebug.log）
     * @param message 日志消息
     */
    public static void log(String message) {
        // 本地日志输出
        SpiderDebug.log(message);

        // 远程上报（默认 info 级别）
        reportLog("info", message, "App", null, null, null);
    }

    /**
     * 带级别的日志方法
     * @param level 日志级别 (info, warning, error, debug)
     * @param message 日志消息
     * @param source 来源类名
     * @param category 日志分类
     */
    public static void log(String level, String message, String source, String category) {
        // 本地日志输出
        SpiderDebug.log("[" + level.toUpperCase() + "][" + source + "] " + message);

        // 远程上报
        reportLog(level, message, source, category, null, null);
    }

    /**
     * 错误日志方法
     * @param message 错误消息
     * @param source 来源类名
     * @param e 异常对象
     */
    public static void logError(String message, String source, Exception e) {
        // 本地日志输出
        SpiderDebug.log("[ERROR][" + source + "] " + message);
        if (e != null) {
            SpiderDebug.log("  Exception: " + e.getMessage());
        }

        // 远程上报
        reportLog("error", message, source, "error",
                  e != null ? e.getClass().getSimpleName() : null,
                  getStackTrace(e));
    }

    /**
     * 警告日志方法
     * @param message 警告消息
     * @param source 来源类名
     */
    public static void logWarning(String message, String source) {
        // 本地日志输出
        SpiderDebug.log("[WARNING][" + source + "] " + message);

        // 远程上报
        reportLog("warning", message, source, "warning", null, null);
    }

    /**
     * 调试日志方法
     * @param message 调试消息
     * @param source 来源类名
     */
    public static void logDebug(String message, String source) {
        // 本地日志输出
        SpiderDebug.log("[DEBUG][" + source + "] " + message);

        // 远程上报
        reportLog("debug", message, source, "debug", null, null);
    }

    /**
     * 上报日志到服务器（内部方法，不建议直接调用）
     * @param level 日志级别 (info, warning, error, debug)
     * @param message 日志消息
     * @param source 来源类名
     * @param category 日志分类 (可选)
     */
    private static void reportLog(String level, String message, String source, String category) {
        reportLog(level, message, source, category, null, null);
    }

    /**
     * 上报日志到服务器（完整参数，内部方法）
     * @param level 日志级别 (info, warning, error, debug)
     * @param message 日志消息
     * @param source 来源类名
     * @param category 日志分类
     * @param errorType 错误类型（可选）
     * @param stackTrace 堆栈跟踪（可选）
     */
    private static void reportLog(String level, String message, String source, String category, String errorType, String stackTrace) {
        // 如果已经关闭，不再提交新任务
        if (isShutdown) {
            return;
        }

        // 异步执行，不阻塞主流程
        if (!shouldReport(level)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    // 构建日志数据
                    JSONObject logData = new JSONObject();
                    logData.put("level", level != null ? level : "info");
                    logData.put("message", message != null ? message : "");
                    logData.put("source", source != null ? source : "Unknown");
                    logData.put("device_id", DeviceInfoHelper.getOrCreateDeviceId());
                    logData.put("device_name", Build.DEVICE);
                    logData.put("app_version", DeviceInfoHelper.getAppVersion());
                    logData.put("platform", DeviceInfoHelper.getDeviceType());

                    if (category != null && !category.isEmpty()) {
                        logData.put("category", category);
                    }

                    if (errorType != null && !errorType.isEmpty()) {
                        logData.put("error_type", errorType);
                    }

                    if (stackTrace != null && !stackTrace.isEmpty()) {
                        logData.put("stack_trace", stackTrace);
                    }

                    // 发送请求
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json");
                    headers.put("User-Agent", "TVBox-Spider/1.0");

                    OkHttp.post(LOG_REPORT_URL, logData.toString(), headers);

                    // 注意：这里不记录上报成功的日志，避免循环调用

                } catch (Exception e) {
                    // 静默失败，不影响主流程
                    // 不使用SpiderDebug.log避免循环调用
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 线程池已关闭，静默忽略
        } catch (Exception e) {
            // 其他异常也静默忽略
        }
    }

    private static boolean shouldReport(String level) {
        if (DebugConfig.ENABLE_VERBOSE_LOG_REPORT) {
            return true;
        }
        if (level == null) {
            return false;
        }
        String normalized = level.toLowerCase();
        return "error".equals(normalized) || "warning".equals(normalized);
    }

    /**
     * 从异常对象提取堆栈跟踪信息
     * @param e 异常对象
     * @return 堆栈跟踪字符串
     */
    public static String getStackTrace(Exception e) {
        if (e == null) return null;

        try {
            StringBuilder sb = new StringBuilder();
            sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");

            StackTraceElement[] elements = e.getStackTrace();
            int maxLines = Math.min(5, elements.length); // 只取前5行堆栈
            for (int i = 0; i < maxLines; i++) {
                sb.append("  at ").append(elements[i].toString()).append("\n");
            }

            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 关闭日志上报器（应用退出时调用）
     */
    public static void shutdown() {
        try {
            isShutdown = true;
            executor.shutdown();
        } catch (Exception e) {
            // 忽略
        }
    }
}
