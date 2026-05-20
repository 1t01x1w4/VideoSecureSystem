<template>
  <div class="player-page">
    <div class="player-header">
      <button class="btn-back" @click="$router.push('/dashboard')">← 返回</button>
      <h2 class="video-title">{{ videoTitle || '视频播放' }}</h2>
      <button class="btn-download" @click="downloadVideo">下载</button>
    </div>

    <div class="player-wrapper">
      <video
        ref="videoEl"
        class="video-js vjs-big-play-centered"
        controls
        preload="auto"
        autoplay
        style="width:100%;height:100%"
      >
        <source :src="streamUrl" type="video/mp4" />
      </video>
    </div>

    <div class="player-info">
      <div class="info-row">
        <span class="info-label">安全状态</span>
        <span class="info-value ok">SM4-CTR 流式解密传输中</span>
      </div>
      <div class="info-row">
        <span class="info-label">传输模式</span>
        <span class="info-value">端到端加密 · 支持拖拽播放 (Range)</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'

const route = useRoute()
const videoEl = ref<HTMLVideoElement | null>(null)
const videoTitle = ref('')

const videoId = computed(() => route.params.videoId as string)
const streamUrl = computed(() => `/api/videos/${videoId.value}/stream`)

onMounted(() => {
  const el = videoEl.value
  if (!el) return
  el.addEventListener('error', () => {
    console.warn('视频加载失败，请确认后端已启动且会话有效')
  })
})

onUnmounted(() => {
  const el = videoEl.value
  if (el) {
    el.pause()
    el.removeAttribute('src')
    el.load()
  }
})

async function downloadVideo() {
  try {
    const res = await axios.get(`/api/videos/${videoId.value}/download`, {
      responseType: 'blob',
      withCredentials: true,
    })
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url
    const disp = res.headers['content-disposition']
    const match = disp?.match(/filename="(.+)"/)
    a.download = match?.[1] || `${videoId.value}.mp4`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch {
    alert('下载失败，请确认会话有效且文件存在')
  }
}
</script>

<style scoped>
.player-page { max-width: 960px; }

.player-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}

.btn-back, .btn-download {
  padding: 8px 18px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-back:hover { border-color: var(--text-secondary); color: var(--text-primary); }
.btn-download:hover { border-color: var(--accent); color: var(--accent); }

.video-title {
  flex: 1;
  font-size: 18px;
  color: var(--text-primary);
  margin: 0;
}

.player-wrapper {
  aspect-ratio: 16 / 9;
  border-radius: 12px;
  overflow: hidden;
  background: #000;
  border: 1px solid var(--border-color);
}

.player-info {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-top: 20px;
}

.info-row {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.info-label { color: var(--text-muted); font-size: 13px; }
.info-value { color: var(--text-primary); font-size: 14px; font-weight: 500; }
.info-value.ok { color: #22c55e; }
</style>
