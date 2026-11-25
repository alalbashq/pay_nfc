import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

export default defineConfig(({ mode }) => {
  if (!['pos', 'pay'].includes(mode)) throw new Error('Choose --mode pos or --mode pay')
  return {
    root: fileURLToPath(new URL('./web', import.meta.url)),
    base: './',
    plugins: [vue()],
    build: { outDir: fileURLToPath(new URL(`./build/web/${mode}/public`, import.meta.url)), emptyOutDir: true },
  }
})
