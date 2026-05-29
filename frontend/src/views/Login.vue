<template>
  <div class="auth-page">
    <div class="auth-bg">
      <div class="grid-overlay"></div>
      <div class="particles">
        <span v-for="i in 20" :key="i" class="particle" :style="particleStyle()"></span>
      </div>
    </div>
    <div class="auth-card-wrapper">
      <div class="auth-header">
        <div class="shield-icon">
          <svg viewBox="0 0 48 48" fill="none">
            <path d="M24 4L6 12v14c0 10.5 7.5 20.3 18 22 10.5-1.7 18-11.5 18-22V12L24 4z" stroke="currentColor" stroke-width="1.5" fill="none" />
            <path d="M18 24l4 4 8-8" stroke="currentColor" stroke-width="1.5" fill="none" />
          </svg>
        </div>
        <h1 class="auth-title">视频大数据安全存储系统</h1>
        <p class="auth-subtitle">Video Security Storage System</p>
      </div>
      <div class="auth-card">
        <h2 class="card-title">{{ isLogin ? '登录' : '注册' }}</h2>
        <form @submit.prevent="handleSubmit" class="auth-form">
          <div class="field">
            <label>邮箱</label>
            <input v-model="email" type="email" placeholder="请输入邮箱地址" required autocomplete="email" />
          </div>
          <div class="field">
            <label>口令</label>
            <input v-model="password" type="password" placeholder="至少8位，含大小写字母和数字" required autocomplete="current-password" />
          </div>
          <div class="field code-field">
            <label>验证码</label>
            <div class="code-row">
              <input v-model="code" type="text" placeholder="6位数字验证码" maxlength="6" required />
              <button type="button" class="btn-code" :disabled="countdown > 0" @click="sendCode">
                {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
              </button>
            </div>
          </div>
          <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
          <button type="submit" class="btn-submit" :disabled="loading">
            {{ loading ? '处理中...' : (isLogin ? '登录' : '注册') }}
          </button>
        </form>
        <p class="switch-mode">
          {{ isLogin ? '没有账号？' : '已有账号？' }}
          <a href="#" @click.prevent="toggleMode">{{ isLogin ? '立即注册' : '去登录' }}</a>
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@/stores/useAuth'
import { authApi } from '@/api/auth'

const router = useRouter()
const { setSession } = useAuth()

const isLogin = ref(true)
const email = ref('')
const password = ref('')
const code = ref('')
const countdown = ref(0)
const loading = ref(false)
const errorMsg = ref('')

function toggleMode() {
  isLogin.value = !isLogin.value
  errorMsg.value = ''
  code.value = ''
}

async function sendCode() {
  if (!email.value) {
    errorMsg.value = '请先输入邮箱地址'
    return
  }
  try {
    errorMsg.value = ''
    await authApi.sendCode(email.value, isLogin.value ? 'login' : 'register')
    countdown.value = 60
    const timer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) clearInterval(timer)
    }, 1000)
    // 验证码发送成功，按钮开始倒计时
  } catch (e: any) {
    errorMsg.value = e.response?.data?.message || '发送验证码失败'
  }
}

async function handleSubmit() {
  if (password.value.length < 8) {
    errorMsg.value = '口令至少8位'
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const api = isLogin.value ? authApi.login : authApi.register
    await api(email.value, password.value, code.value)
    if (isLogin.value) {
      setSession(email.value)
      router.push('/dashboard')
    } else {
      alert('注册成功，请登录')
      isLogin.value = true
      code.value = ''
    }
  } catch (e: any) {
    errorMsg.value = e.response?.data?.message || '操作失败'
  } finally {
    loading.value = false
  }
}

function particleStyle() {
  const left = Math.random() * 100
  const delay = Math.random() * 5
  const duration = 3 + Math.random() * 4
  return {
    left: `${left}%`,
    animationDelay: `${delay}s`,
    animationDuration: `${duration}s`,
  }
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  background: var(--bg-root);
  overflow: hidden;
}

.auth-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.grid-overlay {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(6, 182, 212, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(6, 182, 212, 0.03) 1px, transparent 1px);
  background-size: 60px 60px;
}

.particles {
  position: absolute;
  inset: 0;
}

.particle {
  position: absolute;
  bottom: -10px;
  width: 2px;
  height: 2px;
  background: var(--accent);
  border-radius: 50%;
  animation: float-up linear infinite;
  opacity: 0.3;
}

@keyframes float-up {
  0% { transform: translateY(0); opacity: 0; }
  10% { opacity: 0.8; }
  90% { opacity: 0.2; }
  100% { transform: translateY(-100vh); opacity: 0; }
}

.auth-card-wrapper {
  position: relative;
  z-index: 1;
  text-align: center;
}

.auth-header {
  margin-bottom: 32px;
}

.shield-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  color: var(--accent);
}

.auth-title {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: 2px;
  color: var(--text-primary);
  margin: 0 0 8px;
}

.auth-subtitle {
  font-size: 13px;
  color: var(--text-muted);
  letter-spacing: 4px;
  text-transform: uppercase;
  margin: 0;
}

.auth-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  padding: 40px;
  width: 400px;
  backdrop-filter: blur(10px);
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.3), 0 0 60px rgba(6, 182, 212, 0.05);
}

.card-title {
  font-size: 18px;
  color: var(--text-primary);
  margin: 0 0 28px;
}

.auth-form {
  text-align: left;
}

.field {
  margin-bottom: 20px;
}

.field label {
  display: block;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 6px;
  font-weight: 500;
}

.field input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-input);
  color: var(--text-primary);
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
  box-sizing: border-box;
}

.field input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(6, 182, 212, 0.1);
}

.code-row {
  display: flex;
  gap: 12px;
}

.code-row input {
  flex: 1;
}

.btn-code {
  white-space: nowrap;
  padding: 10px 16px;
  border: 1px solid var(--accent);
  border-radius: 8px;
  background: transparent;
  color: var(--accent);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-code:hover:not(:disabled) {
  background: var(--accent-bg);
}

.btn-code:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.error {
  color: #ef4444;
  font-size: 13px;
  margin: 0 0 16px;
}

.btn-submit {
  width: 100%;
  padding: 12px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #06b6d4, #3b82f6);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-submit:hover:not(:disabled) {
  box-shadow: 0 0 20px rgba(6, 182, 212, 0.4);
  transform: translateY(-1px);
}

.btn-submit:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.switch-mode {
  margin: 20px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.switch-mode a {
  color: var(--accent);
  text-decoration: none;
  font-weight: 500;
}

.switch-mode a:hover {
  text-decoration: underline;
}
</style>
