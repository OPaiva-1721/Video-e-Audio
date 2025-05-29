#!/bin/bash

COOKIES_FILE="./cookies.txt"
TEST_URL="https://www.youtube.com/watch?v=dQw4w9WgXcQ" # vídeo público genérico

# 1. Verificar se o cookies.txt existe
if [[ ! -f "$COOKIES_FILE" ]]; then
  echo "Arquivo cookies.txt não encontrado."
  exit 1
fi

# 2. Verificar se contém os cookies essenciais
REQUIRED_COOKIES=("SID=" "HSID=" "SSID=" "SAPISID=" "APISID=" "LOGIN_INFO=")

for COOKIE in "${REQUIRED_COOKIES[@]}"; do
  if ! grep -q "$COOKIE" "$COOKIES_FILE"; then
    echo "Cookie essencial ausente: $COOKIE"
    exit 1
  fi
done

# 3. Testar yt-dlp com cookies (sem baixar o vídeo)
echo "Validando autenticação no YouTube..."
yt-dlp --cookies "$COOKIES_FILE" --skip-download --get-title "$TEST_URL" > /dev/null 2>&1

if [[ $? -ne 0 ]]; then
  echo "yt-dlp falhou com os cookies. Provavelmente estão expirados ou inválidos."
  exit 1
fi

echo "Cookies válidos. Pode seguir com o deploy!"
exit 0
