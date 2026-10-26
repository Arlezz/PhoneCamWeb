// Se ejecuta en el contexto aislado de la extensión (no le aplican las reglas CSP
// del sitio). Cuando inject.js avisa que la página quiere la cámara, se conecta al
// teléfono, decodifica el video y lo dibuja en el canvas compartido.

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
      receiver = new PhoneCamReceiver({ onFrame, onStatus, onStats });
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
