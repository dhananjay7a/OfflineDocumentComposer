#!/bin/sh
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
GRADLE_JAVA_HOME="${GRADLE_JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java))))}"
exec "$GRADLE_JAVA_HOME/bin/java" -Dorg.gradle.jvmargs="-Xmx4g" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
