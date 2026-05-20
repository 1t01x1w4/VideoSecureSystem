<template>
  <div class="upload-page">
    <h2>上传视频</h2>
    <p class="hint">仅支持 MP4 格式，大文件自动分块上传（5MB/块），支持断点续传</p>

    <div class="upload-zone" :class="dragState" @dragover.prevent="handleDragOver" @dragleave="dragState = ''" @drop.prevent="handleDrop">
      <div v-if="!file" class="upload-prompt">
        <div class="upload-big-icon">&#9654;</div>
        <p>拖拽 MP4 文件到此处，或点击选择</p>
        <input ref="fileInput" type="file" accept="video/mp4,.mp4" @change="handleFileSelect" hidden />
        <button class="btn-browse" @click="fileInput?.click()">选择文件</button>
      </div>
      <div v-else class="upload-detail">
        <div class="file-info">
          <span class="file-name">{{ file.name }}</span>
          <span class="file-size">{{ formatSize(file.size) }}</span>
        </div>
        <div class="field" style="margin-top:16px">
          <label>视频名称（可选）</label>
          <input v-model="title" type="text" placeholder="给视频起个名字" />
        </div>
        <div v-if="uploading" class="progress-wrap">
          <div class="progress-bar">
            <div class="progress-fill" :style="{ width: `${progress}%` }"></div>
          </div>
          <span class="progress-text">{{ progress.toFixed(0) }}%</span>
        </div>
        <div class="upload-actions">
          <button class="btn-start" :disabled="uploading" @click="startUpload">
            {{ uploading ? '上传中...' : '开始上传' }}
          </button>
          <button class="btn-cancel" @click="resetForm">取消</button>
        </div>
      </div>
    </div>

    <div v-if="successMsg" class="success">{{ successMsg }}</div>
    <div v-if="errorMsg" class="error">{{ errorMsg }}</div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { videosApi } from '@/api/videos'

const CHUNK_SIZE = 5 * 1024 * 1024 // 5MB

const fileInput = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const title = ref('')
const dragState = ref('')
const uploading = ref(false)
const progress = ref(0)
const successMsg = ref('')
const errorMsg = ref('')

function isMp4File(f: File | null | undefined): boolean {
  if (!f) return false
  return f.name.toLowerCase().endsWith('.mp4') || f.type === 'video/mp4'
}

function handleDragOver(e: DragEvent) {
  e.preventDefault()
  if (!e.dataTransfer) return
  const f = e.dataTransfer.files?.[0]
  dragState.value = isMp4File(f) ? 'dragging' : 'dragging-invalid'
}

function handleFileSelect(e: Event) {
  const target = e.target as HTMLInputElement
  const f = target.files?.[0]
  if (!f) return
  if (!isMp4File(f)) {
    errorMsg.value = '仅支持 MP4 视频文件'
    return
  }
  file.value = f
  errorMsg.value = ''
  successMsg.value = ''
}

function handleDrop(e: DragEvent) {
  dragState.value = ''
  const f = e.dataTransfer?.files?.[0]
  if (!f) return
  if (!isMp4File(f)) {
    errorMsg.value = '仅支持 MP4 视频文件'
    return
  }
  file.value = f
  errorMsg.value = ''
  successMsg.value = ''
}

function resetForm() {
  file.value = null
  title.value = ''
  progress.value = 0
  uploading.value = false
  errorMsg.value = ''
  successMsg.value = ''
}

function formatSize(bytes: number) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = bytes
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(1)} ${units[i]}`
}

async function startUpload() {
  if (!file.value) return
  uploading.value = true
  errorMsg.value = ''
  successMsg.value = ''

  try {
    const f = file.value
    const totalChunks = Math.ceil(f.size / CHUNK_SIZE)

    // 初始化上传
    const initRes = await videosApi.initUpload(f.name, f.size, totalChunks)
    const uploadId = initRes.data.upload_id

    // 逐块上传
    for (let i = 0; i < totalChunks; i++) {
      const start = i * CHUNK_SIZE
      const end = Math.min(start + CHUNK_SIZE, f.size)
      const chunk = f.slice(start, end)
      await videosApi.uploadChunk(uploadId, i, chunk)
      progress.value = ((i + 1) / totalChunks) * 100
    }

    // 完成上传
    await videosApi.completeUpload(uploadId, title.value || undefined)
    successMsg.value = '上传完成！'
    setTimeout(() => resetForm(), 2000)
  } catch (e: any) {
    errorMsg.value = e.response?.data?.message || '上传失败'
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
.upload-page { max-width: 680px; }

.upload-page h2 {
  font-size: 20px;
  color: var(--text-primary);
  margin: 0 0 8px;
}

.hint {
  font-size: 13px;
  color: var(--text-muted);
  margin: 0 0 24px;
}

.upload-zone {
  background: var(--bg-card);
  border: 2px dashed var(--border-color);
  border-radius: 16px;
  padding: 48px;
  text-align: center;
  transition: border-color 0.2s, background 0.2s;
}

.upload-zone.dragging {
  border-color: var(--accent);
  background: var(--accent-bg);
}

.upload-zone.dragging-invalid {
  border-color: #ef4444;
  background: rgba(239, 68, 68, 0.05);
}

.upload-big-icon {
  font-size: 48px;
  color: var(--accent);
  margin-bottom: 16px;
}

.upload-prompt p {
  color: var(--text-muted);
  margin: 0 0 20px;
}

.btn-browse {
  padding: 10px 28px;
  border: 1px solid var(--accent);
  border-radius: 8px;
  background: transparent;
  color: var(--accent);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-browse:hover {
  background: var(--accent-bg);
}

.file-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--bg-hover);
  border-radius: 8px;
}

.file-name {
  font-weight: 600;
  color: var(--text-primary);
}

.file-size { color: var(--text-muted); font-size: 13px; }

.field { text-align: left; }
.field label {
  display: block;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 6px;
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
  box-sizing: border-box;
}
.field input:focus {
  border-color: var(--accent);
}

.progress-wrap {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 20px;
}

.progress-bar {
  flex: 1;
  height: 8px;
  background: var(--bg-hover);
  border-radius: 4px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #06b6d4, #3b82f6);
  border-radius: 4px;
  transition: width 0.2s;
}

.progress-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--accent);
  min-width: 45px;
}

.upload-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  margin-top: 20px;
}

.btn-start {
  padding: 12px 36px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #06b6d4, #3b82f6);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-start:hover:not(:disabled) { box-shadow: 0 0 20px rgba(6, 182, 212, 0.4); }
.btn-start:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-cancel {
  padding: 12px 24px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 14px;
  cursor: pointer;
}

.btn-cancel:hover { border-color: #ef4444; color: #ef4444; }

.success { color: #22c55e; margin-top: 16px; }
.error { color: #ef4444; margin-top: 16px; }
</style>
