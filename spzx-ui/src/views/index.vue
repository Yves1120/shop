<template>
  <div class="app-container home">
    <el-row :gutter="20">
      <el-col :sm="24" :lg="14">
        <section class="welcome">
          <p class="eyebrow">商城管理平台</p>
          <h2>欢迎使用管理端</h2>
          <p class="description">
            这里集中管理商品、订单、用户、营销和平台运行状态。
            页面数据以当前账号权限为准。
          </p>
          <p class="version">当前版本：{{ version }}</p>
          <el-button type="primary" plain icon="Cloudy" @click="goTarget(repositoryUrl)">
            查看项目仓库
          </el-button>
        </section>
      </el-col>

      <el-col :sm="24" :lg="10">
        <section class="stack">
          <h3>技术栈</h3>
          <div class="stack-grid">
            <div>
              <h4>服务端</h4>
              <p>Spring Boot</p>
              <p>Spring Cloud</p>
              <p>Nacos / Sentinel</p>
              <p>MySQL / Redis</p>
            </div>
            <div>
              <h4>管理端</h4>
              <p>Vue 3</p>
              <p>Vite</p>
              <p>Element Plus</p>
              <p>Axios / ECharts</p>
            </div>
          </div>
        </section>
      </el-col>
    </el-row>

    <el-divider />

    <el-row :gutter="20">
      <el-col :xs="24" :sm="12" :lg="8">
        <el-card class="info-card">
          <template #header>
            <span>核心模块</span>
          </template>
          <ul>
            <li>商品与分类管理</li>
            <li>订单与售后管理</li>
            <li>用户与权限管理</li>
            <li>营销与运营配置</li>
          </ul>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :lg="8">
        <el-card class="info-card">
          <template #header>
            <span>服务入口</span>
          </template>
          <ul>
            <li>网关服务：8080</li>
            <li>认证服务：9090</li>
            <li>文件服务：9300</li>
            <li>监控服务：9100</li>
          </ul>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="24" :lg="8">
        <el-card class="info-card">
          <template #header>
            <span>运行建议</span>
          </template>
          <ul>
            <li>首次使用前检查服务状态</li>
            <li>定期备份业务数据库</li>
            <li>生产环境替换默认配置</li>
            <li>按最小权限分配账号</li>
          </ul>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup name="Index">
const version = ref('3.6.3')
const repositoryUrl = 'https://github.com/Yves1120/shop'

function goTarget(url) {
  window.open(url)
}
</script>

<style lang="scss" scoped>
.home {
  color: #303133;

  .welcome,
  .stack {
    min-height: 230px;
    padding: 28px 30px;
    border: 1px solid #ebeef5;
    background: #fff;
  }

  .eyebrow {
    margin: 0 0 10px;
    color: #409eff;
    font-size: 14px;
  }

  h2 {
    margin: 0 0 16px;
    font-size: 28px;
    font-weight: 500;
  }

  h3 {
    margin: 0 0 20px;
    font-size: 20px;
    font-weight: 500;
  }

  h4 {
    margin: 0 0 12px;
    font-size: 14px;
  }

  .description {
    max-width: 560px;
    margin: 0 0 18px;
    color: #606266;
    line-height: 1.8;
  }

  .version {
    margin: 0 0 20px;
    color: #909399;
    font-size: 13px;
  }

  .stack-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 24px;

    p {
      margin: 0 0 10px;
      color: #606266;
      font-size: 13px;
    }
  }

  .info-card {
    height: 100%;

    ul {
      margin: 0;
      padding-left: 20px;
      color: #606266;
      line-height: 2;
    }
  }

  @media (max-width: 992px) {
    .stack {
      margin-top: 20px;
    }
  }
}
</style>
