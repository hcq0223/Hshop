<template>
  <div class="assistant-page">
    <!-- 背景氛围 -->
    <div class="bg-mesh" aria-hidden="true"></div>

    <!-- 空状态：欢迎屏 -->
    <section v-if="messages.length === 0" class="welcome container">
      <div class="welcome-inner fade-up">
        <div class="ai-badge">
          <span class="ai-dot"></span>
          <span>ShopAi 智能助手</span>
        </div>
        <h1 class="welcome-title">小 Ai<span class="dot">。</span></h1>
        <p class="welcome-sub">我是你的专属导购与运营伙伴，能帮你查商品、管订单、看数据。试试问我一个问题吧。</p>

        <div class="suggestion-grid">
          <button v-for="(q, i) in suggestions" :key="i" class="suggestion-card" :class="'fade-up-delay-' + (i + 1)" @click="quickAsk(q.text)">
            <span class="sg-icon">{{ q.icon }}</span>
            <span class="sg-text">{{ q.text }}</span>
            <span class="sg-arrow">→</span>
          </button>
        </div>
      </div>
    </section>

    <!-- 对话区 -->
    <section v-else class="chat chat-wide">
      <div class="chat-scroll" ref="scrollRef">
        <div v-for="(m, i) in messages" :key="i" class="msg-row" :class="m.role">
          <div class="msg-avatar" :class="m.role">
            <span v-if="m.role === 'user'">{{ userInitial }}</span>
            <span v-else class="ai-face">Ai</span>
          </div>
          <div v-if="m.content || (m.role === 'assistant' && i === messages.length - 1 && streaming)" class="msg-bubble" :class="m.role">
            <div class="msg-text" v-html="renderText(m.content)"></div>
            <div v-if="m.role === 'assistant' && i === messages.length - 1 && streaming" class="typing-cursor"></div>
          </div>
        </div>
      </div>
    </section>

    <!-- 输入区 -->
    <div class="input-dock">
      <div class="input-bar input-wide">
        <div class="input-wrap">
          <textarea
            v-model="draft"
            rows="1"
            placeholder="输入你的问题…（Enter 发送，Shift+Enter 换行）"
            @keydown.enter.exact.prevent="send"
            @input="autoResize"
            ref="inputRef"
          ></textarea>
          <button class="send-btn" :disabled="sending || !draft.trim()" @click="send" title="发送">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 2L11 13"/><path d="M22 2l-7 20-4-9-9-4 20-7z"/></svg>
          </button>
        </div>
        <div class="input-actions">
          <button v-if="messages.length > 0" class="clear-btn" @click="clearChat">清空对话</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { currentRole, currentUser } from '../stores/auth.js'

const router = useRouter()

/* ========== 状态 ========== */
const draft = ref('')
const messages = ref([])
const sending = ref(false)
const streaming = ref(false)
const scrollRef = ref(null)
const inputRef = ref(null)

/* ========== 用户信息 ========== */
const userInitial = computed(() => (currentUser.value?.username?.[0] || '我').toUpperCase())
const roleLabel = computed(() => {
  const map = { customer: '顾客', merchant: '商家', admin: '管理员' }
  return map[currentRole.value] || '用户'
})

/* ========== 建议问题（按角色定制） ========== */
const suggestions = computed(() => {
  const common = [
    { icon: '⌘', text: '你好，介绍一下你能做什么？' },
  ]
  if (currentRole.value === 'customer') {
    return [
      { icon: '👕', text: '帮我推荐几件适合夏天的T恤' },
      { icon: '🛒', text: '我的购物车里有什么？' },
      { icon: '📦', text: '我最近的订单状态如何？' },
      ...common,
    ]
  }
  if (currentRole.value === 'merchant') {
    return [
      { icon: '📊', text: '我的店铺营收情况怎么样？' },
      { icon: '📦', text: '有哪些待处理的订单？' },
      { icon: '💰', text: '我的提现记录和余额？' },
      ...common,
    ]
  }
  return [
    { icon: '📈', text: '平台整体运营数据如何？' },
    { icon: '🏪', text: '有哪些待审核的商家？' },
    { icon: '🧾', text: '全平台订单情况怎么样？' },
    ...common,
  ]
})

