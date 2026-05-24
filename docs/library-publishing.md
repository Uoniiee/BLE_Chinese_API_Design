# Library 发布说明

## 当前阶段

当前 library 先作为本仓库内的 Android module 使用：

```kotlin
implementation(project(":ble_chinese_api"))
```

这适合早期验证 API、示例 App 和真机互联行为。

## 本地 AAR

构建 debug AAR：

```powershell
.\gradlew.bat --no-daemon --console=plain :ble_chinese_api:assembleDebug
```

输出位置：

```text
ble_chinese_api/build/outputs/aar/ble_chinese_api-debug.aar
```

构建 release AAR：

```powershell
.\gradlew.bat --no-daemon --console=plain :ble_chinese_api:assembleRelease
```

输出位置：

```text
ble_chinese_api/build/outputs/aar/ble_chinese_api-release.aar
```

业务 App 可以先把 AAR 放入 `libs/` 目录，再在 Gradle 中引用。

## 后续发布到 Maven

如果 API 稳定，可以增加 `maven-publish` 配置，将 library 发布到 Maven Local、GitHub Packages 或 Maven Central。

建议坐标：

```text
groupId: com.uoniiee
artifactId: ble-chinese-api
version: 0.1.0
```

早期建议先发布到 Maven Local 验证：

```powershell
.\gradlew.bat --no-daemon --console=plain :ble_chinese_api:publishToMavenLocal
```

业务 App 引用形式：

```kotlin
implementation("com.uoniiee:ble-chinese-api:0.1.0")
```

## 发布前检查

发布前建议至少完成：

- `:ble_chinese_api:assembleRelease`
- `:sample_app:assembleDebug`
- 两台 Android 真机文本双向收发测试
- 权限拒绝、蓝牙关闭、停止后重启通信等基础路径测试
- README 与 API 文档同步更新
