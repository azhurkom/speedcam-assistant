# SpeedCam Assistant 🚗📸

PWA + Android WebView додаток для попередження про камери контролю швидкості та контролю перевищення швидкості.

## Можливості

- **Антирадар** — голосове/звукове попередження за 1000 м до камери
- **Speed Limiter** — сигнал при перевищенні ліміту понад 30 секунд (безперервно, доки не скинеш швидкість)
- **Додати камеру** — натиснув +, камера збережена з поточних GPS координат
- **Видалити камеру** — натиснув -, вибрав камеру поруч, видалив
- **Карта камер** — 1900+ камер України з OpenStreetMap
- **Працює у фоні** — Android Foreground Service тримає GPS активним

## Архітектура

```
/                          # PWA фронтенд (HTML/CSS/JS)
backend/                   # FastAPI + PostgreSQL
android/                   # Android WebView wrapper (Kotlin)
scripts/                   # Імпорт з OpenStreetMap
```

## Швидкий старт

### Відкрити сайт
**https://speed.komhub.top** — поки працює без SSL, далі буде HTTPS

### Встановити на Android
1. Завантажте APK з [Releases](https://github.com/azhurkom/speedcam-assistant/releases)
2. Встановіть та надайте дозвіл на геолокацію
3. Натисніть СТАРТ

## Технології

- **Frontend:** Vanilla JS, CSS3, PWA (Service Worker, manifest)
- **Backend:** Python FastAPI, PostgreSQL (pgvector/pg16)
- **Android:** Kotlin, WebView, Foreground Service
- **Data:** OpenStreetMap Overpass API
- **Deploy:** Docker, Nginx Proxy Manager

## Локальна розробка

```bash
git clone https://github.com/azhurkom/speedcam-assistant.git
cd speedcam-assistant

# Frontend — просто відкрий index.html у браузері
open frontend/index.html

# Backend
cd backend
pip install -r requirements.txt
DATABASE_URL=postgresql://user:pass@localhost:5432/speed_cameras uvicorn main:app --reload

# Android — відкрий android/ в Android Studio
```