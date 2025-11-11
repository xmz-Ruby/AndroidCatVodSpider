package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;

/**
 * HTTP服务器版本配置类
 * 根据不同的主App版本，使用不同的混淆类名和字段名
 *
 * 说明：
 * - 主App使用ProGuard混淆，每个版本的类名和字段名可能不同
 * - 通过反编译APK获取实际的类名和字段名
 * - 参考文档：SECURITY_ENHANCEMENT.md
 */
public class ServerVersionConfig {

    // 版本配置信息
    public final String version;
    public final String serverClassName;
    public final String nanoClassName;
    public final String serverHolderClassName;
    public final String serverHolderFieldName;
    public final String serverNanoFieldName;

    private ServerVersionConfig(String version, String serverClassName, String nanoClassName,
                                String serverHolderClassName, String serverHolderFieldName,
                                String serverNanoFieldName) {
        this.version = version;
        this.serverClassName = serverClassName;
        this.nanoClassName = nanoClassName;
        this.serverHolderClassName = serverHolderClassName;
        this.serverHolderFieldName = serverHolderFieldName;
        this.serverNanoFieldName = serverNanoFieldName;
    }

    /**
     * 根据App版本获取对应的配置
     * @param appVersion App版本号（如 "3.1.5", "2.8.4"）
     * @return 版本配置，如果没有匹配的配置则返回null
     */
    public static ServerVersionConfig getConfig(String appVersion) {
        if (appVersion == null || appVersion.isEmpty()) {
            SpiderDebug.log("[ServerVersionConfig] App版本为空，无法获取配置");
            return null;
        }

        SpiderDebug.log("[ServerVersionConfig] 尝试获取版本 " + appVersion + " 的配置");

        // 版本号可能包含额外信息，只取主版本号（如 "3.1.5-beta" -> "3.1.5"）
        String mainVersion = appVersion.split("-")[0].trim();

        // 根据主版本号返回对应的配置
        switch (mainVersion) {
            case "3.1.5|mobile":
                return getConfigFor_3_1_5();

            case "3.0.0|mobile":
                return getConfigFor_3_0_0();

            case "3.0.0|leanback":
                return getConfigFor_3_0_0_leanback();

            case "2.8.4|mobile":
                return getConfigFor_2_8_4();

            case "2.8.4|leanback":
                return getConfigFor_2_8_4_leanback();

            case "2.5.8|mobile":
                return getConfigFor_2_5_8();

            // 可以继续添加其他版本...
            // case "4.0.0":
            //     return getConfigFor_4_0_0();

            default:
                SpiderDebug.log("[ServerVersionConfig] 未找到版本 " + mainVersion + " 的配置，跳过");
                // 跳过
                return null;
        }
    }

