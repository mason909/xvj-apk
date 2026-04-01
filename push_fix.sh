#!/bin/bash
cd /workspace/xvj-apk
git stash -u
git pull --rebase
git stash pop
git add .github/workflows/build.yml commit_and_push.sh push.sh
git commit -m "chore: restore workflow and scripts"
git push
echo "DONE"