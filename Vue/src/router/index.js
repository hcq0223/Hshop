import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn, currentRole } from '../stores/auth.js'
import http from '../utils/http.js'

/*
 * 路由设计：
 * 1. 公开页面（无需登录）：
 *    /               首页
 *    /products       商品列表
 *    /product/:id    商品详情
 *    /login          顾客/商户登录（?admin=1 即管理员登录模式）
 *    /register       注册（?role=customer 或 ?role=merchant）
 *
 * 2. 顾客页面（需要 customer 角色）：
 *    /cart           购物车
 *    /orders         我的订单
 *    /order/:id      订单详情
 *    /order-create   确认订单
 *    /profile        个人中心（地址、信息编辑）
 *
 * 3. 商户页面（需要 merchant 角色且 is_verified=1）：
 *    /merchant       商户后台（商品管理、订单管理、发布商品）
 *
 * 4. 管理员页面（需要 admin 角色）：
 *    /admin          管理后台（用户管理、分类管理、商品审核、订单管理）
 */

/* ---- 懒加载 ---- */
const HomeView          = () => import('../views/HomeView.vue')
const ProductList       = () => import('../views/ProductList.vue')
const ProductDetail     = () => import('../views/ProductDetail.vue')
const LoginView         = () => import('../views/LoginView.vue')
const RegisterView      = () => import('../views/RegisterView.vue')
const CartView          = () => import('../views/CartView.vue')
const OrderList         = () => import('../views/OrderList.vue')
const OrderDetail       = () => import('../views/OrderDetail.vue')
const OrderCreate       = () => import('../views/OrderCreate.vue')
const ProfileView       = () => import('../views/ProfileView.vue')
const MerchantDashboard = () => import('../views/MerchantDashboard.vue')
const SkuManagement     = () => import('../views/SkuManagement.vue')
const AdminLoginView    = () => import('../views/AdminLoginView.vue')
const AdminDashboard    = () => import('../views/AdminDashboard.vue')

const CustomerInfoView  = () => import('../views/CustomerInfoView.vue')
const MerchantInfoView  = () => import('../views/MerchantInfoView.vue')
const MerchantRealNameView = () => import('../views/MerchantRealNameView.vue')
const MerchantProfileView = () => import('../views/MerchantProfileView.vue')
const AllMerchants       = () => import('../views/AllMerchants.vue')
const MerchantShop      = () => import('../views/MerchantShop.vue')
const OrderHistoryView  = () => import('../views/OrderHistory.vue')
const AiAssistantView   = () => import('../views/AiAssistant.vue')
const SeckillList       = () => import('../views/SeckillList.vue')
const SeckillDetail     = () => import('../views/SeckillDetail.vue')
const MySeckill         = () => import('../views/MySeckill.vue')
const MerchantSeckill   = () => import('../views/MerchantSeckill.vue')