    /**
     * 3.1.5 版本配置
     *
     * APK信息：
     * - 版本号：3.1.5
     * - 反编译路径：C:\Users\xmz\yorkspace\ss\sources
     *
     * 类信息：
     * - Nano 类：OoO0oO0o0o0O0oO0.oOo0oOo0Oo0oO0Oo
     *   - 父类：NanoWSD
     *   - 构造函数：public oOo0oOo0Oo0oO0Oo(int i)
     *
     * - Server 类：oOo0oO0o0o0OoO0o.oOoOoOo0O0O0oO0o
     *   - Nano 字段名：OoOo0oO0o0o0oOo0
     *   - 启动方法：OoOo0o0oOo0O0O0o()
     *
     * - ServerHolder 类：OoO0oO0o0o0O0oO0.oOoOoOo0oOo0o0oO
     *   - 静态字段（反编译显示）：f2278oOoOoOoOoOoOoO0o
     *   - 静态字段（运行时实际）：oOoOoOoOoOoOoO0o ⚠️
     */
    private static ServerVersionConfig getConfigFor_3_1_5() {
        return new ServerVersionConfig(
            "3.1.5",
            "oOo0oO0o0o0OoO0o.oOoOoOo0O0O0oO0o",      // Server类名
            "OoO0oO0o0o0O0oO0.oOo0oOo0Oo0oO0Oo",      // Nano类名
            "OoO0oO0o0o0O0oO0.oOoOoOo0oOo0o0oO",      // ServerHolder类名
            "oOoOoOoOoOoOoO0o",                        // ServerHolder字段名（运行时实际名）
            "OoOo0oO0o0o0oOo0"                         // Server中Nano字段名
        );
    }
    /**
     * 3.0.0 版本配置
     *
     * APK信息：
     * - 版本号：3.0.0
     * - 反编译路径：C:\Users\xmz\yorkspace\300\sources
     *
     * 类信息：
     * - Nano 类：OoOoO0oO0o0oO0O0.oOoOoOoOoOoOoO0o
     *   - 反编译显示类名：C1572oOoOoOoOoOoOoO0o（文件：OoOoO0oO0o0oO0O0/C1572oOoOoOoOoOoOoO0o.java）
     *   - 父类：NanoWSD
     *   - 构造函数：public oOoOoOoOoOoOoO0o(int i)
     *
     * - Server 类：OoOo0oO0Oo0oO0oO.oOoOoOo0O0O0oO0o
     *   - Nano 字段（反编译显示）：f5677oOo0oO0o0O0O0Oo0（第98行）
     *   - Nano 字段（运行时实际）：f5677oOo0oO0o0O0O0Oo0（无 renamed from 注释，使用反编译显示名）
     *   - 启动方法：oOoO0Oo0oO0o0O0O()（第459-472行，端口范围 9978-9999）
     *
     * - ServerHolder 类：OoOoO0oO0o0oO0O0.oOo0oOo0Oo0oO0Oo
     *   - 反编译显示类名：AbstractC1570oOo0oOo0Oo0oO0Oo（文件：OoOoO0oO0o0oO0O0/AbstractC1570oOo0oOo0Oo0oO0Oo.java）
     *   - 静态字段（反编译显示）：f7682oOoOoOoOoOoOoO0o（第10行）
     *   - 静态字段（运行时实际）：oOoOoOoOoOoOoO0o（从第9行注释 renamed from 提取）
     */
    private static ServerVersionConfig getConfigFor_3_0_0() {
        return new ServerVersionConfig(
            "3.0.0",
            "OoOo0oO0Oo0oO0oO.oOoOoOo0O0O0oO0o",      // Server类名（运行时实际名）
            "OoOoO0oO0o0oO0O0.oOoOoOoOoOoOoO0o",     // Nano类名（运行时实际名）
            "OoOoO0oO0o0oO0O0.oOo0oOo0Oo0oO0Oo",     // ServerHolder类名（运行时实际名，从注释提取）
            "oOoOoOoOoOoOoO0o",                        // ServerHolder字段名（运行时实际名）
            "oOo0oO0o0O0O0Oo0"                    // Server中Nano字段名（运行时实际名）
        );
    }

