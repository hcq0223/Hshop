<template>
  <div class="admin-login-page">
    <div class="login-card">
      <div class="card-header">
        <div class="admin-badge">ADMIN</div>
        <h2>管理后台</h2>
        <p>请输入管理员账号登录</p>
      </div>

      <form @submit.prevent="handleLogin" class="login-form">
        <div class="field">
          <label>账号</label>
          <input
            v-model="form.username"
            type="text"
            placeholder="请输入管理员用户名/手机号/邮箱"
            autocomplete="username"
          />
        </div>

        <div class="field">
          <label>密码</label>
          <input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
          />
        </div>

        <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>

        <button type="submit" class="btn-submit" :disabled="loading">
          {{ loading ? '登录中...' : '管理员登录' }}
        </button>
      </form>

      <div class="card-footer">
        <router-link to="/login">← 返回顾客/商户登录</router-link>
        <a href="#" class="forgot-link" @click.prevent="showForgot = true">忘记密码？</a>
      </div>
    </div>

    <p class="copyright">&copy; 2026 ShopAi Admin</p>

    <!-- 忘记密码弹窗 -->
    <Teleport to="body">
      <div class="modal-mask" v-if="showForgot" @click.self="showForgot = false">
        <div class="modal-card">
          <h3>忘记密码</h3>
          <p class="modal-sub">通过手机号和邮箱验证重置密码</p>
          <div class="form-group">
            <label>手机号</label>
            <input v-model="forgotForm.phone" type="text" placeholder="请输入注册时的手机号" maxlength="11" />
          </div>
          <div class="form-group">
            <label>邮箱</label>
            <input v-model="forgotForm.email" type="email" placeholder="请输入注册时的邮箱" />
          </div>
          <div class="form-group">
            <label>新密码</label>
            <input v-model="forgotForm.password" type="password" placeholder="请输入新密码" />
          </div>
          <p v-if="forgotMsg" class="msg" :class="{ 'msg-error': forgotError, 'msg-success': !forgotError }">{{ forgotMsg }}</p>
          <div class="modal-actions">
            <button class="btn btn-cancel" @click="showForgot = false">取消</button>
            <button class="btn btn-submit-sm" @click="submitForgot" :disabled="forgotLoading">{{ forgotLoading ? '提交中...' : '重置密码' }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import http from '../utils/http.js'
import { setAuth } from '../stores/auth.js'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const errorMsg = ref('')

// 忘记密码
const showForgot = ref(false)
const forgotLoading = ref(false)
const forgotMsg = ref('')
const forgotError = ref(false)
const forgotForm = reactive({ phone: '', email: '', password: '' })

const form = reactive({
  username: '',
  password: '',
})

const handleLogin = async () => {
  errorMsg.value = ''

  if (!form.username.trim()) return (errorMsg.value = '请输入账号')
  if (!form.password) return (errorMsg.value = '请输入密码')

  loading.value = true
  try {
    const { data } = await http.post('/auth/AdminLogin', {
      username: form.username.trim(),
      password: form.password,
    })

    if (data.code === 200) {
      const { token, user } = data.data
      setAuth(token, user)
      // 登录成功后跳转到之前想去的页面，或直接去管理后台
      const redirect = route.query.redirect || '/admin'
      router.push(redirect)
    } else {
      errorMsg.value = data.message || '登录失败'
    }
  } catch (e) {
    errorMsg.value = e.response?.data?.message || e.message || '网络异常'
  } finally {
    loading.value = false
  }
}

const submitForgot = async () => {
  forgotMsg.value = ''
  forgotError.value = false
  if (!forgotForm.phone.trim()) return showForgotErr('请输入手机号')
  if (!forgotForm.email.trim()) return showForgotErr('请输入邮箱')
  if (!forgotForm.password) return showForgotErr('请输入新密码')
  if (forgotForm.password.length < 6) return showForgotErr('密码至少6位')
  forgotLoading.value = true
  try {
    const { data } = await http.post('/auth/reset-pwd', {
      phone: forgotForm.phone.trim(),
      email: forgotForm.email.trim(),
      password: forgotForm.password,
    })
    if (data.code === 200) {
      forgotError.value = false
      forgotMsg.value = '密码重置成功，请使用新密码登录'
      setTimeout(() => { showForgot.value = false; forgotMsg.value = '' }, 2000)
    } else {
      showForgotErr(data.message || '重置失败')
    }
  } catch (e) {
    showForgotErr(e.response?.data?.message || e.message || '网络异常')
  } finally {
    forgotLoading.value = false
  }
}

const showForgotErr = (msg) => {
  forgotError.value = true
  forgotMsg.value = msg
}
</script>

<style scoped>
.admin-login-page {
  min-height: 100vh;
  background: #0f0f1a;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
}

.login-card {
  width: 100%;
  max-width: 420px;
  background: #1a1a2e;
  border-radius: 12px;
  padding: 48px 40px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.card-header { text-align: center; margin-bottom: 36px; }

.admin-badge {
  display: inline-block;
  padding: 4px 14px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 3px;
  color: #c9a96e;
  border: 1px solid rgba(201, 169, 110, 0.3);
  border-radius: 3px;
  margin-bottom: 16px;
}

.card-header h2 {
  font-size: 24px;
  font-weight: 600;
  color: #f0eee7;
  margin-bottom: 6px;
}

.card-header p {
  font-size: 13px;
  color: #666;
}

/* 表单 */
.field { margin-bottom: 20px; }

.field label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: #888;
  margin-bottom: 6px;
  letter-spacing: 1px;
  text-transform: uppercase;
}

.field input {
  width: 100%;
  padding: 12px 14px;
  font-size: 14px;
  background: #0f0f1a;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 6px;
  color: #f0eee7;
  outline: none;
  transition: border-color 0.3s;
}

.field input:focus { border-color: #c9a96e; }

.field input::placeholder { color: #444; }

.error-msg {
  color: #e74c3c;
  font-size: 13px;
  margin-bottom: 16px;
}

.btn-submit {
  width: 100%;
  padding: 14px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
  background: #c9a96e;
  color: #0f0f1a;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.3s;
}

.btn-submit:hover { background: #d4b87a; }

.btn-submit:disabled { opacity: 0.6; cursor: not-allowed; }

.card-footer {
  text-align: center;
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-footer a {
  font-size: 13px;
  color: #555;
  text-decoration: none;
  transition: color 0.2s;
}

.card-footer a:hover { color: #c9a96e; }

/* 忘记密码弹窗 */
.modal-mask { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-card { background: #1a1a2e; border-radius: 12px; padding: 28px; width: 420px; max-width: 90vw; box-shadow: 0 12px 40px rgba(0,0,0,0.5); border: 1px solid rgba(255,255,255,0.08); }
.modal-card h3 { color: #f0eee7; margin-bottom: 4px; font-size: 20px; }
.modal-sub { font-size: 13px; color: #666; margin-bottom: 20px; }
.form-group { margin-bottom: 14px; }
.form-group label { display: block; font-size: 12px; font-weight: 500; color: #888; margin-bottom: 4px; text-transform: uppercase; letter-spacing: 1px; }
.form-group input { width: 100%; padding: 10px 12px; border: 1px solid rgba(255,255,255,0.08); border-radius: 6px; font-size: 14px; background: #0f0f1a; color: #f0eee7; outline: none; }
.form-group input:focus { border-color: #c9a96e; }
.form-group input::placeholder { color: #444; }
.msg { font-size: 13px; margin-bottom: 8px; }
.msg-error { color: #e74c3c; }
.msg-success { color: #27ae60; }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px; }
.btn-cancel { padding: 8px 16px; border: 1px solid rgba(255,255,255,0.1); background: transparent; color: #888; border-radius: 6px; font-size: 13px; cursor: pointer; }
.btn-cancel:hover { color: #f0eee7; border-color: rgba(255,255,255,0.2); }
.btn-submit-sm { padding: 8px 16px; border: none; background: #c9a96e; color: #0f0f1a; border-radius: 6px; font-size: 13px; font-weight: 600; cursor: pointer; }
.btn-submit-sm:disabled { opacity: 0.6; cursor: not-allowed; }

.copyright {
  margin-top: 32px;
  font-size: 11px;
  color: #333;
}
</style>
