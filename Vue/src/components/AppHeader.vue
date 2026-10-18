<template>
  <header class="app-header" :class="{ scrolled: isScrolled, 'header-light': isLight }">
    <div class="container header-inner">
      <router-link to="/" class="logo">
        <span class="logo-icon">S</span>
        <span class="logo-text">ShopAi</span>
      </router-link>

      <nav class="nav-links">
        <router-link to="/" class="nav-link">首页</router-link>
        <router-link to="/products" class="nav-link">全部商品</router-link>
        <router-link to="/seckill" class="nav-link nav-link--seckill">限时秒杀</router-link>
        <router-link to="/merchants" class="nav-link">全部商家</router-link>
        <template v-if="isLoggedIn && currentRole === 'customer'">
          <router-link to="/orders" class="nav-link">我的订单</router-link>
        </template>
        <template v-if="isLoggedIn && currentRole === 'merchant'">
          <router-link to="/shop" class="nav-link">我的店铺</router-link>
        </template>
        <router-link v-if="isLoggedIn" to="/assistant" class="nav-link">智能助手</router-link>
      </nav>

      <div class="header-actions">
        <!-- 未登录 -->
        <template v-if="!isLoggedIn">
          <router-link to="/login" class="btn btn-outline btn-sm">登录</router-link>
          <router-link to="/register?role=customer" class="btn btn-primary btn-sm">顾客注册</router-link>
          <router-link to="/register?role=merchant" class="btn btn-primary btn-sm btn-merchant">商家入驻</router-link>
        </template>

        <!-- 已登录 -->
        <template v-else>
          <router-link v-if="currentRole === 'customer'" to="/cart" class="icon-btn">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"/></svg>
            <span v-if="cartCount > 0" class="cart-badge">{{ cartCount }}</span>
          </router-link>

          <div class="user-menu" @click="showMenu = !showMenu" v-click-outside="() => showMenu = false">
            <img v-if="avatarUrl" :src="avatarUrl" class="user-avatar-img" />
            <span v-else class="user-avatar">{{ currentUser ? currentUser.username[0].toUpperCase() : 'U' }}</span>
            <div class="dropdown" v-if="showMenu">
              <!-- 商户跳店铺信息页，顾客/管理员跳个人中心 -->
              <router-link v-if="currentUser && currentUser.role === 'merchant'" to="/merchant/profile" @click="showMenu = false">店铺信息</router-link>
              <router-link v-else to="/profile" @click="showMenu = false">个人中心</router-link>
              <router-link v-if="currentUser && currentUser.role === 'merchant'" to="/merchant" @click="showMenu = false">商家后台</router-link>
              <router-link v-if="currentUser && currentUser.role === 'merchant'" to="/merchant/seckill" @click="showMenu = false">秒杀活动</router-link>
              <router-link v-if="currentUser && currentUser.role === 'admin'" to="/admin" @click="showMenu = false">管理后台</router-link>
              <!-- 顾客的秒杀记录入口（商家/管理员的秒杀页在各自后台） -->
              <router-link v-if="currentUser && currentUser.role === 'customer'" to="/my-seckill" @click="showMenu = false">我的秒杀</router-link>
              <a href="#" @click.prevent="showPwdModal = true; showMenu = false">修改密码</a>
              <hr />
              <a href="#" @click.prevent="logout">退出登录</a>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 修改密码弹窗 (Teleport 到 body，避免被 header 的 backdrop-filter 裁剪) -->
    <Teleport to="body">
      <div class="modal-overlay" v-if="showPwdModal" @click.self="showPwdModal = false">
        <div class="modal-box">
          <h3>修改密码</h3>
          <form @submit.prevent="handleChangePwd">
            <div class="form-row"><label>手机号</label><input v-model="pwdForm.phone" type="text" placeholder="请输入注册手机号" maxlength="11" /></div>
            <div class="form-row"><label>邮箱</label><input v-model="pwdForm.email" type="email" placeholder="请输入注册邮箱" /></div>
            <div class="form-row"><label>新密码</label><input v-model="pwdForm.newPassword" type="password" placeholder="至少6位" /></div>
            <div class="form-row"><label>确认密码</label><input v-model="pwdForm.confirmPassword" type="password" placeholder="再次输入新密码" /></div>
            <p v-if="pwdMsg" :class="pwdOk ? 'pub-ok' : 'pub-err'">{{ pwdMsg }}</p>
            <div class="form-actions">
              <button type="submit" class="btn btn-primary btn-sm" :disabled="pwdLoading">{{ pwdLoading ? '提交中...' : '修改密码' }}</button>
              <button type="button" class="btn btn-outline btn-sm" @click="showPwdModal = false">取消</button>
            </div>
          </form>
        </div>
      </div>
    </Teleport>
  </header>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { isLoggedIn, currentUser, currentRole, cartCount, avatarUrl, clearAuth, refreshCartCount } from '../stores/auth.js'
