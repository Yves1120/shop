# 管理端前端

这是商城管理端前端，基于 Vue 3、Vite 和 Element Plus 构建。

## 环境要求

- Node.js 18+
- npm 或 Yarn
- 可访问的后端网关服务

## 安装与运行

```bash
npm install
npm run dev
```

开发服务器默认监听 `http://localhost:80`。接口代理配置位于
`vite.config.js`，默认转发到本地后端网关 `http://localhost:8080`。

## 构建

```bash
npm run build:stage
npm run build:prod
npm run preview
```

## 目录说明

```text
src/
├── api/          接口请求
├── assets/       样式与静态资源
├── components/   通用组件
├── layout/       页面布局
├── router/       路由配置
├── store/        状态管理
├── utils/        工具方法
└── views/        业务页面
```

## 相关配置

环境变量文件：

- `.env.development`
- `.env.staging`
- `.env.production`

请根据实际部署环境配置接口地址、上下文路径和其他运行参数。
