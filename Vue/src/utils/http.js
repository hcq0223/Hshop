import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

/* ==================== 登录失效处理 ==================== */

/** 登录凭证在 localStorage 中的 key（与 stores/auth.js 保持一致） */
const AUTH_STORAGE_KEYS = ['token', 'user', 'avatarUrl']

/** 登录 / 注册接口自身失败时不应触发「清空登录态」（如密码错误） */
const isAuthRequest = (url = '') =>
  url.includes('/auth/login') || url.includes('/auth/register')

let redirecting = false

/**
 * 登录态已失效：清掉本地凭证并回到登录页。
 *
 * 为什么必须做这一步：
 *   stores/auth.js 的 isLoggedIn 是 computed(() => !!state.token)，
 *   而 state.token 初始化自 localStorage —— 也就是说「是否登录」完全由浏览器
 *   本地那份 token 字符串决定，启动时不向服务端校验。token 的过期时间是
 *   JWT 自己的 exp（默认 24h），Redis 里只有退出登录时写入的黑名单。
 *   如果不在收到 401 时清掉本地凭证，就会出现「token 早已过期、页面仍是登录态、
 *   路由守卫照样放行」的假登录状态。
 *
 * 为什么用硬跳转而不是调用 clearAuth()：
 *   http.js 被 stores/auth.js 引用，反向静态 import 会形成循环依赖。
 *   硬跳转同时会把整个 reactive 状态重置，效果与 clearAuth() 一致。
 */
function onUnauthorized() {
  if (redirecting) return
  AUTH_STORAGE_KEYS.forEach(k => localStorage.removeItem(k))

  // 已经停在登录页就不再跳，避免死循环
  const path = window.location.pathname
  if (path.startsWith('/login') || path.startsWith('/admin/login')) return

  redirecting = true
  const loginPath = path.startsWith('/admin') ? '/admin/login' : '/login'
  const redirect = encodeURIComponent(path + window.location.search)
  window.location.replace(`${loginPath}?redirect=${redirect}`)
}

/* ==================== 拦截器 ==================== */

// 请求拦截器 — 自动携带 Token
http.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = token
  }
  return config
}, error => Promise.reject(error))

// 响应拦截器 — 统一错误处理 + 登录失效自愈
http.interceptors.response.use(
  response => {
    // 业务层 401：网关或后端以 HTTP 200 + code=401 表达「请先登录」
    // （例如订单列表接口在身份解析不出来时返回 Result.error(401, "请先登录")）
    const body = response.data
    if (body && body.code === 401 && !isAuthRequest(response.config?.url)) {
      onUnauthorized()
    }
    return response
  },
  error => {
    if (error.response) {
      const { status, data, config } = error.response
      // HTTP 401：网关拦截（Token 过期 / 无效 / 已进黑名单）
      if (status === 401 && !isAuthRequest(config?.url)) {
        onUnauthorized()
      }
      if (status === 401 && data && data.message) {
        return Promise.reject(new Error(data.message))
      }
      if (status === 403 && data && data.message) {
        return Promise.reject(new Error(data.message))
      }
    }
    return Promise.reject(error)
  }
)

export default http
