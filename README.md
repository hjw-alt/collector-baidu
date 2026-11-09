# 百度充电站采集 App

基于 Kotlin 和 Android 无障碍服务的百度地图充电站采集终端。包含完整 `app`、`core` 模块，支持设备注册、共享服务端任务领取、采集状态提示、本地结果缓存与回传。

当前源码版本：**0.1.56**（versionCode 57）。

## 工程结构

- `app/`：Android 界面、无障碍操作、任务调度、采集流程、Room 本地存储与网络回传。
- `core/`：页面识别、站点名称匹配、电价及电桩解析、模型和现有单元测试。
- `gradle/`、`gradlew`、`gradlew.bat`：Gradle Wrapper。

共享调度和回传后端需要单独部署，本仓库仅包含手机端。接口定义见 `app/src/main/java/com/tigercode/evcollector/network/ServerApi.kt`。

## 构建

使用 JDK 17、Android SDK Platform 35。可以在 Android Studio 中打开本目录，等待 Gradle 同步后构建；本机 SDK 路径由 IDE 写入不提交的 `local.properties`。

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux：

```sh
./gradlew :app:assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`。

## 运行配置

1. 安装 App，在系统设置中启用本 App 的无障碍服务。
2. 在界面填写自己的服务端地址、设备激活码和设备名称并保存。
3. 点击“开始设备调度”，由服务端分配任务；已部署的服务端负责控制任务名单及循环轮次。

公开源码默认地址 `https://collect.example.com/` 只是占位符，激活码默认留空，需要使用实际部署配置。APK 不直接连接业务数据库。

## 采集逻辑与逐枪页面

当前日常循环读取站点详情卡片的空闲、忙碌、总数及电价，然后继续查找分时电价；不会默认进入逐枪页面滚动采集静态明细。

- `app/src/main/java/com/tigercode/evcollector/engine/CollectionEngine.kt`：主流程；`collectChargingStatusIfPresent()` 负责读取卡片状态，之后进入分时电价流程。
- `core/src/main/kotlin/com/tigercode/evcollector/core/parser/BaiduChargingPileParser.kt`：保留逐枪页面识别、“全部”Tab 定位、逐枪明细解析及合并方法。
- `app/src/main/java/com/tigercode/evcollector/accessibility/ChargingAccessibilityService.kt`：保留电桩详情入口点击和详情页滚动方法。

补充特定站点进入逐枪页的逻辑时，可从主流程接入已有解析与无障碍方法，读取完成后返回站点详情，再继续采集分时电价。

## 公开版本范围

完整保留手机端的业务逻辑、资源、模块配置及现有测试源码。线上服务地址、默认激活码、本地 SDK 路径、构建缓存、调试备份及业务任务清单不随公开源码分发；`app/src/main/assets/baidu_tasks.json` 为 `[]` 占位文件。本地离线采集需要自行提供任务，服务端调度模式从服务端领取任务。
