const DEFAULT_ADDRESS = 'ws://127.0.0.1:5000';
const $ = (id) => document.getElementById(id);
const labels = {
  conectado: 'Transmitiendo',
  conectando: 'Conectando…',
  reconectando: 'Reconectando…',
  desconectado: 'Desconectado',
  error: 'Error',
};

async function init() {
  const { address = DEFAULT_ADDRESS } = await chrome.storage.local.get('address');
  $('address').value = address;
  await refreshStatus();
  setInterval(refreshStatus, 1000);
}

/** Pregunta a la pestaña actual si está usando la cámara del teléfono. */
async function refreshStatus() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  let state = null;
  try {
    state = await chrome.tabs.sendMessage(tab.id, { type: 'phonecam:status' });
  } catch {
    // La pestaña no tiene la extensión (por ejemplo, chrome://) o no respondió.
  }
  if (!state) {
    $('dot').className = 'dot';
    $('status').textContent = 'Sin uso en esta pestaña';
    $('detail').textContent = '';
    return;
  }
  $('dot').className = `dot ${state.status}`;
  $('status').textContent = labels[state.status] ?? state.status;
  const s = state.stats;
  $('detail').textContent = state.status === 'conectado' && s?.width
    ? `${s.width}×${s.height} · ${s.decodedFps} fps · ${s.kbps} kbps`
    : state.detail ?? '';
}

$('save').addEventListener('click', async () => {
  const address = $('address').value.trim() || DEFAULT_ADDRESS;
  if (!/^wss?:\/\//.test(address)) {
    $('saved').textContent = 'La dirección debe empezar con ws:// o wss://';
    return;
  }
  await chrome.storage.local.set({ address });
  $('saved').textContent = 'Guardado. Se usará la próxima vez que una página pida la cámara.';
});

init();
