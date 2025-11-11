# HTTP服务器安全增强 - 技术文档

> **版本 3.0 更新 (2025-10-19)**：实现多版本自动适配！
> - ✅ 新增 `ServerVersionConfig.java` 集中管理版本配置
> - ✅ 根据 `DeviceInfoHelper.getAppVersion()` 自动选择配置
> - ✅ 支持 3.1.5、3.0.0、2.8.4、2.5.8 版本，易于扩展
> - ✅ 添加新版本只需修改配置类，无需改动核心逻辑
>
> **快速开始**：查看 4.2 节了解如何添加新版本支持

## 1. 问题背景

### 1.1 安全隐患描述
主应用使用 NanoHTTPD 启动了一个 HTTP 服务器，用于提供本地接口服务。但是该服务器默认绑定到 `0.0.0.0`（所有网络接口），这意味着：
- 局域网内的其他设备可以访问该服务器
- 可能暴露隐私敏感接口
- 存在潜在的安全风险

### 1.2 安全目标
通过 JAR 模块使用反射技术，在 `Init.init()` 时修改主应用的 Nano 服务器，使其仅监听 `127.0.0.1`（localhost），只能被本机访问。

---

## 2. 技术方案

### 2.1 整体思路
1. 在 `Init.init()` 中异步执行安全控制任务
2. 等待主应用启动 Nano 服务器（延迟 3 秒）
3. 通过反射找到主应用的 Server 单例
4. 获取正在运行的 Nano 实例
5. 停止旧的 Nano 服务器
6. 创建新的 Nano 实例，设置 `hostname = "127.0.0.1"`
7. 替换 Server 中的 Nano 实例
8. 启动新的安全 Nano 服务器

### 2.2 核心代码位置
- **文件**: `app/src/main/java/com/github/catvod/spider/Init.java`
- **方法**: `secureHttpServer(Application app)` (第 308-388 行)
- **调用时机**: `Init.init()` 异步执行，延迟 3 秒

---

## 3. APK 版本适配指南

### 3.0 区分 mobile 和 leanback 版本

同一个版本号的 App 可能有两种构建类型：
- **mobile**: 手机/平板版本
- **leanback**: 电视（Android TV）版本

这两种构建类型虽然版本号相同，但混淆后的类名和包名**完全不同**！

**判断方法**：

代码中实现了**三层判断机制**，按优先级从高到低依次尝试，确保在各种情况下都能正确判断：

#### **方法1：读取 BuildConfig.FLAVOR（最直接）**

优先使用 BuildConfig.FLAVOR，因为这是最直接的方式。

```java
try {
    Class<?> buildConfig = Class.forName("com.fongmi.android.tv.BuildConfig");
    String flavor = (String) buildConfig.getField("FLAVOR").get(null);

    if (flavor.contains("leanback")) {
        return "leanback";
    } else if (flavor.contains("mobile")) {
        return "mobile";
    }
} catch (Exception e) {
    // BuildConfig 可能被混淆，继续尝试方法2
}
```

**优点**：最直接、最准确
**缺点**：BuildConfig 可能被混淆导致失败

---

#### **方法2：宿主App的ABI架构判断（新增，更可靠）** ⭐

通过检测宿主App实际使用的原生库架构来判断构建类型。

**原理**：
- FongMi TV 的 **mobile 版本**通常编译为 **armeabi-v7a (32位)**，针对手机/平板优化
- FongMi TV 的 **leanback 版本**通常编译为 **arm64-v8a (64位)**，针对电视优化

**实现**：

```java
/**
 * 在 DeviceInfoHelper.java 中实现
 */
public static String getHostAppAbi() {
    ApplicationInfo appInfo = context.getApplicationInfo();
    String nativeLibraryDir = appInfo.nativeLibraryDir;

    // 示例路径：
    // /data/app/~~xxx/com.fongmi.android.tv-xxx/lib/arm64
    // /data/app/~~xxx/com.fongmi.android.tv-xxx/lib/arm

    if (nativeLibraryDir.contains("arm64")) {
        return "arm64-v8a";  // 64位 -> leanback
    } else if (nativeLibraryDir.contains("arm")) {
        return "armeabi-v7a";  // 32位 -> mobile
    }
    // ...
}

public static String getBuildTypeFromAbi() {
    String abi = getHostAppAbi();

    if (abi.equals("armeabi-v7a")) {
        return "mobile";   // 32位 = mobile版本
    } else if (abi.equals("arm64-v8a")) {
        return "leanback"; // 64位 = leanback版本
    }

    return null;
}
```

**优点**：
- 不依赖混淆后的类名
- 检测的是实际的二进制架构，非常可靠
- 适用于所有版本

**缺点**：
- 依赖于 FongMi TV 的构建规则（mobile=32位，leanback=64位）
- 如果未来构建规则改变可能失效

---

#### **方法3：系统特性检测（最后的备选）**

通过 Android 系统提供的设备特性来判断是否为TV设备。

```java
private static boolean isTvDevice(Context context) {
    PackageManager pm = context.getPackageManager();

    // 1. 检查是否有 Leanback 支持（最可靠）
    if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
        return true;
    }

    // 2. 检查 Leanback 启动器 + 无触摸屏
    Intent leanbackIntent = new Intent(Intent.ACTION_MAIN);
    leanbackIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
    List<ResolveInfo> leanbackApps = pm.queryIntentActivities(leanbackIntent, 0);
    boolean hasTouchScreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);

    if (!hasTouchScreen && !leanbackApps.isEmpty()) {
        return true;
    }

    // 3. 检查 UI 模式
    UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
    if (uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
        return true;
    }

    return false;
}
```

**优点**：
- 完全标准化的 Android API
- 不依赖任何特定App的构建规则

**缺点**：
- 检测的是设备类型，而不是App构建类型
- 在某些边缘情况下可能不准确（例如在TV上安装mobile版本）

---

#### **三层判断机制总结**

| 判断方法 | 优先级 | 可靠性 | 适用场景 | 失败情况 |
|---------|-------|--------|---------|---------|
| BuildConfig.FLAVOR | 1（最高） | ⭐⭐⭐⭐⭐ | BuildConfig未被混淆 | BuildConfig被混淆 |
| App ABI架构 | 2 | ⭐⭐⭐⭐ | 遵循标准构建规则 | 构建规则改变 |
| 系统特性检测 | 3（兜底） | ⭐⭐⭐ | 所有情况 | TV上安装mobile版本 |

**工作流程**：
1. 先尝试方法1，如果成功直接返回
2. 方法1失败，尝试方法2（新增的ABI检测）
3. 方法2失败，使用方法3作为最后的兜底
4. 所有方法都失败，返回 null

**日志示例**：
```
[Init-Security] BuildConfig.FLAVOR = null
[Init-Security] 方法1失败：无法获取BuildConfig.FLAVOR: java.lang.ClassNotFoundException
[DeviceInfo] 宿主App原生库目录: /data/app/~~xxx/lib/arm64
[DeviceInfo] 宿主App ABI: arm64-v8a
[DeviceInfo] 根据ABI判断为 leanback 版本（64位）
[Init-Security] 方法2成功：根据ABI判断为 leanback
```

**版本号命名规则**：
- mobile 版本：直接使用版本号，如 `2.8.4`
- leanback 版本：使用版本号加后缀，如 `2.8.4-leanback`

**配置查找顺序**：
1. 先尝试完整版本号（如 `2.8.4-leanback`）
2. 如果没有找到，回退到基础版本号（如 `2.8.4`）
3. 如果仍然没有找到，使用默认配置

**示例**：
- App 版本：2.8.4，BuildConfig.FLAVOR：leanback
- 完整版本号：`2.8.4-leanback`
- ServerVersionConfig 会先查找 `2.8.4-leanback` 配置
- 如果没有 `2.8.4-leanback` 配置，则使用 `2.8.4` 配置

---

### 3.1 前置准备

#### 3.1.1 反编译 APK
```bash
# 使用 jadx 或其他反编译工具
jadx -d output_dir target.apk
```