    /**
     * 3.0.0-leanback 版本配置
     *
     * APK信息：
     * - 版本号：3.0.0
     * - 构建类型：leanback（电视版）
     * - 反编译路径：C:\Users\xmz\yorkspace\300tv\sources
     *
     * ⚠️ 包名大小写问题：
     * - Windows 文件系统路径：与 mobile 版本不同！
     * - Java 包声明（实际）：必须查看 package 声明
     * - 反射时必须使用 Java 包声明的名称！
     *
     * 类信息：
     * - Nano 类：oOoO0O0oOo0Oo0O0.oOoOoOoOoOoOoO0o
     *   - 反编译显示类名：C1841oOoOoOoOoOoOoO0o（文件：oOoO0O0oOo0Oo0O0/C1841oOoOoOoOoOoOoO0o.java）
     *   - 父类：NanoWSD
     *   - 构造函数：public C1841oOoOoOoOoOoOoO0o(int i)
     *   - 运行时实际名：oOoOoOoOoOoOoO0o ⚠️
     *   - 包声明：package oOoO0O0oOo0Oo0O0; ← 小写 o 开头（第4个字符大写O）
     *   - 注释位置：第28行 renamed from: oOoO0O0oOo0Oo0O0.oOoOoOoOoOoOoO0o
     *
     * - Server 类：OoOoO0o0o0O0O0Oo.OoOo0oO0o0o0oOo0
     *   - 反编译显示类名：OoOo0oO0o0o0oOo0（文件：OoOoO0o0o0O0O0Oo/OoOo0oO0o0o0oOo0.java）
     *   - Nano 字段（反编译显示）：f7136oOo0oO0o0O0O0Oo0
     *   - Nano 字段（运行时实际）：oOo0oO0o0O0O0Oo0 ⚠️
     *   - 启动方法：oOoOoO0Oo0oOo0oO()（第694-709行）
     *   - 包声明：package OoOoO0o0o0O0O0Oo; ← 大写 O 开头
     *   - Proxy.set 位置：第703行
     *
     * - ServerHolder 类：oOoO0O0oOo0Oo0O0.oOo0oOo0Oo0oO0Oo
     *   - 反编译显示类名：AbstractC1839oOo0oOo0Oo0oO0Oo（文件：oOoO0O0oOo0Oo0O0/AbstractC1839oOo0oOo0Oo0oO0Oo.java）
     *   - 静态字段（反编译显示）：f12712oOoOoOoOoOoOoO0o
     *   - 静态字段（运行时实际）：oOoOoOoOoOoOoO0o ⚠️
     *   - 包声明：package oOoO0O0oOo0Oo0O0; ← 小写 o 开头（第4个字符大写O）
     *   - 注释位置：第5行 renamed from: oOoO0O0oOo0Oo0O0.oOo0oOo0Oo0oO0Oo
     *              第9行 renamed from: oOoOoOoOoOoOoO0o
     *
     * ⚠️ 与 mobile 版本的差异：
     * - Nano 类的包名不同（oOoO0O0oOo0Oo0O0 vs OoOoO0oO0o0oO0O0）
     * - Server 类的包名不同（OoOoO0o0o0O0O0Oo vs OoOo0oO0Oo0oO0oO）
     * - ServerHolder 类的包名不同（oOoO0O0oOo0Oo0O0 vs OoOoO0oO0o0oO0O0）
     * - Nano 和 ServerHolder 在同一个包中（leanback）
     * - Server 和 ServerHolder 的字段名相同！
     */
    private static ServerVersionConfig getConfigFor_3_0_0_leanback() {
        return new ServerVersionConfig(
            "3.0.0-leanback",
            "OoOoO0o0o0O0O0Oo.OoOo0oO0o0o0oOo0",      // Server类名（运行时实际名）
            "oOoO0O0oOo0Oo0O0.oOoOoOoOoOoOoO0o",     // Nano类名（运行时实际名）
            "oOoO0O0oOo0Oo0O0.oOo0oOo0Oo0oO0Oo",     // ServerHolder类名（运行时实际名）
            "oOoOoOoOoOoOoO0o",                        // ServerHolder字段名（运行时实际名）
            "oOo0oO0o0O0O0Oo0"                         // Server中Nano字段名（运行时实际名）
        );
    }

    /**
     * 2.8.4 版本配置
     *
     * APK信息：
     * - 版本号：2.8.4
     * - 反编译路径：C:\Users\xmz\yorkspace\284\sources
     *
     * ⚠️ 包名大小写问题：
     * - Windows 文件系统路径：OoO0oO0OoOoO0oOo（大写 O 开头）
     * - Java 包声明（实际）：oOo0oO0OoOoO0oOo（小写 o 开头）
     * - 反射时必须使用 Java 包声明的小写 o！
     *
     * 类信息：
     * - Nano 类：oOo0oO0OoOoO0oOo.oOoOoOoOoOoOoO0o
     *   - 反编译显示类名：C1652oOoOoOoOoOoOoO0o（文件：oOo0oO0OoOoO0oOo/C1652oOoOoOoOoOoOoO0o.java）
     *   - 父类：NanoWSD
     *   - 构造函数：public oOoOoOoOoOoOoO0o(int i)
     *   - 包声明：package oOo0oO0OoOoO0oOo; ← 小写 o 开头
     *
     * - Server 类：OoO0oOoO0o0O0O0O.OoOo0OoO0OoO0oO0
     *   - Nano 字段（反编译显示）：f2892oOo0oO0o0O0O0Oo0
     *   - Nano 字段（运行时实际）：oOo0oO0o0O0O0Oo0 ⚠️
     *   - 端口字段（反编译显示）：f2891OoOoO0O0o0oOoO0O
     *   - 端口字段（运行时实际）：OoOoO0O0o0oOoO0O ⚠️
     *   - 启动方法：OoOo0Oo0oO0O0oOo()
     *   - 文件路径：OoO0oOoO0o0O0O0O/OoOo0OoO0OoO0oO0.java
     *   - 包声明：package OoO0oOoO0o0O0O0O; ← 大写 O 开头
     *
     * - ServerHolder 类：oOo0oO0OoOoO0oOo.oOo0oOo0Oo0oO0Oo
     *   - 反编译显示类名：AbstractC1650oOo0oOo0Oo0oO0Oo（文件：oOo0oO0OoOoO0oOo/AbstractC1650oOo0oOo0Oo0oO0Oo.java）
     *   - 静态字段（反编译显示）：f11467oOoOoOoOoOoOoO0o
     *   - 静态字段（运行时实际）：oOoOoOoOoOoOoO0o ⚠️
     *   - 文件路径：oOo0oO0OoOoO0oOo/AbstractC1650oOo0oOo0Oo0oO0Oo.java（反编译工具为避免大小写冲突而重命名文件）
     *   - 包声明：package oOo0oO0OoOoO0oOo; ← 小写 o 开头
     */
    private static ServerVersionConfig getConfigFor_2_8_4() {
        return new ServerVersionConfig(
            "2.8.4",
            "OoO0oOoO0o0O0O0O.OoOo0OoO0OoO0oO0",      // Server类名（大写O开头）
            "oOo0oO0OoOoO0oOo.oOoOoOoOoOoOoO0o",     // Nano类名（运行时实际名）
            "oOo0oO0OoOoO0oOo.oOo0oOo0Oo0oO0Oo",     // ServerHolder类名（运行时实际名）
            "oOoOoOoOoOoOoO0o",                        // ServerHolder字段名（运行时实际名）
            "oOo0oO0o0O0O0Oo0"                         // Server中Nano字段名（运行时实际名）
        );
    }

