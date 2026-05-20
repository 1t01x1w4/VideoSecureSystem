<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="logo">
        <div class="logo-icon">
          <svg viewBox="0 0 40 40" fill="none">
            <rect x="4" y="8" width="32" height="24" rx="3" stroke="currentColor" stroke-width="1.5" />
            <path d="M14 16l6 4-6 4V16z" fill="currentColor" />
            <rect x="5" y="5" width="6" height="3" rx="1" fill="currentColor" opacity="0.5" />
          </svg>
        </div>
        <span class="logo-text">VSEC</span>
      </div>
      <nav class="nav">
        <router-link to="/dashboard" class="nav-item" active-class="active">
          <span class="nav-icon">&#9632;</span>
          <span>仪表盘</span>
        </router-link>
        <router-link to="/upload" class="nav-item" active-class="active">
          <span class="nav-icon">&#9654;</span>
          <span>上传视频</span>
        </router-link>
        <router-link to="/audit" class="nav-item" active-class="active">
          <span class="nav-icon">&#9776;</span>
          <span>审计日志</span>
        </router-link>
      </nav>
      <div class="sidebar-footer">
        <div class="user-info">
          <span class="user-dot"></span>
          <span class="user-email">{{ email }}</span>
        </div>
        <button class="btn-logout" @click="handleLogout">退出</button>
      </div>
    </aside>
    <main class="main-content">
      <div class="topbar">
        <h1 class="page-title">视频大数据安全存储系统</h1>
        <div class="topbar-status">
          <span class="status-indicator"></span>
          <span>系统运行中</span>
        </div>
      </div>
      <div class="content-area">
        <router-view />
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuth } from '@/stores/useAuth'
import { authApi } from '@/api/auth'

const router = useRouter()
const { userEmail: email, clearSession } = useAuth()

// M8: 退出时调用后端 API 清理服务端会话
async function handleLogout() {
  try {
    await authApi.logout()
  } catch {
    // 忽略网络错误，继续清理本地状态
  }
  clearSession()
  router.push('/login')
}
</script>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  width: 240px;
  background: var(--bg-card);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  padding: 0;
  flex-shrink: 0;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 24px 20px;
  border-bottom: 1px solid var(--border-color);
}

.logo-icon {
  width: 40px;
  height: 40px;
  color: var(--accent);
}

.logo-text {
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 3px;
  color: var(--text-primary);
}

.nav {
  flex: 1;
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 8px;
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.2s;
}

.nav-item:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.nav-item.active {
  background: var(--accent-bg);
  color: var(--accent);
}

.nav-icon {
  font-size: 12px;
}

.sidebar-footer {
  padding: 20px;
  border-top: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 6px #22c55e;
}

.user-email {
  font-size: 13px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.btn-logout {
  width: 100%;
  padding: 8px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-logout:hover {
  border-color: #ef4444;
  color: #ef4444;
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 32px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-card);
  flex-shrink: 0;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 1px;
  background: linear-gradient(135deg, var(--accent), #a78bfa);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.topbar-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.status-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 8px #22c55e;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.content-area {
  flex: 1;
  overflow-y: auto;
  padding: 32px;
}
</style>
