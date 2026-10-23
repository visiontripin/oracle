# Tailscale вариант (рекомендуется для личного использования)

Tailscale даёт приватную сеть без публичного HTTPS-порта: API вообще не
торчит в интернет, приложение ходит по `http://<tailnet-name>:8000`.

1. Установи Tailscale на сервере (или в Termux: `pkg install tailscale`)
   и на Android-телефоне, войди в один аккаунт.
2. На сервере: `sudo tailscale up`, узнай имя: `tailscale status`.
3. (Опционально) включи HTTPS внутри tailnet: `tailscale cert <name>`,
   либо просто используй http внутри шифрованного tailnet — трафик уже
   зашифрован WireGuard.
4. В приложении укажи Base URL: `http://<tailnet-name>:8000`
   (или `https://...`, если выпустил cert).

Плюс: не нужен домен и Cloudflare, доступ только у твоих устройств.