    /**
     * 2.8.4-leanback 版本配置
     *
     * APK信息：
     * - 版本号：2.8.4
     * - 构建类型：leanback（电视版）
     * - 反编译路径：C:\Users\xmz\yorkspace\284tv\sources
     *
     * ⚠️ 包名大小写问题：
     * - Windows 文件系统路径：与 mobile 版本不同！
     * - Java 包声明（实际）：必须查看 package 声明
     * - 反射时必须使用 Java 包声明的名称！
     *
     * 类信息：
     * - Nano 类：oOo0oO0oOoOo0oOo.oOoOoOoOoOoOoO0o
     *   - 反编译显示类名：C1667oOoOoOoOoOoOoO0o（文件：oOo0oO0oOoOo0oOo/C1667oOoOoOoOoOoOoO0o.java）
     *   - 父类：NanoWSD
     *   - 构造函数：public C1667oOoOoOoOoOoOoO0o(int i)
     *   - 运行时实际名：oOoOoOoOoOoOoO0o ⚠️
     *   - 包声明：package oOo0oO0oOoOo0oOo; ← 小写 o 开头
     *   - 注释位置：第25行 renamed from: oOo0oO0oOoOo0oOo.oOoOoOoOoOoOoO0o
     *
     * - Server 类：OoO0oOo0Oo0o0Oo0.oOoOo0O0Oo0O0o0o
     *   - 反编译显示类名：C1372oOoOo0O0Oo0O0o0o（文件：OoO0oOo0Oo0o0Oo0/C1372oOoOo0O0Oo0O0o0o.java）
     *   - Nano 字段（反编译显示）：f2986oOo0oO0o0O0O0Oo0
     *   - Nano 字段（运行时实际）：oOo0oO0o0O0O0Oo0 ⚠️
     *   - 端口字段（反编译显示）：f2985OoOoO0O0o0oOoO0O
     *   - 端口字段（运行时实际）：OoOoO0O0o0oOoO0O ⚠️
     *   - 启动方法：通过 do-while 循环尝试端口（第247-256行）
     *   - 包声明：package OoO0oOo0Oo0o0Oo0; ← 大写 O 开头
     *   - 注释位置：第52行 renamed from: OoO0oOo0Oo0o0Oo0.oOoOo0O0Oo0O0o0o
     *
     * - ServerHolder 类：oOo0oO0oOoOo0oOo.oOo0oOo0Oo0oO0Oo
     *   - 反编译显示类名：AbstractC1665oOo0oOo0Oo0oO0Oo（文件：oOo0oO0oOoOo0oOo/AbstractC1665oOo0oOo0Oo0oO0Oo.java）
     *   - 静态字段（反编译显示）：f11114oOoOoOoOoOoOoO0o
     *   - 静态字段（运行时实际）：oOoOoOoOoOoOoO0o ⚠️
     *   - 包声明：package oOo0oO0oOoOo0oOo; ← 小写 o 开头
     *   - 注释位置：第5行 renamed from: oOo0oO0oOoOo0oOo.oOo0oOo0Oo0oO0Oo
     *              第9行 renamed from: oOoOoOoOoOoOoO0o
     *
     * ⚠️ 与 mobile 版本的差异：
     * - Nano 类的包名不同（oOo0oO0oOoOo0oOo vs oOo0oO0OoOoO0oOo）
     * - Server 类的包名不同（OoO0oOo0Oo0o0Oo0 vs OoO0oOoO0o0O0O0O）
     * - ServerHolder 类的包名不同（oOo0oO0oOoOo0oOo vs oOo0oO0OoOoO0oOo）
     * - Server 和 ServerHolder 的字段名相同！
     */
    private static ServerVersionConfig getConfigFor_2_8_4_leanback() {
        return new ServerVersionConfig(
            "2.8.4-leanback",
            "OoO0oOo0Oo0o0Oo0.oOoOo0O0Oo0O0o0o",      // Server类名（运行时实际名）
            "oOo0oO0oOoOo0oOo.oOoOoOoOoOoOoO0o",     // Nano类名（运行时实际名）
            "oOo0oO0oOoOo0oOo.oOo0oOo0Oo0oO0Oo",     // ServerHolder类名（运行时实际名）
            "oOoOoOoOoOoOoO0o",                        // ServerHolder字段名（运行时实际名）
            "oOo0oO0o0O0O0Oo0"                         // Server中Nano字段名（运行时实际名）
        );
    }

