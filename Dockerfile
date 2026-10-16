# ============================================================
# 尚品甄选 微服务通用镜像构建文件（多阶段构建）
#
# 用法（SERVICE_PATH 传模块相对路径，JAR_NAME 传 jar 名称）：
#   docker build --build-arg SERVICE_PATH=spzx-gateway --build-arg JAR_NAME=spzx-gateway -t spzx/gateway:1.0 .
#   docker build --build-arg SERVICE_PATH=spzx-modules/spzx-order --build-arg JAR_NAME=spzx-order -t spzx/order:1.0 .
#
# 设计要点（面试可讲）：
# 1.多阶段构建：构建层用完整JDK+Maven，运行层只留JRE，镜像体积从1GB+降到约300MB，减小攻击面
# 2.依赖缓存挂载：/root/.m2 挂载为cache，依赖不变时秒级重建
# 3.运行时JVM参数通过 JAVA_OPTS 环境变量注入，无需重新构建镜像即可调整
# 4.G1回收器 + GC日志滚动 + OOM时自动dump，生产问题可回溯
# 5.容器内存限制下使用 MaxRAMPercentage（而非写死Xmx），自适应容器配额
# ============================================================

# ---------- 第一阶段：编译打包 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
ARG SERVICE_PATH
WORKDIR /build

# 先复制pom利用Docker层缓存解析依赖，源码变动时不重新下载依赖
COPY pom.xml ./
COPY spzx-common spzx-common
COPY spzx-api spzx-api
COPY spzx-gateway spzx-gateway
COPY spzx-auth spzx-auth
COPY spzx-modules spzx-modules
COPY spzx-visual spzx-visual

# 只打包目标服务及其依赖模块（-am自动带上common/api依赖）
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q package -pl ${SERVICE_PATH} -am -DskipTests

# ---------- 第二阶段：轻量运行镜像 ----------
FROM eclipse-temurin:17-jre
ARG SERVICE_PATH
ARG JAR_NAME
ARG PORT=8080

WORKDIR /app
RUN mkdir -p /app/logs /app/dump

# 从构建阶段拷贝服务jar（finalName=artifactId）
COPY --from=builder /build/${SERVICE_PATH}/target/${JAR_NAME}.jar app.jar

# JVM调优参数：G1 + 容器内存感知 + GC日志滚动 + OOM自动dump
ENV JAVA_OPTS="-XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:MaxRAMPercentage=75.0 \
  -Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=50m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/dump/"

EXPOSE ${PORT}

# TCP端口探活（JRE镜像无curl/wget，用bash的/dev/tcp）
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD bash -c "exec 3<>/dev/tcp/127.0.0.1/${PORT}" || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
