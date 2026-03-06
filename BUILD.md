---
AIGC:
    ContentProducer: Minimax Agent AI
    ContentPropagator: Minimax Agent AI
    Label: AIGC
    ProduceID: "00000000000000000000000000000000"
    PropagateID: "00000000000000000000000000000000"
    ReservedCode1: 30440220484aaea2837b093dbb9667c38857afd8db2918453bac3aed644cfc743673bea302200c03ff9e367d066f992a2406acc488eba56cbf15335ddcaa2696f19250758291
    ReservedCode2: 3044022037c177232c8ea8b0e7ac7c02a5b3d7d036d0c375709f90dd360fdabef233b2aa0220090372e23344787e9d4a122448aaf6399a48617e4d99d153ce62585a0820d665
---

# XVJ Android APK 构建指南

## 项目结构

```
xvj-apk/
├── app/
│   ├── src/main/
│   │   ├── java/com/xvj/app/
│   │   │   ├── MainActivity.kt      # 主界面 (视频播放)
│   │   │   └── BootReceiver.kt      # 开机启动
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml # 布局文件
│   │   │   ├── drawable/
│   │   │   │   └── circle_button_bg.xml
│   │   │   └── values/
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── gradle/wrapper/
│   └── gradle-wrapper.properties
└── gradlew
```

## 功能列表 (v1.0)

### ✅ 已完成
1. **视频播放** - ExoPlayer，支持多种格式 (mp4, mkv, avi, mov, wmv, flv, webm)
2. **文件夹管理** - 自动扫描常见视频目录
3. **播放控制** - 播放/暂停、上一曲/下一曲
4. **音量控制** - 滑块调节
5. **横屏全屏** - 自动横屏，隐藏状态栏
6. **Kiosk 模式** - 禁止返回键、禁止切出
7. **设置记忆** - 保存音量、文件夹选择

### ⏳ 待开发
- DMX512 触发
- 云端同步 (MQTT)
- 素材管理

## 本地构建步骤

### 方法 1: Android Studio
1. 安装 Android Studio (最新版本)
2. File → Open → 选择 xvj-apk 文件夹
3. 等待 Gradle 同步完成
4. Build → Build Bundle(s) / APK(s) → Build APK(s)
5. 输出: app/build/outputs/apk/debug/app-debug.apk

### 方法 2: 命令行
```bash
# 安装 Gradle (如果未安装)
# macOS
brew install gradle

# Ubuntu
sudo apt install gradle

# 构建
cd xvj-apk
./gradlew assembleDebug

# 输出
# app/build/outputs/apk/debug/app-debug.apk
```

### 方法 3: 直接下载 Gradle Wrapper
```bash
cd xvj-apk
# 下载 gradle-wrapper.jar
curl -L -o gradle/wrapper/gradle-wrapper.jar \
  "https://github.com/gradle/gradle/raw/master/gradle/wrapper/gradle-wrapper.jar"

# 构建
chmod +x gradlew
./gradlew assembleDebug
```

## 技术参数

| 参数 | 值 |
|------|-----|
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |
| Kotlin | 1.9.20 |
| ExoPlayer | 2.19.1 |

## 权限需求

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
```

## 下一步

1. 安装 Android Studio
2. 打开项目构建测试
3. 安装到设备测试

---

*文档更新: 2026-03-03*
