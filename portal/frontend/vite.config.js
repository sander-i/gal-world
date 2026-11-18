/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时把 /api 代理到后端；构建产物直接进 Spring Boot 的 static/
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // 第三方库单独成块：业务代码改动不会冲掉 vendor 的浏览器缓存。
        // 不要把 element-plus 整体塞进 manualChunks —— 那会让 tree-shaking 失效，
        // 未使用的组件全被打进首屏（实测 938 KB）。只分 vue 运行时。
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router'],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
