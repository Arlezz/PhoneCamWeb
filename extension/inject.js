// Se ejecuta DENTRO de la página (world: "MAIN"), antes que el código de la página.
// Agrega "Cámara Android" a la lista de cámaras y, cuando la página la pide, le
// entrega el video de un <canvas>. Quien dibuja en ese canvas es bridge.js.
//
// No se conecta al teléfono desde aquí: este código queda sujeto a las reglas de
// seguridad (CSP) del sitio, que podrían bloquear la conexión a ws://127.0.0.1.

(() => {
  const DEVICE_ID = 'phonecam-web-android';
  const GROUP_ID = 'phonecam-web';
  const LABEL = 'Cámara Android (PhoneCam Web)';
  const CANVAS_ID = 'phonecam-web-canvas';
  const FRAME_RATE = 30;
  const STOP_DELAY_MS = 3000; // si la página cambia de cámara y vuelve, no reconectamos

  const mediaDevices = navigator.mediaDevices;
  if (!mediaDevices?.getUserMedia || !mediaDevices?.enumerateDevices) return;

  const originalGetUserMedia = mediaDevices.getUserMedia.bind(mediaDevices);
  const originalEnumerateDevices = mediaDevices.enumerateDevices.bind(mediaDevices);
  let activeTracks = 0;
  let stopTimer = null;

  // ---- La lista de cámaras ----

  function phoneDeviceInfo() {
    // Hereda de InputDeviceInfo para que `instanceof` funcione en la página.
    const info = Object.create(globalThis.InputDeviceInfo?.prototype ?? Object.prototype);
    const fields = { deviceId: DEVICE_ID, groupId: GROUP_ID, kind: 'videoinput', label: LABEL };
    for (const [key, value] of Object.entries(fields)) {
      Object.defineProperty(info, key, { value, enumerable: true });
    }
    info.toJSON = () => ({ ...fields });
    info.getCapabilities = () => capabilities();
    return info;
  }

  mediaDevices.enumerateDevices = async function enumerateDevices() {
    const devices = await originalEnumerateDevices();
    return [...devices, phoneDeviceInfo()];
  };

  // ---- Pedir la cámara ----

  /** ¿La página pidió nuestra cámara? deviceId puede ser texto, lista o {exact}/{ideal}. */
  function matches(value) {
    if (!value) return false;
    if (typeof value === 'string') return value === DEVICE_ID;
    if (Array.isArray(value)) return value.includes(DEVICE_ID);
    if (typeof value === 'object') return matches(value.exact) || matches(value.ideal);
    return false;
  }

  function requestsPhone(video) {
    if (!video || typeof video !== 'object') return false;
    return matches(video.deviceId) || (video.advanced ?? []).some((set) => matches(set.deviceId));
  }

  mediaDevices.getUserMedia = async function getUserMedia(constraints = {}) {
    if (!requestsPhone(constraints.video)) return originalGetUserMedia(constraints);

    const video = phoneVideoTrack();
    if (!constraints.audio) return new MediaStream([video]);

    // El audio sigue saliendo del micrófono real.
    try {
      const audio = await originalGetUserMedia({ audio: constraints.audio });
      return new MediaStream([video, ...audio.getAudioTracks()]);
    } catch (error) {
      video.stop();
      throw error;
    }
  };

  // ---- El canvas y su pista de video ----

  function getCanvas() {
    let canvas = document.getElementById(CANVAS_ID);
    if (!canvas) {
      canvas = document.createElement('canvas');
      canvas.id = CANVAS_ID;
      canvas.width = 1280;
      canvas.height = 720;
      // Fuera de la vista: solo sirve de puente entre bridge.js y la página.
      canvas.style.cssText = 'position:fixed;left:-10000px;top:0;width:1px;height:1px;pointer-events:none;';
      document.documentElement.appendChild(canvas);
    }
    return canvas;
  }

  function capabilities() {
    return {
      deviceId: DEVICE_ID,
      groupId: GROUP_ID,
      width: { min: 1, max: 1280 },
      height: { min: 1, max: 720 },
      frameRate: { min: 1, max: FRAME_RATE },
      aspectRatio: { min: 1.777, max: 1.778 },
      facingMode: [],
      resizeMode: ['none'],
    };
  }

  function phoneVideoTrack() {
    const canvas = getCanvas();
    clearTimeout(stopTimer);
    document.dispatchEvent(new CustomEvent('phonecam:start')); // bridge.js se conecta
    // Sin argumento: captura cada vez que el canvas cambia. Con captureStream(30) se
    // perdían cuadros (16 fps en vez de 30), porque los cuadros decodificados llegan
    // en ráfagas y solo se toma uno por cada ventana de 1/30 s.
    return patchTrack(canvas.captureStream().getVideoTracks()[0], canvas);
  }

  /**
   * Hace que la pista parezca una cámara: nombre, deviceId y capacidades.
   * También cuenta las pistas activas para desconectarse cuando ya nadie las usa.
   */
  function patchTrack(track, canvas) {
    activeTracks++;
    let released = false;
    const release = () => {
      if (released) return;
      released = true;
      activeTracks--;
      if (activeTracks === 0) {
        stopTimer = setTimeout(() => document.dispatchEvent(new CustomEvent('phonecam:stop')), STOP_DELAY_MS);
      }
    };

    const originalStop = track.stop.bind(track);
    const originalClone = track.clone.bind(track);
    const originalSettings = track.getSettings.bind(track);

    Object.defineProperty(track, 'label', { value: LABEL });
    track.stop = () => {
      originalStop();
      release();
    };
    track.addEventListener('ended', release);
    track.clone = () => {
      clearTimeout(stopTimer);
      return patchTrack(originalClone(), canvas);
    };
    track.getSettings = () => ({
      ...originalSettings(),
      deviceId: DEVICE_ID,
      groupId: GROUP_ID,
      width: canvas.width,
      height: canvas.height,
      aspectRatio: canvas.width / canvas.height,
      frameRate: FRAME_RATE,
    });
    track.getCapabilities = () => capabilities();
    // El tamaño lo decide el teléfono; aceptamos cualquier restricción sin cambiar nada.
    track.applyConstraints = async () => {};
    return track;
  }
})();
