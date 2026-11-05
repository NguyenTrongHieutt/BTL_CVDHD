#!/usr/bin/env bash
# Kiểm tra môi trường phát triển (CB-01). Chạy: bash scripts/check-env.sh  (Windows: dùng Git Bash)
ok=0
check() {
  local name=$1 cmd=$2 want=$3
  if out=$(eval "$cmd" 2>&1); then
    printf '  %-8s %s   (cần %s)\n' "$name" "$(echo "$out" | head -1)" "$want"
  else
    printf '  %-8s THIẾU   (cần %s)\n' "$name" "$want"
    ok=1
  fi
}
echo "Công cụ:"
check docker "docker version --format '{{.Server.Version}}'" "Docker Desktop đang chạy"
check compose "docker compose version --short" "v2 trở lên"
check java "java -version 2>&1 | head -1" "JDK 25"
check node "node -v" ">= 24"
check npm "npm -v" ">= 11"
check uv "uv --version" ">= 0.11"
check git "git --version" "bất kỳ"
echo "Git:"
printf '  user.name  = %s\n  user.email = %s\n' "$(git config user.name)" "$(git config user.email)"
echo "  (commit bằng tài khoản của chính bạn, không dùng tài khoản bot/AI)"
if [ -f deploy/.env ]; then echo "deploy/.env: có"; else echo "deploy/.env: CHƯA CÓ — cp deploy/.env.example deploy/.env"; ok=1; fi
exit $ok
