#!/bin/bash
cd /workspace/xvj-apk
git add app/src/main/java/com/xvj/app/MainActivity.kt
git commit -m "fix: 优化状态文字，隐藏技术细节"
git push
echo "DONE"