<template>
  <div class="sd-page">
    <div class="container sd-main">
      <div v-if="loading" class="sd-empty">加载中…</div>

      <template v-else-if="activity">
        <!-- 活动头 -->
        <div class="sd-head">
          <div class="sd-head-left">
            <p class="section-label">Flash Sale</p>
            <h1 class="sd-title">{{ activity.title }}</h1>
            <p class="sd-time">{{ fmtTime(activity.startTime) }} — {{ fmtTime(activity.endTime) }}</p>
            <p v-if="activity.description" class="sd-desc">{{ activity.description }}</p>
          </div>
          <div class="sd-head-right">
            <div class="sd-countdown" :class="phase">
              <span class="sd-cd-label">{{ cdLabel }}</span>
              <span class="sd-cd-value">{{ countdownText }}</span>
            </div>
          </div>
        </div>

        <div class="sd-body">
          <!-- 左：商品列表 -->
          <div class="sd-items">
            <h3 class="sd-sub-title">选择商品</h3>
            <div
              v-for="item in activity.items"
              :key="item.stockId"
              class="sd-item"
              :class="{ active: selected && selected.stockId === item.stockId, soldout: item.soldOut }"
              @click="selectItem(item)"
            >
              <div class="sd-item-img">
                <img :src="item.skuImage || item.productImage || '/images/products/OIP-C.webp'" alt="" />
              </div>
              <div class="sd-item-info">
                <p v-if="item.infoMissing" class="sd-item-name sd-item-name--missing">商品信息加载失败</p>
                <p v-else class="sd-item-name">{{ item.productName }}</p>
                <p v-if="item.skuAttrs" class="sd-item-attrs">{{ item.skuAttrs }}</p>
                <div class="sd-item-price">
                  <span class="sd-price-now">¥{{ item.seckillPrice }}</span>
                  <span v-if="item.originPrice" class="sd-price-old">¥{{ item.originPrice }}</span>
                </div>
                <p class="sd-item-stock">
                  <template v-if="item.soldOut">已抢完</template>
                  <template v-else>
                    剩余 {{ item.availableStock }} / {{ item.totalStock }}
                    <span v-if="item.stockSource === 'db'" class="sd-stock-src">（未预热，余量以实际为准）</span>
                  </template>
                </p>
              </div>
              <!-- 一人一单已抢过 -->
              <div v-if="item.boughtByMe" class="sd-item-badge">已抢过</div>
              <div v-else-if="item.soldOut" class="sd-item-badge sd-item-badge--gray">售罄</div>
            </div>
          </div>

          <!-- 右：抢购面板 -->
          <div class="sd-panel">
            <template v-if="selected">
              <div class="sd-panel-item">
                <img :src="selected.skuImage || selected.productImage || '/images/products/OIP-C.webp'" alt="" />
                <div>
                  <p class="sd-panel-name">{{ selected.productName || '商品信息加载失败' }}</p>
                  <p v-if="selected.skuAttrs" class="sd-panel-attrs">{{ selected.skuAttrs }}</p>
                </div>
              </div>

              <div class="sd-panel-row">
                <span class="sd-panel-label">秒杀价</span>
                <span class="sd-price-now sd-price-lg">¥{{ selected.seckillPrice }}</span>
              </div>
              <div class="sd-panel-row">
                <span class="sd-panel-label">数量</span>
                <div class="sd-qty">
                  <button :disabled="quantity <= 1" @click="quantity--">−</button>
                  <span>{{ quantity }}</span>
                  <button :disabled="!canIncrease" @click="quantity++">+</button>
                </div>
                <span v-if="selected.perUserLimit > 0" class="sd-limit-hint">限购 {{ selected.perUserLimit }} 件</span>
              </div>

              <!--
                收货地址块：仅顾客需要。
                「收货地址」是顾客维度数据（由 customers.id 派生），商家/管理员在
                customers 表里没有记录，秒杀也轮不到他们下单 ——
                对他们显示这块只会引导到他们进不去的顾客端个人中心，故整块隐藏。
              -->
              <div v-if="isCustomer" class="sd-panel-row sd-panel-row--col">
                <!-- 标题行：「收货地址」与「管理地址」同行，下拉框另起一行 -->
                <div class="sd-addr-head">
                  <span class="sd-panel-label">收货地址</span>
                  <router-link to="/profile" class="sd-addr-manage">管理地址</router-link>
                </div>
                <select v-model="addressId" class="sd-addr-select">
                  <option :value="null" disabled>请选择收货地址</option>
                  <option v-for="a in addresses" :key="a.id" :value="a.id">
                    {{ a.receiverName }} {{ a.receiverPhone }} · {{ a.province }}{{ a.city }}{{ a.district }}{{ a.detailAddress }}
                  </option>
                </select>
                <p v-if="addresses.length === 0" class="sd-addr-empty">
                  还没有收货地址，请先<router-link to="/profile">添加地址</router-link>
                </p>
              </div>

              <div class="sd-panel-total">
                <span>合计</span>
                <span class="sd-price-now sd-price-lg">¥{{ totalAmount }}</span>
              </div>

              <button
                class="btn sk-buy-btn"
                :disabled="!canBuy"
                @click="doBuy"
              >
                {{ buyButtonText }}
              </button>

              <p v-if="errorMsg" class="sd-error">{{ errorMsg }}</p>
            </template>
            <div v-else class="sd-panel-empty">请从左侧选择要抢购的商品</div>
          </div>
        </div>
      </template>

      <div v-else class="sd-empty">
        <p class="sd-empty-title">活动不存在或已结束</p>
        <router-link to="/seckill" class="btn btn-outline">返回秒杀列表</router-link>
      </div>
    </div>

    <!-- 抢购结果弹层（排队中 / 成功 / 失败） -->
    <div v-if="resultVisible" class="sd-overlay">
      <div class="sd-dialog">
        <template v-if="result && result.status === 'pending'">
          <div class="sd-spinner"></div>
          <h3 class="sd-dialog-title">抢购成功，正在生成订单…</h3>
          <p class="sd-dialog-desc">请稍候，不要关闭页面</p>
        </template>

        <template v-else-if="result && result.status === 'success'">
          <div class="sd-dialog-icon sd-dialog-icon--ok">✓</div>
          <h3 class="sd-dialog-title">下单成功</h3>
          <p class="sd-dialog-desc">
            订单号 {{ result.orderNo }}<br />
            请尽快完成支付，超时订单将自动关闭
          </p>
          <div class="sd-dialog-actions">
            <router-link :to="`/order/${result.orderId}`" class="btn btn-primary">去支付</router-link>
            <button class="btn btn-outline" @click="closeResult">继续逛</button>
          </div>
        </template>

        <template v-else>
          <div class="sd-dialog-icon sd-dialog-icon--fail">!</div>
          <h3 class="sd-dialog-title">抢购未成功</h3>
          <p class="sd-dialog-desc">{{ (result && result.message) || '请稍后重试' }}</p>
          <div class="sd-dialog-actions">
            <button class="btn btn-primary" @click="closeResult">知道了</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import http from '../utils/http.js'
