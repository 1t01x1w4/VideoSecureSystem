<template>
  <div class="audit-page">
    <h2>审计日志</h2>
    <p class="hint">记录所有关键操作：注册、登录、上传、查看、下载、异常</p>

    <div class="filters">
      <input v-model="filterUuid" placeholder="按用户UUID筛选" />
      <select v-model="filterAction">
        <option value="">全部操作类型</option>
        <option value="LOGIN">登录</option>
        <option value="REGISTER">注册</option>
        <option value="UPLOAD">上传</option>
        <option value="VIEW">查看</option>
        <option value="DOWNLOAD">下载</option>
        <option value="ERROR">异常</option>
      </select>
      <button class="btn-search" @click="fetchLogs">查询</button>
    </div>

    <table v-if="logs.length" class="log-table">
      <thead>
        <tr>
          <th>时间</th>
          <th>用户UUID</th>
          <th>操作类型</th>
          <th>目标</th>
          <th>IP</th>
          <th>结果</th>
          <th>详情</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="log in logs" :key="log.id">
          <td>{{ formatTime(log.createdAt) }}</td>
          <td class="mono">{{ log.uuid || '-' }}</td>
          <td>
            <span class="action-tag" :class="tagClass(log.action)">{{ log.action }}</span>
          </td>
          <td class="mono">{{ log.targetId || '-' }}</td>
          <td class="mono">{{ formatIp(log.ip) }}</td>
          <td>
            <span :class="log.result === 'SUCCESS' ? 'badge ok' : 'badge fail'">
              {{ log.result }}
            </span>
          </td>
          <td>{{ log.detail || '-' }}</td>
        </tr>
      </tbody>
    </table>

    <div v-else-if="!loading" class="empty">暂无审计日志记录</div>

    <div class="pagination" v-if="totalPages > 1">
      <button :disabled="page <= 0" @click="page--; fetchLogs()">上一页</button>
      <span>第 {{ page + 1 }} / {{ totalPages }} 页</span>
      <button :disabled="page + 1 >= totalPages" @click="page++; fetchLogs()">下一页</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { videosApi } from '@/api/videos'

interface LogEntry {
  id: number
  uuid: string | null
  action: string
  targetId: string | null
  ip: string | null
  result: string
  detail: string | null
  createdAt: string
}

const logs = ref<LogEntry[]>([])
const loading = ref(false)
const page = ref(0)
const totalPages = ref(1)
const filterUuid = ref('')
const filterAction = ref('')

onMounted(() => fetchLogs())

async function fetchLogs() {
  loading.value = true
  try {
    const res = await videosApi.getAuditLogs({
      page: page.value,
      size: 20,
      uuid: filterUuid.value || undefined,
      action: filterAction.value || undefined,
    })
    logs.value = res.data.content || res.data || []
    totalPages.value = res.data.totalPages || 1
  } catch {
    // 后端未就绪
  } finally {
    loading.value = false
  }
}

function tagClass(action: string) {
  const map: Record<string, string> = {
    LOGIN: 'tag-info', REGISTER: 'tag-info',
    UPLOAD: 'tag-ok', VIEW: 'tag-warn',
    DOWNLOAD: 'tag-warn', ERROR: 'tag-err',
  }
  return map[action] || ''
}

function formatTime(ts: string) {
  return ts ? new Date(ts).toLocaleString() : '-'
}

function formatIp(ip: string | null) {
  if (!ip) return '-'
  if (ip === '0:0:0:0:0:0:0:1') return '127.0.0.1'
  return ip
}
</script>

<style scoped>
.audit-page h2 {
  font-size: 20px;
  color: var(--text-primary);
  margin: 0 0 8px;
}

.hint {
  font-size: 13px;
  color: var(--text-muted);
  margin: 0 0 24px;
}

.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.filters input, .filters select {
  padding: 10px 14px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-input);
  color: var(--text-primary);
  font-size: 14px;
  outline: none;
}

.filters input:focus, .filters select:focus {
  border-color: var(--accent);
}

.filters input { width: 280px; }
.filters select { width: 160px; }

.btn-search {
  padding: 10px 20px;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.log-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  overflow: hidden;
}

.log-table th, .log-table td {
  padding: 12px 14px;
  text-align: left;
  font-size: 13px;
}

.log-table th {
  background: var(--bg-hover);
  color: var(--text-secondary);
  font-weight: 600;
  font-size: 12px;
}

.log-table td {
  color: var(--text-primary);
  border-top: 1px solid var(--border-color);
}

.mono { font-family: 'SF Mono', 'Cascadia Code', monospace; font-size: 12px; }

.action-tag {
  padding: 3px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
}
.tag-info { background: rgba(59, 130, 246, 0.1); color: #3b82f6; }
.tag-ok { background: rgba(34, 197, 94, 0.1); color: #22c55e; }
.tag-warn { background: rgba(234, 179, 8, 0.1); color: #eab308; }
.tag-err { background: rgba(239, 68, 68, 0.1); color: #ef4444; }

.badge {
  padding: 3px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
}
.badge.ok { background: rgba(34, 197, 94, 0.1); color: #22c55e; }
.badge.fail { background: rgba(239, 68, 68, 0.1); color: #ef4444; }

.empty { text-align: center; padding: 60px; color: var(--text-muted); }

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  margin-top: 24px;
}

.pagination button {
  padding: 8px 18px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}

.pagination button:hover:not(:disabled) { color: var(--accent); border-color: var(--accent); }
.pagination button:disabled { opacity: 0.4; cursor: not-allowed; }
.pagination span { color: var(--text-secondary); font-size: 14px; }
</style>
