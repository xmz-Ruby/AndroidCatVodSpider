package com.github.catvod.spider;

/**
 * 调试配置，通过常量开关控制调试行为
 */
public final class DebugConfig {

    /**
     * 设置为 true 时允许通过 LogReportManager 上报全部日志。
     * 正式环境请保持为 false，仅上报关键日志。
     */
    public static final boolean ENABLE_VERBOSE_LOG_REPORT = true;

    private DebugConfig() {
    }
}