推荐工具：
- [JADX](https://github.com/skylot/jadx) - GUI 和命令行都支持
- [JD-GUI](http://java-decompiler.github.io/) - 老牌工具

#### 3.1.2 定位关键类

**步骤 1：找到 Nano 类（推荐新方法）**

**🔍 方法 A（推荐）：搜索 openWebSocket 方法**

```bash
# 在反编译源码目录搜索（更精准）
grep -r "public final NanoWSD.WebSocket openWebSocket(" --include="*.java"
```

**优点**：
- 更精准，几乎只会返回一个结果
- 适用于各个版本（包括 2.8.4 等旧版本）
- 搜索速度快

> 📌 **提示**：如果目标 APK 尚未引入 `NanoWSD`（如 2.5.8 版本），可以改为搜索 `public final NanoHTTPD.Response serve(` 或直接搜索 `extends NanoHTTPD`。

**🔍 方法 B（备选）：搜索静态 Response 方法**

```bash
# 在反编译源码目录搜索
grep -r "public static NanoHTTPD.Response" --include="*.java" | \
  cut -d: -f1 | uniq -c | awk '$1 >= 2 {print $2}'
```

**示例结果（3.1.5 版本）**：
```
反编译后的 Nano 类：
文件路径: C:\Users\xmz\yorkspace\ss\sources\OoO0oO0o0o0O0oO0\oOo0oOo0Oo0oO0Oo.java
- 包名：OoO0oO0o0o0O0oO0
- 类名：oOo0oOo0Oo0oO0Oo
- 完整类名：OoO0oO0o0o0O0oO0.oOo0oOo0Oo0oO0Oo
- 父类：extends NanoWSD
- 构造函数：public oOo0oOo0Oo0oO0Oo(int i) { super(i); }
```

**示例结果（2.8.4 版本）**：
```
反编译后的 Nano 类：
文件路径: C:\Users\xmz\yorkspace\284\sources\oOo0oO0OoOoO0oOo\C1652oOoOoOoOoOoOoO0o.java
- 包名：oOo0oO0OoOoO0oOo
- 类名（反编译显示）：C1652oOoOoOoOoOoOoO0o
- 运行时完整类名：oOo0oO0OoOoO0oOo.oOoOoOoOoOoOoO0o
- `renamed from` 注释：oOoOoOoOoOoOoO0o（反射时必须使用）
- 父类：extends NanoWSD
- 构造函数（反编译显示）：public C1652oOoOoOoOoOoOoO0o(int i) { super(i); }
```

验证特征：
```java
// 应该包含这个方法
@Override
public final NanoWSD.WebSocket openWebSocket(NanoHTTPD.IHTTPSession session) { ... }

// 通常还包含两个静态 Response 方法
public static NanoHTTPD.Response method1(...) { ... }
public static NanoHTTPD.Response method2(...) { ... }
```

**步骤 2：找到 Server 类（推荐新方法）**

**🔍 方法 A（推荐）：搜索 Proxy.set 调用**

```bash
# 直接搜索 Proxy.set（更精准）
grep -r "Proxy\.set" --include="*.java"
```

**优点**：
- 直接定位到启动服务器的代码
- 适用于各个版本
- 搜索结果少，容易确认

**🔍 方法 B（备选）：搜索 Nano 类引用**

```bash
# 搜索引用 Nano 类的文件（替换为你找到的 Nano 类名）
grep -r "OoO0oO0o0o0O0oO0\.oOo0oOo0Oo0oO0Oo" --include="*.java"
```

在搜索结果中，找到包含以下特征的类：
- 有一个字段类型是 Nano 类
- 有启动服务器的方法（通常在端口 9978-9999 之间尝试）
- 包含 `Proxy.set(端口)` 调用

**示例结果（3.1.5 版本）**：
```
反编译后的 Server 类：
文件路径: C:\Users\xmz\yorkspace\ss\sources\oOo0oO0o0o0OoO0o\oOoOoOo0O0O0oO0o.java
- 包名：oOo0oO0o0o0OoO0o
- 类名：oOoOoOo0O0O0oO0o
- 完整类名：oOo0oO0o0o0OoO0o.oOoOoOo0O0O0oO0o
- Nano 实例字段：OoOo0oO0o0o0oOo0
```

**示例结果（2.8.4 版本）**：
```
反编译后的 Server 类：
文件路径: C:\Users\xmz\yorkspace\284\sources\OoO0oOoO0o0O0O0O\OoOo0OoO0OoO0oO0.java
- 包名：OoO0oOoO0o0O0O0O
- 类名：OoOo0OoO0OoO0oO0
- 完整类名：OoO0oOoO0o0O0O0O.OoOo0OoO0OoO0oO0
- Nano 字段（反编译显示）：f2892oOo0oO0o0O0O0Oo0
- Nano 字段（运行时实际）：oOo0oO0o0O0O0Oo0
- 端口字段（反编译显示）：f2891OoOoO0O0o0oOoO0O
- 端口字段（运行时实际）：OoOoO0O0o0oOoO0O
```

验证代码示例（Server 类中的启动方法）：

**3.1.5 版本示例**：
```java
public void OoOo0o0oOo0O0O0o() {
    if (((OoO0oO0o0o0O0oO0.oOo0oOo0Oo0oO0Oo) this.OoOo0oO0o0o0oOo0) != null) {
        return;  // 已启动
    }
    for (int i = 9978; i < 9999; i++) {
        try {
            OoO0oO0o0o0O0oO0.oOo0oOo0Oo0oO0Oo ooo0ooo0oo0oo0oo =
                new OoO0oO0o0o0O0oO0.oOo0oOo0Oo0oO0Oo(i);
            this.OoOo0oO0o0o0oOo0 = ooo0ooo0oo0oo0oo;  // ← 这里的字段名
            ooo0ooo0oo0oo0oo.start();
            Proxy.set(i);  // ← 验证特征
            return;
        } catch (Throwable unused) {
            this.OoOo0oO0o0o0oOo0 = null;
        }
    }
}
```

**2.8.4 版本示例**：
```java
public void OoOo0Oo0oO0O0oOo() {
    if (((C1652oOoOoOoOoOoOoO0o) this.f2892oOo0oO0o0O0O0Oo0) != null) {
        return;  // 已启动
    }
    do {
        try {
            this.f2892oOo0oO0o0O0O0Oo0 = new C1652oOoOoOoOoOoOoO0o(this.f2891OoOoO0O0o0oOoO0O);
            Proxy.set(this.f2891OoOoO0O0o0oOoO0O);  // ← 验证特征
            ((C1652oOoOoOoOoOoOoO0o) this.f2892oOo0oO0o0O0O0Oo0).start();
            return;
        } catch (Exception unused) {
            this.f2891OoOoO0O0o0oOoO0O++;  // 端口递增
            ((C1652oOoOoOoOoOoOoO0o) this.f2892oOo0oO0o0O0O0Oo0).stop();
            this.f2892oOo0oO0o0O0O0Oo0 = null;
        }
    } while (this.f2891OoOoO0O0o0oOoO0O < 9999);
}
```

**步骤 3：找到 ServerHolder 类**

**🔍 方法 A（推荐 - 适用于 3.1.5 等较新版本）：搜索 Server 类型的静态字段**

```bash
# 搜索 Server 类型的静态字段（替换为你找到的 Server 类名）
grep -r "static.*oOo0oO0o0o0OoO0o\.oOoOoOo0O0O0oO0o" --include="*.java"
```

**优点**：
- 直接定位 ServerHolder 类
- 适用于结构清晰的版本

**示例结果（3.1.5 版本）**：
```
反编译后的 ServerHolder 类：
文件路径: C:\Users\xmz\yorkspace\ss\sources\OoO0oO0o0o0O0oO0\oOoOoOo0oOo0o0oO.java
- 包名：OoO0oO0o0o0O0oO0
- 类名：oOoOoOo0oOo0o0oO
- 完整类名：OoO0oO0o0o0O0oO0.oOoOoOo0oOo0o0oO
- 静态字段（反编译显示）：f2278oOoOoOoOoOoOoO0o
- 静态字段（运行时实际）：oOoOoOoOoOoOoO0o  ⚠️ 注意区别！
```

**🔍 方法 B（推荐 - 适用于 2.8.4 等旧版本）：在 HomeActivity 中查找**

对于某些版本（如 2.8.4），ServerHolder 类可能难以通过静态字段搜索找到。这时应该在主应用的 HomeActivity 中查找 Server 类的使用：

```bash
# 1. 先找到 HomeActivity
find sources -name "HomeActivity.java" -type f

# 2. 在 HomeActivity 中搜索 Server 类的引用（替换为你的 Server 类名）
grep -n "OoO0oOoO0o0O0O0O.OoOo0OoO0OoO0oO0" HomeActivity.java
```

查找包含类似这样的代码：
```java
// 查找静态字段访问模式
SomeClass.staticField.someMethod();
```

**示例结果（2.8.4 版本）**：
```
在 HomeActivity.java 中找到：
OoOo0OoO0OoO0oO0 server = AbstractC1650oOo0oOo0Oo0oO0Oo.f11467oOoOoOoOoOoOoO0o;
                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^^^^^
                          这是 ServerHolder 类名          这是静态字段名（反编译显示）
```

然后打开 ServerHolder 类文件验证：
```
反编译后的 ServerHolder 类：
文件路径: C:\Users\xmz\yorkspace\284\sources\oOo0oO0OoOoO0oOo\AbstractC1650oOo0oOo0Oo0oO0Oo.java
- 包名：oOo0oO0OoOoO0oOo
- 类名（反编译显示）：AbstractC1650oOo0oOo0Oo0oO0Oo
- 运行时完整类名：oOo0oO0OoOoO0oOo.oOo0oOo0Oo0oO0Oo
- `renamed from` 注释：oOo0oOo0Oo0oO0Oo
- 静态字段（反编译显示）：f11467oOoOoOoOoOoOoO0o
- 静态字段（运行时实际）：oOoOoOoOoOoOoO0o  ⚠️ 从注释中提取！
```

**⚠️ 关键难点**：ServerHolder 类中字段名的两个版本

打开反编译的 ServerHolder.java 文件，会看到类似这样的代码：

**3.1.5 版本示例**：
```java
package OoO0oO0o0o0O0oO0;

public abstract class oOoOoOo0oOo0o0oO {

    /* renamed from: oOoOoOoOoOoOoO0o, reason: collision with root package name */
    public static volatile oOo0oO0o0o0OoO0o.oOoOoOo0O0O0oO0o f2278oOoOoOoOoOoOoO0o =
        new oOo0oO0o0o0OoO0o.oOoOoOo0O0O0oO0o();
}
```

**2.8.4 版本示例**：
```java
package oOo0oO0OoOoO0oOo;

import OoO0oOoO0o0O0O0O.OoOo0OoO0OoO0oO0;

/* renamed from: oOo0oO0OoOoO0oOo.oOo0oOo0Oo0oO0Oo, reason: case insensitive filesystem */
public abstract class AbstractC1650oOo0oOo0Oo0oO0Oo {

    /* renamed from: oOoOoOoOoOoOoO0o, reason: collision with root package name */
    public static volatile OoOo0OoO0OoO0oO0 f11467oOoOoOoOoOoOoO0o;

    static {
        OoOo0OoO0OoO0oO0 server = new OoOo0OoO0OoO0oO0();
        server.f2891OoOoO0O0o0oOoO0O = 9978;
        f11467oOoOoOoOoOoOoO0o = server;
    }
}
```

**重要提示**：
```
⚠️ 字段名的两个版本：
1. 反编译显示名：f2278oOoOoOoOoOoOoO0o  （仅用于阅读反编译代码）
2. 运行时实际名：oOoOoOoOoOoOoO0o      （反射时使用此名称！）

注释 "renamed from: oOoOoOoOoOoOoO0o" 中的就是运行时实际字段名！

反编译工具为了避免命名冲突，会重命名某些字段，但在实际的 APK 字节码中，
字段名仍然是原始的混淆名称。反射时必须使用原始名称。
```

### 3.2 关键信息提取总结

根据反编译结果，提取以下信息并添加到 `ServerVersionConfig.java`：

| 项目 | 说明 | 示例值 (3.1.5) | 示例值 (3.0.0) | 示例值 (2.8.4) | 示例值 (2.5.8) | 如何获取 |
|------|------|----------------|----------------|----------------|----------------|----------|
| **Nano 完整类名** | Nano HTTP 服务器类 | `OoO0oO0o0o0O0oO0.oOo0oOo0Oo0oO0Oo` | `OoOoO0oO0o0oO0O0.oOoOoOoOoOoOoO0o`（反编译显示：`OoOoO0oO0o0oO0O0.C1572oOoOoOoOoOoOoO0o`） | `oOo0oO0OoOoO0oOo.oOoOoOoOoOoOoO0o`（反编译显示：`oOo0oO0OoOoO0oOo.C1652oOoOoOoOoOoOoO0o`） | `oOoO0oO0Oo0O0OoO.oOoOoOoOoOoOoO0o`（反编译显示：`oOoO0oO0Oo0O0OoO.C1150oOoOoOoOoOoOoO0o`） | 搜索 `openWebSocket` 或两个 `public static NanoHTTPD.Response` |
| **Nano 父类** | 继承的类 | `NanoWSD` | `NanoWSD` | `NanoWSD` | `NanoHTTPD` | 查看类声明 `extends ...` |
| **Nano 构造函数** | 参数类型 | `int` (端口号) | `int` (端口号) | `int` (端口号) | `int` (端口号) | 查看构造函数签名 |
| **Server 完整类名** | Server 管理类 | `oOo0oO0o0o0OoO0o.oOoOoOo0O0O0oO0o` | `OoOo0oO0Oo0oO0oO.oOoOoOo0O0O0oO0o` | `OoO0oOoO0o0O0O0O.OoOo0OoO0OoO0oO0` | `OoO0oOoO0o0O0O0O.oOoOoOoOoO0oOo0o` | 搜索 `Proxy.set` 或引用 Nano 类的文件 |
| **Server 中 Nano 字段名** | 存储 Nano 实例的字段 | `OoOo0oO0o0o0oOo0` | `f5677oOo0oO0o0O0O0Oo0`（反编译显示：`oOo0oO0o0O0O0Oo0`） | `oOo0oO0o0O0O0Oo0` | `oOoOoO0oOoO0OoOo` | 查看启动方法中的赋值语句 `this.字段名 = new Nano(...)` |
| **ServerHolder 完整类名** | 持有 Server 单例的类 | `OoO0oO0o0o0O0oO0.oOoOoOo0oOo0o0oO` | `OoOoO0oO0o0oO0O0.AbstractC1570oOo0oOo0Oo0oO0Oo` | `oOo0oO0OoOoO0oOo.oOo0oOo0Oo0oO0Oo`（反编译显示：`oOo0oO0OoOoO0oOo.AbstractC1650oOo0oOo0Oo0oO0Oo`） | `oOoO0oO0Oo0O0OoO.oOo0oOo0Oo0oO0Oo`（反编译显示：`oOoO0oO0Oo0O0OoO.AbstractC1149oOo0oOo0Oo0oO0Oo`） | 搜索 Server 类型的静态字段或在 HomeActivity 中查找 |
| **ServerHolder 字段名（运行时）** | Server 单例字段 | `oOoOoOoOoOoOoO0o` | `oOoOoOoOoOoOoO0o` | `oOoOoOoOoOoOoO0o` | `oOoOoOoOoOoOoO0o` | ⚠️ 从注释 `renamed from:` 中提取 |
| **端口范围** | Nano 尝试绑定的端口 | `9978-9999` | `9978-9999` | `9978-9999` | `9978-9999` | 查看启动方法中的 for/do-while 循环 |

#### 3.2.1 运行时类名与反编译显示名的差异

- ProGuard 会在字节码中写入原始混淆名，但反编译工具会为了避免与文件系统大小写冲突或关键字冲突而“临时改名”，例如将 `oOoOoOoOoOoOoO0o` 显示为 `C1652oOoOoOoOoOoOoO0o`。
- 始终以注释 `/* renamed from: Xxx */` 中的内容作为**反射时使用的真实名称**，并在文档或配置中注明“反编译显示”版本，避免团队协作时混淆。
- 建议在 `ServerVersionConfig` 中只存储运行时真实名称，调试日志与文档额外备注反编译显示名，便于在源码仓库与设备运行时两侧快速对照。

### 3.3 添加新版本配置

找到所有关键信息后，在 `ServerVersionConfig.java` 中添加新版本的配置方法：

```java
/**
 * X.Y.Z 版本配置
 *
 * APK信息：
 * - 版本号：X.Y.Z
 * - 反编译路径：...
 *
 * 类信息：
 * - Nano 类：...
 * - Server 类：...
 * - ServerHolder 类：...
 */
private static ServerVersionConfig getConfigFor_X_Y_Z() {
    return new ServerVersionConfig(
        "X.Y.Z",
        "包名.Server类名",      // Server类名
        "包名.Nano类名",        // Nano类名
        "包名.ServerHolder类名", // ServerHolder类名
        "运行时字段名",          // ServerHolder字段名（运行时实际名）
        "运行时字段名"           // Server中Nano字段名（运行时实际名）
    );
}
```

然后在 `getConfig()` 方法的 switch 语句中添加新版本的 case：

```java
case "X.Y.Z":
    return getConfigFor_X_Y_Z();
```

### 3.4 验证字段名的方法

如果不确定 ServerHolder 的字段名，可以使用以下调试代码：

```java
// 临时调试代码：列出 ServerHolder 类的所有字段
Class<?> serverHolderClass = classLoader.loadClass("OoO0oO0o0o0O0oO0.oOoOoOo0oOo0o0oO");
Field[] fields = serverHolderClass.getDeclaredFields();
SpiderDebug.log("[Debug] ServerHolder 类的所有字段:");
for (Field field : fields) {
    SpiderDebug.log("[Debug]   字段名: " + field.getName() +
                    ", 类型: " + field.getType().getName() +
                    ", 静态: " + java.lang.reflect.Modifier.isStatic(field.getModifiers()));
}
```

预期输出：
```
[Debug] ServerHolder 类的所有字段:
[Debug]   字段名: oOoOoOoOoOoOoO0o, 类型: oOo0oO0o0o0OoO0o.oOoOoOo0O0O0oO0o, 静态: true
```

### 3.5 已适配版本速查表

| 版本 | Server (运行时) | Nano (运行时) | ServerHolder (运行时) | 反编译显示备注 |
|------|-----------------|---------------|------------------------|----------------|
| 3.1.5 | `oOo0oO0o0o0OoO0o.oOoOoOo0O0O0oO0o` | `OoO0oO0o0o0O0oO0.oOo0oOo0Oo0oO0Oo` | `OoO0oO0o0o0O0oO0.oOoOoOo0oOo0o0oO` | 运行时与反编译显示一致 |
| 3.0.0 | `OoOo0oO0Oo0oO0oO.oOoOoOo0O0O0oO0o` | `OoOoO0oO0o0oO0O0.oOoOoOoOoOoOoO0o`<br>反编译显示：`OoOoO0oO0o0oO0O0.C1572oOoOoOoOoOoOoO0o` | `OoO0oO0o0o0O0oO0.oOoOoOo0oOo0o0oO` | 结构与 3.1.5 相近，字段名仍然是 `oOo0oO0o0O0O0Oo0` / `oOoOoOoOoOoOoO0o` |
| 3.0.0-leanback | `OoOoO0o0o0O0O0Oo.OoOo0oO0o0o0oOo0` | `oOoO0O0oOo0Oo0O0.oOoOoOoOoOoOoO0o`<br>反编译显示：`oOoO0O0oOo0Oo0O0.C1841oOoOoOoOoOoOoO0o` | `oOoO0O0oOo0Oo0O0.oOo0oOo0Oo0oO0Oo`<br>反编译显示：`oOoO0O0oOo0Oo0O0.AbstractC1839oOo0oOo0Oo0oO0Oo` | **电视版（leanback）**，包名与 mobile 版本完全不同！Nano 和 ServerHolder 在同一包中 |
| 2.8.4 | `OoO0oOoO0o0O0O0O.OoOo0OoO0OoO0oO0` | `oOo0oO0OoOoO0oOo.oOoOoOoOoOoOoO0o`<br>反编译显示：`oOo0oO0OoOoO0oOo.C1652oOoOoOoOoOoOoO0o` | `oOo0oO0OoOoO0oOo.oOo0oOo0Oo0oO0Oo`<br>反编译显示：`oOo0oO0OoOoO0oOo.AbstractC1650oOo0oOo0Oo0oO0Oo` | 请根据 `renamed from` 注释提取运行时名称，并注意包名大小写 |
| 2.8.4-leanback | `OoO0oOo0Oo0o0Oo0.oOoOo0O0Oo0O0o0o`<br>反编译显示：`OoO0oOo0Oo0o0Oo0.C1372oOoOo0O0Oo0O0o0o` | `oOo0oO0oOoOo0oOo.oOoOoOoOoOoOoO0o`<br>反编译显示：`oOo0oO0oOoOo0oOo.C1667oOoOoOoOoOoOoO0o` | `oOo0oO0oOoOo0oOo.oOo0oOo0Oo0oO0Oo`<br>反编译显示：`oOo0oO0oOoOo0oOo.AbstractC1665oOo0oOo0Oo0oO0Oo` | **电视版（leanback）**，包名与 mobile 版本完全不同！注意字段名相同但包名不同 |
| 2.5.8 | `OoO0oOoO0o0O0O0O.oOoOoOoOoO0oOo0o` | `oOoO0oO0Oo0O0OoO.oOoOoOoOoOoOoO0o`<br>反编译显示：`oOoO0oO0Oo0O0OoO.C1150oOoOoOoOoOoOoO0o` | `oOoO0oO0Oo0O0OoO.oOo0oOo0Oo0oO0Oo`<br>反编译显示：`oOoO0oO0Oo0O0OoO.AbstractC1149oOo0oOo0Oo0oO0Oo` | 请根据 `renamed from` 注释提取运行时名称，并注意包名大小写；Nano 继承 `NanoHTTPD`（无 `openWebSocket`） |

---

## 4. 代码适配流程

### 4.1 多版本自动适配架构

从 v2.0 版本开始，`Init.java` 使用 `ServerVersionConfig` 类实现多版本自动适配：

```
Init.init()
    ↓
secureHttpServer()
    ↓
获取 App 版本 (DeviceInfoHelper.getAppVersion())
    ↓
加载版本配置 (ServerVersionConfig.getConfig(appVersion))
    ↓
使用配置中的类名和字段名进行反射操作
```

**优势**：
- ✅ **集中管理**：所有版本配置集中在 `ServerVersionConfig.java`
- ✅ **自动适配**：根据 App 版本自动选择正确的配置
- ✅ **易于扩展**：添加新版本只需在配置类中新增一个方法
- ✅ **降低出错**：避免手动修改 `Init.java` 中的硬编码类名
- ✅ **便于调试**：每个版本配置都有详细的文档注释

### 4.2 为新版本添加配置

**步骤 1：反编译并提取信息**

按照 3.1.2 节的方法反编译 APK 并提取以下信息：
- Nano 完整类名
- Server 完整类名
- ServerHolder 完整类名
- ServerHolder 字段名（运行时）
- Server 中 Nano 字段名

**步骤 2：在 `ServerVersionConfig.java` 中添加配置方法**

```java
/**
 * X.Y.Z 版本配置
 * （添加详细的注释，说明APK信息和类信息）
 */
private static ServerVersionConfig getConfigFor_X_Y_Z() {
    return new ServerVersionConfig(
        "X.Y.Z",
        "Server完整类名",
        "Nano完整类名",
        "ServerHolder完整类名",
        "ServerHolder字段名（运行时实际名）",
        "Server中Nano字段名"
    );
}
```

**步骤 3：在 `getConfig()` 方法中注册新版本**

```java
public static ServerVersionConfig getConfig(String appVersion) {
    // ...
    switch (mainVersion) {
        case "3.1.5":
            return getConfigFor_3_1_5();

        case "2.8.4":
            return getConfigFor_2_8_4();

        case "X.Y.Z":  // ← 添加新版本
            return getConfigFor_X_Y_Z();

        default:
            // ...
    }
}
```

**步骤 4：编译并测试**

```bash
# 编译 JAR
build.bat

# 部署到设备
adb push jar\custom_spider.jar /sdcard/

# 查看日志验证
adb logcat | grep -E "Init-Security|ServerVersionConfig"
```

### 4.3 旧版适配方式（已废弃）

⚠️ **不再推荐**：直接修改 `Init.java` 中的硬编码类名

旧方式的缺点：
- ❌ 每次适配都要修改核心逻辑代码
- ❌ 只能支持一个版本
- ❌ 容易出错，难以维护

### 4.4 更新 Init.java（仅当核心逻辑变化时）

通常情况下，添加新版本支持**不需要修改** `Init.java`，只需在 `ServerVersionConfig.java` 中添加配置即可。

只有当安全控制的**核心逻辑**需要改变时才修改 `Init.java`，例如：
- 修改 hostname 设置的方式
- 更改服务器启动/停止的逻辑
- 添加新的安全控制措施

当前 `Init.java` 的 `secureHttpServer()` 方法已经实现了版本自动适配，核心代码如下：

```java
// 0. 获取App版本并加载对应配置
String appVersion = DeviceInfoHelper.getAppVersion();
ServerVersionConfig config = ServerVersionConfig.getConfig(appVersion);

// 1. 加载类（使用版本配置）
Class<?> serverClass = classLoader.loadClass(config.serverClassName);
Class<?> nanoClass = classLoader.loadClass(config.nanoClassName);
Class<?> serverHolderClass = classLoader.loadClass(config.serverHolderClassName);

// 2. 获取字段（使用版本配置）
Field serverInstanceField = serverHolderClass.getDeclaredField(config.serverHolderFieldName);
Field nanoField = serverClass.getDeclaredField(config.serverNanoFieldName);

// 3-8. 执行安全控制（通用逻辑，无需修改）
```

### 4.5 适配检查清单（新版本）

为新版本 APK 适配时，按顺序完成以下检查：

- [ ] **1. 反编译 APK**
  - 工具：JADX
  - 输出路径：记录在配置注释中

- [ ] **2. 提取 Nano 类信息**
  - 搜索方法：`grep -r "public final NanoWSD.WebSocket openWebSocket("`
  - 完整类名：`包名.类名`
  - 验证特征：继承 `NanoWSD`，构造函数接受 `int` 参数

- [ ] **3. 提取 Server 类信息**
  - 搜索方法：`grep -r "Proxy\\.set"`
  - 完整类名：`包名.类名`
  - Nano 字段名：查看启动方法中的赋值 `this.字段名 = new Nano(...)`

- [ ] **4. 提取 ServerHolder 类信息**
  - 搜索方法：`grep -r "static.*Server类名"` 或在 HomeActivity 中查找
  - 完整类名：`包名.类名`
  - 字段名（运行时）：从注释 `renamed from:` 中提取

- [ ] **5. 在 `ServerVersionConfig.java` 中添加配置方法**
  - 方法名：`getConfigFor_X_Y_Z()`
  - 参数：版本号、5个类名/字段名
  - 添加详细的注释文档

- [ ] **6. 在 `getConfig()` 方法中注册版本**
  - 添加 `case "X.Y.Z": return getConfigFor_X_Y_Z();`

- [ ] **7. 编译并部署**
  - 运行 `build.bat`
  - `adb push jar\custom_spider.jar /sdcard/`

- [ ] **8. 验证日志**
  - 查看 `[ServerVersionConfig] 当前App版本: X.Y.Z`
  - 确认 `[Init-Security] ✓ 安全Nano已启动（仅监听127.0.0.1:端口）`

- [ ] **9. 功能测试**
  - 本机访问：`adb shell "curl http://127.0.0.1:9978/"` ✅ 应该成功
  - 外部访问：`curl http://<设备IP>:9978/` ✅ 应该被拒绝
  - netstat检查：绑定地址应该是 `127.0.0.1`，不是 `0.0.0.0`

### 4.6 适配示例：完整流程

假设要为 `4.0.0` 版本添加支持：

**1. 反编译并搜索**

```bash
jadx -d output_4.0.0 FongMi_TV_4.0.0.apk
cd output_4.0.0/sources

# 搜索 Nano 类
grep -r "public final NanoWSD.WebSocket openWebSocket(" --include="*.java"
# 结果：abc/def/NanoServer.java

# 搜索 Server 类
grep -r "Proxy\\.set" --include="*.java"
# 结果：xyz/uvw/HttpServer.java

# 搜索 ServerHolder 类
grep -r "static.*xyz.uvw.HttpServer" --include="*.java"
# 结果：xyz/uvw/ServerContainer.java
```

**2. 提取字段名**

打开 `xyz/uvw/ServerContainer.java`：
```java
/* renamed from: serverInstance, reason: ... */
public static volatile HttpServer f12345serverInstance = ...;
```
→ 运行时字段名：`serverInstance`

打开 `xyz/uvw/HttpServer.java`：
```java
this.f67890nano = new NanoServer(port);  // ← 反编译显示名
/* renamed from: nano, reason: ... */   // ← 运行时实际名
```
→ 运行时字段名：`nano`

**3. 在 `ServerVersionConfig.java` 中添加**

```java
/**
 * 4.0.0 版本配置
 *
 * APK信息：
 * - 版本号：4.0.0
 * - 反编译路径：C:\output_4.0.0\sources
 *
 * 类信息：
 * - Nano 类：abc.def.NanoServer
 * - Server 类：xyz.uvw.HttpServer
 * - ServerHolder 类：xyz.uvw.ServerContainer
 */
private static ServerVersionConfig getConfigFor_4_0_0() {
    return new ServerVersionConfig(
        "4.0.0",
        "xyz.uvw.HttpServer",
        "abc.def.NanoServer",
        "xyz.uvw.ServerContainer",
        "serverInstance",
        "nano"
    );
}
```

**4. 注册版本**

```java
case "4.0.0":
    return getConfigFor_4_0_0();
```

**5. 完成！**

编译、部署、测试即可。无需修改 `Init.java`。

---

## 5. 常见问题与解决方案

### 5.1 ClassNotFoundException

**现象**：
```
[Init-Security] 未找到Server或Nano类（可能主App版本不匹配）
```

**原因**：
- 主应用版本更新，类名已改变
- 包名或类名拼写错误
- 混淆规则变化

**解决方案**：
1. 重新反编译最新版 APK
2. 按照 3.1.2 的步骤重新定位 Nano、Server 和 ServerHolder 类
3. 更新 `Init.java` 中的类名（适配点 1）
4. 重新编译并测试

### 5.2 NoSuchFieldException

**现象**：
```
No field f2278oOoOoOoOoOoOoO0o in class LOoO0oO0o0o0O0oO0/oOoOoOo0oOo0o0oO; ...
```

**原因**：
- 使用了反编译显示的字段名，而不是运行时字段名
- 主应用版本更新，字段名已改变

**解决方案**：
1. 打开反编译的 ServerHolder.java 文件
2. 查找 `/* renamed from: ... */` 注释
3. 使用注释中的原始字段名，而不是反编译工具重命名后的名字
4. 如果没有注释，使用 3.3 节的调试代码枚举所有字段
5. 更新 `Init.java` 中的字段名（适配点 2）

**示例**：
```java
// 错误 ❌ - 使用反编译显示名
Field serverInstanceField = serverHolderClass.getDeclaredField("f2278oOoOoOoOoOoOoO0o");

// 正确 ✅ - 使用运行时实际名（从注释中提取）
Field serverInstanceField = serverHolderClass.getDeclaredField("oOoOoOoOoOoOoO0o");
```

### 5.3 字段类型不匹配

**现象**：
```
获取到的 Nano 实例为 null
或
java.lang.ClassCastException: cannot cast ...
```

**原因**：
- Server 类中可能有多个 Object 类型的字段
- 取到了错误的字段

**解决方案**：
1. 在反编译的 Server.java 中仔细查看启动服务器的方法
2. 确认哪个字段被赋值为 Nano 实例：
```java
// 查找这样的代码
this.字段名 = new Nano类(端口);
```
3. 更新 `Init.java` 中的字段名（适配点 3）
4. 可以添加类型检查以验证：
```java
Object nano = nanoField.get(serverInstance);
if (nano != null && nanoClass.isInstance(nano)) {
    SpiderDebug.log("[Init-Security] ✓ 获取到正确的Nano实例");
} else {
    SpiderDebug.log("[Init-Security] ✗ Nano实例类型不匹配");
}
```

### 5.4 Nano 服务器启动失败

**现象**：
```
java.net.BindException: Address already in use
或
端口已被占用
```

**原因**：
- 旧 Nano 未正确停止
- 端口被其他进程占用
- 停止操作与启动操作之间没有足够的等待时间

**解决方案**：
1. 增加停止旧 Nano 后的等待时间：
```java
oldNano.getClass().getMethod("stop").invoke(oldNano);
Thread.sleep(500);  // 等待端口释放
SpiderDebug.log("[Init-Security] 已等待端口释放");
```

2. 验证旧 Nano 是否真的停止了：
```java
try {
    boolean wasRunning = (boolean) oldNano.getClass().getMethod("wasStarted").invoke(oldNano);
    boolean isAlive = (boolean) oldNano.getClass().getMethod("isAlive").invoke(oldNano);
    SpiderDebug.log("[Init-Security] 旧Nano状态 - wasStarted: " + wasRunning + ", isAlive: " + isAlive);
} catch (Exception e) {
    // 方法可能不存在，忽略
}
```

3. 如果端口仍然被占用，可以尝试下一个端口：
```java
for (int tryPort = port; tryPort < port + 10; tryPort++) {
    try {
        newNano = nanoClass.getDeclaredConstructor(int.class).newInstance(tryPort);
        // 设置 hostname...
        newNano.getClass().getMethod("start").invoke(newNano);
        SpiderDebug.log("[Init-Security] ✓ 成功启动在端口: " + tryPort);
        break;
    } catch (Exception e) {
        SpiderDebug.log("[Init-Security] 端口 " + tryPort + " 启动失败，尝试下一个");
    }
}
```

### 5.5 hostname 字段未找到

**现象**：
```
[Init-Security] 警告：未找到hostname字段
```

**原因**：
- `hostname` 字段在 NanoHTTPD 父类中，但父类结构可能变化
- NanoHTTPD 版本不同

**解决方案**：
1. 当前代码已经实现了父类遍历，通常能找到
2. 如果确实找不到，检查 NanoHTTPD 的版本和源码
3. 可能需要使用其他方法限制绑定地址：
```java
// 备选方案：通过构造函数传入 hostname
Constructor<?> constructor = nanoClass.getDeclaredConstructor(String.class, int.class);
Object newNano = constructor.newInstance("127.0.0.1", port);
```

---

## 6. 测试验证

### 6.1 成功日志示例

```
[Init] 开始HTTP服务器安全控制
[Init-Security] ========== HTTP服务器安全控制 ==========
[INFO][Init] 开始执行HTTP服务器安全控制
[Init-Security] 成功加载Server、Nano和ServerHolder类
[Init-Security] 停止旧Nano服务器
[Init-Security] 旧Nano已停止
[Init-Security] 获取端口: 9978
[Init-Security] 创建新Nano实例
[Init-Security] 成功设置hostname为127.0.0.1
[INFO][Init] 成功设置Nano hostname为127.0.0.1
[Init-Security] ✓ 安全Nano已启动（仅监听127.0.0.1:9978）
[INFO][Init] 安全Nano已启动，端口: 9978
```

### 6.2 验证方法

**方法 1：查看日志**
1. 连接设备：`adb connect <设备IP>`
2. 查看实时日志：`adb logcat | grep -E "Init-Security|Init\]"`
3. 确认看到 "✓ 安全Nano已启动（仅监听127.0.0.1:端口）"

**方法 2：网络访问测试**

本机访问测试（应该成功）：
```bash
# 方式1：通过 adb shell
adb shell "curl http://127.0.0.1:9978/"

# 方式2：在设备上安装 curl/wget 等工具测试
```

局域网访问测试（应该失败）：
```bash
# 从电脑访问设备的局域网IP（替换为实际IP）
curl http://192.168.1.100:9978/
# 预期结果：Connection refused 或 Timeout
```

**方法 3：netstat 网络监听检查**
```bash
# 查看端口监听情况
adb shell "netstat -tuln | grep 9978"
```

预期输出：
```
tcp        0      0 127.0.0.1:9978          0.0.0.0:*               LISTEN
```

关键验证点：
- ✅ 绑定地址是 `127.0.0.1`（localhost）
- ❌ 不应该是 `0.0.0.0`（所有接口）

**方法 4：代码内验证**

在主应用中添加测试代码（可选）：
```java
// 获取 Nano 实例并检查其绑定地址
Object nano = /* 获取 Nano 实例 */;
Field hostnameField = nano.getClass().getSuperclass().getDeclaredField("hostname");
hostnameField.setAccessible(true);
String hostname = (String) hostnameField.get(nano);
Log.d("SecurityCheck", "Nano 绑定地址: " + hostname);
// 应该输出: Nano 绑定地址: 127.0.0.1
```

---

## 7. 版本适配记录模板

为每次适配创建记录，方便后续维护：

```markdown
### APK 版本：FongMi TV v4.5.6 (2025-10-19)

**APK 信息**：
- 包名：com.fongmi.android.tv
- 版本号：4.5.6
- 版本代码：456
- 反编译工具：JADX 1.4.7
- 反编译路径：C:\Users\xmz\yorkspace\ss\sources

**适配信息**：
- Nano 类：`OoO0oO0o0o0O0oO0.oOo0oOo0Oo0oO0Oo`
  - 父类：`NanoWSD`
  - 构造函数：`public oOo0oOo0Oo0oO0Oo(int i)`
  - 文件路径：`OoO0oO0o0o0O0oO0\oOo0oOo0Oo0oO0Oo.java`

- Server 类：`oOo0oO0o0o0OoO0o.oOoOoOo0O0O0oO0o`
  - Nano 字段名：`OoOo0oO0o0o0oOo0`
  - 启动方法：`OoOo0o0oOo0O0O0o()`
  - 文件路径：`oOo0oO0o0o0OoO0o\oOoOoOo0O0O0oO0o.java`

- ServerHolder 类：`OoO0oO0o0o0O0oO0.oOoOoOo0oOo0o0oO`
  - 静态字段（反编译显示）：`f2278oOoOoOoOoOoOoO0o`
  - 静态字段（运行时实际）：`oOoOoOoOoOoOoO0o`
  - 文件路径：`OoO0oO0o0o0O0oO0\oOoOoOo0oOo0o0oO.java`

- 端口范围：9978-9999

**代码修改**：
- 文件：`Init.java`
- 修改行：316-328
- Git Commit: `abc123def` (如果使用版本控制)

**测试结果**：✅ 通过
- 本机访问：✅ 正常 (curl http://127.0.0.1:9978/)
- 外部访问：✅ 已拒绝 (Connection refused)
- 日志验证：✅ 成功 (看到 "✓ 安全Nano已启动")
- netstat验证：✅ 绑定到 127.0.0.1:9978

**已知问题**：无

**备注**：
- 这个版本的混淆比之前更激进，ServerHolder 的字段被重命名
- 注意必须使用运行时字段名 `oOoOoOoOoOoOoO0o`，不是反编译显示的 `f2278...`
```

### APK 版本：FongMi TV v3.0.0 (新增适配案例)

**APK 信息**：
- 包名：com.fongmi.android.tv
- 版本号：3.0.0
- 版本代码：300
- 反编译工具：JADX（版本未记录）
- 反编译路径：C:\Users\xmz\yorkspace\300\sources

**适配信息**：

**⚠️ 包名大小写提醒**

| 组件 | Windows 文件路径 | Java 包声明 | 反射时使用 |
|------|----------------|------------|----------|
| Nano 类 | `OoOoO0oO0o0oO0O0\` | `package OoOoO0oO0o0oO0O0;` | **大写 O** ✅ |
| Server 类 | `OoOo0oO0Oo0oO0oO\` | `package OoOo0oO0Oo0oO0oO;` | **大写 O** ✅ |
| ServerHolder 类 | `OoOoO0oO0o0oO0O0\` | `package OoOoO0oO0o0oO0O0;` | **大写 O** ✅ |

- **Nano 类**：运行时 `OoOoO0oO0o0oO0O0.oOoOoOoOoOoOoO0o`（反编译显示：`OoOoO0oO0o0oO0O0.C1572oOoOoOoOoOoOoO0o`）
  - 父类：`NanoWSD`
  - 构造函数：`public oOoOoOoOoOoOoO0o(int i)`
  - 文件路径：`OoOoO0oO0o0oO0O0\C1572oOoOoOoOoOoOoO0o.java`
  - 查找方法：搜索 `public final NanoWSD.WebSocket openWebSocket(`

- **Server 类**：`OoOo0oO0Oo0oO0oO.oOoOoOo0O0O0oO0o`
  - Nano 字段（运行时实际）：`f5677oOo0oO0o0O0O0Oo0`（反编译显示：`oOo0oO0o0O0O0Oo0`）
  - 端口循环：`for (int i = 9978; i < 9999; i++) { ... Proxy.set(i); }`
  - 文件路径：`OoOo0oO0Oo0oO0oO\oOoOoOo0O0O0oO0o.java`
  - 查找方法：搜索 `Proxy.set(`

- **ServerHolder 类**：`OoOoO0oO0o0oO0O0.AbstractC1570oOo0oOo0Oo0oO0Oo`
  - 静态字段（运行时实际）：`oOoOoOoOoOoOoO0o`（反编译显示：`f2235oOoOoOoOoOoOoO0o`）
  - 文件路径：`OoOoO0oO0o0oO0O0\AbstractC1570oOo0oOo0Oo0oO0Oo.java`
  - 查找方法：搜索 `new oOoOoOo0O0O0oO0o()` 或 `static volatile`

**查找方法总结**：
1. Nano 类：使用 `grep -r "public final NanoWSD.WebSocket openWebSocket(" --include="*.java"` ✅
2. Server 类：使用 `grep -r "Proxy\\.set(" --include="*.java"` ✅
3. ServerHolder 类：在 `OoO0oO0o0o0O0oO0` 包中搜索 `static volatile` 或利用 Server 类型的引用 ✅

**代码修改**：
- 文件：`ServerVersionConfig.java`
- 需要的类名和字段名（已验证并修正）：
  - Nano 类：`OoOoO0oO0o0oO0O0.oOoOoOoOoOoOoO0o`
  - Server 类：`OoOo0oO0Oo0oO0oO.oOoOoOo0O0O0oO0o`
  - ServerHolder 类：`OoO0oO0o0o0O0oO0.oOoOoOo0oOo0o0oO`
  - ServerHolder 字段：`oOoOoOoOoOoOoO0o`
  - Server Nano 字段：`oOo0oO0o0O0O0Oo0`

**测试建议**：
- 版本日志：`[ServerVersionConfig] App版本: 3.0.0`
- 安全日志：`[Init-Security] ✓ 安全Nano已启动（仅监听127.0.0.1:端口）`
- 网络验证：`adb shell "curl http://127.0.0.1:9978/device"`

---

### APK 版本：FongMi TV v2.8.4 (实际适配案例)

**APK 信息**：
- 包名：com.fongmi.android.tv
- 版本号：2.8.4
- 版本代码：284
- 反编译工具：JADX（版本未记录）
- 反编译路径：C:\Users\xmz\yorkspace\284\sources

**适配信息**：

**⚠️ 重要提示：包名大小写陷阱**

Windows 文件系统不区分大小写，但 Java 类加载器严格区分。必须查看 `package` 声明！

| 组件 | Windows 文件路径 | Java 包声明 | 反射时使用 |
|------|----------------|------------|----------|
| Nano 类 | `OoO0oO0OoOoO0oOo\` | `package oOo0oO0OoOoO0oOo;` | **小写 o** ✅ |
| Server 类 | `OoO0oOoO0o0O0O0O\` | `package OoO0oOoO0o0O0O0O;` | **大写 O** ✅ |
| ServerHolder 类 | `oOo0oO0OoOoO0oOo\` | `package oOo0oO0OoOoO0oOo;` | **小写 o** ✅ |

- **Nano 类**：运行时 `oOo0oO0OoOoO0oOo.oOoOoOoOoOoOoO0o`（反编译显示：`oOo0oO0OoOoO0oOo.C1652oOoOoOoOoOoOoO0o`）⚠️ 小写 o 开头
  - 父类：`NanoWSD`
  - 构造函数：`public oOoOoOoOoOoOoO0o(int i)`（反编译文件中显示为 `C1652...`，但运行时无需前缀）
  - 文件路径：`oOo0oO0OoOoO0oOo\C1652oOoOoOoOoOoOoO0o.java`
  - 包声明：`package oOo0oO0OoOoO0oOo;` ← **小写 o 开头**
  - 查找方法：搜索 `public final NanoWSD.WebSocket openWebSocket(`

- **Server 类**：`OoO0oOoO0o0O0O0O.OoOo0OoO0OoO0oO0` ⚠️ 大写 O 开头
  - Nano 字段（反编译显示）：`f2892oOo0oO0o0O0O0Oo0`
  - Nano 字段（运行时实际）：`oOo0oO0o0O0O0Oo0` ⚠️
  - 端口字段（反编译显示）：`f2891OoOoO0O0o0oOoO0O`
  - 端口字段（运行时实际）：`OoOoO0O0o0oOoO0O` ⚠️
  - 启动方法：`OoOo0Oo0oO0O0oOo()`
  - 文件路径：`OoO0oOoO0o0O0O0O\OoOo0OoO0OoO0oO0.java`
  - 包声明：`package OoO0oOoO0o0O0O0O;` ← **大写 O 开头**
  - 查找方法：搜索 `Proxy.set`


- **ServerHolder 类**：运行时 `oOo0oO0OoOoO0oOo.oOo0oOo0Oo0oO0Oo`（反编译显示：`oOo0oO0OoOoO0oOo.AbstractC1650oOo0oOo0Oo0oO0Oo`）⚠️ 小写 o 开头
  - 静态字段（反编译显示）：`f11467oOoOoOoOoOoOoO0o`
  - 静态字段（运行时实际）：`oOoOoOoOoOoOoO0o` ⚠️
  - 文件路径：`oOo0oO0OoOoO0oOo\AbstractC1650oOo0oOo0Oo0oO0Oo.java`
  - 包声明：`package oOo0oO0OoOoO0oOo;` ← **小写 o 开头**
  - 查找方法：在 HomeActivity.java 中搜索 Server 类引用

- 端口范围：9978-9999（初始端口 9978）

**查找方法总结**：
1. Nano 类：使用新方法 `grep -r "public final NanoWSD.WebSocket openWebSocket("` ✅
2. Server 类：使用新方法 `grep -r "Proxy\.set"` ✅
3. ServerHolder 类：旧方法失效，改用 HomeActivity 查找法 ✅

**⚠️ 关键陷阱 - 包名大小写**：
- **错误做法**：直接使用 Windows 文件路径的大小写
- **正确做法**：打开文件查看 `package` 声明的实际大小写
- **症状**：如果使用错误的大小写会出现 `ClassNotFoundException`
- **验证方法**：打开每个类文件，查看第一行的 `package` 声明

**⚠️ mobile 与 leanback 版本差异**：
- 同一版本号（如 2.8.4）的 mobile 和 leanback 版本，混淆后的包名**完全不同**
- 例如 2.8.4-mobile 的 Nano 类：`oOo0oO0OoOoO0oOo.oOoOoOoOoOoOoO0o`
- 而 2.8.4-leanback 的 Nano 类：`oOo0oO0oOoOo0oOo.oOoOoOoOoOoOoO0o`（注意第3个和第6个字符不同）
- 必须分别反编译两种构建类型的 APK，并创建独立的配置

**代码修改**：
- 文件：`ServerVersionConfig.java`
- 需要的类名和字段名（已验证并修正）：
  - Nano 类：运行时 `oOo0oO0OoOoO0oOo.oOoOoOoOoOoOoO0o`（反编译显示：`oOo0oO0OoOoO0oOo.C1652oOoOoOoOoOoOoO0o`）← 小写 o（已修正）
  - Server 类：`OoO0oOoO0o0O0O0O.OoOo0OoO0OoO0oO0` ← 大写 O（正确）
  - ServerHolder 类：运行时 `oOo0oO0OoOoO0oOo.oOo0oOo0Oo0oO0Oo`（反编译显示：`oOo0oO0OoOoO0oOo.AbstractC1650oOo0oOo0Oo0oO0Oo`）← 小写 o（已修正）
  - ServerHolder 字段：`oOoOoOoOoOoOoO0o` (运行时实际名)
  - Server Nano 字段：`oOo0oO0o0O0O0Oo0` (运行时实际名)

**测试结果**：✅ 已修复包名大小写问题
- 包名验证：✅ 已查看 package 声明并修正
- 配置更新：✅ 完成（ServerVersionConfig.java）
- 类加载测试：等待实际运行验证

**已知问题**：
- ServerHolder 类使用 `abstract class`，在早期版本中可能是普通类
- Server 类使用 `do-while` 循环而非 `for` 循环进行端口尝试

**备注**：
- **关键发现**：2.8.4 版本的 ServerHolder 类无法通过静态字段搜索找到！
- **解决方案**：必须在 HomeActivity 中查找 Server 类的使用来定位 ServerHolder
- **字段重命名**：所有关键字段都被重命名，必须从 `renamed from:` 注释中提取实际名称
- **文档更新**：已将新的查找方法添加到文档 3.1.2 节
- **通用性**：新方法（搜索 `openWebSocket` 和 `Proxy.set`）对新旧版本都有效

### APK 版本：FongMi TV v2.5.8 (新增适配案例)

**APK 信息**：
- 包名：com.fongmi.android.tv
- 版本号：2.5.8
- 版本代码：258
- 反编译工具：JADX（版本未记录）
- 反编译路径：C:\Users\xmz\yorkspace\258\sources

**包名对照**（同样注意大小写）：

| 组件 | Windows 文件路径 | Java 包声明 | 反射时使用 |
|------|----------------|------------|----------|
| Nano 类 | `oOoO0oO0Oo0O0OoO\` | `package oOoO0oO0Oo0O0OoO;` | **小写 o** ✅ |
| Server 类 | `OoO0oOoO0o0O0O0O\` | `package OoO0oOoO0o0O0O0O;` | **大写 O** ✅ |
| ServerHolder 类 | `oOoO0oO0Oo0O0OoO\` | `package oOoO0oO0Oo0O0OoO;` | **小写 o** ✅ |

- **Nano 类**：运行时 `oOoO0oO0Oo0O0OoO.oOoOoOoOoOoOoO0o`（反编译显示：`oOoO0oO0Oo0O0OoO.C1150oOoOoOoOoOoOoO0o`）⚠️ 小写 o 开头
  - 父类：`NanoHTTPD`（旧版本尚未引入 `NanoWSD`）
  - 构造函数：`public oOoOoOoOoOoOoO0o(int i)`（反编译文件显示为 `C1150...`）
  - 文件路径：`oOoO0oO0Oo0O0OoO\C1150oOoOoOoOoOoOoO0o.java`
  - 查找方法：搜索 `public final NanoHTTPD.Response serve(` 或关键路径 `/m3u8`

- **Server 类**：`OoO0oOoO0o0O0O0O.oOoOoOoOoO0oOo0o` ⚠️ 大写 O 开头
  - Nano 字段（运行时实际）：`oOoOoO0oOoO0OoOo`
  - 端口字段（运行时实际）：`oOo0oO0o0O0O0Oo0`
  - 启动方法：`OoOo0O0oOo0Oo0o0()`（do-while 端口递增，范围 9978-9999）
  - 文件路径：`OoO0oOoO0o0O0O0O\C0681oOoOoOoOoO0oOo0o.java`
  - 查找方法：搜索 `Proxy.set`，确认 `new C1150...` 构造语句

- **ServerHolder 类**：运行时 `oOoO0oO0Oo0O0OoO.oOo0oOo0Oo0oO0Oo`（反编译显示：`oOoO0oO0Oo0O0OoO.AbstractC1149oOo0oOo0Oo0oO0Oo`）⚠️ 小写 o 开头
  - 静态字段（运行时实际）：`oOoOoOoOoOoOoO0o`
  - 静态字段初始端口：9978（`static` 块中直接设置）
  - 文件路径：`oOoO0oO0Oo0O0OoO\AbstractC1149oOo0oOo0Oo0oO0Oo.java`
  - 查找方法：搜索 `new C0681oOoOoOoOoO0oOo0o()` 或使用日志定位 `oOoOoOoOoOoOoO0o`

**特殊差异**：
- 该版本的 Nano 直接继承 `NanoHTTPD`，没有 WebSocket 支持，`openWebSocket` 搜索无结果。改用 `serve()` 或 `NanoHTTPD` 相关关键字定位。
- `Server` 类的字段使用 `do-while` 循环，不同于 3.1.5 的 `for`。但 `Proxy.set` 调用仍然是可靠定位入口。
- `ServerHolder` 与 Nano 在同一包下（`oOoO0oO0Oo0O0OoO`），注意与 2.8.4 版本不同的目录层级。

**代码修改**：
- 文件：`ServerVersionConfig.java`
- 新增配置：
  - Nano 类：`oOoO0oO0Oo0O0OoO.oOoOoOoOoOoOoO0o`
  - Server 类：`OoO0oOoO0o0O0O0O.oOoOoOoOoO0oOo0o`
  - ServerHolder 类：`oOoO0oO0Oo0O0OoO.oOo0oOo0Oo0oO0Oo`
  - ServerHolder 字段：`oOoOoOoOoOoOoO0o`
  - Server Nano 字段：`oOoOoO0oOoO0OoOo`

**测试建议**：
- 再次验证包名大小写（尤其是 Nano 与 ServerHolder 均位于以小写 `o` 开头的包）
- 编译并部署后，在日志中确认：
  - `[ServerVersionConfig] App版本: 2.5.8`
  - `[Init-Security] ✓ 安全Nano已启动（仅监听127.0.0.1:端口）`
- 执行 `adb shell "curl http://127.0.0.1:9978/device"` 验证本地访问

---

## 8. 工作原理详解

### 8.1 执行时序图

```
主App启动
    ↓
加载 JAR 包 (custom_spider.jar)
    ↓
调用 Init.init(context)
    ↓
启动异步任务（延迟3秒）
    ↓
主App启动 Nano 服务器 (绑定 0.0.0.0:9978)
    ↓
延迟结束，执行 secureHttpServer()
    ↓
┌─────────────────────────────────────────────────┐
│ 1. 通过 ClassLoader 加载混淆类                   │
│    ├─ ServerHolder 类                            │
│    ├─ Server 类                                  │
│    └─ Nano 类                                    │
│                                                  │
│ 2. 通过反射获取 Server 单例                      │
│    ServerHolder.oOoOoOoOoOoOoO0o (静态字段)     │
│                                                  │
│ 3. 获取正在运行的 Nano 实例                      │
│    Server.OoOo0oO0o0o0oOo0 (实例字段)           │
│                                                  │
│ 4. 停止旧 Nano                                   │
│    oldNano.stop()                                │
│                                                  │
│ 5. 创建新的安全 Nano                             │
│    newNano = new Nano(port)                      │
│                                                  │
│ 6. 设置 hostname = "127.0.0.1"                   │
│    遍历父类查找 hostname 字段                    │
│                                                  │
│ 7. 替换 Server 中的 Nano 实例                    │
│    Server.OoOo0oO0o0o0oOo0 = newNano            │
│                                                  │
│ 8. 启动新 Nano                                   │
│    newNano.start()                               │
└─────────────────────────────────────────────────┘
    ↓
✓ HTTP 服务器已安全（仅 127.0.0.1:9978）
    ↓
主App 正常使用 HTTP 服务（只能本机访问）
```

### 8.2 关键技术点

**1. 延迟执行**
- 问题：JAR 加载时，主App 的 Nano 可能还未启动
- 解决：异步任务 + 延迟 3 秒
```java
execute(() -> {
    Thread.sleep(3000);  // 等待主App启动Nano
    secureHttpServer(get().app);
});
```

**2. ClassLoader 使用**
- 问题：JAR 和主App 在不同的 ClassLoader
- 解决：使用主App 的 ClassLoader 加载混淆类
```java
ClassLoader classLoader = app.getClassLoader();
Class<?> serverClass = classLoader.loadClass("混淆的类名");
```

**3. 反射访问私有字段**
- 问题：字段都是私有的
- 解决：使用 `getDeclaredField()` + `setAccessible(true)`
```java
Field field = clazz.getDeclaredField("字段名");
field.setAccessible(true);
Object value = field.get(instance);
```

**4. 父类字段遍历**
- 问题：`hostname` 在 NanoHTTPD 父类中
- 解决：循环遍历父类查找字段
```java
Class<?> currentClass = nanoClass;
while (currentClass != null) {
    try {
        Field hostnameField = currentClass.getDeclaredField("hostname");
        // 找到了！
        break;
    } catch (NoSuchFieldException e) {
        currentClass = currentClass.getSuperclass();  // 向上查找
    }
}
```

**5. 字段名混淆处理**
- 问题：反编译工具会重命名字段避免冲突
- 解决：从注释 `renamed from:` 提取原始名称
```java
/* renamed from: oOoOoOoOoOoOoO0o, reason: collision with root package name */
public static volatile ... f2278oOoOoOoOoOoOoO0o = ...;

// 使用原始名称
Field field = clazz.getDeclaredField("oOoOoOoOoOoOoO0o");  // ✅
// 不是反编译显示的名称
Field field = clazz.getDeclaredField("f2278oOoOoOoOoOoOoO0o");  // ❌
```

---

## 9. 附录

### 9.1 完整代码参考

**当前实现**：
- 文件：`app/src/main/java/com/github/catvod/spider/Init.java`
- 方法：`secureHttpServer(Application app)` (第 308-388 行)
- 调用：`Init.init()` 第 71-89 行（异步执行）

### 9.2 相关文件

| 文件 | 路径 | 说明 |
|------|------|------|
| **Init.java** | `app/src/main/java/com/github/catvod/spider/Init.java` | 主实现，包含 `secureHttpServer()` 方法 |
| **ServerVersionConfig.java** | `app/src/main/java/com/github/catvod/spider/ServerVersionConfig.java` | 版本配置管理，定义各版本的类名和字段名 |
| **DeviceInfoHelper.java** | `app/src/main/java/com/github/catvod/spider/DeviceInfoHelper.java` | 设备信息工具，提供 `getAppVersion()` 方法 |
| **LogReportManager.java** | `app/src/main/java/com/github/catvod/spider/LogReportManager.java` | 日志上报工具 |
| **SpiderDebug** | `com.github.catvod.crawler.SpiderDebug` | 调试日志工具 |
| **SECURITY_ENHANCEMENT.md** | `app/src/main/java/com/github/catvod/spider/SECURITY_ENHANCEMENT.md` | 本技术文档 |
| **build.bat** | `build.bat` | JAR 编译脚本 |

### 9.3 反编译参考路径（示例）

基于当前版本的反编译路径：
```
C:\Users\xmz\yorkspace\ss\sources\
├── OoO0oO0o0o0O0oO0\
│   ├── oOo0oOo0Oo0oO0Oo.java          ← Nano 类
│   └── oOoOoOo0oOo0o0oO.java          ← ServerHolder 类
├── oOo0oO0o0o0OoO0o\
│   └── oOoOoOo0O0O0oO0o.java          ← Server 类
└── com\fongmi\android\p002tv\
    └── p004ui\activity\
        └── HomeActivity.java           ← 参考：如何使用 Server
```

### 9.4 技术参考链接

- [NanoHTTPD 官方仓库](https://github.com/NanoHttpd/nanohttpd)
- [Java 反射 API 文档](https://docs.oracle.com/javase/8/docs/api/java/lang/reflect/package-summary.html)
- [Android ClassLoader 机制](https://developer.android.com/reference/java/lang/ClassLoader)
- [JADX 反编译工具](https://github.com/skylot/jadx)
- [ProGuard 混淆规则](https://www.guardsquare.com/manual/configuration/usage)

### 9.5 快速参考卡片

```
┌─────────────────────────────────────────────────────────────┐
│                HTTP 服务器安全增强 - 快速参考                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│ 📋 版本适配步骤：                                            │
│   1. 反编译 APK (jadx -d output_dir target.apk)             │
│   2. 搜索 Nano (grep "public static NanoHTTPD.Response")    │
│   3. 搜索 Server (grep "Nano类完整名")                       │
│   4. 搜索 ServerHolder (grep "static.*Server类名")          │
│   5. 提取字段名（注意 renamed from 注释）                    │
│   6. 更新 Init.java (3 个适配点)                            │
│   7. 编译测试                                               │
│                                                             │
│ ⚠️  关键难点：                                               │
│   • ServerHolder 字段名有两个版本                           │
│   • 反射时用运行时名称（从 renamed from 提取）               │
│   • 不是反编译显示的名称！                                   │
│                                                             │
│ ✅ 验证方法：                                                │
│   • 日志："✓ 安全Nano已启动（仅监听127.0.0.1:端口）"         │
│   • 本机访问：curl http://127.0.0.1:9978/ (成功)           │
│   • 外部访问：curl http://<局域网IP>:9978/ (拒绝)          │
│   • netstat: 绑定地址应是 127.0.0.1，不是 0.0.0.0          │
│                                                             │
│ 🐛 常见错误：                                                │
│   • ClassNotFoundException → 重新反编译，检查类名            │
│   • NoSuchFieldException → 使用运行时字段名，不是反编译名    │
│   • BindException → 增加停止后等待时间 (Thread.sleep(500))  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

**文档版本**: 3.0 (多版本自动适配)
**创建日期**: 2025-10-19
**最后更新**: 2025-10-19
**维护者**: Claude Code
**适用版本**: Init.java v3.0 (多版本自动适配，使用 ServerVersionConfig)
**支持的App版本**: 3.1.5, 2.8.4（可扩展）
