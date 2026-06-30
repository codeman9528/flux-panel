import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [
    react(),
    // iOS(file://)下 type="module" / crossorigin 会被 CORS 拦截致白屏，去掉改用普通 <script>/<link>
    ...(process.env.IOS_BUILD ? [{
      name: "ios-strip-module",
      transformIndexHtml(html: string) {
        // module→defer：去掉 module（file:// 下被 CORS 拦），但保留延迟执行，否则脚本在 #root 渲染前跑导致 createRoot 找不到容器(React #299)
        return html.replace(/ type="module"/g, " defer").replace(/ crossorigin/g, "");
      },
    }] : []),
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
    rollupOptions: process.env.IOS_BUILD ? {
      // iOS 用 file:// 加载，ES module 脚本(type="module")会被 CORS 拦截导致白屏；
      // 打包成 iife 单文件，index.html 用普通 <script> 加载，规避该限制
      treeshake: false,
      output: { format: 'iife', inlineDynamicImports: true },
    } : {
      treeshake: false,
    }
  }
});
