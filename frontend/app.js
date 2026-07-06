/* ===== SpeedCam Assistant — Application Logic ===== */

(function () {
  'use strict';

  // ─── DOM refs ──────────────────────────────────────────────────────────
  const $ = (id) => document.getElementById(id);
  const speedValue     = $('speedValue');
  const speedUnit      = $('speedUnit');
  const gpsDot         = $('gpsDot');
  const gpsText        = $('gpsText');
  const limitValue     = $('limitValue');
  const limitMinus     = $('limitMinus');
  const limitPlus      = $('limitPlus');
  const timerTrack     = $('timerTrack');
  const timerFill      = $('timerFill');
  const timerLabel     = $('timerLabel');
  const sirenAlert     = $('sirenAlert');
  const cameraWarning  = $('cameraWarning');
  const cameraWarningText = $('cameraWarningText');
  const cameraDistance = $('cameraDistance');
  const btnStartStop   = $('btnStartStop');
  const btnAddCamera   = $('btnAddCamera');
  const btnRemoveCamera = $('btnRemoveCamera');
  const statusText     = $('statusText');

  // ─── API Base URL ───────────────────────────────────────────────────────
  const API_BASE = window.API_BASE_URL || '';

  // ─── State ─────────────────────────────────────────────────────────────
  let running         = false;
  let speedLimit      = 80;              // км/год
  let currentSpeed    = 0;
  let prevCoords      = null;            // {lat, lng, time}
  let exceedTimer     = null;            // interval handle
  let exceedSeconds   = 0;
  let sirenActive     = false;
  let sirenAudioTimer = null;            // interval handle for siren sound
  let cameraBeepTimer = null;            // timeout handle for camera beep replay
  let lastCameraBeep  = 0;               // timestamp of last camera beep
  let watchId         = null;            // geolocation watch id
  let cameras         = [];              // cached cameras array
  let lastNearCamera   = null;           // {lat, lng, limit, distance}
  let audioCtx        = null;
  let isWebView       = false;           // true if running in Android WebView

  // Speed limit range
  const LIMIT_MIN = 50;
  const LIMIT_MAX = 130;
  const LIMIT_STEP = 1;

  // Timer duration
  const EXCEED_TIMEOUT = 30; // seconds

  // Camera radii
  const CAMERA_WARN_RADIUS = 1000;  // meters — show warning
  const CAMERA_NEAR_RADIUS = 200;   // meters — for "remove nearest" detection

  // GPS smoothing: minimum speed change (km/h) to update display
  const SPEED_UPDATE_THRESHOLD = 0.5;

  // ─── Kalman filter (1D) for speed ───────────────────────────────────────
  let kalmanX = 0;        // estimated speed (km/h)
  let kalmanP = 0.5;      // estimation error covariance
  const KALMAN_Q = 0.1;   // process noise — how fast can speed change
  const KALMAN_R = 3.0;   // measurement noise — GPS accuracy
  const DRIFT_THRESHOLD = 2.5;  // km/h — below this show 0

  function kalmanUpdate(z) {
    // Prediction step
    kalmanP = kalmanP + KALMAN_Q;
    // Update step
    const k = kalmanP / (kalmanP + KALMAN_R);
    kalmanX = kalmanX + k * (z - kalmanX);
    kalmanP = (1 - k) * kalmanP;
    // Apply drift threshold
    if (kalmanX < DRIFT_THRESHOLD) {
      kalmanX = 0;
    }
    return kalmanX;
  }

  // ─── Haversine ─────────────────────────────────────────────────────────
  function haversine(lat1, lng1, lat2, lng2) {
    const R = 6371000; // Earth radius in meters
    const toRad = (deg) => (deg * Math.PI) / 180;
    const dLat = toRad(lat2 - lat1);
    const dLng = toRad(lng2 - lng1);
    const a =
      Math.sin(dLat / 2) ** 2 +
      Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  // ─── Audio ─────────────────────────────────────────────────────────────
  function ensureAudioCtx() {
    if (!audioCtx) {
      audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    }
    if (audioCtx.state === 'suspended') {
      audioCtx.resume().catch(() => {});
    }
  }

  /** Short double-beep (camera warning) */
  function playCameraBeep() {
    ensureAudioCtx();
    if (!audioCtx) return;
    const now = audioCtx.currentTime;

    // First beep
    const osc1 = audioCtx.createOscillator();
    const gain1 = audioCtx.createGain();
    osc1.type = 'sine';
    osc1.frequency.value = 880;
    gain1.gain.setValueAtTime(0.4, now);
    gain1.gain.exponentialRampToValueAtTime(0.001, now + 0.15);
    osc1.connect(gain1).connect(audioCtx.destination);
    osc1.start(now);
    osc1.stop(now + 0.15);

    // Second beep 200ms later
    const osc2 = audioCtx.createOscillator();
    const gain2 = audioCtx.createGain();
    osc2.type = 'sine';
    osc2.frequency.value = 880;
    gain2.gain.setValueAtTime(0.4, now + 0.2);
    gain2.gain.exponentialRampToValueAtTime(0.001, now + 0.35);
    osc2.connect(gain2).connect(audioCtx.destination);
    osc2.start(now + 0.2);
    osc2.stop(now + 0.35);
  }

  /** Start siren — modulated frequency oscillation */
  function startSirenSound() {
    stopSirenSound();
    ensureAudioCtx();
    if (!audioCtx) return;

    const now = audioCtx.currentTime;
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();

    osc.type = 'sine';
    gain.gain.setValueAtTime(0.25, now);
    gain.gain.setValueAtTime(0.25, now + 100); // keep alive

    // Frequency modulation: sweep 500→1000→500 every ~500ms
    const sweep = (startTime) => {
      osc.frequency.setValueAtTime(500, startTime);
      osc.frequency.linearRampToValueAtTime(1000, startTime + 0.25);
      osc.frequency.linearRampToValueAtTime(500, startTime + 0.5);
    };
    // Schedule many sweeps ahead
    for (let i = 0; i < 200; i++) {
      sweep(now + i * 0.5);
    }

    osc.connect(gain).connect(audioCtx.destination);
    osc.start(now);

    sirenAudioTimer = { osc, gain };
    // Poll to stop siren when speed drops
  }

  function stopSirenSound() {
    if (sirenAudioTimer) {
      try {
        sirenAudioTimer.osc.stop();
        sirenAudioTimer.osc.disconnect();
        sirenAudioTimer.gain.disconnect();
      } catch (_) { /* already stopped */ }
      sirenAudioTimer = null;
    }
  }

  // ─── Status text ───────────────────────────────────────────────────────
  function setStatus(msg, type) {
    statusText.textContent = msg;
    statusText.className = 'status-text' + (type ? ' ' + type : '');
  }

  // ─── GPS ───────────────────────────────────────────────────────────────
  function updateGPSIndicator(active, error) {
    gpsDot.className = 'gps-dot' + (active ? ' active' : '') + (error ? ' error' : '');
    gpsText.textContent = active ? (error ? 'Помилка GPS' : 'GPS OK') : 'GPS';
    gpsText.style.color = error ? '#ff1744' : active ? '#00c853' : '#888';
  }

  function onPosition(lat, lng, timeMs, accuracy) {
    updateGPSIndicator(true, false);

    const now = timeMs || Date.now();

    if (prevCoords && prevCoords.time) {
      const dt = (now - prevCoords.time) / 1000; // seconds
      if (dt > 0.5 && dt < 10) {
        const dist = haversine(prevCoords.lat, prevCoords.lng, lat, lng);
        const speedMs = dist / dt;
        const speedKmh = speedMs * 3.6;

        // Kalman filter + drift threshold
        if (speedKmh >= 0 && speedKmh <= 300) {
          currentSpeed = kalmanUpdate(speedKmh);
          updateSpeedDisplay();
          checkExceed();
        }
      }
    }

    prevCoords = { lat, lng, time: now };

    // Check camera proximity
    checkCameras(lat, lng);
  }

  function onGPSError(err) {
    updateGPSIndicator(true, true);
    setStatus('Помилка GPS: ' + (err.message || err), 'error');
    if (running) {
      // Keep running, GPS might recover
    }
  }

  // ─── Speed display ─────────────────────────────────────────────────────
  function updateSpeedDisplay() {
    const display = Math.round(currentSpeed);
    speedValue.textContent = display;

    // Color via gradient is default in CSS. For exceed we add class?
  }

  // ─── Speed limit ───────────────────────────────────────────────────────
  function updateLimitDisplay() {
    limitValue.textContent = speedLimit;
  }

  limitMinus.addEventListener('click', () => {
    speedLimit = Math.max(LIMIT_MIN, speedLimit - LIMIT_STEP);
    updateLimitDisplay();
    // Reset exceed timer if currently exceeding and new limit is above speed
    checkExceed();
  });

  limitPlus.addEventListener('click', () => {
    speedLimit = Math.min(LIMIT_MAX, speedLimit + LIMIT_STEP);
    updateLimitDisplay();
    checkExceed();
  });

  // ─── Exceed timer / siren ──────────────────────────────────────────────
  function checkExceed() {
    if (!running) return;

    const exceeding = currentSpeed > speedLimit;

    if (exceeding) {
      if (exceedTimer === null) {
        // Start/restart the countdown
        exceedSeconds = 0;
        exceedTimer = setInterval(tickExceed, 1000);
      }
    } else {
      resetExceed();
    }

    updateTimerUI();
  }

  function tickExceed() {
    exceedSeconds++;

    if (exceedSeconds >= EXCEED_TIMEOUT) {
      // Trigger siren!
      if (!sirenActive) {
        sirenActive = true;
        sirenAlert.classList.add('active');
        startSirenSound();
        setStatus('⚠ ПЕРЕВИЩЕННЯ! ЗМЕНШІТЬ ШВИДКІСТЬ!', 'error');
      }
    }

    updateTimerUI();

    // If speed dropped while siren is active, keep siren going (per spec:
    // siren until speed drops below limit). We check in checkExceed() which
    // calls resetExceed when speed < limit.
    // Also re-check speed in case it dropped
    if (currentSpeed <= speedLimit && sirenActive) {
      resetExceed();
    }
  }

  function updateTimerUI() {
    if (exceedTimer !== null) {
      const pct = Math.min(100, (exceedSeconds / EXCEED_TIMEOUT) * 100);
      timerFill.style.width = pct + '%';
      timerTrack.classList.add('visible');
      timerLabel.textContent = exceedSeconds + ' / ' + EXCEED_TIMEOUT + ' с';
      timerLabel.classList.add('visible');
    } else {
      timerTrack.classList.remove('visible');
      timerLabel.classList.remove('visible');
    }
  }

  function resetExceed() {
    if (exceedTimer !== null) {
      clearInterval(exceedTimer);
      exceedTimer = null;
    }
    exceedSeconds = 0;
    if (sirenActive) {
      sirenActive = false;
      sirenAlert.classList.remove('active');
      stopSirenSound();
    }
    updateTimerUI();
    if (running) {
      setStatus('Відстеження активне', 'active');
    }
  }

  // ─── Cameras API ───────────────────────────────────────────────────────
  function loadCamerasFromCache() {
    try {
      const data = localStorage.getItem('speedcam_cameras');
      if (data) {
        cameras = JSON.parse(data);
      }
    } catch (_) {
      cameras = [];
    }
  }

  function saveCamerasToCache() {
    try {
      localStorage.setItem('speedcam_cameras', JSON.stringify(cameras));
    } catch (_) { /* storage full or unavailable */ }
  }

  async function fetchCameras() {
    try {
      const res = await fetch(API_BASE + '/api/cameras');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      cameras = data;
      saveCamerasToCache();
      return true;
    } catch (err) {
      console.warn('Failed to fetch cameras from API, using cache:', err);
      loadCamerasFromCache();
      return false;
    }
  }

  function checkCameras(lat, lng) {
    let nearest = null;
    let nearestDist = Infinity;

    for (const cam of cameras) {
      const dist = haversine(lat, lng, cam.lat, cam.lng);
      if (dist < nearestDist) {
        nearestDist = dist;
        nearest = cam;
      }
    }

    if (nearest && nearestDist <= CAMERA_WARN_RADIUS) {
      // Show warning
      const limitText = nearest.speed_limit ? ' (' + nearest.speed_limit + ' км/год)' : '';
      cameraWarningText.textContent = '📷 Камера попереду' + limitText;
      const distM = Math.round(nearestDist);
      const distText = distM >= 1000 ? (distM / 1000).toFixed(1) + ' км' : distM + ' м';
      cameraDistance.textContent = '— ' + distText;
      cameraWarning.classList.add('visible');

      // Beep every 5 seconds if still within range
      const now = Date.now();
      if (now - lastCameraBeep > 5000) {
        playCameraBeep();
        lastCameraBeep = now;
      }

      lastNearCamera = nearest;
    } else {
      cameraWarning.classList.remove('visible');
      lastNearCamera = null;
    }
  }

  // ─── Add camera ────────────────────────────────────────────────────────
  btnAddCamera.addEventListener('click', async () => {
    if (!running || !prevCoords) {
      setStatus('Спочатку запустіть відстеження (СТАРТ)', 'error');
      return;
    }

    const payload = {
      lat: prevCoords.lat,
      lng: prevCoords.lng
    };

    try {
      const res = await fetch(API_BASE + '/api/cameras', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const newCam = await res.json();
      cameras.push(newCam);
      saveCamerasToCache();
      setStatus('✅ Камеру додано!', 'active');
    } catch (err) {
      setStatus('Помилка додавання камери: ' + err.message, 'error');
    }
  });

  // ─── Remove nearest camera ─────────────────────────────────────────────
  btnRemoveCamera.addEventListener('click', async () => {
    if (!running || !prevCoords) {
      setStatus('Спочатку запустіть відстеження (СТАРТ)', 'error');
      return;
    }

    // Find nearest camera within 200m
    let nearest = null;
    let nearestDist = Infinity;
    for (const cam of cameras) {
      const dist = haversine(prevCoords.lat, prevCoords.lng, cam.lat, cam.lng);
      if (dist < nearestDist) {
        nearestDist = dist;
        nearest = cam;
      }
    }

    if (!nearest || nearestDist > CAMERA_NEAR_RADIUS) {
      setStatus('Поруч немає камер у радіусі 200м', 'error');
      return;
    }

    if (!confirm('Видалити камеру за ' + Math.round(nearestDist) + ' м (ліміт ' + (nearest.speed_limit || '?') + ' км/год)?')) {
      return;
    }

    try {
      const res = await fetch(API_BASE + '/api/cameras/nearby', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ lat: nearest.lat, lng: nearest.lng })
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      // Remove from local cache
      cameras = cameras.filter(c => c !== nearest);
      saveCamerasToCache();
      setStatus('Камеру видалено!', 'active');
    } catch (err) {
      setStatus('Помилка видалення камери: ' + err.message, 'error');
    }
  });

  // ─── Start / Stop ──────────────────────────────────────────────────────
  btnStartStop.addEventListener('click', () => {
    if (running) {
      stopTracking();
    } else {
      startTracking();
    }
  });

  async function startTracking() {
    // Create AudioContext on user gesture
    ensureAudioCtx();

    setStatus('Запуск GPS...', 'active');
    btnStartStop.textContent = 'СТОП';
    btnStartStop.className = 'btn-startstop running';
    running = true;

    // Detect WebView
    isWebView = typeof window.SpeedCamBridge !== 'undefined';

    // Load cameras from API
    await fetchCameras();

    if (isWebView) {
      // Android WebView — register native GPS callback
      window.SpeedCamBridgeCallback = onPosition;
      // Notify native to start GPS
      if (window.SpeedCamBridge && window.SpeedCamBridge.startGps) {
        window.SpeedCamBridge.startGps();
      }
      updateGPSIndicator(true, false);
      setStatus('WebView режим — GPS від нативного коду', 'active');
    } else {
      // Browser — use navigator.geolocation
      if (!navigator.geolocation) {
        setStatus('GPS недоступний у цьому браузері', 'error');
        stopTracking();
        return;
      }

      watchId = navigator.geolocation.watchPosition(
        (pos) => {
          const lat = pos.coords.latitude;
          const lng = pos.coords.longitude;
          const timeMs = pos.timestamp;
          onPosition(lat, lng, timeMs, pos.coords.accuracy);
        },
        onGPSError,
        {
          enableHighAccuracy: true,
          timeout: 10000,
          maximumAge: 3000
        }
      );

      if (watchId) {
        updateGPSIndicator(true, false);
        setStatus('Відстеження активне', 'active');
      }
    }
  }

  function stopTracking() {
    running = false;
    btnStartStop.textContent = 'СТАРТ';
    btnStartStop.className = 'btn-startstop stopped';

    // Stop GPS
    if (isWebView) {
      if (window.SpeedCamBridge && window.SpeedCamBridge.stopGps) {
        window.SpeedCamBridge.stopGps();
      }
      updateGPSIndicator(false, false);
    } else {
      if (watchId !== null) {
        navigator.geolocation.clearWatch(watchId);
        watchId = null;
      }
      updateGPSIndicator(false, false);
    }

    // Reset exceed
    resetExceed();

    // Reset speed display
    currentSpeed = 0;
    speedValue.textContent = '—';

    // Hide camera warning
    cameraWarning.classList.remove('visible');

    // Stop sounds
    stopSirenSound();

    // Reset state
    prevCoords = null;

    setStatus('Зупинено', '');
  }

  // ─── WebView native GPS injection ──────────────────────────────────────
  // If the native side calls us via: SpeedCamBridgeCallback(lat, lng, timeMs, accuracy)
  // This is set above in startTracking as window.SpeedCamBridgeCallback = onPosition;
  //
  // Also allow direct calls from Java: evaluateJavascript("onNativePosition(lat, lng, timeMs, accuracy)")
  window.onNativePosition = function (lat, lng, timeMs, accuracy) {
    if (running) {
      onPosition(lat, lng, timeMs || Date.now(), accuracy || 0);
    }
  };

  // ─── Init ──────────────────────────────────────────────────────────────
  updateLimitDisplay();
  loadCamerasFromCache();
  setStatus('Натисніть СТАРТ', '');

  // Register service worker
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/service-worker.js').catch((err) => {
      console.warn('SW registration failed:', err);
    });
  }

})();