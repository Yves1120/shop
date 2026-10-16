# 尚品甄选微服务电商平台

基于 Spring Cloud Alibaba 的 B2C 微服务电商项目，覆盖商品、购物车、订单、支付、用户、后台管理等核心业务流程。项目在已有课程工程基础上进行了交易链路、缓存治理、幂等控制和部署工程的二次开发与优化。

## 技术栈

- Java 17 / Spring Boot 3.0.5
- Spring Cloud 2022.0.2 / Spring Cloud Alibaba 2022.0.0.0-RC2
- Nacos / Gateway / OpenFeign / Sentinel
- MySQL / MyBatis-Plus
- Redis
- RabbitMQ delayed message
- Alipay SDK
- Docker / Kubernetes / GitHub Actions

## 核心功能

- 商品管理、商品详情、SKU 查询
- 购物车，基于 Redis Hash 存储
- 订单交易：下单、取消、超时未支付自动取消
- 支付宝支付、支付回调、支付状态同步
- 用户中心、收货地址、浏览历史、收藏
- 系统管理、权限、后台管理

## 重点设计

### 声明式缓存组件

项目提供 `@GuiguCache` 注解和 `GuiguCacheAspect` 切面，用 AOP 统一处理缓存读取、写入、空值缓存和锁释放。

- 缓存穿透：空结果短 TTL 缓存
- 缓存击穿：热点 key 使用 Redis 分布式锁和双重检查
- 缓存雪崩：过期时间增加随机偏移
- 锁释放：使用 Lua 脚本校验锁持有者，避免误删其他请求的锁

### 订单防重与超时取消

- 结算页生成一次性交易号，提交订单时校验并立即删除，配合本地事务降低重复下单风险
- 下单后发送 RabbitMQ 延迟消息，到期检查订单状态并取消未支付订单
- 消费端手动 ACK，并按订单状态做幂等处理，避免消息丢失和重复消费

### 支付安全

支付链路在发起支付、支付回调和状态更新时进行多层校验：

- 校验订单归属和订单状态
- 校验支付金额
- 使用 Redis SETNX 拦截同一订单的并发支付请求
- 支付回调验签并做幂等处理

### 网关鉴权

Gateway 统一完成 JWT 登录态校验，敏感接口校验用户身份和数据归属，防止未登录调用支付接口和订单水平越权。

## 模块结构

```text
spzx-parent
├── spzx-gateway
├── spzx-auth
├── spzx-api
├── spzx-common
├── spzx-modules
│   ├── spzx-system
│   ├── spzx-product
│   ├── spzx-cart
│   ├── spzx-order
│   ├── spzx-payment
│   ├── spzx-user
│   └── ...
└── spzx-visual
```

## 本地运行

要求：

- JDK 17
- Maven 3.6+
- MySQL
- Redis
- Nacos
- RabbitMQ delayed message 插件

基本步骤：

1. 初始化 `sql/` 目录中的数据库脚本。
2. 启动 Nacos、MySQL、Redis、RabbitMQ。
3. 根据本地环境调整各服务的 `bootstrap.yml`。
4. 编译并测试：

```bash
mvn test
```

5. 打包：

```bash
mvn package -DskipTests
```

数据库、Redis、RabbitMQ、Nacos 等连接信息应按环境配置。示例工程中的默认密码仅用于本地开发，不要在生产环境直接使用。

## Docker

构建指定服务镜像：

```bash
docker build --build-arg SERVICE_PATH=spzx-gateway --build-arg JAR_NAME=spzx-gateway --build-arg PORT=8080 -t spzx/spzx-gateway:local .
```

也可以使用应用编排文件构建全部业务服务：

```bash
cd docker
docker compose -f docker-compose-app.yml build
docker compose -f docker-compose-app.yml up -d
```

默认只暴露网关端口，内部服务通过 compose 网络访问。

## Kubernetes

项目提供基础部署模板：

```bash
kubectl apply -f k8s/
```

模板包含 Namespace、ConfigMap、Deployment、Service、探针、资源配额和滚动更新策略。生产环境需要按集群实际情况修改镜像地址、Nacos 地址和副本数。

## CI

GitHub Actions 配置见 `.github/workflows/ci.yml`：

- `mvn test` 作为质量门禁
- 测试通过后按矩阵构建 9 个业务服务镜像
- 镜像 tag 使用 commit SHA，便于追踪和回滚

## 说明

该项目用于学习和展示微服务电商核心链路设计与实现，基础工程结构来源于已有课程项目。当前仓库重点保留课程基础上完成的业务增强、测试补充、缓存治理、幂等控制、支付安全与部署工程化改造。