import { isLoggedIn, isCustomer } from '../stores/auth.js'

const route = useRoute()
const activityId = route.params.id

const activity = ref(null)
const loading = ref(true)
const selected = ref(null)
const quantity = ref(1)
const addresses = ref([])
const addressId = ref(null)
const errorMsg = ref('')
const resultVisible = ref(false)
const result = ref(null)

/** 服务端时间偏移，理由见 SeckillList.vue */
const serverOffset = ref(0)
const now = ref(Date.now())
let timer = null
let pollTimer = null

const syncedNow = () => now.value + serverOffset.value

const parseTime = (s) => {
  if (!s) return 0
  const t = Date.parse(String(s).replace(' ', 'T'))
  return Number.isNaN(t) ? 0 : t
}
const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(5, 16) : '')
const pad = (n) => String(n).padStart(2, '0')

const phase = computed(() => {
  const a = activity.value
  if (!a) return 'ended'
  if (a.status === 'ended' || a.status === 'offline') return 'ended'
  const t = syncedNow()
  if (t >= parseTime(a.endTime)) return 'ended'
  if (t < parseTime(a.startTime)) return 'pending'
  return 'running'
})

const cdLabel = computed(() => {
  if (phase.value === 'running') return '距结束'
  if (phase.value === 'pending') return '距开始'
  return '已结束'
})

const countdownText = computed(() => {
  const a = activity.value
  if (!a || phase.value === 'ended') return '——'
  const target = phase.value === 'running' ? parseTime(a.endTime) : parseTime(a.startTime)
  const diff = Math.max(0, target - syncedNow())
  const sec = Math.floor(diff / 1000)
  return `${pad(Math.floor(sec / 3600))}:${pad(Math.floor((sec % 3600) / 60))}:${pad(sec % 60)}`
})

const totalAmount = computed(() => {
  if (!selected.value) return '0.00'
  return (Number(selected.value.seckillPrice) * quantity.value).toFixed(2)
})