/* ========== 渲染：把 \n 转 <br>，简单高亮 ========== */
function renderText(text) {
  if (!text) return ''
  return String(text)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\n/g, '<br/>')
}

/* ========== 快捷提问 ========== */
function quickAsk(text) {
  draft.value = text
  send()
}

/* ========== 发送 ========== */
async function send() {
  const text = draft.value.trim()
  if (!text || sending.value) return
  draft.value = ''
  autoResize()
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '' })
  sending.value = true
  streaming.value = true
  scrollToBottom()

  const history = messages.value
    .filter(m => m.content)
    .slice(0, -1)
    .map(m => ({ role: m.role, content: m.content }))
    .slice(-20)

  try {
    const token = localStorage.getItem('token') || ''
    const resp = await fetch('/agent/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token,
      },
      body: JSON.stringify({ message: text, history }),
    })

    if (!resp.ok) {
      let detail = `请求失败 (${resp.status})`
      try {
        const err = await resp.json()
        detail = err.detail || detail
      } catch (_) {}
      throw new Error(detail)
    }
    if (!resp.body) throw new Error('浏览器不支持流式读取')

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // SSE: data: <JSON字符串>  / data: [DONE]
      const lines = buffer.split('\n')
      buffer = lines.pop()
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed.startsWith('data:')) continue
        const payload = trimmed.slice(5).trim()
        if (payload === '[DONE]') continue
        if (payload) {
          // 后端把内容 JSON 编码后发送（含换行等特殊字符），这里解析还原
          let text = payload
          try {
            text = JSON.parse(payload)
          } catch (_) {
            // 解析失败则原样使用（兼容旧格式）
          }
          messages.value[messages.value.length - 1].content += text
          scrollToBottom()
        }
      }
    }
  } catch (e) {
    messages.value[messages.value.length - 1].content = (messages.value[messages.value.length - 1].content || '') + `\n[出错了] ${e.message}`
  } finally {
    sending.value = false
    streaming.value = false
    scrollToBottom()
  }
}

/* ========== 清空 ========== */
function clearChat() {
  if (sending.value) return
  if (!confirm('确定清空当前对话吗？')) return
  messages.value = []
  nextTick(() => inputRef.value?.focus())
}

/* ========== 输入框自适应 ========== */
function autoResize() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 140) + 'px'
}

/* ========== 滚动到底部 ========== */
function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

onMounted(() => inputRef.value?.focus())
</script>

<style scoped>
.assistant-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  position: relative;
  background: var(--color-bg);
  padding-top: 88px; /* 给 fixed header 让位 */
}

/* ── 背景氛围 ── */
.bg-mesh {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background:
    radial-gradient(800px 400px at 15% -10%, rgba(201,169,110,0.10), transparent 60%),
    radial-gradient(700px 420px at 85% 110%, rgba(26,26,46,0.06), transparent 60%);
}

/* ══════════ 欢迎屏 ══════════ */
.welcome {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  z-index: 1;
  padding: 40px 24px;
}
.welcome-inner { max-width: 760px; width: 100%; text-align: center; }

.ai-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  font-size: 12px;
  letter-spacing: 2px;
  color: var(--color-text-light);
  background: rgba(255,255,255,0.6);
  margin-bottom: 28px;
}
.ai-dot {
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--color-accent);
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.8); }
}

.welcome-title {
  font-family: var(--font-en);
  font-size: 64px;
  font-weight: 400;
  color: var(--color-primary);
  letter-spacing: 2px;
  line-height: 1.1;
  margin-bottom: 16px;
}
.welcome-title .dot { color: var(--color-accent); }

.welcome-sub {
  font-size: 16px;
  color: var(--color-text-light);
  max-width: 520px;
  margin: 0 auto 44px;
  line-height: 1.9;
}

