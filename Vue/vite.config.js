import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

// 本项目根目录（vite.config.js 所在目录），用于 watcher 的根目录放行判断
const rootDir = dirname(fileURLToPath(import.meta.url)).replace(/\\/g, '/')

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,

    /*
     * 文件监视配置（Windows 必需）
     *
     * 背景：IDE（IDEA / VS Code）保存文件用的是「原子保存」——
     * 先在同目录建一个临时目录写内容，再 rename 覆盖目标文件，形如：
     *   src/views/.OrderDetail.vue.<vite的pid>.<uuid>.tmpdir/OrderDetail.vue.tmp
     *
     * Vite 的 watcher（chokidar）会去监视这个临时文件，而它几乎立刻就被删除/仍被占用，
     * Windows 上就抛 EBUSY。致命的是 chokidar 的 error 事件没人处理，
     * 于是整个 dev server 直接崩掉：
     *   Error: EBUSY: resource busy or locked, watch '...tmpdir\OrderDetail.vue.tmp'
     *
     * 这不是代码问题（改任何文件都可能触发），是「编辑器原子保存」与
     * 「Windows 文件锁」之间的竞态。忽略掉点开头的隐藏临时产物即可。
     *
     * ignored 用函数而不是 glob：glob 需要递归匹配整棵子树，成本较高；
     * 而 basename 判断只对每个被监视路径做一次字符串检查，几乎零开销。
     */
    watch: {
      ignored: (p) => {
        const normalized = String(p).replace(/\\/g, '/')

        // ⚠️ 必须先放行「workspace 根目录本身」。
        // chokidar 会对根目录也调用一次 ignored，若返回 true，
        // 整个项目都不会被监视 —— 表现为 vite 起来了但热更新完全失效，很难排查。
        if (normalized === rootDir) return false

        // 忽略路径中【任意一段】以点开头的隐藏产物：
        //   src/views/.OrderDetail.vue.18348.<uuid>.tmpdir/OrderDetail.vue.tmp
        //                 └────────── 要点名的是这一整段 ──────────┘
        // 只判断 basename 是不够的 —— 临时目录出现在路径中间，
        // 而最后一段往往是正常的文件名（如 OrderDetail.vue.tmp）。
        const segments = normalized.split('/').filter(Boolean)
        if (segments.some(s => s.startsWith('.'))) return true

        // 忽略构建产物，避免 dist 变化反过来触发 watcher
        if (segments.includes('dist')) return true

        return false
      }
    },

    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/agent': {
        target: 'http://localhost:8090',
        changeOrigin: true
      }
    }
  },
  resolve: {
    alias: {
      '@': '/src'
    }
  }
})
