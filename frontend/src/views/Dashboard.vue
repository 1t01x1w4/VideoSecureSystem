<template>
  <div class="dashboard">
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-icon">&#9654;</div>
        <div class="stat-body">
          <div class="stat-value">{{ videos.length }}</div>
          <div class="stat-label">视频总数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">&#8663;</div>
        <div class="stat-body">
          <div class="stat-value">{{ formatSize(totalSize) }}</div>
          <div class="stat-label">存储总量</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">&#9888;</div>
        <div class="stat-body">
          <div class="stat-value status-ok">正常</div>
          <div class="stat-label">系统状态</div>
        </div>
      </div>
    </div>

    <div class="section-header">
      <h2>我的视频</h2>
      <router-link to="/upload" class="btn-upload">+ 上传视频</router-link>
    </div>

    <div class="search-bar">
      <input
        v-model="keyword"
        type="text"
        placeholder="搜索视频标题或文件名..."
        @keyup.enter="search"
      />
      <button class="btn-search" @click="search">搜索</button>
      <button v-if="keyword" class="btn-clear" @click="clearSearch">清除</button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <table v-else-if="videos.length" class="video-table">
      <thead>
        <tr>
          <th>视频名称</th>
          <th>原始文件名</th>
          <th>大小</th>
          <th>上传时间</th>
          <th>完整性</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="v in videos" :key="v.video_id">
          <td class="cell-title">{{ v.title || '未命名' }}</td>
          <td>{{ v.original_filename }}</td>
          <td>{{ formatSize(v.size) }}</td>
          <td>{{ formatTime(v.created_at) }}</td>
          <td>
            <span v-if="v.verified === true" class="badge ok">完整</span>
            <span v-else-if="v.verified === false" class="badge fail">已损坏</span>
            <span v-else class="badge warn">待校验</span>
          </td>
          <td class="cell-actions">
            <button class="action-btn" @click="playVideo(v.video_id)">播放</button>
            <button class="action-btn" @click="downloadVideo(v.video_id)">下载</button>
            <button class="action-btn" @click="verifyVideo(v.video_id)">校验</button>
            <button class="action-btn danger" @click="removeVideo(v.video_id)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-else class="empty">
      <p>暂无视频，点击上方按钮上传</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { videosApi } from '@/api/videos'
import axios from 'axios'

interface VideoItem {
  video_id: string
  title: string
  original_filename: string
  size: number
  created_at: string
  verified?: boolean
}

const router = useRouter()
const videos = ref<VideoItem[]>([])
const totalSize = ref(0)
const loading = ref(true)
const keyword = ref('')

async function fetchVideos() {
  loading.value = true
  try {
    const kw = keyword.value.trim() || undefined
    const res = await videosApi.list(kw)
    videos.value = res.data
    totalSize.value = res.data.reduce((s: number, v: VideoItem) => s + v.size, 0)
  } catch {
    // 后端未就绪时静默
  } finally {
    loading.value = false
  }
}

function search() { fetchVideos() }

function clearSearch() {
  keyword.value = ''
  fetchVideos()
}

onMounted(() => { fetchVideos() })

function formatSize(bytes: number) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = bytes
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(1)} ${units[i]}`
}

function formatTime(ts: string) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString()
}

function playVideo(id: string) { router.push(`/player/${id}`) }

async function downloadVideo(id: string) {
  try {
    const res = await axios.get(`/api/videos/${id}/download`, {
      responseType: 'blob',
      withCredentials: true,
    })
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url
    const disp = res.headers['content-disposition']
    const match = disp?.match(/filename="(.+)"/)
    a.download = match?.[1] || `${id}.mp4`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch {
    alert('下载失败，请确认会话有效且文件存在')
  }
}

async function verifyVideo(id: string) {
  try {
    const res = await videosApi.verifyVideo(id)
    alert(res.data.match ? '校验通过，文件完整' : '校验失败，文件可能损坏')
    const v = videos.value.find((x) => x.video_id === id)
    if (v) v.verified = res.data.match
  } catch {
    alert('校验请求失败')
  }
}

async function removeVideo(id: string) {
  if (!confirm('确定删除该视频？')) return
  try {
    await videosApi.deleteVideo(id)
    videos.value = videos.value.filter((v) => v.video_id !== id)
  } catch {
    alert('删除失败')
  }
}
</script>

<style scoped>
.stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin-bottom: 32px;
}

.stat-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 20px 24px;
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-icon {
  font-size: 28px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.status-ok { color: #22c55e; }

.stat-label {
  font-size: 13px;
  color: var(--text-muted);
  margin-top: 2px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.section-header h2 {
  font-size: 18px;
  color: var(--text-primary);
  margin: 0;
}

.btn-upload {
  padding: 10px 24px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #06b6d4, #3b82f6);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-upload:hover {
  box-shadow: 0 0 20px rgba(6, 182, 212, 0.4);
}

.search-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.search-bar input {
  flex: 1;
  max-width: 360px;
  padding: 9px 14px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-card);
  color: var(--text-primary);
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.search-bar input:focus {
  border-color: #06b6d4;
}

.btn-search {
  padding: 9px 20px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #06b6d4, #3b82f6);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-search:hover {
  box-shadow: 0 0 16px rgba(6, 182, 212, 0.4);
}

.btn-clear {
  padding: 9px 16px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-clear:hover {
  background: var(--border-color);
}

.video-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  overflow: hidden;
}

.video-table th,
.video-table td {
  padding: 14px 16px;
  text-align: left;
  font-size: 14px;
}

.video-table th {
  background: var(--bg-hover);
  color: var(--text-secondary);
  font-weight: 600;
  font-size: 13px;
}

.video-table td {
  color: var(--text-primary);
  border-top: 1px solid var(--border-color);
}

.cell-title { font-weight: 600; }

.cell-actions { display: flex; gap: 8px; }

.action-btn {
  padding: 6px 14px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn:hover { border-color: var(--accent); color: var(--accent); }
.action-btn.danger:hover { border-color: #ef4444; color: #ef4444; }

.badge {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 12px;
  font-weight: 600;
}
.badge.ok { background: rgba(34, 197, 94, 0.1); color: #22c55e; }
.badge.warn { background: rgba(234, 179, 8, 0.1); color: #eab308; }
.badge.fail { background: rgba(239, 68, 68, 0.1); color: #ef4444; }

.loading, .empty {
  text-align: center;
  padding: 60px;
  color: var(--text-muted);
}
</style>