/* 建议卡片 */
.suggestion-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
  text-align: left;
}
.suggestion-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  background: rgba(255,255,255,0.8);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  cursor: pointer;
  transition: var(--transition);
  opacity: 0;
}
.suggestion-card:hover {
  transform: translateY(-2px);
  border-color: var(--color-accent);
  box-shadow: var(--shadow-soft);
}
.sg-icon { font-size: 20px; }
.sg-text { flex: 1; font-size: 13px; color: var(--color-text); line-height: 1.5; }
.sg-arrow { color: var(--color-accent); font-size: 16px; transition: transform var(--transition); }
.suggestion-card:hover .sg-arrow { transform: translateX(4px); }

/* ══════════ 对话区 ══════════ */
.chat {
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
  z-index: 1;
  min-height: 0;
}
/* 聊天区近全宽，减少左右留白 */
.chat-wide {
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  padding: 0 12px;
}
.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 24px 8px 180px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.msg-row {
  display: flex;
  gap: 12px;
  width: 100%;
  max-width: 100%;
}
/* AI 消息：靠最左 */
.msg-row.assistant { justify-content: flex-start; }
/* 用户消息：row-reverse 下主轴反转，flex-start 指向视觉右侧 → 整体靠右，头像贴最右 */
.msg-row.user { justify-content: flex-start; flex-direction: row-reverse; }
/* 头像与气泡顶部对齐 */
.msg-row { align-items: flex-start; }

.msg-avatar {
  width: 36px; height: 36px;
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 600;
  flex-shrink: 0;
}
.msg-avatar.user { background: var(--color-primary); color: #fff; }
.msg-avatar.assistant {
  background: linear-gradient(135deg, var(--color-accent), #d9bf8e);
  color: #fff;
  font-family: var(--font-en);
}

.msg-bubble {
  padding: 14px 18px;
  border-radius: var(--radius);
  font-size: 14.5px;
  line-height: 1.8;
  max-width: 72%;
  word-break: break-word;
}
.msg-bubble.user {
  background: var(--color-primary);
  color: #fff;
  border-top-right-radius: 2px;
}
.msg-bubble.assistant {
  background: #fff;
  border: 1px solid var(--color-border);
  border-top-left-radius: 2px;
  box-shadow: var(--shadow-card);
}
.msg-text :deep(code) {
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 13px;
}

.typing-cursor {
  display: inline-block;
  width: 2px; height: 1em;
  background: var(--color-accent);
  margin-left: 2px;
  vertical-align: -0.15em;
  animation: blink 0.8s infinite;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

/* ══════════ 输入区 ══════════ */
.input-dock {
  position: fixed;
  bottom: 0;
  left: 0; right: 0;
  z-index: 10;
  padding: 16px 0 20px;
  background: linear-gradient(180deg, rgba(250,250,248,0), rgba(250,250,248,0.92) 40%, var(--color-bg) 100%);
}
.input-bar { display: flex; flex-direction: column; gap: 10px; }
.input-wide {
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  padding: 0 12px;
}
.input-wrap {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 10px 10px 10px 18px;
  box-shadow: 0 8px 30px rgba(0,0,0,0.08);
  transition: border-color var(--transition);
}
.input-wrap:focus-within { border-color: var(--color-accent); }
.input-wrap textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  font-size: 15px;
  font-family: var(--font-zh);
  color: var(--color-text);
  background: transparent;
  max-height: 140px;
  line-height: 1.6;
}
.send-btn {
  width: 42px; height: 42px;
  flex-shrink: 0;
  border: none;
  border-radius: 50%;
  background: var(--color-primary);
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer;
  transition: var(--transition);
}
.send-btn:hover:not(:disabled) { background: #2a2a42; }
.send-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.input-actions { display: flex; justify-content: center; }
.clear-btn {
  border: none;
  background: none;
  font-size: 12px;
  color: var(--color-text-light);
  cursor: pointer;
  padding: 4px 8px;
  letter-spacing: 1px;
  transition: color var(--transition);
}
.clear-btn:hover { color: #c0392b; }

@media (max-width: 640px) {
  .welcome-title { font-size: 44px; }
  .suggestion-grid { grid-template-columns: 1fr; }
  .msg-bubble { max-width: 78vw; }
}
</style>