const canIncrease = computed(() => {
  if (!selected.value) return false
  const limit = selected.value.perUserLimit || 0
  if (limit > 0 && quantity.value >= limit) return false
  if (quantity.value >= (selected.value.availableStock || 0)) return false
  return true
})

/**
 * 抢购按钮状态。
 *
 * 分情况给出不同文案，而不是笼统置灰 —— 用户需要知道"为什么不能点"：
 * 未登录要去登录、未开始要等、已抢过不用再抢。
 */
const buyButtonText = computed(() => {
  if (!isLoggedIn.value) return '登录后抢购'
  // 秒杀下单需要 customerId + 收货地址，二者都是顾客维度数据；
  // 商家/管理员在 customers 表里没有记录，下单必然失败，
  // 这里直接给出原因，避免他们点了之后收到误导性的「系统繁忙」。
  if (!isCustomer.value) return '仅顾客可参与秒杀'
  if (phase.value === 'pending') return '活动未开始'
  if (phase.value === 'ended') return '活动已结束'
  if (!selected.value) return '请选择商品'
  if (selected.value.boughtByMe) return '您已参与过本活动'
  if (selected.value.soldOut) return '已抢完'
  if (addressId.value == null) return '请选择收货地址'
  return '立即抢购'
})

const canBuy = computed(() => {
  if (!isLoggedIn.value) return false
  if (!isCustomer.value) return false
  if (phase.value !== 'running') return false
  if (!selected.value) return false
  if (selected.value.boughtByMe || selected.value.soldOut) return false
  if (addressId.value == null) return false
  return true
})

const selectItem = (item) => {
  selected.value = item
  quantity.value = 1
  errorMsg.value = ''
}

const fetchDetail = async (autoSelect = true) => {
  try {
    const { data } = await http.get(`/seckill/detail/${activityId}`)
    if (data.code === 200 && data.data) {
      activity.value = data.data
      // 用服务端时间校准倒计时（关键：不信任本地时钟）
      if (data.data.serverTime) {
        serverOffset.value = parseTime(data.data.serverTime) - Date.now()
      }
      if (autoSelect) {
        // 仅在首次进入时自动选品。
        // 抢购后刷新余量时不能重选 —— 那会把用户刚选的数量重置成 1，
        // 而轮询弹层还开着，用户会看到数量莫名变化。
        const first = (data.data.items || []).find(i => !i.soldOut && !i.boughtByMe)
          || (data.data.items || [])[0]
        if (first) selectItem(first)
      } else if (selected.value) {
        // 刷新时保留当前选中项，只把它的最新数据换进去（余量、已抢过状态）
        const fresh = (data.data.items || []).find(i => i.stockId === selected.value.stockId)
        if (fresh) selected.value = fresh
      }
    }
  } catch (e) {
    // 刷新失败不清空页面：活动还在，只是这次没拿到最新余量
    if (autoSelect) activity.value = null
  } finally {
    loading.value = false
  }
}

const fetchAddresses = async () => {
  // 只有顾客才有收货地址 —— 非顾客发这个请求必然失败（返回空或报错），
  // 白白制造一条无用请求与一次异常日志。
  if (!isLoggedIn.value || !isCustomer.value) return
  try {
    const { data } = await http.get('/address/list')
    if (data.code === 200) {
      addresses.value = data.data || []
      const def = addresses.value.find(a => a.isDefault === 1)
      addressId.value = def ? def.id : (addresses.value[0]?.id ?? null)
    }
  } catch (_) {}
}

const doBuy = async () => {
  if (!isLoggedIn.value) {
    window.location.href = `/login?redirect=/seckill/${activityId}`
    return
  }
  errorMsg.value = ''
  resultVisible.value = true
  result.value = { status: 'pending', message: '正在抢购…' }
  try {
    const { data } = await http.post('/seckill/buy', {
      stockId: selected.value.stockId,
      quantity: quantity.value,
      addressId: addressId.value
    })
    if (data.code === 200) {
      result.value = data.data
      if (data.data.status === 'pending') {
        // 抢到了，订单在异步生成 —— 开始轮询结果
        startPolling()
      } else {
        // 失败是业务结果（卖完了 / 已抢过），不是异常
        stopPolling()
      }
      // 刷新余量与"已抢过"状态（不重选，保留用户当前选择）
      fetchDetail(false)
    } else {
      result.value = { status: 'failed', message: data.message || '抢购失败' }
    }
  } catch (e) {
    result.value = { status: 'failed', message: e.response?.data?.message || e.message || '网络异常，请重试' }
  }
}

