/* ===== SpeedCam Assistant — Application Logic ===== */
(function () {
  'use strict';

  // ─── DOM refs ──────────────────────────────────────────────────────────
  const $ = (id) => document.getElementById(id);
  const speedValue     = $('speedValue');
  const gpsDot         = $('gpsDot');
  const gpsText        = $('gpsText');
  const limitValue     = $('limitValue');
  const limitMinus     = $('limitMinus');
  const limitPlus      = $('limitPlus');
  const timerTrack     = $('timerTrack');
  const timerFill      = $('timerFill');
  const timerLabel     = $('timerLabel');
  const sirenAlert     = $('sirenAlert');
  const btnStartStop   = $('btnStartStop');
  const statusText     = $('statusText');

  // ─── State ─────────────────────────────────────────────────────────────
  let running         = false;
  let speedLimit      = 80;
  let currentSpeed    = 0;
  let prevCoords      = null;
  let exceedTimer     = null;
  let exceedSeconds   = 0;
  let sirenActive     = false;
  let sirenAudioTimer = null;
  let watchId         = null;
  let audioCtx        = null;
  let isWebView       = false;

  // Speed limit range
  const LIMIT_MIN = 30;
  const LIMIT_MAX = 150;
  const LIMIT_STEP = 1;
  const EXCEED_TIMEOUT = 30; // seconds before siren

  // ─── Kalman filter (1D) for speed ───────────────────────────────────────
  let kalmanX = 0;
  let kalmanP = 0.5;
  const KALMAN_Q = 0.1;
  const KALMAN_R = 3.0;
  const DRIFT_THRESHOLD = 2.5;

  function kalmanUpdate(z) {
    kalmanP = kalmanP + KALMAN_Q;
    const k = kalmanP / (kalmanP + KALMAN_R);
    kalmanX = kalmanX + k * (z - kalmanX);
    kalmanP = (1 - k) * kalmanP;
    if (kalmanX < DRIFT_THRESHOLD) {
      kalmanX = 0;
    }
    return kalmanX;
  }

  // ─── Haversine ─────────────────────────────────────────────────────────
  function haversine(lat1, lng1, lat2, lng2) {
    const R = 6371000;
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

  function startSirenSound() {
    stopSirenSound();
    ensureAudioCtx();
    if (!audioCtx) return;

    const now = audioCtx.currentTime;
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();

    osc.type = 'sine';
    gain.gain.setValueAtTime(0.25, now);
    gain.gain.setValueAtTime(0.25, now + 100);

    for (let i = 0; i < 200; i++) {
      const t = now + i * 0.5;
      osc.frequency.setValueAtTime(500, t);
      osc.frequency.linearRampToValueAtTime(1000, t + 0.25);
      osc.frequency.linearRampToValueAtTime(500, t + 0.5);
    }

    osc.connect(gain).connect(audioCtx.destination);
    osc.start(now);
    sirenAudioTimer = { osc, gain };
  }

  function stopSirenSound() {
    if (sirenAudioTimer) {
      try {
        sirenAudioTimer.osc.stop();
        sirenAudioTimer.osc.disconnect();
        sirenAudioTimer.gain.disconnect();
      } catch (_) {}
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

  function onPosition(lat, lng, timeMs) {
    updateGPSIndicator(true, false);
    const now = timeMs || Date.now();

    if (prevCoords && prevCoords.time) {
      const dt = (now - prevCoords.time) / 1000;
      if (dt > 0.5 && dt < 10) {
        const dist = haversine(prevCoords.lat, prevCoords.lng, lat, lng);
        const speedKmh = (dist / dt) * 3.6;
        if (speedKmh >= 0 && speedKmh <= 300) {
          currentSpeed = kalmanUpdate(speedKmh);
          updateSpeedDisplay();
          checkExceed();
        }
      }
    }
    prevCoords = { lat, lng, time: now };
  }

  function onGPSError(err) {
    updateGPSIndicator(true, true);
    setStatus('Помилка GPS: ' + (err.message || err), 'error');
  }

  // ─── Speed display ─────────────────────────────────────────────────────
  function updateSpeedDisplay() {
    speedValue.textContent = Math.round(currentSpeed);
  }

  // ─── Speed limit ───────────────────────────────────────────────────────
  function updateLimitDisplay() {
    limitValue.textContent = speedLimit;
  }

  limitMinus.addEventListener('click', () => {
    speedLimit = Math.max(LIMIT_MIN, speedLimit - LIMIT_STEP);
    updateLimitDisplay();
    if (running) checkExceed();
  });

  limitPlus.addEventListener('click', () => {
    speedLimit = Math.min(LIMIT_MAX, speedLimit + LIMIT_STEP);
    updateLimitDisplay();
    if (running) checkExceed();
  });

  // ─── Exceed timer / siren ──────────────────────────────────────────────
  function checkExceed() {
    if (!running) return;
    if (currentSpeed > speedLimit) {
      if (exceedTimer === null) {
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
    if (exceedSeconds >= EXCEED_TIMEOUT && !sirenActive) {
      sirenActive = true;
      sirenAlert.classList.add('active');
      startSirenSound();
      setStatus('⚠ ПЕРЕВИЩЕННЯ! ЗМЕНШІТЬ ШВИДКІСТЬ!', 'error');
    }
    updateTimerUI();

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
    if (running) setStatus('Відстеження активне', 'active');
  }

  // ─── Start / Stop ──────────────────────────────────────────────────────
  btnStartStop.addEventListener('click', () => {
    if (running) stopTracking();
    else startTracking();
  });

  async function startTracking() {
    ensureAudioCtx();
    setStatus('Запуск GPS...', 'active');
    btnStartStop.textContent = 'СТОП';
    btnStartStop.className = 'btn-startstop running';
    running = true;

    isWebView = typeof window.SpeedCamBridge !== 'undefined';

    if (isWebView) {
      window.SpeedCamBridgeCallback = onPosition;
      if (window.SpeedCamBridge && window.SpeedCamBridge.startGps) {
        window.SpeedCamBridge.startGps();
      }
      updateGPSIndicator(true, false);
      setStatus('WebView режим — GPS від нативного коду', 'active');
    } else {
      if (!navigator.geolocation) {
        setStatus('GPS недоступний', 'error');
        stopTracking();
        return;
      }
      watchId = navigator.geolocation.watchPosition(
        (pos) => onPosition(pos.coords.latitude, pos.coords.longitude, pos.timestamp),
        onGPSError,
        { enableHighAccuracy: true, timeout: 10000, maximumAge: 3000 }
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
    btnStartStop.className = 'btn-startstop';
    updateGPSIndicator(false, false);

    if (!isWebView && watchId !== null) {
      navigator.geolocation.clearWatch(watchId);
      watchId = null;
    }
    if (isWebView && window.SpeedCamBridge && window.SpeedCamBridge.stopGps) {
      window.SpeedCamBridge.stopGps();
    }
    resetExceed();
    prevCoords = null;
    setStatus('Зупинено', '');
  }

  // ─── Init ──────────────────────────────────────────────────────────────
  updateLimitDisplay();
  updateSpeedDisplay();
  updateTimerUI();

  if (typeof window.SpeedCamBridge !== 'undefined') {
    isWebView = true;
    window.SpeedCamBridgeCallback = onPosition;
  }
})();