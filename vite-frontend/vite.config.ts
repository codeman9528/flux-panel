import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [
    react(),
  ],
  // IOS_BUILD=1 时用相对路径，适配 iOS app 内 file:// 加载
  base: process.env.IOS_BUILD ? './' : '/',
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 3000,
    host: '0.0.0.0'
  },
  build: {
    outDir: 'dist',
    // iOS 打包时 app bundle 会把 assets/ 子目录拍平到根，故资源直接输出到根目录，避免路径错位白屏
    assetsDir: process.env.IOS_BUILD ? '' : 'assets',
    sourcemap: false,
    minify: false,  
    rollupOptions: {
      treeshake: false,
    }
  }
});
