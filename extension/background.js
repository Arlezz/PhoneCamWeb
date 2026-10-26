// Service worker de la extensión: abre el WebSocket hacia el teléfono en nombre de la página.
//
// ¿Por qué aquí? Meet se carga por https, y Chrome bloquea que una página https (o
// cualquier cosa dentro de ella, incluido un content script o un iframe) abra un
// WebSocket sin cifrar (ws://) hacia la red local: es "contenido mixto". localhost es
// la excepción, por eso por cable funcionaba. El service worker no pertenece a ninguna
// página, así que puede conectarse a ws://192.168.x.x sin ese bloqueo.
//
// Protocolo con bridge.js (por un chrome.runtime.Port llamado "phonecam-relay"):
//   bridge → {t:'connect', url}
//   worker → {t:'open'} · {t:'d', b:<bytes en base64>} · {t:'close', code, reason}

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== 'phonecam-relay') return;
  let ws = null;

  port.onMessage.addListener((message) => {
    if (message?.t !== 'connect' || ws) return;
    try {
      ws = new WebSocket(message.url);
    } catch (error) {
      port.postMessage({ t: 'close', code: 1006, reason: error.message });
      port.disconnect();
      return;
    }
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => port.postMessage({ t: 'open' });
    // Los mensajes entre partes de una extensión viajan como JSON: los bytes van en base64.
    ws.onmessage = (event) => port.postMessage({ t: 'd', b: toBase64(new Uint8Array(event.data)) });
    ws.onclose = (event) => {
      try {
        port.postMessage({ t: 'close', code: event.code, reason: event.reason });
        port.disconnect();
      } catch {
        // la pestaña ya se cerró
      }
    };
  });

  // La pestaña se cerró, navegó o soltó la cámara: cerramos la conexión con el teléfono.
  port.onDisconnect.addListener(() => {
    if (!ws) return;
    ws.onclose = null;
    ws.close();
  });
});

function toBase64(bytes) {
  if (typeof bytes.toBase64 === 'function') return bytes.toBase64(); // Chrome reciente
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}
