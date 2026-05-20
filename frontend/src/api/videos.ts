import api from './index'

export const videosApi = {
  list() {
    return api.get('/videos')
  },
  initUpload(filename: string, totalSize: number, totalChunks: number) {
    return api.post('/videos/upload/init', {
      filename,
      totalSize,
      totalChunks,
      mime_type: 'video/mp4',
    })
  },
  uploadChunk(uploadId: string, chunkIndex: number, chunk: Blob) {
    return api.put(`/videos/upload/${uploadId}/chunk/${chunkIndex}`, chunk, {
      headers: { 'Content-Type': 'application/octet-stream' },
    })
  },
  completeUpload(uploadId: string, title?: string) {
    return api.post(`/videos/upload/${uploadId}/complete`, { title })
  },
  deleteVideo(videoId: string) {
    return api.delete(`/videos/${videoId}`)
  },
  verifyVideo(videoId: string) {
    return api.post(`/videos/${videoId}/verify`)
  },
  getAuditLogs(params: {
    page?: number
    size?: number
    uuid?: string
    action?: string
  }) {
    return api.get('/admin/audit-logs', { params })
  },
}
