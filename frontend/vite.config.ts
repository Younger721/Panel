import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    // 编辑器/工具写入时的临时目录会让 fs.watch 抛 EBUSY，直接忽略
    watch: {
      ignored: ['**/*.tmpdir/**', '**/.*.tmp']
    },
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true
      },
      // 实时语音识别：浏览器 ↔ 后端 ↔ 百炼，浏览器这一跳是原生 WebSocket
      '/ws': {
        target: 'ws://127.0.0.1:8081',
        ws: true
      }
    }
  }
})
