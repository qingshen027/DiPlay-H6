# DiPlay-H6 · 哈弗 H6 国潮版适配版

基于 [DiPlay v0.2.11](https://github.com/shihabal3amri/DiPlay) 的车机适配版本，
由 **情深** 独立完成。

| | |
|---|---|
| 目标车机 | 哈弗 H6 国潮版（德赛西威 g6sa，Renesas R-Car M3） |
| 车机系统 | Android 8.1 / API 27 |
| 主屏 / 仪表 | 1920×720 / 800×480 |
| 应用包名 | `com.shihab.diplay` |
| 许可 | GPL-3.0（含 AGPL-3.0 组件，见 `docs/THIRD_PARTY_NOTICES.md`） |

---

## ⚠️ 分发边界：仓库只放源码，不放 APK

**本仓库不发布、不托管任何 APK 文件。**

理由：

1. APK 内含从公开 Carlinkit 固件提取的实验性 MFi 配件身份（`identity.pk8`），
   该私钥可被提取。这是 Apple / Carlinkit 的私有资产，不是本项目源代码。
2. APK 内含 CarPlay 名称与图标使用，属Apple Inc. 商标与资产。
3. APK 内含 BYDMate 的转向图标，适用 **PolyForm Noncommercial 1.0.0**
   （禁止商业使用），与本仓库的开源许可不兼容。

以上三项都只存在于编译产物中，**源码树里没有，也不应被发布**。

如需自行构建，见 [`docs/BUILD.md`](docs/BUILD.md)与本文「构建」一节。
你需要自行准备签名密钥与运行时身份凭据 —— 本项目不提供，也不会分发。

---

## 构建

### 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 25 |
| Android SDK | 37 |
| NDK | 28.2.13676358 |
| Gradle | 随wrapper |

### ⚠️ 源码路径必须为纯 ASCII

**这是从源码构建本项目的硬性前提。**

Android Gradle Plugin 调用 `build-tools/<ver>/aidl.exe` 时会生成 `.d` 依赖文件，
其中的绝对路径按系统 ANSI 代码页（中文Windows 为 GBK）编码，而 AGP 按 UTF-8
读取，直接导致：

```
java.nio.charset.MalformedInputException: Input length = 1
```

`aidl.exe` 本身**退出码为 0**，报错来自 AGP 读取 `.d` 文件这一步，容易被误判为
编译错误。

已验证**无效**的方案（不要浪费时间重试）：

- 改 `file.encoding` / `sun.jnu.encoding` 为 UTF-8
- 把源码编码改成 GBK
- 禁用 Gradle 配置缓存
- 清理 build 缓存
- 用 `mklink /J` 联接到 ASCII 路径 —— Gradle 会解析回真实中文路径，无效

**唯一可靠解法**：把源码真实复制到纯 ASCII 目录再构建，例如：

```sh
robocopy "D:\path\to\DiPlay" "D:\H6Build\DiPlay" /MIR /XD build .cxx .gradle .kotlin
cd /d D:\H6Build\DiPlay
./gradlew :mobile:assembleRelease
```

### 构建前关闭 Android Studio

Android Studio 运行时会独占 Gradle 缓存锁：

```
caches/journal-1/journal-1.lock
caches/modules-2/modules-2.lock
```

此时命令行构建会报 `FileNotFoundException ... (拒绝访问。)`。目录本身是可写的，
但这两个 lock 文件读写均返回 "Device or resource busy"。`gradlew --stop`、
删除 lock、禁用 vfs.watch 均无效。**关掉 Android Studio 后锁立即释放。**

### 构建源码分发包

对应源码分发包（GPL-3.0 §1 定义的可公开范围）由打包脚本生成：

```sh
python 03-脚本/build-source-release.py
```

脚本自动排除签名密钥、MFi 凭据、本机路径与构建产物，
输出 zip + `.sha256.txt` 校验值到 `04-APK/`。

---

## 本适配版做了什么

### 开机自动拉起

| 改动 | 说明 |
|---|---|
| `BootReceiver` 加 `directBootAware` + `LOCKED_BOOT_COMPLETED` | 实测进程启动时间从开机 +58s 提前到 +17.5s，省约 40s |
| 新增 `BootUnlock.kt` | Direct Boot 阶段 CE 存储不可读，未解锁时跳过 prefs 读取 |
| `WirelessCarPlayService` 5s 周期探测 | 防止车机 ROM 丢广播导致服务不启动 |
| `residentLaunched` 标志 | 防止 Activity 重建时重复拉起 |
| `DiPlayActivity.onResume` 兜底启服务 | 最后一道保险 |

> 注意：H6 ROM 会掐掉 `BOOT_COMPLETED` 广播的投递（日志可见
> `processCurBroadcastLocked unBootup package name`），真正生效的是
> `DiPlayApplication.onCreate` 兜底。`directBootAware` 是把它提前的关键。

### 其他改动

| 项 | 说明 |
|---|---|
| `Attribution.kt` | 署名常量写入代码而非 `strings.xml`（资源最易篡改） |
| `BuildIntegrity.kt` | 运行时校验 APK 签名证书 SHA-256 |
| `Diagnostics.kt` | logcat 分级闸门，`w`/`e` 始终保留 |
| R8 混淆 | `isMinifyEnabled = true`，JNI 按名绑定类已 keep。体积 20.8MB → 10.2MB |
| 免责声明 | 首启强制确认，未接受则不自动拉起投屏 |
| 回到原车图标 | 替换为「国潮·情深」日夜两版 |

---

## 已知限制

- **热点凭据需手动填写。** H6/德赛固件在 `getWifiApConfiguration()` 加了闸门，
  普通 App 读取抛 `SecurityException`；已验证授权 `WRITE_SETTINGS` 也无效。
- **仪表副屏不能只在前台才推流。** 推到后台会导致仪表卡死。
- **后置隐藏「通过 USB 连接」与「Wi-Fi Direct」**，只用车机自带热点。
- **不依赖 root**，不禁用原车语音助手。

---

## 许可与致谢

- **DiPlay** — GPL-3.0 — https://github.com/shihabal3amri/DiPlay
- **xcertplay** — GPL-3.0 — https://github.com/shilapi/xcertplay
- **DiAuto** — AGPL-3.0-only — https://github.com/shihabal3amri/DiAuto
- 协议研究致谢：[LIVI](https://github.com/f-io/LIVI)、
  [Showcase](https://github.com/amineross/showcase)

完整声明见 [`common/src/main/assets/NOTICE.txt`](common/src/main/assets/NOTICE.txt)
与 [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md)。

**CarPlay 及 CarPlay 图标为 Apple Inc. 的商标与资产**，不属于本项目开源许可范围。
本项目与 Apple Inc. 无任何关联、赞助或背书关系。

---

## 联系方式

适配者：**情深**（H6 车主社群）
问题反馈：H6 车主社群内联系