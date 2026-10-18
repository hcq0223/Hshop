import { reactive, computed } from 'vue'
import http from '../utils/http.js'

/**
 * ShopAi 全局状态管理（零依赖，基于 reactive + localStorage）
 * Token 中包含：userId, phone, role
 * customers.id / merchants.id 需通过 userId 映射获取
 */

const state = reactive({
  token: localStorage.getItem('token') || '',
  user: JSON.parse(localStorage.getItem('user') || 'null'),
  cartCount: 0,
  avatarUrl: localStorage.getItem('avatarUrl') || '',
})

/* ========== 响应式计算属性 (模板中自动解包) ========== */

/** 是否已登录 */
export const isLoggedIn = computed(() => !!state.token)

/** 当前登录用户的 users 表信息（username、phone、email、role 等） */
export const currentUser = computed(() => state.user)

/** 当前角色：customer / merchant / admin */
export const currentRole = computed(() => state.user?.role || '')

/**
 * 当前用户是否为「顾客」。
 * <p>
 * 用途：区分「顾客专属功能」（收货地址、购物车、下单、个人中心）。
 * 这些功能背后的接口（/api/address/**、/api/cart/**、/api/customer/**）
 * 都要把 X-User-Id 映射成 customers.id，而商家/管理员在 customers 表里没有记录，
 * 映射结果为 null —— 因此非顾客进入这些页面必然拿不到数据。
 * <p>
 * 之所以单独暴露这个 computed 而不是在每处写 `currentRole.value === 'customer'`：
 * 登录后「待完善资料」阶段的顾客 role 仍是 customer（只是 status=0），
 * 若将来角色口径变化（比如新增客服角色），只需要改这一处。
 */
export const isCustomer = computed(() => currentRole.value === 'customer')

/** 购物车数量 */
export const cartCount = computed(() => state.cartCount)

/** 当前用户头像 URL（全局共享，Header + ProfileView 联动） */
export const avatarUrl = computed(() => state.avatarUrl)

/* ========== 登录/登出 ========== */

/** 保存登录凭证（token + 用户信息） */
export function setAuth(token, user) {
  state.token = token
  state.user = user
  localStorage.setItem('token', token)
  localStorage.setItem('user', JSON.stringify(user))
}

/** 清除登录凭证 */
export function clearAuth() {
  state.token = ''
  state.user = null
  state.avatarUrl = ''
  state.cartCount = 0
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  localStorage.removeItem('avatarUrl')
}

/** 设置头像 URL（全局） */
export function setAvatarUrl(url) {
  state.avatarUrl = url
  localStorage.setItem('avatarUrl', url)
}

/* ========== 购物车 ========== */

/** 更新购物车角标数量 */
export function setCartCount(n) {
  state.cartCount = n
}

/** 从后端拉取购物车数量并更新角标 */
export async function refreshCartCount() {
  try {
    const { data } = await http.get('/cart/list')
    if (data.code === 200) {
      state.cartCount = (data.data || []).length
    }
  } catch (_) {}
}

/* ========== 商户ID获取 ========== */

/**
 * 从当前登录用户信息中获取 merchantId
 * 注意：如果 user 对象中没有 merchantId，需要从 merchants 表获取
 * 这里先尝试从 user 对象中读取，如果没有则返回 null
 */
export function getMerchantId() {
  return state.user?.merchantId || null
}
