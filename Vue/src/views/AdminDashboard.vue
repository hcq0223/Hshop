<template>
  <div class="admin-page">
    <!-- 侧边栏 -->
    <aside class="admin-sidebar">
      <div class="sidebar-brand">
        <router-link to="/admin" class="sidebar-logo">S</router-link>
        <span>ShopAi Admin</span>
      </div>
      <nav class="sidebar-nav">
        <a :class="{ active: tab === 'dashboard' }" @click="tab = 'dashboard'">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>
          仪表盘
        </a>
        <a :class="{ active: tab === 'users' }" @click="tab = 'users'">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
          用户管理
        </a>
        <a :class="{ active: tab === 'categories' }" @click="tab = 'categories'">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
          分类管理
        </a>

        <!-- 商家管理（可展开） -->
        <div class="nav-group" :class="{ open: navGroupOpen === 'merchant' }">
          <a class="nav-group-toggle" @click="navGroupOpen = navGroupOpen === 'merchant' ? '' : 'merchant'">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="7" width="20" height="14" rx="2"/><path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/></svg>
            商家管理
            <span class="nav-arrow" :class="{ rotated: navGroupOpen === 'merchant' }">▾</span>
          </a>
          <div class="nav-sub" v-show="navGroupOpen === 'merchant'">
            <a :class="{ active: subTab === 'products' }" @click="subTab = 'products'; tab = 'merchant-group'">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z"/><line x1="3" y1="6" x2="21" y2="6"/><path d="M16 10a4 4 0 0 1-8 0"/></svg>
              商品审核
            </a>
            <a :class="{ active: subTab === 'merchants' }" @click="subTab = 'merchants'; tab = 'merchant-group'">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/></svg>
              商家入驻审核
            </a>
            <a :class="{ active: subTab === 'withdraws' }" @click="subTab = 'withdraws'; tab = 'merchant-group'">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
              提现审核
            </a>
          </div>
        </div>

        <a :class="{ active: tab === 'orders' }" @click="tab = 'orders'">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
          订单管理
        </a>
        <router-link to="/admin/order-history" :class="{ active: tab === 'history' }">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
          订单历史
        </router-link>
        <a :class="{ active: tab === 'reviews' }" @click="tab = 'reviews'">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          评论管理
        </a>
        <a :class="{ active: tab === 'complaints' }" @click="tab = 'complaints'">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          评论申诉
        </a>
        <a :class="{ active: tab === 'seckill' }" @click="tab = 'seckill'">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
          秒杀监管
        </a>
      </nav>
      <div class="sidebar-footer">
        <router-link to="/" class="back-link">← 返回商城</router-link>
      </div>
    </aside>

    <!-- 主内容 -->
    <main class="admin-main">
      <header class="admin-topbar">
        <h2>{{ tabTitle }}</h2>
        <div class="topbar-user">
          <span>{{ user?.username || 'Admin' }}</span>
          <a href="#" @click.prevent="doLogout">退出</a>
        </div>
      </header>

      <div class="admin-content">

        <!-- ========== 仪表盘 ========== -->
        <template v-if="tab === 'dashboard'">
          <!-- 统计卡片 -->
          <div class="stats-grid">
            <div class="stat-card"><span class="stat-num">{{ ds.totalUsers || 0 }}</span><span class="stat-label">总用户</span></div>
            <div class="stat-card"><span class="stat-num">{{ ds.totalMerchants || 0 }}</span><span class="stat-label">商家</span></div>
            <div class="stat-card"><span class="stat-num">{{ ds.totalProducts || 0 }}</span><span class="stat-label">商品</span></div>
            <div class="stat-card"><span class="stat-num">{{ ds.totalOrders || 0 }}</span><span class="stat-label">订单</span></div>
          </div>

          <!-- 图表行 1：下单趋势 + 状态分布 -->
          <div class="charts-row">
            <div class="chart-card">
              <h4 class="chart-title">近7天下单趋势</h4>
              <div class="chart-wrap"><canvas id="chartDailyOrders"></canvas></div>
            </div>
            <div class="chart-card">
              <h4 class="chart-title">订单状态分布</h4>
              <div class="chart-wrap"><canvas id="chartStatusDist"></canvas></div>
            </div>
          </div>

          <!-- 图表行 2：新增用户 + 营收趋势 -->
          <div class="charts-row">
            <div class="chart-card">
              <h4 class="chart-title">近7天新增用户</h4>
              <div class="chart-wrap"><canvas id="chartDailyUsers"></canvas></div>
            </div>
            <div class="chart-card">
              <h4 class="chart-title">近7天营收趋势</h4>
              <div class="chart-wrap"><canvas id="chartDailyRevenue"></canvas></div>
            </div>
          </div>

          <!-- 图表行 3：商家销售额排行 + 角色分布 -->
          <div class="charts-row">
            <div class="chart-card">
              <h4 class="chart-title">Top 5 商家销售额</h4>
              <div class="chart-wrap"><canvas id="chartTopMerchants"></canvas></div>
            </div>
            <div class="chart-card">
              <h4 class="chart-title">角色分布</h4>
              <div class="chart-wrap chart-wrap-sm"><canvas id="chartRoleDist"></canvas></div>
            </div>
          </div>

          <!-- 图表行 4：Bottom 3 商家销售额 -->
          <div class="charts-row">
            <div class="chart-card">
              <h4 class="chart-title">Bottom 3 商家销售额</h4>
              <div class="chart-wrap"><canvas id="chartBottomMerchants"></canvas></div>
            </div>
          </div>
        </template>

        <!-- ========== 用户管理 ========== -->
        <template v-if="tab === 'users'">
          <div class="section-bar">
            <input v-model="userKeyword" placeholder="搜索用户..." @keyup.enter="fetchUsers" />
            <button class="btn-sm primary" @click="fetchUsers">搜索</button>
          </div>
          <table class="data-table" v-if="users.length">
            <thead><tr><th>ID</th><th>用户名</th><th>角色</th><th>手机号</th><th>状态</th><th>注册时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="u in users" :key="u.id">
                <td>{{ u.id }}</td>
                <td>{{ u.username }}</td>
                <td><span class="role-tag" :class="'role-' + u.role">{{ roleLabel(u.role) }}</span></td>
                <td>{{ u.phone || '-' }}</td>
                <td><span :class="u.status === 1 ? 'tag-on' : 'tag-off'">{{ u.status === 1 ? '正常' : '禁用' }}</span></td>
                <td class="td-date">{{ formatTime(u.createTime) }}</td>
                <td class="td-actions">
                  <button class="btn-sm outline" @click="editUser(u)">编辑</button>
                  <button class="btn-sm danger" @click="deleteUser(u.id)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty">暂无用户</p>

          <div class="pagination" v-if="userTotal > userSize">
            <button :disabled="userPage <= 1" @click="userPage--; fetchUsers()">上一页</button>
            <span>{{ userPage }} / {{ Math.ceil(userTotal / userSize) }}</span>
            <button :disabled="userPage >= Math.ceil(userTotal / userSize)" @click="userPage++; fetchUsers()">下一页</button>
          </div>
        </template>

        <!-- ========== 分类管理 ========== -->
        <template v-if="tab === 'categories'">
          <div class="section-bar">
            <button class="btn-sm primary" @click="openCatForm()">+ 新增分类</button>
          </div>
          <!-- 折叠面板式树形 -->
          <div class="cat-panels" v-if="topCats.length">
            <div class="cat-panel" v-for="top in topCats" :key="top.id">
              <div class="cat-panel-header" @click="toggleCatExpand(top.id)">
                <span class="cat-expand-icon">{{ expandedCats[top.id] ? '▼' : '▶' }}</span>
                <span class="cat-panel-name">{{ top.name }}</span>
                <span class="cat-panel-meta">ID: {{ top.id }} | 排序: {{ top.sortOrder || 0 }}</span>
                <div class="cat-panel-actions">
                  <button class="btn-sm outline" @click.stop="openCatForm(top)">编辑</button>
                  <button class="btn-sm danger" @click.stop="deleteCategory(top.id)">删除</button>
                </div>
              </div>
              <!-- 子分类列表 -->
              <div class="cat-panel-body" v-show="expandedCats[top.id]">
                <div class="cat-sub-item" v-for="sub in getSubCats(top.id)" :key="sub.id">
                  <span class="sub-indent">└ {{ sub.name }}</span>
                  <span class="cat-sub-meta">ID: {{ sub.id }} | 排序: {{ sub.sortOrder || 0 }}</span>
                  <div class="cat-sub-actions">
                    <button class="btn-sm outline" @click="openCatForm(sub)">编辑</button>
                    <button class="btn-sm danger" @click="deleteCategory(sub.id)">删除</button>
                  </div>
                </div>
                <p class="cat-empty-sub" v-if="getSubCats(top.id).length === 0">暂无子分类</p>
              </div>
            </div>
          </div>
          <p v-else class="empty">暂无分类，请先新增顶级分类</p>
        </template>

        <!-- ========== 商品审核 ========== -->
        <template v-if="tab === 'merchant-group' && subTab === 'products'">
          <div class="search-bar">
            <input type="text" v-model="productSearchName" placeholder="商品名称..." style="width:180px;padding:8px 12px;border:1px solid #e8e5dd;border-radius:6px;font-size:14px;" @input="applyProductFilters" />
            <input type="text" v-model="productSearchMerchant" placeholder="商家名称..." style="width:180px;padding:8px 12px;border:1px solid #e8e5dd;border-radius:6px;font-size:14px;margin-left:8px;" @input="applyProductFilters" />
            <button class="btn-sm outline" @click="clearProductFilters" style="margin-left:8px;" v-if="productSearchName || productSearchMerchant">清除</button>
          </div>
          <table class="data-table" v-if="filteredProducts.length">
            <thead><tr><th>图片</th><th>名称</th><th>商家</th><th>价格</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="p in filteredProducts" :key="p.id">
                <td><img :src="p.mainImage || '/images/products/OIP-C.webp'" class="table-img" /></td>
                <td>{{ p.name }}</td>
                <td>{{ p.shopName || '-' }}</td>
                <td>¥{{ p.priceMin }}</td>
                <td><span :class="p.status === 'on_sale' ? 'tag-on' : 'tag-off'">{{ statusLabel(p.status) }}</span></td>
                <td class="td-actions">
                  <button v-if="p.status !== 'on_sale'" class="btn-sm primary" @click="approveProduct(p.id)">上架</button>
                  <button v-else class="btn-sm outline" @click="toggleProduct(p.id, 'off_sale')">下架</button>
                  <button class="btn-sm danger" @click="deleteProduct(p.id)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty">{{ productSearchName || productSearchMerchant ? '无匹配商品' : '暂无商品' }}</p>
        </template>

        <!-- ========== 商家入驻审核 ========== -->
        <template v-if="tab === 'merchant-group' && subTab === 'merchants'">
          <!-- 新入驻审核 -->
          <h4 class="section-label">新入驻申请</h4>
          <table class="data-table" v-if="pendingMerchants.length">
            <thead><tr><th>商户ID</th><th>用户ID</th><th>店铺名称</th><th>联系人</th><th>电话</th><th>实名姓名</th><th>身份证号</th><th>身份证</th><th>营业执照</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="m in pendingMerchants" :key="m.id">
                <td>{{ m.id }}</td>
                <td>{{ m.userId }}</td>
                <td>{{ m.shopName }}</td>
                <td>{{ m.contactName }}</td>
                <td>{{ m.contactPhone }}</td>
                <td>{{ m.realName ? m.realName.realName : '-' }}</td>
                <td>{{ m.realName ? m.realName.cardId : '-' }}</td>
                <td>
                  <template v-if="m.realName && m.realName.cardUrl">
                    <a :href="m.realName.cardUrl" target="_blank" class="link-sm">查看</a>
                  </template>
                  <span v-else class="text-muted">-</span>
                </td>
                <td>
                  <template v-if="m.businessLicense">
                    <a :href="m.businessLicense" target="_blank" class="link-sm">查看</a>
                  </template>
                  <span v-else class="text-muted">-</span>
                </td>
                <td class="td-actions">
                  <button class="btn-sm primary" @click="verifyMerchant(m.id, 1)">同意入驻</button>
                  <button class="btn-sm danger" @click="verifyMerchant(m.id, 0)">拒绝入驻</button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty">暂无待审核商家</p>

          <div class="pagination" v-if="merchantTotal > merchantSize">
            <button :disabled="merchantPage <= 1" @click="merchantPage--; fetchPendingMerchants()">上一页</button>
            <span>{{ merchantPage }} / {{ Math.ceil(merchantTotal / merchantSize) }}</span>
            <button :disabled="merchantPage >= Math.ceil(merchantTotal / merchantSize)" @click="merchantPage++; fetchPendingMerchants()">下一页</button>
          </div>

          <!-- 修改审核 -->
          <h4 class="section-label" style="margin-top:40px;">信息修改申请</h4>
          <table class="data-table" v-if="merchantMods.length">
            <thead><tr><th>ID</th><th>商户ID</th><th>店铺名称</th><th>类型</th><th>修改内容</th><th>提交时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="mod in merchantMods" :key="mod.id">
                <td>{{ mod.id }}</td>
                <td>{{ mod.merchantId }}</td>
                <td>{{ mod.shopName }}</td>
                <td><span class="mod-type-tag" :class="'mt-' + mod.type">{{ modTypeLabel(mod.type) }}</span></td>
                <td class="td-change-detail">
                  <template v-for="(val, key) in parseChange(mod.changeDetail)" :key="key">
                    <div class="change-line" v-if="isImageField(key) || key === 'cardUrl'">
                      <span class="change-key">{{ fieldLabel(key) }}</span>
                      <div class="change-img-wrap">
                        <img :src="val" class="change-img" @click="previewImage = val" />
                        <a :href="val" target="_blank" class="link-xs">查看原图</a>
                      </div>
                    </div>
                    <div v-else class="change-line">
                      <span class="change-key">{{ fieldLabel(key) }}</span>
                      <span v-if="isLongText(val)" class="change-long">
                        <span class="change-val">{{ expanded[mod.id + '_' + key] ? val : truncate(val) }}</span>
                        <a class="link-xs"
                          @click="expanded[mod.id + '_' + key] = !expanded[mod.id + '_' + key]">
                          {{ expanded[mod.id + '_' + key] ? '收起' : '展开' }}
                        </a>
                      </span>
                      <span v-else>→ <span class="change-val">{{ val }}</span></span>
                    </div>
                  </template>
                </td>
                <td class="td-date">{{ formatTime(mod.createdAt) }}</td>
                <td class="td-actions">
                  <button class="btn-sm primary" @click="approveMod(mod.id)">同意修改</button>
                  <button class="btn-sm danger" @click="rejectMod(mod.id)">拒绝修改</button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty">暂无修改申请</p>
        </template>

        <!-- ========== 提现审核 ========== -->
        <template v-if="tab === 'merchant-group' && subTab === 'withdraws'">
          <h4 class="section-label">待处理提现</h4>
          <table class="data-table" v-if="pendingWithdraws.length">
            <thead><tr><th>商户ID</th><th>提现金额</th><th>备注</th><th>申请时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="w in pendingWithdraws" :key="w.id">
                <td>{{ w.merchantId }}</td>
                <td class="td-price">¥{{ w.amount }}</td>
                <td>{{ w.remark || '-' }}</td>
                <td class="td-date">{{ formatTime(w.createdAt) }}</td>
                <td class="td-actions">
                  <button class="btn-sm primary" @click="handleWithdraw(w.id, 'approved')">通过</button>
                  <button class="btn-sm danger" @click="handleWithdraw(w.id, 'rejected')">拒绝</button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty">暂无待处理提现</p>
        </template>

        <!-- ========== 订单管理 ========== -->
        <template v-if="tab === 'orders'">
          <div class="status-tabs">
            <button :class="{ active: orderStatus === '' }" @click="orderStatus = ''; fetchOrders()">全部</button>
            <button :class="{ active: orderStatus === 'pending_pay' }" @click="orderStatus = 'pending_pay'; fetchOrders()">待付款</button>
            <button :class="{ active: orderStatus === 'paid' }" @click="orderStatus = 'paid'; fetchOrders()">待发货</button>
            <button :class="{ active: orderStatus === 'shipped' }" @click="orderStatus = 'shipped'; fetchOrders()">待收货</button>
            <button :class="{ active: orderStatus === 'received' }" @click="orderStatus = 'received'; fetchOrders()">已收货</button>
            <button :class="{ active: orderStatus === 'refunding' }" @click="orderStatus = 'refunding'; fetchOrders()">退款中</button>
          </div>
          <table class="data-table" v-if="allOrders.length">
            <thead><tr><th>订单号</th><th>金额</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="o in allOrders" :key="o.id">
                <td>{{ o.orderNo }}</td>
                <td>¥{{ o.totalAmount }}</td>
                <td>{{ orderStatusLabel(o.status) }}</td>
                <td class="td-date">{{ formatTime(o.createTime) }}</td>
                <td class="td-actions">
                  <button class="btn-sm outline" @click="$router.push(`/order/${o.id}`)">查看详情</button>
                  <button v-if="o.status !== 'completed' && o.status !== 'cancelled'" class="btn-sm danger"
                          :disabled="isOrderActing(o.id)" @click="cancelOrder(o.id)">
                    {{ isOrderActing(o.id) ? '处理中…' : '强制取消' }}
                  </button>
                  <button v-if="o.status === 'refunding'" class="btn-sm primary"
                          :disabled="isOrderActing(o.id)" @click="forceRefundComplete(o.id)">
                    {{ isOrderActing(o.id) ? '处理中…' : '强制退款完成' }}
                  </button>
                  <button v-if="o.status !== 'completed' && o.status !== 'cancelled'" class="btn-sm primary"
                          :disabled="isOrderActing(o.id)" @click="forceComplete(o.id)">
                    {{ isOrderActing(o.id) ? '处理中…' : '强制完成' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty">暂无订单</p>
        </template>

        <!-- ========== 订单历史 ========== -->
        <!-- 已移至独立页面：/admin/order-history -->

        <!-- ========== 评论管理 ========== -->
        <template v-if="tab === 'reviews'">
          <div class="search-bar">
            <input type="text" v-model="reviewSearchId" placeholder="输入评论ID搜索..." style="width:260px;padding:8px 12px;border:1px solid #e8e5dd;border-radius:6px;font-size:14px;" @keyup.enter="searchReviewById" />
            <button class="btn-sm primary" @click="searchReviewById" style="margin-left:8px;">搜索</button>
            <button class="btn-sm outline" @click="clearReviewSearch" style="margin-left:8px;" v-if="reviewSearchResults !== null">清除</button>
          </div>
          <table class="data-table" v-if="displayedReviews.length">
            <thead><tr><th>评论ID</th><th>用户</th><th>商品名称</th><th>商家名称</th><th>评分</th><th>内容</th><th>时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="r in displayedReviews" :key="r.id">
                <td>{{ r.id }}</td>
                <td>{{ r.customer?.nickname || '匿名用户' }}</td>
                <td>{{ r.productName || '-' }}</td>
                <td>{{ r.merchantName || '-' }}</td>
                <td>{{ '★'.repeat(r.rating) }}{{ '☆'.repeat(5 - r.rating) }}</td>
                <td class="td-content">{{ r.content || '-' }}</td>
                <td class="td-date">{{ formatTime(r.createTime) }}</td>
                <td class="td-actions">
                  <button class="btn-sm danger" @click="deleteAdminReview(r.id)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty">{{ reviewSearchResults !== null ? '未找到该评论' : '暂无评论' }}</p>

          <!-- 分页（仅非搜索模式显示） -->
          <div class="pagination" v-if="!reviewSearchId && reviewTotalPages > 1">
            <button :disabled="reviewPage <= 1" @click="reviewPage--; fetchAdminReviews()">上一页</button>
            <span>{{ reviewPage }} / {{ reviewTotalPages }}</span>
            <button :disabled="reviewPage >= reviewTotalPages" @click="reviewPage++; fetchAdminReviews()">下一页</button>
          </div>
        </template>

        <!-- ========== 评论申诉 ========== -->
        <template v-if="tab === 'complaints'">
          <div class="status-tabs">
            <button :class="{ active: complaintStatus === '' }" @click="complaintStatus = ''; fetchComplaints()">全部</button>
            <button :class="{ active: complaintStatus === 'pending' }" @click="complaintStatus = 'pending'; fetchComplaints()">待处理</button>
            <button :class="{ active: complaintStatus === 'rejected' }" @click="complaintStatus = 'rejected'; fetchComplaints()">已驳回</button>
            <button :class="{ active: complaintStatus === 'approved' }" @click="complaintStatus = 'approved'; fetchComplaints()">已通过</button>
          </div>
          <table class="data-table" v-if="adminComplaints.length">
            <thead><tr><th>评论ID</th><th>商家名称</th><th>申诉理由</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="c in adminComplaints" :key="c.id">
                <td>{{ c.reviewId }}</td>
                <td>{{ c.merchantName || '-' }}</td>
                <td class="td-content">{{ c.reason }}</td>
                <td>{{ complaintStatusLabel(c.status) }}</td>
                <td class="td-date">{{ formatTime(c.createTime) }}</td>
                <td class="td-actions" v-if="c.status === 'pending'">
                  <button class="btn-sm primary" @click="approveComplaint(c)">通过(删评论)</button>
                  <button class="btn-sm danger" @click="rejectComplaint(c)">驳回</button>
                </td>
                <td class="td-actions" v-else>
                  <span v-if="c.adminNote" class="admin-note-hint">备注: {{ c.adminNote }}</span>
                  <span v-else>-</span>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty">暂无申诉</p>

          <!-- 驳回弹窗 -->
          <div class="modal-overlay" v-if="rejectTarget" @click.self="rejectTarget = null">
            <div class="modal-box">
              <h3>驳回申诉</h3>
              <div class="form-row"><label>备注</label><textarea v-model="rejectNote" rows="3" placeholder="驳回理由..."></textarea></div>
              <div class="form-actions">
                <button class="btn-sm primary" @click="confirmReject">确认驳回</button>
                <button class="btn-sm outline" @click="rejectTarget = null">取消</button>
              </div>
            </div>
          </div>

          <!-- 通过确认 -->
          <div class="modal-overlay" v-if="approveTarget" @click.self="approveTarget = null">
            <div class="modal-box">
              <h3>确认通过申诉</h3>
              <p style="margin-bottom:16px;color:#666;">通过后将删除该评论，确定吗？</p>
              <div class="form-row"><label>备注</label><input v-model="approveNote" placeholder="操作备注..." /></div>
              <div class="form-actions">
                <button class="btn-sm danger" @click="confirmApprove">确认删除评论</button>
                <button class="btn-sm outline" @click="approveTarget = null">取消</button>
              </div>
            </div>
          </div>
        </template>

        <!-- ========== 秒杀监管 ========== -->
        <template v-if="tab === 'seckill'">
          <!-- 子标签 -->
          <div class="status-tabs">
            <button :class="{ active: seckillSub === 'activities' }" @click="seckillSub = 'activities'; fetchSeckillActivities()">活动监管</button>
            <button :class="{ active: seckillSub === 'records' }" @click="seckillSub = 'records'; fetchSeckillRecords()">抢购记录</button>
            <button :class="{ active: seckillSub === 'messages' }" @click="seckillSub = 'messages'; fetchSeckillMessages()">异步消息</button>
          </div>

          <!-- 活动监管 -->
          <template v-if="seckillSub === 'activities'">
            <div class="status-tabs">
              <button :class="{ active: seckillStatus === '' }" @click="seckillStatus = ''; fetchSeckillActivities()">全部</button>
              <button :class="{ active: seckillStatus === 'pending' }" @click="seckillStatus = 'pending'; fetchSeckillActivities()">待开始</button>
              <button :class="{ active: seckillStatus === 'running' }" @click="seckillStatus = 'running'; fetchSeckillActivities()">进行中</button>
              <button :class="{ active: seckillStatus === 'ended' }" @click="seckillStatus = 'ended'; fetchSeckillActivities()">已结束</button>
              <button :class="{ active: seckillStatus === 'offline' }" @click="seckillStatus = 'offline'; fetchSeckillActivities()">已下线</button>
            </div>
            <table class="data-table" v-if="seckillActivities.length">
              <thead>
                <tr>
                  <th>活动名称</th>
                  <th>商家ID</th>
                  <th>时间</th>
                  <th>状态</th>
                  <th>总库存</th>
                  <th>已售</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="a in seckillActivities" :key="a.id">
                  <td>{{ a.title }}</td>
                  <td class="td-mono">{{ a.merchantId }}</td>
                  <td class="td-date">{{ formatTime(a.startTime) }}<br />{{ formatTime(a.endTime) }}</td>
                  <td>
                    <span class="seckill-status" :class="a.status">{{ seckillStatusLabel(a.status) }}</span>
                  </td>
                  <td>{{ a.totalStock || 0 }}</td>
                  <td>{{ a.soldCount || 0 }}</td>
                  <td class="td-actions">
                    <!-- 强制下线：处理违规活动，可下线任意商家的活动 -->
                    <button
                      v-if="a.status === 'pending' || a.status === 'running'"
                      class="btn-sm danger"
                      @click="forceOfflineSeckill(a)"
                    >强制下线</button>
                    <span v-else>-</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <p v-else class="empty">暂无秒杀活动</p>
          </template>

          <!-- 抢购记录 -->
          <template v-if="seckillSub === 'records'">
            <div class="seckill-query">
              <input v-model="seckillQuery.userId" placeholder="用户ID" />
              <input v-model="seckillQuery.activityId" placeholder="活动ID" />
              <select v-model="seckillQuery.status">
                <option value="">全部状态</option>
                <option value="pending">订单生成中</option>
                <option value="success">已成单</option>
                <option value="failed">已失败</option>
              </select>
              <button class="btn-sm primary" @click="fetchSeckillRecords">查询</button>
            </div>
            <p class="seckill-tip">
              排查「用户说抢到了却没订单」时用这里：<b>订单生成中</b>说明还在异步建单；
              <b>已失败</b>说明库存已回补，记录里的失败原因会写明原因。
            </p>
            <table class="data-table" v-if="seckillRecords.length">
              <thead>
                <tr>
                  <th>记录ID</th>
                  <th>活动ID</th>
                  <th>用户ID</th>
                  <th>数量</th>
                  <th>秒杀价</th>
                  <th>状态</th>
                  <th>订单号</th>
                  <th>时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in seckillRecords" :key="r.id">
                  <td class="td-mono">{{ r.id }}</td>
                  <td class="td-mono">{{ r.activityId }}</td>
                  <td class="td-mono">{{ r.userId }}</td>
                  <td>{{ r.quantity }}</td>
                  <td class="td-price">¥{{ r.seckillPrice }}</td>
                  <td>
                    <span class="seckill-status" :class="r.status">{{ seckillRecordLabel(r.status) }}</span>
                    <div v-if="r.failReason" class="seckill-fail-reason">{{ r.failReason }}</div>
                  </td>
                  <td class="td-mono">{{ r.orderNo || '-' }}</td>
                  <td class="td-date">{{ formatTime(r.createdAt) }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="empty">暂无记录</p>
          </template>

          <!-- 异步消息 -->
          <template v-if="seckillSub === 'messages'">
            <div class="seckill-query">
              <select v-model="seckillMsgStatus">
                <option value="">全部状态</option>
                <option value="pending">待投递</option>
                <option value="sent">已投递待确认</option>
                <option value="done">已完成</option>
                <option value="failed">已失败</option>
              </select>
              <button class="btn-sm primary" @click="fetchSeckillMessages">查询</button>
              <button class="btn-sm outline" @click="triggerSeckillCompensate">手动触发补偿</button>
            </div>
            <p class="seckill-tip">
              本地消息表是「扣减成功」与「订单创建」之间的一致性载体。
              若这里出现大量 <b>待投递</b> 或 <b>已失败</b>，说明订单服务可能异常，
              可点「手动触发补偿」立即重投一轮（幂等，重复执行安全）。
            </p>
            <table class="data-table" v-if="seckillMessages.length">
              <thead>
                <tr>
                  <th>消息ID</th>
                  <th>活动ID</th>
                  <th>用户ID</th>
                  <th>状态</th>
                  <th>重试</th>
                  <th>下次重试</th>
                  <th>错误</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="m in seckillMessages" :key="m.id">
                  <td class="td-mono">{{ m.msgId }}</td>
                  <td class="td-mono">{{ m.activityId }}</td>
                  <td class="td-mono">{{ m.userId }}</td>
                  <td><span class="seckill-status" :class="m.status">{{ seckillMsgLabel(m.status) }}</span></td>
                  <td>{{ m.retryCount }} / {{ m.maxRetry }}</td>
                  <td class="td-date">{{ formatTime(m.nextRetryAt) }}</td>
                  <td class="seckill-fail-reason">{{ m.errorMsg || '-' }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="empty">暂无消息</p>
          </template>
        </template>

      </div>
    </main>

    <!-- ===== 编辑用户弹窗 ===== -->
    <div class="modal-overlay" v-if="editingUser" @click.self="editingUser = null">
      <div class="modal-box">
        <h3>编辑用户</h3>
        <div class="form-row"><label>用户名</label><input v-model="editingUser.username" /></div>
        <div class="form-row"><label>角色</label>
          <select v-model="editingUser.role">
            <option value="customer">普通用户</option>
            <option value="merchant">商家</option>
            <option value="admin">管理员</option>
          </select>
        </div>
        <div class="form-row"><label>状态</label>
          <select v-model="editingUser.status">
            <option :value="1">正常</option>
            <option :value="0">禁用</option>
          </select>
        </div>
        <div class="form-actions">
          <button class="btn-sm primary" @click="saveUser">保存</button>
          <button class="btn-sm outline" @click="editingUser = null">取消</button>
        </div>
      </div>
    </div>

    <!-- ===== 分类表单弹窗 ===== -->
    <div class="modal-overlay" v-if="showCatForm" @click.self="showCatForm = false">
      <div class="modal-box">
        <h3>{{ editingCat ? '编辑分类' : '新增分类' }}</h3>
        <div class="form-row"><label>名称</label><input v-model="catForm.name" placeholder="分类名称" /></div>
        <div class="form-row"><label>父级</label>
          <select v-model="catForm.parentId">
            <option :value="0">顶级分类</option>
            <option v-for="c in topCats" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </div>
        <div class="form-row"><label>排序</label><input v-model="catForm.sortOrder" type="number" /></div>
        <div class="form-actions">
          <button class="btn-sm primary" @click="saveCategory">{{ editingCat ? '保存' : '新增' }}</button>
          <button class="btn-sm outline" @click="showCatForm = false">取消</button>
        </div>
      </div>
    </div>
    <!-- ===== 图片预览弹窗 ===== -->
    <div class="modal-overlay" v-if="previewImage" @click.self="previewImage = null">
      <div class="modal-box preview-box">
        <button class="preview-close" @click="previewImage = null">&times;</button>
        <img :src="previewImage" class="preview-img" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import http from '../utils/http.js'
import { currentUser, clearAuth } from '../stores/auth.js'

const router = useRouter()
const user = currentUser

const tab = ref('dashboard')
const subTab = ref('products')
const navGroupOpen = ref('')

/* ======== 仪表盘 ======== */
const ds = reactive({ totalUsers: 0, totalMerchants: 0, totalProducts: 0, totalOrders: 0 })
const dashData = reactive({
  dailyOrders: [],
  orderStatusDistribution: [],
  dailyNewUsers: [],
  roleDistribution: [],
  topMerchants: [],
  bottomMerchants: []
})
const chartInstances = {}

/* ======== 用户管理 ======== */
const users = ref([])
const userPage = ref(1)
const userSize = ref(15)
const userTotal = ref(0)
const userKeyword = ref('')
const editingUser = ref(null)

/* ======== 分类管理 ======== */
const categories = ref([])
const showCatForm = ref(false)
const editingCat = ref(null)
const catForm = reactive({ name: '', parentId: '', sortOrder: 0 })


/* ======== 商家审核 ======== */
const pendingMerchants = ref([])
const merchantPage = ref(1)
const merchantSize = ref(15)
const merchantTotal = ref(0)
const merchantMods = ref([])
const previewImage = ref(null)
const expanded = reactive({})

const parseChange = (detail) => {
  try { return JSON.parse(detail) } catch { return {} }
}

const imageFields = ['logoUrl', 'businessLicense', 'cardUrl']
const isImageField = key => imageFields.includes(key)
const fieldLabel = key => {
  const map = {
    shopName: '店铺名称', contactName: '联系人', contactPhone: '联系电话',
    logoUrl: '店铺Logo', businessLicense: '营业执照', shopDescription: '店铺简介',
    realName: '真实姓名', cardId: '身份证号', cardUrl: '身份证照片'
  }
  return map[key] || key
}

const isLongText = val => val && val.length > 40
const truncate = val => val ? val.slice(0, 40) + '...' : ''

const modTypeLabel = t => t === 'realname' ? '实名信息修改' : '店铺信息修改'

/* ======== 订单管理 ======== */
const allOrders = ref([])
const orderStatus = ref('')

/* ======== 提现审核 ======== */
const pendingWithdraws = ref([])

/* ======== 标签 ======== */
const tabTitle = computed(() => {
  const map = { dashboard: '仪表盘', users: '用户管理', categories: '分类管理', 'merchant-group': '商家管理', orders: '订单管理', reviews: '评论管理', complaints: '评论申诉', seckill: '秒杀监管' }
  return map[tab.value] || ''
})

const roleLabel = r => r === 'admin' ? '管理员' : r === 'merchant' ? '商家' : '顾客'
const statusLabel = s => s === 'on_sale' ? '上架' : s === 'off_sale' ? '下架' : s
const orderStatusLabel = s => ({ pending_pay: '待付款', paid: '待发货', shipped: '待收货', received: '已收货', completed: '已完成', cancelled: '已取消', refunding: '退款中' }[s] || s)
const complaintStatusLabel = s => ({ pending: '待处理', rejected: '已驳回', approved: '已通过' }[s] || s)

const formatTime = t => t ? t.replace('T', ' ').substring(0, 19) : '-'

/* ======== 评论管理 ======== */
const adminReviews = ref([])
const adminComplaints = ref([])
const complaintStatus = ref('')
const rejectTarget = ref(null)
const rejectNote = ref('')
const approveTarget = ref(null)
const approveNote = ref('')
const reviewSearchId = ref('')
const reviewSearchResults = ref(null)
const reviewPage = ref(1)
const reviewTotalPages = ref(1)
const REVIEW_PAGE_SIZE = 10

const displayedReviews = computed(() => {
  if (reviewSearchId.value && reviewSearchResults.value !== null) {
    return reviewSearchResults.value ? [reviewSearchResults.value] : []
  }
  return adminReviews.value
})

const searchReviewById = async () => {
  const rawId = reviewSearchId.value.trim()
  if (!rawId) return
  try {
    const { data } = await http.get(`/review/admin/search?id=${encodeURIComponent(rawId)}`)
    if (data.code === 200) {
      reviewSearchResults.value = data.data
    }
  } catch (_) {}
}

const clearReviewSearch = () => {
  reviewSearchId.value = ''
  reviewSearchResults.value = null
}

const fetchAdminReviews = async () => {
  try {
    const { data } = await http.get('/review/admin/list', { params: { page: reviewPage.value, size: REVIEW_PAGE_SIZE } })
    if (data.code === 200) {
      adminReviews.value = data.data?.list || data.data || []
      const total = data.data?.total || 0
      reviewTotalPages.value = Math.max(1, Math.ceil(total / REVIEW_PAGE_SIZE))
    }
  } catch (_) {}
}

const deleteAdminReview = async (id) => {
  if (!confirm('确认删除该评论？')) return
  try {
    const { data } = await http.delete(`/review/${id}`)
    if (data.code === 200) {
      adminReviews.value = adminReviews.value.filter(r => r.id !== id)
      if (reviewSearchResults.value && reviewSearchResults.value.id === id) {
        reviewSearchResults.value = null
        reviewSearchId.value = ''
      }
    } else alert(data.message || '删除失败')
  } catch (e) { alert(e.message || '网络异常') }
}

const fetchComplaints = async () => {
  try {
    const params = complaintStatus.value ? { status: complaintStatus.value } : {}
    const { data } = await http.get('/review/complaint/list', { params })
    if (data.code === 200) adminComplaints.value = data.data || []
  } catch (_) {}
}

const approveComplaint = (c) => { approveTarget.value = c; approveNote.value = '' }
const rejectComplaint = (c) => { rejectTarget.value = c; rejectNote.value = '' }

const confirmApprove = async () => {
  try {
    const { data } = await http.put(`/review/complaint/${approveTarget.value.id}/approve`, { adminNote: approveNote.value })
    if (data.code === 200) { approveTarget.value = null; fetchComplaints(); fetchAdminReviews() }
    else alert(data.message || '操作失败')
  } catch (e) { alert(e.message || '网络异常') }
}

const confirmReject = async () => {  try {
    const { data } = await http.put(`/review/complaint/${rejectTarget.value.id}/reject`, { adminNote: rejectNote.value })
    if (data.code === 200) { rejectTarget.value = null; fetchComplaints() }
    else alert(data.message || '操作失败')
  } catch (e) { alert(e.message || '网络异常') }
}

/* ======== 数据接口 ======== */
const fetchStats = async () => {
  try {
    const { data } = await http.get('/admin/dashboard/stats')
    if (data.code === 200 && data.data) {
      const body = data.data
      // 汇总数字
      const s = body.stats || body
      ds.totalUsers = s.totalUsers || 0
      ds.totalMerchants = s.totalMerchants || 0
      ds.totalProducts = s.totalProducts || 0
      ds.totalOrders = s.totalOrders || 0
      // 图表数据
      dashData.dailyOrders = body.dailyOrders || []
      dashData.orderStatusDistribution = body.orderStatusDistribution || []
      dashData.dailyNewUsers = body.dailyNewUsers || []
      dashData.roleDistribution = body.roleDistribution || []
      dashData.topMerchants = body.topMerchants || []
      dashData.bottomMerchants = body.bottomMerchants || []
      // 渲染图表
      await nextTick()
      renderCharts()
    }
  } catch (_) {}
}

const ORDER_STATUS_MAP = {
  pending_pay: '待付款', paid: '待发货', shipped: '待收货',
  received: '已收货', completed: '已完成', cancelled: '已取消',
  refunding: '退款中'
}
const ALL_STATUS_KEYS = Object.keys(ORDER_STATUS_MAP) // 7种状态固定不变
const ROLE_LABEL_MAP = { admin: '管理员', merchant: '商家', customer: '顾客' }
const ALL_ROLES = Object.keys(ROLE_LABEL_MAP) // 3种角色固定不变

// 补全近7天日期（缺失日期填0），保证图表宽度一致
const padDaily = (rows, days) => {
  const map = {}
  rows.forEach(r => { map[r.date] = Number(r.count) || 0 })
  const result = []
  const now = new Date()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(d.getDate() - i)
    const key = d.toISOString().slice(0, 10)
    result.push({ date: key, count: map[key] || 0 })
  }
  return result
}
const padDailyAmount = (rows, days) => {
  const map = {}
  rows.forEach(r => { map[r.date] = Number(r.amount) || 0 })
  const result = []
  const now = new Date()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(d.getDate() - i)
    const key = d.toISOString().slice(0, 10)
    result.push({ date: key, amount: map[key] || 0, count: 0 })
  }
  return result
}

const destroyCharts = () => {
  Object.values(chartInstances).forEach(c => c.destroy())
  Object.keys(chartInstances).forEach(k => delete chartInstances[k])
}

const renderCharts = () => {
  if (tab.value !== 'dashboard') return
  destroyCharts()

  // 1. 近7天下单趋势 (折线)
  const dailyOrders = padDaily(dashData.dailyOrders, 7)
  chartInstances.dailyOrders = new Chart(document.getElementById('chartDailyOrders'), {
    type: 'line',
    data: {
      labels: dailyOrders.map(d => d.date),
      datasets: [{
        label: '订单数',
        data: dailyOrders.map(d => d.count),
        borderColor: '#c9a96e',
        backgroundColor: 'rgba(201,169,110,0.1)',
        fill: true,
        tension: 0.3,
        pointRadius: 4,
        pointBackgroundColor: '#c9a96e'
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        y: { beginAtZero: true, ticks: { stepSize: 1 }, max: Math.max(5, ...dailyOrders.map(d => d.count)) }
      }
    }
  })

  // 2. 订单状态分布 (柱状) — 始终展示全部7种状态，无数据为0
  const statusMap = {}
  dashData.orderStatusDistribution.forEach(s => { statusMap[s.status] = Number(s.count) || 0 })
  const statusLabels = ALL_STATUS_KEYS.map(k => ORDER_STATUS_MAP[k])
  const statusCounts = ALL_STATUS_KEYS.map(k => statusMap[k] || 0)
  const statusMax = Math.max(5, ...statusCounts)
  chartInstances.statusDist = new Chart(document.getElementById('chartStatusDist'), {
    type: 'bar',
    data: {
      labels: statusLabels,
      datasets: [{
        label: '订单数',
        data: statusCounts,
        backgroundColor: ['#f39c12','#3498db','#2ecc71','#9b59b6','#27ae60','#e74c3c','#e67e22'],
        borderRadius: 4
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        y: { beginAtZero: true, ticks: { stepSize: 1 }, max: statusMax }
      }
    }
  })

  // 3. 近7天新增用户 (折线)
  const dailyUsers = padDaily(dashData.dailyNewUsers, 7)
  chartInstances.dailyUsers = new Chart(document.getElementById('chartDailyUsers'), {
    type: 'line',
    data: {
      labels: dailyUsers.map(d => d.date),
      datasets: [{
        label: '新增用户',
        data: dailyUsers.map(d => d.count),
        borderColor: '#3498db',
        backgroundColor: 'rgba(52,152,219,0.1)',
        fill: true,
        tension: 0.3,
        pointRadius: 4,
        pointBackgroundColor: '#3498db'
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        y: { beginAtZero: true, ticks: { stepSize: 1 }, max: Math.max(5, ...dailyUsers.map(d => d.count)) }
      }
    }
  })

  // 4. 近7天营收趋势 (柱状)
  const dailyRevenue = padDailyAmount(dashData.dailyOrders, 7)
  chartInstances.dailyRevenue = new Chart(document.getElementById('chartDailyRevenue'), {
    type: 'bar',
    data: {
      labels: dailyRevenue.map(d => d.date),
      datasets: [{
        label: '营收(元)',
        data: dailyRevenue.map(d => d.amount),
        backgroundColor: dailyRevenue.map((_, i) => ['#c9a96e','#e0b878','#d4a58c','#b8956a','#9e8570','#c9a96e','#e0b878'][i % 7]),
        borderRadius: 4
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        y: { beginAtZero: true, max: Math.max(10, ...dailyRevenue.map(d => d.amount)) }
      }
    }
  })

  // 5. Top 5 商家销售额 (横向柱状) — 始终展示5个位置
  const topM = dashData.topMerchants
  const topLabels = [], topDataVals = []
  for (let i = 0; i < 5; i++) {
    const m = topM[i]
    topLabels.push(m ? (m.shopName || '未命名') : '暂无')
    topDataVals.push(m ? (Number(m.totalSales) || 0) : 0)
  }
  const topRawMax = Math.max(...topDataVals, 1)
  const topXMax = topRawMax * 1.5 + 1
  chartInstances.topMerchants = new Chart(document.getElementById('chartTopMerchants'), {
    type: 'bar',
    data: {
      labels: topLabels,
      datasets: [{
        label: '销售额(元)',
        data: topDataVals,
        backgroundColor: ['#c9a96e','#e0b878','#b8956a','#d4a58c','#9e8570'],
        borderRadius: 4
      }]
    },
    options: {
      indexAxis: 'y',
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { beginAtZero: true, max: topXMax }
      }
    }
  })

  // 5b. Bottom 3 商家销售额 (横向柱状，红褐色调) — 始终展示3个位置
  const bottomM = dashData.bottomMerchants
  const bottomLabels = [], bottomDataVals = []
  for (let i = 0; i < 3; i++) {
    const m = bottomM[i]
    bottomLabels.push(m ? (m.shopName || '未命名') : '暂无')
    bottomDataVals.push(m ? (Number(m.totalSales) || 0) : 0)
  }
  const bottomRawMax = Math.max(...bottomDataVals, 1)
  const bottomXMax = bottomRawMax * 1.5 + 1
  chartInstances.bottomMerchants = new Chart(document.getElementById('chartBottomMerchants'), {
    type: 'bar',
    data: {
      labels: bottomLabels,
      datasets: [{
        label: '销售额(元)',
        data: bottomDataVals,
        backgroundColor: ['#e8a87c','#d4956a','#c08a5e'],
        borderRadius: 4
      }]
    },
    options: {
      indexAxis: 'y',
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { beginAtZero: true, max: bottomXMax }
      }
    }
  })

  // 6. 角色分布 (环形) — 始终展示全部3种角色
  const roleMap = {}
  dashData.roleDistribution.forEach(r => { roleMap[r.role] = Number(r.count) || 0 })
  const roleLabels = ALL_ROLES.map(r => ROLE_LABEL_MAP[r])
  const roleData = ALL_ROLES.map(r => roleMap[r] || 0)
  const totalRoles = roleData.reduce((s, v) => s + v, 0) || 1

  // 计算百分比，确保总和为100%
  const rawPcts = (() => {
    const pcts = roleData.map(v => Math.round((v / totalRoles) * 100))
    const sum = pcts.reduce((a, b) => a + b, 0)
    if (sum !== 100) {
      const maxIdx = pcts.reduce((best, v, i, arr) => v > arr[best] ? i : best, 0)
      pcts[maxIdx] += (100 - sum)
    }
    return pcts
  })()

  chartInstances.roleDist = new Chart(document.getElementById('chartRoleDist'), {
    type: 'doughnut',
    data: {
      labels: roleLabels,
      datasets: [{
        data: roleData,
        backgroundColor: ['#e8786a','#4dab9e','#f0a860'],
        borderColor: '#fff',
        borderWidth: 2,
        hoverBorderWidth: 3
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: '60%',
      plugins: {
        legend: {
          position: 'bottom',
          labels: {
            padding: 20,
            usePointStyle: true,
            pointStyleWidth: 10,
            font: { size: 13 },
            generateLabels: function(chart) {
              const data = chart.data
              return data.labels.map((label, i) => {
                return {
                  text: label + '  ' + (rawPcts[i] || 0) + '%',
                  fillStyle: data.datasets[0].backgroundColor[i],
                  strokeStyle: data.datasets[0].backgroundColor[i],
                  pointStyle: 'circle',
                  index: i
                }
              })
            }
          }
        },
        tooltip: {
          callbacks: {
            label: function(ctx) {
              const pct = Math.round((ctx.raw / totalRoles) * 100)
              return ctx.label + ': ' + ctx.raw + ' 人 (' + pct + '%)'
            }
          }
        }
      }
    }
  })
}

const fetchUsers = async () => {
  try {
    const params = { page: userPage.value, size: userSize.value }
    if (userKeyword.value) params.keyword = userKeyword.value
    const { data } = await http.get('/admin/users', { params })
    if (data.code === 200) {
      const body = data.data
      users.value = body.records || body || []
      userTotal.value = body.total || users.value.length
    }
  } catch (_) {}
}

const editUser = u => { editingUser.value = { ...u } }

const saveUser = async () => {
  try {
    const { data } = await http.put(`/admin/users/${editingUser.value.id}/status`, null, {
      params: { status: editingUser.value.status, role: editingUser.value.role }
    })
    if (data.code === 200) { editingUser.value = null; fetchUsers() }
    else alert(data.message)
  } catch (_) {}
}

const deleteUser = async id => {
  if (!confirm('确定删除该用户？')) return
  try {
    const { data } = await http.delete(`/admin/users/${id}`)
    if (data.code === 200) fetchUsers()
  } catch (_) {}
}

/* ======== 分类 ======== */
const topCats = computed(() => categories.value.filter(c => c.parentId === 0))
const getSubCats = (parentId) => categories.value.filter(c => c.parentId === parentId)
const expandedCats = reactive({})

const toggleCatExpand = (id) => {
  if (expandedCats[id]) {
    delete expandedCats[id]
  } else {
    expandedCats[id] = true
  }
}

const fetchCategories = async () => {
  try {
    const { data } = await http.get('/category/all')
    if (data.code === 200) categories.value = data.data || []
  } catch (_) {}
}

const openCatForm = c => {
  if (c) {
    editingCat.value = c
    Object.assign(catForm, { name: c.name, parentId: c.parentId || 0, sortOrder: c.sortOrder || 0 })
  } else {
    editingCat.value = null
    Object.assign(catForm, { name: '', parentId: 0, sortOrder: 0 })
  }
  showCatForm.value = true
}

const saveCategory = async () => {
  try {
    let res
    if (editingCat.value) {
      res = await http.put('/category/update', { id: editingCat.value.id, ...catForm })
    } else {
      res = await http.post('/category/add', catForm)
    }
    if (res.data.code === 200) {
      showCatForm.value = false; editingCat.value = null
      fetchCategories()
    }
  } catch (_) {}
}

const deleteCategory = async id => {
  if (!confirm('确定删除？')) return
  try {
    const { data } = await http.delete(`/category/delete/${id}`)
    if (data.code === 200) fetchCategories()
  } catch (_) {}
}

/* ======== 商品 ======== */
const productSearchName = ref('')
const productSearchMerchant = ref('')
const allProducts = ref([])

const filteredProducts = computed(() => {
  let list = allProducts.value
  const kwName = productSearchName.value.trim().toLowerCase()
  const kwMerchant = productSearchMerchant.value.trim().toLowerCase()
  if (kwName) list = list.filter(p => (p.name || '').toLowerCase().includes(kwName))
  if (kwMerchant) list = list.filter(p => (p.shopName || '').toLowerCase().includes(kwMerchant))
  return list
})

const applyProductFilters = () => {} // 配合 @input 用 computed 实时更新
const clearProductFilters = () => { productSearchName.value = ''; productSearchMerchant.value = '' }

const fetchAllProducts = async () => {
  try {
    const { data } = await http.get('/search/product', { params: { page: 1, size: 200 } })
    if (data.code === 200) {
      allProducts.value = data.data?.records || data.data || []
      ds.totalProducts = data.data?.total || allProducts.value.length
    }
  } catch (_) {}
}

const approveProduct = async id => { await http.put(`/product/${id}/status`, null, { params: { status: 'on_sale' } }); fetchAllProducts() }
const toggleProduct = async (id, status) => { await http.put(`/product/${id}/status`, null, { params: { status } }); fetchAllProducts() }
const deleteProduct = async id => {
  if (!confirm('确定删除？')) return
  try { await http.delete(`/product/${id}`); fetchAllProducts() } catch (_) {}
}

/* ======== 商家审核 ======== */
const fetchPendingMerchants = async () => {
  try {
    const { data } = await http.get('/admin/merchants/pending', {
      params: { page: merchantPage.value, size: merchantSize.value }
    })
    if (data.code === 200) {
      const body = data.data
      pendingMerchants.value = body.records || body || []
      merchantTotal.value = body.total || pendingMerchants.value.length
    }
  } catch (_) {}
  // 同时加载修改申请
  fetchModifications()
}

const fetchModifications = async () => {
  try {
    const { data } = await http.get('/admin/merchants/modifications', {
      params: { status: 'pending' }
    })
    if (data.code === 200) {
      merchantMods.value = data.data || []
    }
  } catch (_) {}
}

const verifyMerchant = async (merchantId, verified) => {
  const action = verified === 1 ? '同意入驻' : '拒绝入驻'
  if (!confirm(`确定${action}该商家？`)) return
  try {
    const { data } = await http.put(`/admin/merchants/${merchantId}/verify`, null, {
      params: { verified }
    })
    if (data.code === 200) {
      alert(`已${action}`)
      fetchPendingMerchants()
      fetchStats()
    } else {
      alert(data.message || '操作失败')
    }
  } catch (_) {}
}

const approveMod = async (modId) => {
  if (!confirm('确定同意修改？修改将通过并更新到商家信息')) return
  try {
    const { data } = await http.put(`/admin/merchants/modifications/${modId}/approve`)
    if (data.code === 200) {
      alert('修改已通过并生效')
      fetchModifications()
    } else alert(data.message || '操作失败')
  } catch (_) {}
}

const rejectMod = async (modId) => {
  if (!confirm('确定拒绝修改？修改将被驳回')) return
  try {
    const { data } = await http.put(`/admin/merchants/modifications/${modId}/reject`)
    if (data.code === 200) {
      alert('修改已拒绝')
      fetchModifications()
    } else alert(data.message || '操作失败')
  } catch (_) {}
}

/* ======== 订单 ======== */
/** 拉取订单列表（不改动界面），失败返回 null */
const loadOrders = async () => {
  try {
    const params = { page: 1, size: 200 }
    if (orderStatus.value) params.status = orderStatus.value
    const { data } = await http.get('/order/admin/orders', { params })
    if (data.code === 200) return (data.data?.records || data.data || [])
  } catch (_) {}
  return null
}

/** 应用订单列表并同步统计 */
const applyOrders = (list) => {
  allOrders.value = list
  ds.totalOrders = list.length
}

const fetchOrders = async () => {
  const list = await loadOrders()
  if (list) applyOrders(list)
}

/* ========== 订单强制操作 ==========
 *
 * 管理员订单列表同样读 ES 投影（写库 → 发 MQ → 索引，实测 100~300ms）。
 * 原来「await 写接口 → 立刻 fetchOrders()」拿到的是旧状态：被处理的订单
 * 迟迟不消失，管理员以为没生效就反复点。
 * 三个强制操作的结果都是终态（cancelled / completed），而管理员订单列表
 * 默认不展示终态，所以直接把该行从本地移除，再等索引收敛后以服务端为准。
 */
const orderActingIds = ref(new Set())

const isOrderActing = (id) => orderActingIds.value.has(id)

/** 终态订单默认不出现在该列表，操作成功后直接本地移除最符合直觉 */
const removeLocalOrder = (id) => {
  const idx = allOrders.value.findIndex(o => o.id === id)
  if (idx < 0) return
  allOrders.value.splice(idx, 1)
  ds.totalOrders = allOrders.value.length
}

/** 等索引收敛（该订单已不在当前视图即算追上）；只有确实追上才覆盖界面 */
const refetchOrdersUntilSettled = async (orderId, tries = 6, delay = 300) => {
  for (let i = 0; i < tries; i += 1) {
    await new Promise(resolve => setTimeout(resolve, delay))
    const list = await loadOrders()
    if (!list) continue
    if (!list.find(o => o.id === orderId)) {
      applyOrders(list)
      return
    }
  }
  // 兜底：多轮仍未收敛则最终以服务端为准
  await fetchOrders()
}

/** 强制操作统一入口：防连点 → 本地移除 → 等索引收敛 */
const runOrderAction = async (id, request, failText) => {
  if (orderActingIds.value.has(id)) return
  orderActingIds.value.add(id)
  try {
    const { data } = await request()
    if (data.code !== 200) {
      alert(data.message || failText)
      await fetchOrders()
      return
    }
    removeLocalOrder(id)
    await refetchOrdersUntilSettled(id)
  } catch (e) {
    alert(e.response?.data?.message || e.message || failText)
    await fetchOrders()
  } finally {
    orderActingIds.value.delete(id)
  }
}

const cancelOrder = async id => {
  if (!confirm('确定强制取消？')) return
  await runOrderAction(id, () => http.post(`/order/admin/force-cancel/${id}`), '强制取消失败，请稍后重试')
}
const forceRefundComplete = async id => {
  if (!confirm('确定强制退款完成吗？')) return
  await runOrderAction(id, () => http.post(`/order/admin/force-refund-complete/${id}`), '强制退款完成失败，请稍后重试')
}
const forceComplete = async id => {
  if (!confirm('确定强制完成吗？')) return
  await runOrderAction(id, () => http.post(`/order/admin/force-complete/${id}`), '强制完成失败，请稍后重试')
}

/* ======== 提现审核 ======== */
const fetchPendingWithdraws = async () => {
  try {
    const { data } = await http.get('/admin/merchants/withdraws/pending')
    if (data.code === 200) pendingWithdraws.value = data.data || []
  } catch (_) {}
}
const handleWithdraw = async (withdrawId, action) => {
  const label = action === 'approved' ? '通过' : '拒绝'
  if (!confirm(`确定${label}该提现申请吗？`)) return
  try {
    const { data } = await http.put(`/admin/merchants/withdraws/${withdrawId}`, null, { params: { action } })
    if (data.code === 200) { alert(`已${label}提现申请`); fetchPendingWithdraws() }
    else alert(data.message || '操作失败')
  } catch (_) {}
}

/* ======== 退出 ======== */
const doLogout = () => { clearAuth(); router.push('/') }

/* ======== 秒杀监管 ======== */
const seckillSub = ref('activities')
const seckillStatus = ref('')
const seckillActivities = ref([])
const seckillRecords = ref([])
const seckillMessages = ref([])
const seckillMsgStatus = ref('')
const seckillQuery = reactive({ userId: '', activityId: '', status: '' })

const seckillStatusLabel = (s) => ({
  draft: '草稿', pending: '待开始', running: '进行中',
  ended: '已结束', offline: '已下线'
}[s] || s)

const seckillRecordLabel = (s) => ({
  pending: '订单生成中', success: '已成单', failed: '已失败', cancelled: '已取消'
}[s] || s)

const seckillMsgLabel = (s) => ({
  pending: '待投递', sent: '已投递待确认', done: '已完成', failed: '已失败'
}[s] || s)

const fetchSeckillActivities = async () => {
  try {
    const params = {}
    if (seckillStatus.value) params.status = seckillStatus.value
    const { data } = await http.get('/seckill/admin/activities', { params })
    if (data.code === 200) seckillActivities.value = data.data || []
  } catch (_) { seckillActivities.value = [] }
}

const fetchSeckillRecords = async () => {
  try {
    const params = {}
    if (seckillQuery.userId) params.userId = seckillQuery.userId
    if (seckillQuery.activityId) params.activityId = seckillQuery.activityId
    if (seckillQuery.status) params.status = seckillQuery.status
    const { data } = await http.get('/seckill/admin/records', { params })
    if (data.code === 200) seckillRecords.value = data.data || []
  } catch (_) { seckillRecords.value = [] }
}

const fetchSeckillMessages = async () => {
  try {
    const params = seckillMsgStatus.value ? { status: seckillMsgStatus.value } : {}
    const { data } = await http.get('/seckill/admin/messages', { params })
    if (data.code === 200) seckillMessages.value = data.data || []
  } catch (_) { seckillMessages.value = [] }
}

const forceOfflineSeckill = async (a) => {
  if (!confirm(`强制下线活动「${a.title}」？将立即停止抢购并清除已预热的库存。`)) return
  try {
    const { data } = await http.put(`/seckill/admin/activities/${a.id}/offline`)
    if (data.code === 200) {
      fetchSeckillActivities()
    } else alert(data.message || '下线失败')
  } catch (e) { alert(e.response?.data?.message || e.message || '操作失败') }
}

const triggerSeckillCompensate = async () => {
  if (!confirm('手动触发一次秒杀补偿？会重投待处理消息并回补超时未成单的库存（幂等，可安全重复执行）。')) return
  try {
    const { data } = await http.post('/seckill/admin/compensate')
    if (data.code === 200) {
      alert(`补偿已执行：重投消息 ${data.data.retriedMessages} 条，回补记录 ${data.data.restoredRecords} 条`)
      fetchSeckillMessages()
    } else alert(data.message || '执行失败')
  } catch (e) { alert(e.response?.data?.message || e.message || '执行失败') }
}

/* ======== 切换tab自动加载 ======== */
const loadData = () => {
  if (tab.value === 'dashboard') { fetchStats() }
  if (tab.value === 'users') fetchUsers()
  if (tab.value === 'categories') fetchCategories()
  if (tab.value === 'merchant-group') {
    if (subTab.value === 'products') fetchAllProducts()
    if (subTab.value === 'merchants') { fetchPendingMerchants(); fetchModifications() }
    if (subTab.value === 'withdraws') fetchPendingWithdraws()
  }
  if (tab.value === 'orders') fetchOrders()
  if (tab.value === 'reviews') fetchAdminReviews()
  if (tab.value === 'complaints') fetchComplaints()
  if (tab.value === 'seckill') {
    if (seckillSub.value === 'activities') fetchSeckillActivities()
    if (seckillSub.value === 'records') fetchSeckillRecords()
    if (seckillSub.value === 'messages') fetchSeckillMessages()
  }
}

/* 监听tab切换自动加载数据 */
watch(tab, (newTab) => {
  if (newTab !== 'dashboard') destroyCharts()
  loadData()
})

onMounted(() => {
  fetchStats()
  fetchUsers()
})
</script>

<style scoped>
/* ======== 整体布局 ======== */
.admin-page { display: flex; min-height: 100vh; background: #f5f6f8; }

/* ======== 侧边栏 ======== */
.admin-sidebar {
  width: 220px; background: #1a1a2e; color: #ccc;
  display: flex; flex-direction: column; flex-shrink: 0;
}
.sidebar-brand { display: flex; align-items: center; gap: 10px; padding: 20px 20px; border-bottom: 1px solid rgba(255,255,255,0.06); }
.sidebar-logo { width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; background: #c9a96e; color: #1a1a2e; font-weight: 700; border-radius: 4px; font-size: 16px; }
.sidebar-brand span { font-size: 15px; font-weight: 600; color: #f0eee7; }
.sidebar-nav { flex: 1; padding: 16px 0; }
.sidebar-nav a {
  display: flex; align-items: center; gap: 10px; padding: 10px 20px; font-size: 13px;
  color: #999; cursor: pointer; transition: all 0.2s; text-decoration: none;
}
.sidebar-nav a:hover, .sidebar-nav a.active { color: #c9a96e; background: rgba(201,169,110,0.08); }

/* 侧边栏可展开组 */
.nav-group { border-bottom: 1px solid rgba(255,255,255,0.04); }
.nav-group-toggle {
  display: flex; align-items: center; gap: 10px; padding: 10px 20px; font-size: 13px;
  color: #999; cursor: pointer; transition: all 0.2s; user-select: none;
}
.nav-group-toggle:hover { color: #c9a96e; background: rgba(201,169,110,0.08); }
.nav-arrow { margin-left: auto; font-size: 10px; transition: transform 0.2s; color: #666; }
.nav-arrow.rotated { transform: rotate(-180deg); }
.nav-sub a {
  display: flex; align-items: center; gap: 10px; padding: 8px 20px 8px 48px; font-size: 12px;
  color: #888; cursor: pointer; transition: all 0.2s;
}
.nav-sub a:hover, .nav-sub a.active { color: #c9a96e; background: rgba(201,169,110,0.06); }
.nav-sub a.active { border-left: 2px solid #c9a96e; }
.sidebar-footer { padding: 16px 20px; border-top: 1px solid rgba(255,255,255,0.06); }
.back-link { font-size: 12px; color: #555; }

/* ======== 主区 ======== */
.admin-main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.admin-topbar {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 32px; background: #fff; border-bottom: 1px solid #eee;
  box-shadow: 0 1px 8px rgba(0,0,0,0.04);
}
.admin-topbar h2 { font-size: 18px; font-weight: 700; color: #1a1a2e; }
.topbar-user { display: flex; align-items: center; gap: 16px; font-size: 13px; }
.topbar-user a { color: #999; }

.admin-content { flex: 1; padding: 24px 32px; overflow-y: auto; }

/* ======== 统计卡片 ======== */
.stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 28px; }
.stat-card {
  position: relative; overflow: hidden;
  background: #fff; border-radius: 12px; padding: 28px 24px 24px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  text-align: center; transition: transform 0.2s, box-shadow 0.2s;
}
.stat-card:hover { transform: translateY(-2px); box-shadow: 0 6px 20px rgba(0,0,0,0.10); }
.stat-card::before {
  content: ''; position: absolute; top: 0; left: 0; right: 0; height: 4px;
}
.stat-card:nth-child(1)::before { background: linear-gradient(90deg, #667eea, #764ba2); }
.stat-card:nth-child(2)::before { background: linear-gradient(90deg, #f093fb, #f5576c); }
.stat-card:nth-child(3)::before { background: linear-gradient(90deg, #4facfe, #00f2fe); }
.stat-card:nth-child(4)::before { background: linear-gradient(90deg, #43e97b, #38f9d7); }
.stat-num { display: block; font-size: 36px; font-weight: 800; color: #1a1a2e; margin-bottom: 6px; letter-spacing: -0.5px; line-height: 1; }
.stat-label { font-size: 13px; color: #999; font-weight: 500; text-transform: uppercase; letter-spacing: 0.5px; }

/* ======== 工具条 ======== */
.section-bar { display: flex; gap: 8px; margin-bottom: 16px; }
.section-bar input { padding: 8px 12px; border: 1px solid #ddd; border-radius: 4px; font-size: 13px; outline: none; width: 240px; }
.search-bar { display: flex; align-items: center; margin-bottom: 16px; }
.status-tabs { display: flex; gap: 6px; margin-bottom: 16px; }
.status-tabs button { padding: 6px 14px; font-size: 12px; border: 1px solid #ddd; background: #fff; border-radius: 16px; cursor: pointer; }
.status-tabs button.active { background: #1a1a2e; color: #fff; border-color: #1a1a2e; }

/* ======== 表格 ======== */
.data-table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 4px rgba(0,0,0,0.04); }
.data-table th, .data-table td { padding: 12px 14px; text-align: left; font-size: 13px; border-bottom: 1px solid #f0f0f0; }
.data-table th { font-weight: 600; color: #999; font-size: 12px; background: #fafafa; }
.table-img { width: 44px; height: 56px; object-fit: cover; border-radius: 4px; }
.td-actions { display: flex; gap: 6px; white-space: nowrap; }
.td-date { font-size: 11px; color: #999; }

/* ======== 标签 ======== */
.role-tag { font-size: 12px; font-weight: 600; }
.role-admin { color: #c0392b; }
.role-merchant { color: #2980b9; }
.role-customer { color: #999; }
.tag-on { color: #27ae60; background: #eafaf1; padding: 2px 10px; border-radius: 10px; font-size: 11px; }
.tag-off { color: #999; background: #f5f5f5; padding: 2px 10px; border-radius: 10px; font-size: 11px; }

/* ======== 按钮 ======== */
.btn-sm { padding: 6px 16px; font-size: 12px; border: none; border-radius: 4px; cursor: pointer; font-weight: 500; }
.btn-sm.primary { background: #1a1a2e; color: #fff; }
.btn-sm.outline { background: #fff; border: 1px solid #ddd; color: #333; }
.btn-sm.danger { background: #fff; border: 1px solid #e0d0d0; color: #c0392b; }
.btn-sm.danger:hover { background: #fdf2f2; }

.pagination { display: flex; justify-content: center; align-items: center; gap: 16px; padding: 24px 0; }
.pagination button { padding: 6px 16px; border: 1px solid #ddd; background: #fff; border-radius: 4px; cursor: pointer; font-size: 13px; }
.pagination button:disabled { opacity: 0.4; cursor: not-allowed; }

.empty { text-align: center; padding: 60px 0; color: #999; font-size: 13px; }

.td-content { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.admin-note-hint { font-size: 12px; color: #888; }

/* 审核分区标题 */
.section-label { font-size: 16px; font-weight: 700; margin-bottom: 12px; color: #333; padding-bottom: 8px; border-bottom: 2px solid #1a1a2e; }

.mod-type-tag { font-size: 11px; padding: 2px 8px; border-radius: 10px; font-weight: 500; }
.mt-store { background: #eef2ff; color: #4f46e5; }
.mt-realname { background: #fef3c7; color: #b45309; }

/* 修改内容 */
.td-change-detail { max-width: 300px; }
.change-line { font-size: 12px; margin: 4px 0; }
.change-key { color: #999; font-weight: 500; }
.change-val { color: #27ae60; font-weight: 600; word-break: break-all; }
.change-img-wrap { margin-top: 4px; display: flex; align-items: flex-start; gap: 8px; }
.change-img { width: 80px; height: 60px; object-fit: cover; border-radius: 4px; border: 1px solid #eee; cursor: pointer; transition: transform 0.2s; }
.change-img:hover { transform: scale(1.05); }
.link-xs { font-size: 11px; color: #2980b9; white-space: nowrap; cursor: pointer; }
.change-long { display: inline; }

/* ===== 折叠面板式分类管理 ===== */
.cat-panels { display: flex; flex-direction: column; gap: 12px; }
.cat-panel { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.04); overflow: hidden; }
.cat-panel-header {
  display: flex; align-items: center; gap: 12px; padding: 14px 20px;
  cursor: pointer; transition: background 0.15s; user-select: none;
}
.cat-panel-header:hover { background: #fafafa; }
.cat-expand-icon { font-size: 11px; color: #999; width: 16px; flex-shrink: 0; }
.cat-panel-name { font-size: 15px; font-weight: 600; color: #1a1a2e; }
.cat-panel-meta { font-size: 12px; color: #bbb; margin-left: auto; }
.cat-panel-actions { display: flex; gap: 6px; margin-left: 12px; }
.cat-panel-body { padding: 0 20px 14px; border-top: 1px solid #f0f0f0; }
.cat-sub-item {
  display: flex; align-items: center; gap: 12px; padding: 10px 0 10px 16px;
  border-bottom: 1px solid #f8f8f8;
}
.cat-sub-item:last-child { border-bottom: none; }
.sub-indent { font-size: 14px; color: #555; }
.cat-sub-meta { font-size: 12px; color: #bbb; margin-left: auto; }
.cat-sub-actions { display: flex; gap: 6px; }
.cat-empty-sub { font-size: 12px; color: #ccc; padding: 8px 0 8px 16px; }

/* ======== 弹窗 ======== */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 3000; }
.modal-box { background: #fff; border-radius: 12px; padding: 32px; width: 420px; max-width: 90vw; box-shadow: 0 20px 60px rgba(0,0,0,0.3); }
.modal-box h3 { margin-bottom: 20px; font-size: 18px; }
.form-row { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
.form-row label { width: 64px; flex-shrink: 0; font-size: 13px; color: #666; }
.form-row input, .form-row select, .form-row textarea { flex: 1; padding: 8px 12px; border: 1px solid #ddd; border-radius: 4px; font-size: 13px; outline: none; }
.form-row textarea { resize: vertical; }
.form-actions { display: flex; gap: 8px; margin-top: 16px; }
.preview-box { width: auto; max-width: 80vw; max-height: 80vh; padding: 16px; background: #111; border-radius: 8px; position: relative; }
.preview-close { position: absolute; top: 8px; right: 12px; font-size: 28px; color: #fff; background: none; border: none; cursor: pointer; line-height: 1; }
.preview-img { display: block; max-width: 78vw; max-height: 76vh; border-radius: 4px; }

/* ======== 图表区域 ======== */
.charts-row { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 24px; }
.chart-card {
  background: #fff; border-radius: 12px; padding: 24px 28px 20px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  transition: box-shadow 0.2s;
}
.chart-card:hover { box-shadow: 0 4px 18px rgba(0,0,0,0.09); }
.chart-title {
  font-size: 15px; font-weight: 700; color: #1a1a2e; margin-bottom: 16px;
  display: flex; align-items: center; gap: 8px;
}
.chart-title::before {
  content: ''; display: inline-block; width: 4px; height: 18px;
  border-radius: 2px; background: #c9a96e;
}
.chart-wrap { position: relative; height: 260px; }
.chart-wrap-sm { position: relative; height: 300px; }

/* ======== 秒杀监管 ======== */
.seckill-query { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.seckill-query input, .seckill-query select {
  padding: 8px 12px; border: 1px solid #e0e0e0; border-radius: 6px;
  font-size: 13px; outline: none; background: #fff; min-width: 130px;
}
.seckill-query input:focus, .seckill-query select:focus { border-color: #c9a96e; }

.seckill-tip {
  font-size: 12px; line-height: 1.9; color: #8a6d3b;
  background: #fcf8e3; border: 1px solid #faebcc;
  border-radius: 6px; padding: 10px 14px; margin-bottom: 14px;
}
.seckill-tip b { color: #6b5426; }

.seckill-status {
  display: inline-block; font-size: 11px; padding: 2px 10px;
  border-radius: 10px; background: #f0f0f0; color: #888; white-space: nowrap;
}
.seckill-status.running { background: #c0392b; color: #fff; }
.seckill-status.pending { background: #c9a96e; color: #fff; }
.seckill-status.success, .seckill-status.done { background: #2e7d5b; color: #fff; }
.seckill-status.failed { background: #c0392b; color: #fff; }
.seckill-status.ended { background: #b8b5ad; color: #fff; }
.seckill-status.offline, .seckill-status.cancelled { background: #999; color: #fff; }
.seckill-status.sent { background: #4a6fa5; color: #fff; }
.seckill-status.draft { background: #ececec; color: #777; }

.seckill-fail-reason { font-size: 11px; color: #c0392b; margin-top: 3px; max-width: 220px; }

@media (max-width: 768px) { .admin-sidebar { display: none; } .stats-grid { grid-template-columns: 1fr 1fr; } .charts-row { grid-template-columns: 1fr; } }
</style>
