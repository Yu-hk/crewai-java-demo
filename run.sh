#!/usr/bin/env bash
# 无需 Maven，直接 javac 编译并运行（Git Bash / Linux / macOS 可用）
set -e
cd "$(dirname "$0")"

rm -rf out && mkdir -p out
javac -encoding UTF-8 -d out $(find src/main/java -name "*.java")

# -Dfile.encoding=UTF-8 在 Java 17 上是必需的：默认 GBK 会让中文输出变成乱码
exec java -Dfile.encoding=UTF-8 -cp out io.example.crew.Main "$@"
