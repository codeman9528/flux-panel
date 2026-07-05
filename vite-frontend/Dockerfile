# 多阶段构建 - 构建阶段
FROM node:20.19.0 AS builder

WORKDIR /app

COPY package*.json ./
# 使用 --legacy-peer-deps 忽略 HeroUI 子包间的 peer 版本漂移（无 lock 文件，^ 会装到新版导致 ERESOLVE）
RUN npm install --legacy-peer-deps

COPY . .
RUN npm run build

# 生产阶段
FROM nginx:stable-alpine AS production-stage

COPY nginx.conf /etc/nginx/nginx.conf

COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"] 