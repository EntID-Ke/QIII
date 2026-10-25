#!/usr/bin/env bash
set -eu
task_root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$task_root/build/core-tests"
task_javac=javac
task_java=java
if [ -n "${JAVA_HOME:-}" ]; then
    task_javac="$JAVA_HOME/bin/javac"
    task_java="$JAVA_HOME/bin/java"
fi
"$task_javac" -d "$task_root/build/core-tests" "$task_root"/core/src/main/java/io/github/qqmodulemanager/core/*.java "$task_root/tests/CoreTest.java"
"$task_java" -cp "$task_root/build/core-tests" CoreTest