/** 轮询抢购结果，直到 success / failed */
const startPolling = () => {
  stopPolling()
  let tries = 0
  pollTimer = setInterval(async () => {
    tries++
    // 最多轮询 60 次（约 90 秒），避免用户一直卡在这个弹层上
    if (tries > 60) {
      stopPolling()
      result.value = {
        status: 'failed',
        message: '订单生成较慢，请稍后到「我的秒杀」查看结果'
      }
      return
    }
    try {
      const { data } = await http.get(`/seckill/result/${activityId}`)
      if (data.code === 200 && data.data) {
        // pending 时继续轮询，其它状态停下来
        if (data.data.status !== 'pending') {
          result.value = data.data
          stopPolling()
          // 同 doBuy：刷新余量但不重选商品
          fetchDetail(false)
        }
      }
    } catch (_) {
      // 单次查询失败不中断轮询，网络抖动很常见
    }
  }, 1500)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const closeResult = () => {
  resultVisible.value = false
  stopPolling()
}

onMounted(() => {
  fetchDetail()
  fetchAddresses()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  stopPolling()
})
</script>

<style scoped>
.sd-page { background: var(--color-bg); min-height: 100vh; padding: 110px 0 80px; }
.sd-main { max-width: 1280px; }
.sd-empty { text-align: center; padding: 80px 0; color: var(--color-text-light); }
.sd-empty-title { font-size: 18px; color: var(--color-text); margin-bottom: 20px; }

/* 活动头 */
.sd-head {
  display: flex; align-items: center; justify-content: space-between; gap: 20px;
  background: linear-gradient(135deg, #1a1a2e 0%, #2d2d4a 100%);
  border-radius: var(--radius-lg); padding: 28px 32px; margin-bottom: 24px;
  flex-wrap: wrap;
}
.sd-head .section-label { color: var(--color-accent); }
.sd-title { font-size: 28px; font-weight: 600; color: #fff; letter-spacing: 1px; margin-bottom: 4px; }
.sd-time { font-size: 12px; color: rgba(255, 255, 255, .6); }
.sd-desc { font-size: 13px; color: rgba(255, 255, 255, .75); margin-top: 8px; max-width: 640px; }
.sd-countdown {
  display: flex; flex-direction: column; align-items: center;
  padding: 10px 22px; border-radius: var(--radius);
  background: rgba(255, 255, 255, .1);
}
.sd-cd-label { font-size: 11px; color: rgba(255, 255, 255, .6); letter-spacing: 1px; }
.sd-cd-value {
  font-family: var(--font-en); font-size: 26px; font-weight: 700;
  color: var(--color-accent); letter-spacing: 2px;
}

/* 主体两栏 */
.sd-body { display: grid; grid-template-columns: 1fr 360px; gap: 24px; align-items: start; }

/* 商品列表 */
.sd-items {
  background: #fff; border-radius: var(--radius-lg);
  border: 1px solid var(--color-border); box-shadow: var(--shadow-card);
  padding: 20px 24px;
}
.sd-sub-title { font-size: 15px; font-weight: 600; color: var(--color-primary); margin-bottom: 14px; }
.sd-item {
  display: flex; gap: 14px; align-items: center;
  padding: 12px; border: 1px solid var(--color-border); border-radius: var(--radius);
  margin-bottom: 12px; cursor: pointer; transition: all var(--transition);
  position: relative;
}
.sd-item:last-child { margin-bottom: 0; }
.sd-item:hover { border-color: var(--color-accent); }
.sd-item.active { border-color: var(--color-accent); background: #fffaf3; }
.sd-item.soldout { opacity: .55; }
.sd-item-img { width: 72px; height: 72px; flex-shrink: 0; border-radius: 6px; overflow: hidden; background: #f5f5f5; }
.sd-item-img img { width: 100%; height: 100%; object-fit: cover; }
.sd-item-info { flex: 1; min-width: 0; }
.sd-item-name { font-size: 14px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sd-item-name--missing { color: #c0392b; font-style: italic; }
.sd-item-attrs { font-size: 12px; color: var(--color-text-light); margin-top: 2px; }
.sd-item-price { display: flex; align-items: baseline; gap: 8px; margin-top: 4px; }
.sd-price-now { font-family: var(--font-en); font-size: 18px; font-weight: 700; color: #c0392b; }
.sd-price-lg { font-size: 22px; }
.sd-price-old { font-size: 12px; color: #bbb; text-decoration: line-through; }
.sd-item-stock { font-size: 11px; color: var(--color-text-light); margin-top: 2px; }
.sd-stock-src { color: #c9a96e; }
.sd-item-badge {
  position: absolute; top: 8px; right: 8px;
  font-size: 11px; padding: 2px 8px; border-radius: 10px;
  background: #c0392b; color: #fff;
}
.sd-item-badge--gray { background: #999; }

/* 右侧面板 */
.sd-panel {
  background: #fff; border-radius: var(--radius-lg);
  border: 1px solid var(--color-border); box-shadow: var(--shadow-card);
  padding: 20px; position: sticky; top: 100px;
}
.sd-panel-empty { text-align: center; color: var(--color-text-light); font-size: 13px; padding: 40px 0; }
.sd-panel-item { display: flex; gap: 12px; align-items: center; padding-bottom: 14px; border-bottom: 1px solid var(--color-border); }
.sd-panel-item img { width: 56px; height: 56px; border-radius: 6px; object-fit: cover; background: #f5f5f5; }
.sd-panel-name { font-size: 13px; font-weight: 500; }
.sd-panel-attrs { font-size: 11px; color: var(--color-text-light); }
.sd-panel-row {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 0; border-bottom: 1px dashed var(--color-border);
}
.sd-panel-row--col { flex-direction: column; align-items: stretch; gap: 8px; }
.sd-panel-label { font-size: 12px; color: var(--color-text-light); flex-shrink: 0; }
.sd-qty { display: flex; align-items: center; border: 1px solid var(--color-border); border-radius: 6px; overflow: hidden; }
.sd-qty button {
  width: 30px; height: 28px; border: none; background: #fafaf8;
  cursor: pointer; font-size: 15px; color: var(--color-text);
}
.sd-qty button:disabled { opacity: .35; cursor: not-allowed; }
.sd-qty span { width: 40px; text-align: center; font-size: 13px; }
.sd-limit-hint { font-size: 11px; color: var(--color-accent); }

/* 地址块：标题行（收货地址 + 管理地址）与下拉框分两行 */
.sd-addr-head { display: flex; align-items: center; justify-content: space-between; }
.sd-addr-select {
  flex: 1; padding: 8px 10px; border: 1px solid var(--color-border);
  border-radius: 6px; font-size: 12px; outline: none; background: #fafaf8;
  color: var(--color-text);
}
.sd-addr-select:focus { border-color: var(--color-accent); }
.sd-addr-manage { font-size: 12px; color: var(--color-accent); white-space: nowrap; }
.sd-addr-manage:hover { text-decoration: underline; }
.sd-addr-empty { font-size: 12px; color: #c0392b; }
.sd-addr-empty a { color: var(--color-accent); text-decoration: underline; }

.sd-panel-total {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 0;
}
.sd-panel-total span:first-child { font-size: 13px; color: var(--color-text-light); }

.sk-buy-btn {
  width: 100%; justify-content: center; padding: 14px;
  background: linear-gradient(90deg, #e8a87c, #c0392b); color: #fff;
  font-size: 15px; letter-spacing: 2px; border: none;
}
.sk-buy-btn:hover:not(:disabled) { filter: brightness(1.06); }
.sk-buy-btn:disabled { background: #d8d5cd; color: #fff; cursor: not-allowed; }
.sd-error { font-size: 12px; color: #c0392b; margin-top: 10px; text-align: center; }

/* 结果弹层 */
.sd-overlay {
  position: fixed; inset: 0; background: rgba(0, 0, 0, .5);
  display: flex; align-items: center; justify-content: center; z-index: 2000;
}
.sd-dialog {
  background: #fff; border-radius: var(--radius-lg); padding: 36px 40px;
  width: 90%; max-width: 420px; text-align: center;
  box-shadow: 0 20px 60px rgba(0, 0, 0, .2);
}
.sd-dialog-icon {
  width: 56px; height: 56px; margin: 0 auto 16px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 28px; color: #fff;
}
.sd-dialog-icon--ok { background: #2e7d5b; }
.sd-dialog-icon--fail { background: #c0392b; }
.sd-dialog-title { font-size: 18px; font-weight: 600; color: var(--color-primary); margin-bottom: 10px; }
.sd-dialog-desc { font-size: 13px; color: var(--color-text-light); line-height: 1.9; }
.sd-dialog-actions { display: flex; gap: 12px; justify-content: center; margin-top: 24px; }

/* 排队中动画 */
.sd-spinner {
  width: 48px; height: 48px; margin: 0 auto 18px;
  border: 3px solid #f0ede6; border-top-color: var(--color-accent);
  border-radius: 50%; animation: sdSpin .8s linear infinite;
}
@keyframes sdSpin { to { transform: rotate(360deg); } }

@media (max-width: 1024px) {
  .sd-body { grid-template-columns: 1fr; }
  .sd-panel { position: static; }
}
</style>
