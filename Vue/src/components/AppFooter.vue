<template>
  <footer class="app-footer">
    <div class="container footer-grid">
      <div class="footer-brand">
        <div class="footer-logo">ShopAi</div>
        <p>为每一个追求品质的你，甄选好衣。</p>
        <p class="footer-copy">&copy; 2026 ShopAi. All rights reserved.</p>
      </div>
      <div class="footer-col">
        <h4>服务支持</h4>
        <a href="#">帮助中心</a>
        <a href="#">退换货政策</a>
        <a href="#">尺码指南</a>
        <a href="#">联系客服</a>
      </div>
      <div class="footer-col">
        <h4>平台信息</h4>
        <a href="#">关于我们</a>
        <a href="#">商家入驻</a>
        <a href="#">用户协议</a>
        <a href="#">隐私政策</a>
      </div>
      <div class="footer-col">
        <h4>关注我们</h4>
        <a href="#">微信公众号</a>
        <a href="#">小红书</a>
        <a href="#">微博</a>
      </div>
    </div>
    <!-- 管理员入口（仅未登录时显示） -->
    <div class="footer-admin" v-if="showAdminEntry">
      <div class="container">
        <router-link to="/admin/login">管理员入口</router-link>
      </div>
    </div>
  </footer>
</template>

<script setup>
import { computed } from 'vue'
import { isLoggedIn } from '../stores/auth.js'

const showAdminEntry = computed(() => !isLoggedIn.value)
</script>

<style scoped>
.app-footer {
  background: var(--color-bg-dark);
  color: var(--color-text-inverse);
  padding: 56px 0 0;
  margin-top: 80px;
}

.footer-grid {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  gap: 48px;
}

.footer-logo {
  font-family: var(--font-en);
  font-size: 28px;
  font-weight: 600;
  letter-spacing: 3px;
  margin-bottom: 12px;
}

.footer-brand p {
  font-size: 13px;
  color: #999;
  margin-bottom: 4px;
}

.footer-copy {
  margin-top: 20px !important;
  font-size: 12px !important;
  color: #666 !important;
}

.footer-col h4 {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 1px;
  margin-bottom: 16px;
  color: #ccc;
}
.footer-col a {
  display: block;
  font-size: 13px;
  color: #777;
  margin-bottom: 10px;
  transition: color var(--transition);
}
.footer-col a:hover {
  color: var(--color-accent);
}

/* 管理员入口栏 */
.footer-admin {
  margin-top: 48px;
  border-top: 1px solid rgba(255,255,255,0.08);
  padding: 16px 0;
  text-align: right;
}
.footer-admin a {
  font-size: 12px;
  color: #555;
  transition: color 0.2s;
}
.footer-admin a:hover {
  color: var(--color-accent);
}

@media (max-width: 768px) {
  .footer-grid {
    grid-template-columns: 1fr 1fr;
    gap: 32px;
  }
}
</style>
