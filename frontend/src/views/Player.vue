<template>
  <div class="player-page">
    <div class="player-header">
      <button class="btn-back" @click="$router.push('/dashboard')">← 返回</button>
      <h2 class="video-title">{{ videoTitle || '视频播放' }}</h2>
      <button class="btn-download" @click="downloadVideo">下载</button>
    </div>

    <div class="player-wrapper">
      <div v-if="loading" class="loading-overlay">加载中...</div>
      <video
        v-show="!loading"
        ref="videoEl"
        class="video-js vjs-big-play-centered"
        controls
        preload="auto"
        autoplay
        playsinline
        webkit-playsinline
        x5-video-player-type="h5"
        x5-video-orientation="portraint"
        style="width:100%;height:100%"
      />
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
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'

const route = useRoute()
const videoEl = ref<HTMLVideoElement | null>(null)
const videoTitle = ref('')
const streamUrl = ref('')
const loading = ref(true)

const videoId = route.params.videoId as string

onMounted(async () => {
  try {
    const res = await axios.get(`/api/videos/${videoId}/stream-token`)
    streamUrl.value = `/api/videos/${videoId}/stream?token=${res.data.token}`
  } catch {
    console.warn('获取播放凭证失败，请确认已登录')
  } finally {
    loading.value = false
  }
  const el = videoEl.value
  if (!el) return
  if (streamUrl.value) {
    el.src = streamUrl.value
  }
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

function downloadVideo() {
  window.open(`/api/videos/${videoId}/download`, '_blank')
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
  position: relative;
}

.loading-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 16px;
  z-index: 1;
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
