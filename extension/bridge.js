// Se ejecuta en el contexto aislado de la extensión (no le aplican las reglas CSP
// del sitio). Cuando inject.js avisa que la página quiere la cámara, se conecta al
// teléfono, decodifica el video y lo dibuja en el canvas compartido.
//
// La conexión en sí la abre el service worker (background.js): desde aquí Chrome
// bloquearía ws:// hacia la red local por venir de una página https. RelaySocket
// imita a un WebSocket para que receiver.js no note la diferencia.

(() => {
  const CANVAS_ID = 'phonecam-web-canvas';
  const DEFAULT_ADDRESS = 'ws://127.0.0.1:5000';
  const PLACEHOLDER_MS = 250;

  let receiver = null;
  let canvas = null;
  let ctx = null;
  let running = false;
  let gotFrame = false;
  let placeholderTimer = null;
  let placeholderText = '';
  const state = { status: 'inactivo', detail: '', stats: null, address: null };

  document.addEventListener('phonecam:start', () => start().catch(reportError));
  document.addEventListener('phonecam:stop', stop);

  // El popup pregunta por el estado de la pestaña actual.
  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message?.type !== 'phonecam:status') return;
    // Solo responde el frame que está usando la cámara.
    if (state.status !== 'inactivo') sendResponse(state);
  });

  async function start() {
    if (running) return;
    canvas = document.getElementById(CANVAS_ID);
    if (!canvas) return;
    running = true;
    ctx = canvas.getContext('2d');

    if (!receiver) {
      // receiver.js es un módulo; los content scripts no, así que lo importamos así.
      const { PhoneCamReceiver } = await import(chrome.runtime.getURL('receiver.js'));
      receiver = new PhoneCamReceiver({ onFrame, onStatus, onStats, createSocket: (url) => new RelaySocket(url) });
    }
    const { address = DEFAULT_ADDRESS } = await chrome.storage.local.get('address');
    if (!running) return; // la página la soltó mientras cargábamos
    state.address = address;
    gotFrame = false;
    showPlaceholder('Conectando con el teléfono…');
    receiver.connect(address);
  }

  function stop() {
    running = false;
    receiver?.disconnect();
    stopPlaceholder();
    state.status = 'inactivo';
    state.stats = null;
  }

  function onFrame(frame) {
    if (!running || !ctx) {
      frame.close();
      return;
    }
    if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
      canvas.width = frame.displayWidth;
      canvas.height = frame.displayHeight;
    }
    ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
    frame.close();
    if (!gotFrame) {
      gotFrame = true;
      stopPlaceholder();
    }
  }

  function onStatus(status, detail = '') {
    state.status = status;
    state.detail = detail;
    if (!running) return;
    if (status === 'reconectando') {
      gotFrame = false;
      showPlaceholder('Sin conexión con el teléfono. Reintentando…');
    } else if (status === 'error') {
      showPlaceholder(detail);
    }
  }

  function onStats(stats) {
    state.stats = stats;
  }

  function reportError(error) {
    running = false;
    onStatus('error', `PhoneCam Web: ${error.message}`);
    console.error('[PhoneCam Web]', error);
  }

  /** Un "WebSocket" cuyo extremo real vive en el service worker. */
  class RelaySocket {
    constructor(url) {
      this.binaryType = 'arraybuffer';
      this.onopen = null;
      this.onmessage = null;
      this.onclose = null;
      this.closed = false;
      try {
        this.port = chrome.runtime.connect({ name: 'phonecam-relay' });
      } catch (error) {
        // La extensión se recargó: esta página quedó con una versión vieja.
        setTimeout(() => this.#finish(1006, 'Recarga la página: la extensión se actualizó'), 0);
        return;
      }
      this.port.onMessage.addListener((message) => {
        if (message.t === 'd') this.onmessage?.({ data: fromBase64(message.b).buffer });
        else if (message.t === 'open') this.onopen?.();
        else if (message.t === 'close') this.#finish(message.code, message.reason);
      });
      this.port.onDisconnect.addListener(() => this.#finish(1006, ''));
      this.port.postMessage({ t: 'connect', url });
    }

    close() {
      this.closed = true;
      try {
        this.port?.disconnect();
      } catch {
        // ya estaba cerrado
      }
    }

    #finish(code, reason) {
      if (this.closed) return;
      this.closed = true;
      this.onclose?.({ code, reason });
    }
  }

  function fromBase64(text) {
    if (typeof Uint8Array.fromBase64 === 'function') return Uint8Array.fromBase64(text); // Chrome reciente
    const binary = atob(text);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  // Mientras no hay video dibujamos un aviso. Además mantiene viva la pista:
  // captureStream solo entrega cuadros cuando el canvas cambia.
  function showPlaceholder(text) {
    placeholderText = text;
    if (!placeholderTimer) {
      drawPlaceholder();
      placeholderTimer = setInterval(drawPlaceholder, PLACEHOLDER_MS);
    }
  }

  function stopPlaceholder() {
    clearInterval(placeholderTimer);
    placeholderTimer = null;
  }

  function drawPlaceholder() {
    if (!ctx) return;
    const { width, height } = canvas;
    ctx.fillStyle = '#111';
    ctx.fillRect(0, 0, width, height);
    ctx.fillStyle = '#ddd';
    ctx.font = `${Math.round(height / 22)}px system-ui, sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('PhoneCam Web', width / 2, height / 2 - height / 14);
    ctx.fillStyle = '#999';
    ctx.fillText(placeholderText, width / 2, height / 2 + height / 28);
  }
})();