const routes = [
  /* ==================== 公开 ==================== */
  { path: '/',              name: 'Home',          component: HomeView,       meta: { title: 'ShopAi · 品质服装商城' } },
  { path: '/products',      name: 'ProductList',   component: ProductList,    meta: { title: '全部商品' } },
  { path: '/product/:id',   name: 'ProductDetail', component: ProductDetail,  meta: { title: '商品详情' } },
  { path: '/login',         name: 'Login',         component: LoginView,      meta: { title: '登录', guest: true } },
  { path: '/register',      name: 'Register',      component: RegisterView,   meta: { title: '注册', guest: true } },

  /* ==================== 管理员独立登录 ==================== */
  { path: '/admin/login',   name: 'AdminLogin',    component: AdminLoginView, meta: { title: '管理员登录', guest: true } },

  /* ==================== 顾客（需 customer 角色） ==================== */
  /*
   * 为什么加 roles: ['customer']：
   *   这些页面背后的接口（/api/address/**、/api/cart/**、/api/customer/**、
   *   /api/order/customer/**）都要把网关注入的 X-User-Id 映射为 customers.id，
   *   而商家/管理员在 customers 表里没有记录 → 映射结果为 null → 后续查询
   *   要么返回空、要么直接报错。原先只校验 requiresAuth，
   *   导致商家可以从「秒杀详情 → 管理地址」这类入口进到顾客端个人中心，
   *   进去后接口全部失败、页面空白。这里在路由层直接拦住。
   *
   * 例外：/order/:id 保留三端可访问 —— 订单详情页是共用的，
   *   商家/管理员后台也需要跳进来查看（页面内部按角色渲染不同操作按钮）。
   */
  { path: '/complete-profile', name: 'CompleteProfile', component: CustomerInfoView, meta: { title: '完善信息', requiresAuth: true, roles: ['customer'] } },
  { path: '/cart',          name: 'Cart',          component: CartView,       meta: { title: '购物车',     requiresAuth: true, roles: ['customer'] } },
  { path: '/orders',        name: 'OrderList',     component: OrderList,      meta: { title: '我的订单',   requiresAuth: true, roles: ['customer'] } },
  { path: '/order/:id',     name: 'OrderDetail',   component: OrderDetail,    meta: { title: '订单详情',   requiresAuth: true, roles: ['customer', 'merchant', 'admin'] } },
  { path: '/order-create',  name: 'OrderCreate',   component: OrderCreate,    meta: { title: '确认订单',   requiresAuth: true, roles: ['customer'] } },
  { path: '/profile',       name: 'Profile',       component: ProfileView,    meta: { title: '个人中心',   requiresAuth: true, roles: ['customer'] } },

  { path: '/order-history', name: 'OrderHistory', component: OrderHistoryView, meta: { title: '订单历史', requiresAuth: true, roles: ['customer'] } },
  { path: '/assistant',   name: 'AiAssistant',   component: AiAssistantView,  meta: { title: '智能助手', requiresAuth: true } },

  /* ==================== 秒杀 ==================== */
  /* 列表与详情公开（游客可浏览活动，下单时才要求登录） */
  { path: '/seckill',         name: 'SeckillList',   component: SeckillList,    meta: { title: '限时秒杀' } },
  { path: '/seckill/:id',     name: 'SeckillDetail', component: SeckillDetail,  meta: { title: '秒杀详情' } },
  /* 我的秒杀记录（顾客专属：记录里的 userId/customerId 都是顾客维度） */
  { path: '/my-seckill',      name: 'MySeckill',     component: MySeckill,      meta: { title: '我的秒杀', requiresAuth: true, roles: ['customer'] } },

  /* ==================== 商户（需 merchant 或 admin 角色） ==================== */
  { path: '/merchant/order-history', name: 'MerchantOrderHistory', component: OrderHistoryView, meta: { title: '订单历史', requiresAuth: true, roles: ['merchant', 'admin'] } },
  /*
   * 入驻资料 / 实名认证：必须限制为 merchant。
   *
   * 这两页是「商家入驻」流程的前两步，正常入口只有两个：
   *   1) 注册页选择「商家」→ role=merchant → 本页 → /merchant-real-name
   *   2) 商家登录且后端返回 needsInfo=true → 本页
   * 也就是说访问者一定是 role=merchant 的用户。
   *
   * 原实现只写了 requiresAuth，任何登录用户都能直接输入 URL 打开入驻页；
   * 而页面提交的 POST /api/merchant/apply 在网关的商家自助清单里、要求 merchant 角色，
   * 于是顾客进来填完一整套资料、点提交才拿到 403「仅商家可操作」—— 白填一遍。
   * 这里按角色直接挡在路由层，与后端鉴权口径保持一致（顾客就是顾客，商户就是商户）。
   */
  { path: '/merchant-info',  name: 'MerchantInfo',  component: MerchantInfoView,  meta: { title: '商家入驻', requiresAuth: true, roles: ['merchant'] } },
  { path: '/merchant-real-name', name: 'MerchantRealName', component: MerchantRealNameView, meta: { title: '实名认证', requiresAuth: true, roles: ['merchant'] } },
  { path: '/merchant',       name: 'Merchant',      component: MerchantDashboard, meta: { title: '商家后台', requiresAuth: true, roles: ['merchant', 'admin'] } },
  { path: '/merchant/sku/:productId', name: 'SkuManagement', component: SkuManagement, meta: { title: '规格管理', requiresAuth: true, roles: ['merchant', 'admin'] } },
  { path: '/merchant/seckill', name: 'MerchantSeckill', component: MerchantSeckill, meta: { title: '秒杀活动', requiresAuth: true, roles: ['merchant', 'admin'] } },
  { path: '/merchant/profile', name: 'MerchantProfile', component: MerchantProfileView, meta: { title: '店铺信息', requiresAuth: true, roles: ['merchant'] } },
  { path: '/shop',        name: 'MerchantShop',   component: MerchantShop,      meta: { title: '店铺',     requiresAuth: true, roles: ['merchant'] } },
  { path: '/shop/:merchantId', name: 'MerchantShopPublic', component: MerchantShop, meta: { title: '店铺' } },
  { path: '/merchants',   name: 'AllMerchants',  component: AllMerchants,     meta: { title: '全部商家' } },

  /* ==================== 管理员（需 admin 角色） ==================== */
  { path: '/admin',         name: 'Admin',         component: AdminDashboard, meta: { title: '管理后台', requiresAuth: true, roles: ['admin'] } },
  { path: '/admin/order-history', name: 'AdminOrderHistory', component: OrderHistoryView, meta: { title: '订单历史', requiresAuth: true, roles: ['admin'] } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() { return { top: 0 } },
})

