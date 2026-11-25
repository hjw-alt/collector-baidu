# 百度采集 0.1.75：监控台逐设备启停

此版本增加采集监控台远程开始/停止能力，并保留现场 0.1.72–0.1.74 已有的轮休自动续采、认证修复、Shizuku 重启百度地图和 DeepSeek 未回首页时继续恢复等改动。

- applicationId：com.tigercode.bdcollector
- versionName：0.1.75
- versionCode：76
- 源码分支：codex/baidu-monitor-control-20261125
- 配套后端：AXXXt/amap-charging-station-crawler 的 codex/collector-monitor-device-control-20261125，按交接提供的固定 commit 部署。

## 使用行为

监控台“开始”启动**设备调度**：关闭本地队列、巡检和列表模式，先等待旧采集任务释放，再启动前台采集服务。开始服务成功后才回报 APPLIED，正常采集进度继续通过心跳和实际入库反映。

“停止”取消本地采集/同步操作，归还任务，停止采集前台服务；无障碍指令接收器保持运行，之后仍可在监控台点开始。服务端只释放该客户端的租约，不会停止其他手机或同机高德客户端。

手机必须联网、无障碍已连接、App 进程存活。被系统强行停止后先在手机打开一次。此版本不依赖 USB 保持远程指令通道；Shizuku 的可用状态仍由手机环境决定。

## 指令与回执

接收器随 ChargingAccessibilityService 启动，每约 3 秒读取设备 config，每约 15 秒上报心跳；注册/心跳声明 remoteDeviceControl=1、当前模式、扫描状态和无障碍连接状态。

设备 config 中的 fleetControl.deviceCommand 包含 commandId、requestId、action、mode、stopGeneration。百度仅接受 DEVICE_DISPATCH 模式。执行前二次核对最新指令和全停状态，避免旧 START 在全停之后重新启动。

执行状态按服务器地址、设备编码、全停 generation 和 commandId 持久化；回执丢失只补发，不重复执行开始。执行中进程被中断时报告 FAILED，等待一条新指令。回执通过 POST /api/v1/devices/me/command-result 上报，沿用设备 token，不使用监控密码登录手机端。

## 构建与验证

JDK 17、Android SDK 35、Gradle 8.9：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :core:test
```

本次构建通过；App/core 共 197 项测试通过，包含 9 项远程指令处理测试。APK 位于 app/build/outputs/apk/debug/app-debug.apk。交付包使用 collector-baidu-0.1.75.apk 命名并附 SHA256SUMS。

安装时覆盖升级并保留数据、模型设置和设备登记。先更新采集后端，再更新手机 App，最后在监控台逐台确认“开始已执行/停止已执行”及心跳和入库情况。

网页入口切换和旧 station-dashboard 日报/EVCS 的保留要求详见配套后端仓库 dev-docs/collector-monitor-cutover.md；本仓库不发布或改动旧日报服务。