import http from '../utils/http.js'

const router = useRouter()
const route = useRoute()
const showMenu = ref(false)

// 每次路由变化时强制收起下拉框
watch(() => route.fullPath, () => {
  showMenu.value = false
})

// 登录/注册页、深色 hero 页面（如 /shop）时 header 自动切换浅色
const isLight = computed(() => {
  return route.path.startsWith('/shop') || route.path === '/login' || route.path === '/register'
})

const isScrolled = ref(false)
const onScroll = () => { isScrolled.value = window.scrollY > 60 }
onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  // 登录后拉取购物车数量用于角标显示
  if (isLoggedIn.value) refreshCartCount()
})
onUnmounted(() => window.removeEventListener('scroll', onScroll))

/* v-click-outside 指令 */
const vClickOutside = {
  mounted(el, binding) {
    el._clickOutside = (e) => {
      if (!(el === e.target || el.contains(e.target))) {
        binding.value(e)
      }
    }
    document.addEventListener('click', el._clickOutside)
  },
  unmounted(el) {
    document.removeEventListener('click', el._clickOutside)
  }
}

const logout = () => {
  clearAuth()
  showMenu.value = false
  router.push('/')
}

/* ===== 修改密码 ===== */
const showPwdModal = ref(false)
const pwdLoading = ref(false)
const pwdMsg = ref('')
const pwdOk = ref(false)
const pwdForm = reactive({ phone: '', email: '', newPassword: '', confirmPassword: '' })

const handleChangePwd = async () => {
  pwdMsg.value = ''
  if (!pwdForm.phone.trim()) return (pwdMsg.value = '请输入注册手机号')
  if (!pwdForm.email.trim()) return (pwdMsg.value = '请输入注册邮箱')
  if (!pwdForm.newPassword || pwdForm.newPassword.length < 6) return (pwdMsg.value = '新密码至少6位')
  if (pwdForm.newPassword !== pwdForm.confirmPassword) return (pwdMsg.value = '两次密码不一致')

  pwdLoading.value = true
  try {
    const { data } = await http.put('/auth/change-pwd', {
      phone: pwdForm.phone.trim(),
      email: pwdForm.email.trim(),
      password: pwdForm.newPassword
    })
    if (data.code === 200) {
      pwdOk.value = true
      pwdMsg.value = '密码修改成功'
      setTimeout(() => {
        showPwdModal.value = false
        pwdMsg.value = ''
        pwdOk.value = false
        Object.assign(pwdForm, { phone: '', email: '', newPassword: '', confirmPassword: '' })
      }, 1200)
    } else {
      pwdOk.value = false
      pwdMsg.value = data.message || '修改失败'
    }
  } catch (e) {
    pwdOk.value = false
    pwdMsg.value = e.response?.data?.message || e.message || '网络异常'
  } finally {
    pwdLoading.value = false
  }
}
</script>

<style scoped>
.app-header { position: fixed; top: 0; left: 0; right: 0; z-index: 1000; padding: 16px 0; transition: all var(--transition); }
.app-header.scrolled { background: rgba(255,255,255,0.92); backdrop-filter: blur(16px); box-shadow: var(--shadow-soft); padding: 10px 0; }
.header-inner { display: flex; align-items: center; justify-content: space-between; }