/* ==================== 全局路由守卫 ==================== */
router.beforeEach((to, from, next) => {
  const role = currentRole.value
  const loggedIn = isLoggedIn.value

  /* 1. 需要登录的页面 → 未登录跳转到相应登录页 */
  if (to.meta.requiresAuth && !loggedIn) {
    // 管理员页面跳管理员登录，其余跳普通登录
    const loginRoute = to.path.startsWith('/admin') ? '/admin/login' : '/login'
    return next({ path: loginRoute, query: { redirect: to.fullPath } })
  }

  /* 2. 登录页、注册页 — 已登录用户直接跳走 */
  if (to.meta.guest && loggedIn) {
    if (role === 'admin') return next('/admin')
    if (role === 'merchant') return next('/shop')
    return next('/')
  }

  /* 3. 角色权限检查 */
  if (to.meta.roles && to.meta.roles.length > 0) {
    if (!to.meta.roles.includes(role)) {
      // 管理员误入商户页 → 仍然放行（roles 里 admin 也在 merchant 页面白名单里）
      return next('/')
    }
  }

  /* 4. 商户后台访问前校验 is_verified=1（管理员跳过此校验） */
  if ((to.path.startsWith('/merchant') || to.path.startsWith('/shop')) && role === 'merchant') {
    // 完善信息页不需要校验（商户还没提交资料，当然未认证）
    const isInfoPage = ['/merchant-info', '/merchant-real-name'].includes(to.path)
    if (!isInfoPage) {
      checkMerchantVerified().then(verified => {
        if (verified) {
          next()
        } else {
          alert('管理员正在审核中，审核通过后方可使用商家功能')
          next(false)
        }
      })
      return
    }
  }

  next()
})

/**
 * 调用后端接口检查商家 is_verified 是否为 1
 * @returns {Promise<boolean>} true=已认证, false=审核中/未认证
 */
async function checkMerchantVerified() {
  try {
    const { data } = await http.get('/merchant/me/check-status')
    if (data.code === 200) {
      const payload = data.data
      return payload && payload.isVerified === 1
    }
    return false
  } catch (_) {
    return false
  }
}

/* 页面标题 */
router.afterEach(to => {
  document.title = (to.meta.title || 'ShopAi') + ' - ShopAi'
})

export default router
