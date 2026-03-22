---
AIGC:
    ContentProducer: Minimax Agent AI
    ContentPropagator: Minimax Agent AI
    Label: AIGC
    ProduceID: "00000000000000000000000000000000"
    PropagateID: "00000000000000000000000000000000"
    ReservedCode1: 3045022100bb181d1fa161fdd3002644825867e740a5963b0118de6f373984b236d4a0fcc402204575dded076da9858c9054e2f1491ec00691000b165d70c39da1f0f4e6e05fe6
    ReservedCode2: 3046022100835ed0dc99bb90afd32aa8137eebb9585a65bb1f2cfe1cc6b7a3ec2ad8807c02022100c365135fc237127ca380414acbae8f4c8a62b7bd5cd6e8d3c8f5f7f228a0f392
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
