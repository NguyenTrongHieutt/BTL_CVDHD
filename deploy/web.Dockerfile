# Image web: build frontend bằng Node 26, phục vụ bằng nginx (AD-1). Chủ: Hiếu.
# Build từ thư mục gốc repo: docker build -f deploy/web.Dockerfile .
FROM node:26-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.30.6-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
