#!/bin/bash
cd /workspace/xvj-apk
git add app/src/main/java/com/xvj/app/MainActivity.kt
git commit -m "安全修复 P1-P4

P1 (CRITICAL): play_local/push_file 路径遍历防护
- playLocalVideo: folder 限制2位数字，filename 必须通过 isValidFilepath() 校验
- downloadAndPlay: 同上防护

P2 (CRITICAL): APK 签名验证启用
- verifyApkSignature() 替换 return true 为真实指纹校验
- EXPECTED_CERT_FINGERPRINT 需替换为实际证书 SHA-256 指纹

P3 (HIGH): calculateMd5 流式计算防 OOM
- 改用 8KB buffer 流式读取，不再 whole-file readBytes()

P4 (HIGH): MQTT 重连指数退避
- reconnectMQTT() 增加退避: 5s→10s→20s→...→300s 上限
- IMqttActionListener 精确绑定连接成功/失败回调
- checkForUpdate/requestAuthSync 移入 onSuccess 闭包"
git push origin main