.logo { display: flex; align-items: center; gap: 10px; font-family: var(--font-en); }
.logo-icon { width: 38px; height: 38px; display: flex; align-items: center; justify-content: center; background: var(--color-primary); color: #fff; font-size: 18px; font-weight: 700; border-radius: 4px; }
.logo-text { font-size: 22px; font-weight: 600; color: var(--color-primary); letter-spacing: 2px; }

.nav-links { display: flex; gap: 36px; }
.nav-link { font-size: 14px; letter-spacing: 1px; color: var(--color-text); position: relative; padding-bottom: 4px; transition: color var(--transition); }
.nav-link.router-link-exact-active { color: var(--color-accent); }
.nav-link::after { content: ''; position: absolute; bottom: 0; left: 0; width: 0; height: 1px; background: var(--color-accent); transition: width var(--transition); }
.nav-link:hover::after, .nav-link.router-link-exact-active::after { width: 100%; }
/* 秒杀入口：用暖金强调色做视觉引导，和普通导航区分开 */
.nav-link--seckill { color: #c0392b; font-weight: 600; }
.nav-link--seckill::after { background: #c0392b; }
.nav-link--seckill.router-link-exact-active { color: #c0392b; }

.header-actions { display: flex; align-items: center; gap: 12px; }
.btn-sm { padding: 8px 20px; font-size: 13px; }

	/* 顾客注册按钮白色字体 */
	.header-actions .btn-primary { color: #fff; }

.icon-btn { position: relative; display: flex; align-items: center; padding: 6px; color: var(--color-text); }
.cart-badge { position: absolute; top: -2px; right: -6px; min-width: 18px; height: 18px; display: flex; align-items: center; justify-content: center; background: var(--color-accent); color: #fff; font-size: 11px; border-radius: 9px; padding: 0 4px; }

.user-menu { position: relative; cursor: pointer; }
.user-avatar { width: 34px; height: 34px; display: flex; align-items: center; justify-content: center; background: var(--color-primary); color: #fff; font-size: 14px; font-weight: 600; border-radius: 50%; }
.user-avatar-img { width: 34px; height: 34px; border-radius: 50%; object-fit: cover; border: 2px solid var(--color-accent); }
.dropdown { position: absolute; top: 44px; right: 0; background: #fff; box-shadow: 0 8px 30px rgba(0,0,0,0.12); border-radius: var(--radius); padding: 8px 0; min-width: 150px; }
.dropdown a { display: block; padding: 10px 20px; font-size: 13px; color: var(--color-text); transition: background 0.2s; }
.dropdown a:hover { background: var(--color-bg); }
.dropdown hr { border: none; border-top: 1px solid var(--color-border); margin: 4px 0; }

.btn-merchant { background: var(--color-accent); border-color: var(--color-accent); color: #fff; }
.btn-merchant:hover { background: #d4b87a; border-color: #d4b87a; }

/* 修改密码弹窗 */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 3000; }
.modal-box { background: #fff; border-radius: 12px; padding: 32px; width: 400px; max-width: 90vw; box-shadow: 0 20px 60px rgba(0,0,0,0.3); }
.modal-box h3 { font-size: 18px; margin-bottom: 24px; }
.form-row { margin-bottom: 14px; display: flex; gap: 8px; align-items: center; }
.form-row label { width: 72px; flex-shrink: 0; font-size: 13px; color: #666; }
.form-row input { flex: 1; padding: 10px 12px; border: 1px solid #e8e5dd; border-radius: 6px; font-size: 14px; outline: none; }
.form-row input:focus { border-color: var(--color-accent); }
.pub-ok { color: #27ae60; font-size: 13px; margin-bottom: 12px; }
.pub-err { color: #d44; font-size: 13px; margin-bottom: 12px; }
.form-actions { display: flex; gap: 8px; margin-top: 8px; }
.form-actions .btn-sm { padding: 8px 20px; font-size: 13px; }

	/* 顾客注册按钮白色字体 */
	.header-actions .btn-primary { color: #fff; }

@media (max-width: 768px) { .nav-links { display: none; } }
</style>

<style>
/* 浅色 header 主题（非 scoped — 深色 hero / 登录 / 注册页专用，由 .header-light class 驱动） */
.app-header.header-light { background: transparent !important; box-shadow: none !important; }
.header-light .logo-text,
.header-light .nav-link,
.header-light .icon-btn,
.header-light .btn-outline { color: #fff !important; border-color: rgba(255,255,255,0.3) !important; }
.header-light .btn-outline:hover { border-color: #fff !important; }
.header-light .btn-primary { color: #0f0f1a !important; }
.header-light .btn-merchant { background: var(--color-accent) !important; border-color: var(--color-accent) !important; color: #fff !important; }
.header-light .nav-link::after { background: var(--color-accent); }
.header-light .user-avatar { background: rgba(255,255,255,0.15); }
.header-light .logo-icon { background: var(--color-accent); }
.app-header.header-light.scrolled { background: rgba(26,26,46,0.95) !important; backdrop-filter: blur(16px); box-shadow: 0 2px 20px rgba(0,0,0,0.3) !important; }
</style>
