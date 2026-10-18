<template>
  <div id="app-root">
    <!-- 管理后台、完善信息页不显示公共 Header/Footer -->
    <AppHeader v-if="!isAdminRoute && !isInfoRoute && !isAuthRoute" />
    <main :class="{ 'admin-layout': isAdminRoute }">
      <router-view />
    </main>
    <AppFooter v-if="!isAdminRoute && !isInfoRoute && !isAuthRoute && !isAssistantRoute" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from './components/AppHeader.vue'
import AppFooter from './components/AppFooter.vue'

const route = useRoute()

/** 管理后台（不含登录页）不展示公共 Header/Footer */
const isAdminRoute = computed(() => {
  return route.path.startsWith('/admin') && route.path !== '/admin/login'
})

/** 完善信息页面不展示 Header/Footer，防止通过导航栏规避填写 */
const isInfoRoute = computed(() => {
  return ['/complete-profile', '/merchant-info', '/merchant-real-name'].includes(route.path)
})

/** 登录/注册页面不展示 Header/Footer */
const isAuthRoute = computed(() => {
  return route.path === '/login' || route.path === '/register'
})

/** 智能助手页不展示 Footer */
const isAssistantRoute = computed(() => {
  return route.path === '/assistant'
})
</script>

<style scoped>
#app-root {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
main {
  flex: 1;
}
main.admin-layout {
  /* 管理后台不需要顶部的 header 间距 */
}
</style>
