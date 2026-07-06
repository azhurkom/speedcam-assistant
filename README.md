# SpeedCam Assistant 🚗

PWA + Android WebView додаток для контролю перевищення швидкості.

## Можливості

- **Speed Limiter** — задаєш граничну швидкість
- **30 секунд** — якщо перевищення довше 30 с — вмикається сирена
- **Сирена** — грає безперервно, доки не скинеш швидкість
- **Фільтр Калмана** — стабільне відображення швидкості без шуму GPS
- **Працює у фоні** — Android Foreground Service тримає GPS активним

## Встановлення

### Веб-версія
Відкрий **https://speed.komhub.top** у Chrome на телефоні

### Android APK
1. Завантаж APK з [Releases](https://github.com/azhurkom/speedcam-assistant/releases)
2. Встанови та надай дозвіл на геолокацію
3. Натисни СТАРТ

## Як це працює

1. Запускаєш додаток (СТАРТ)
2. Встановлюєш граничну швидкість (+/−)
3. Їдеш — на екрані поточна швидкість
4. Якщо перевищення триває >30 с — сирена
5. Скинув газ — сирена вимикається, таймер скидається

## Технології

- **Frontend:** Vanilla JS, CSS3, PWA (Service Worker)
- **Android:** Kotlin, WebView, Foreground Service, FusedLocationProviderClient
- **Deploy:** Docker, Nginx Proxy Manager

## Розробка

```bash
git clone https://github.com/azhurkom/speedcam-assistant.git
cd speedcam-assistant

# Frontend
open frontend/index.html

# Android — відкрий android/ в Android Studio
```