    /**
     * 2.5.8 版本配置
     *
     * APK信息：
     * - 版本号：2.5.8
     * - 反编译路径：C:\Users\xmz\yorkspace\258\sources
     *
     * 类信息：
     * - Nano 类：oOoO0oO0Oo0O0OoO.oOoOoOoOoOoOoO0o
     *   - 反编译显示类名：C1150oOoOoOoOoOoOoO0o（文件：oOoO0oO0Oo0O0OoO/C1150oOoOoOoOoOoOoO0o.java）
     *   - 父类：NanoHTTPD
     *   - 构造函数：public oOoOoOoOoOoOoO0o(int i)
     *
     * - Server 类：OoO0oOoO0o0O0O0O.oOoOoOoOoO0oOo0o
     *   - Nano 字段（运行时实际）：oOoOoO0oOoO0OoOo
     *   - 端口字段（运行时实际）：oOo0oO0o0O0O0Oo0
     *   - 启动方法：OoOo0O0oOo0Oo0o0()
     *
     * - ServerHolder 类：oOoO0oO0Oo0O0OoO.oOo0oOo0Oo0oO0Oo
     *   - 静态字段（运行时实际）：oOoOoOoOoOoOoO0o
     *   - 静态字段初始端口：9978
     */
    private static ServerVersionConfig getConfigFor_2_5_8() {
        return new ServerVersionConfig(
            "2.5.8",
            "OoO0oOoO0o0O0O0O.oOoOoOoOoO0oOo0o",      // Server类名（运行时实际名）
            "oOoO0oO0Oo0O0OoO.oOoOoOoOoOoOoO0o",     // Nano类名（运行时实际名）
            "oOoO0oO0Oo0O0OoO.oOo0oOo0Oo0oO0Oo",     // ServerHolder类名（运行时实际名）
            "oOoOoOoOoOoOoO0o",                        // ServerHolder字段名（运行时实际名）
            "oOoOoO0oOoO0OoOo"                         // Server中Nano字段名（运行时实际名）
        );
    }

    /**
     * 打印当前配置信息（用于调试）
     */
    public void printConfig() {
        SpiderDebug.log("[ServerVersionConfig] ========== 版本配置信息 ==========");
        SpiderDebug.log("[ServerVersionConfig] App版本: " + version);
        SpiderDebug.log("[ServerVersionConfig] Server类: " + serverClassName);
        SpiderDebug.log("[ServerVersionConfig] Nano类: " + nanoClassName);
        SpiderDebug.log("[ServerVersionConfig] ServerHolder类: " + serverHolderClassName);
        SpiderDebug.log("[ServerVersionConfig] ServerHolder字段: " + serverHolderFieldName);
        SpiderDebug.log("[ServerVersionConfig] Server Nano字段: " + serverNanoFieldName);
        SpiderDebug.log("[ServerVersionConfig] ==========================================");
    }

    /**
     * 验证配置完整性
     * @return 配置是否完整
     */
    public boolean isValid() {
        boolean valid = version != null && !version.isEmpty()
                     && serverClassName != null && !serverClassName.isEmpty()
                     && nanoClassName != null && !nanoClassName.isEmpty()
                     && serverHolderClassName != null && !serverHolderClassName.isEmpty()
                     && serverHolderFieldName != null && !serverHolderFieldName.isEmpty()
                     && serverNanoFieldName != null && !serverNanoFieldName.isEmpty();

        if (!valid) {
            SpiderDebug.log("[ServerVersionConfig] 配置不完整！");
        }

        return valid;
    }
}
