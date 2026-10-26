// Recibe el stream del teléfono por WebSocket y lo decodifica con WebCodecs.
// Es la única copia de este código: la usan la extensión (bridge.js) y el visor
// de prueba (web/visor.html, a través de web/receiver.js).
//
// Uso:
//   const receiver = new PhoneCamReceiver({
//     onFrame: (frame) => { ctx.drawImage(frame, 0, 0); frame.close(); }, // ¡siempre cerrar el frame!
//     onStatus: (status, detail) => {},
//     onStats: (stats) => {},
//   });
//   receiver.connect('ws://127.0.0.1:5000');

const HEADER_SIZE = 10;
const PROTOCOL_VERSION = 1;
const TYPE_KEYFRAME = 1;
// Si el decodificador acumula más cuadros que esto, vamos atrasados:
// descartamos deltas hasta el próximo keyframe (la misma idea que FrameHub en el teléfono).
const MAX_DECODE_QUEUE = 3;
const RECONNECT_MS = 1000;
// El teléfono cierra con este código si el código de acceso no coincide (WebSocketServer.kt).
const CLOSE_BAD_CODE = 4001;

/** Lee el encabezado de 10 bytes. Ver Protocol.kt. */
export function parseMessage(buffer) {
  if (buffer.byteLength <= HEADER_SIZE) throw new Error('Mensaje demasiado corto');
  const view = new DataView(buffer);
  const version = view.getUint8(0);
  if (version !== PROTOCOL_VERSION) throw new Error(`Versión de protocolo no soportada: ${version}`);
  return {
    isKey: view.getUint8(1) === TYPE_KEYFRAME,
    timestamp: Number(view.getBigUint64(2)), // microsegundos
    data: new Uint8Array(buffer, HEADER_SIZE),
  };
}

/**
 * Busca el SPS (NAL tipo 7) dentro de un keyframe y arma el "codec string"
 * que pide WebCodecs: avc1.PPCCLL (perfil, restricciones y nivel, en hex).
 * Ejemplo: Baseline nivel 3.1 → avc1.42001F (o avc1.42C01F según las restricciones).
 */
export function codecFromKeyframe(data) {
  const hex = (b) => b.toString(16).padStart(2, '0').toUpperCase();
  for (let i = 0; i + 6 < data.length; i++) {
    if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
      if ((data[i + 3] & 0x1f) === 7) return `avc1.${hex(data[i + 4])}${hex(data[i + 5])}${hex(data[i + 6])}`;
      i += 2;
    }
  }
  return null;
}

export class PhoneCamReceiver {
  #ws = null;
  #decoder = null;
  #url = null;
  #shouldRun = false;
  #retryTimer = null;
  #statsTimer = null;
  #waitingForKeyframe = true;
  #arrivals = new Map(); // timestamp → momento en que llegó (para medir la decodificación)
  #window = null;

  /**
   * createSocket permite cambiar cómo se abre la conexión. Por defecto es un WebSocket
   * normal; la extensión usa uno que pasa por su service worker (ver bridge.js).
   * Lo que devuelva debe imitar a WebSocket: binaryType, onopen, onmessage, onclose y close().
   */
  constructor({ onFrame, onStatus = () => {}, onStats = () => {}, hardwareAcceleration = 'no-preference', createSocket = (url) => new WebSocket(url) }) {
    this.onFrame = onFrame;
    this.createSocket = createSocket;
    this.hardwareAcceleration = hardwareAcceleration;
    this.onStatus = onStatus;
    this.onStats = onStats;
    this.codec = null;
    this.width = 0;
    this.height = 0;
  }

  /** 'no-preference' | 'prefer-hardware' | 'prefer-software'. Recrea el decodificador en el próximo keyframe. */
  setHardwareAcceleration(value) {
    this.hardwareAcceleration = value;
    this.#closeDecoder();
  }

  static isSupported() {
    return typeof VideoDecoder !== 'undefined' && typeof EncodedVideoChunk !== 'undefined';
  }

  connect(url) {
    if (!PhoneCamReceiver.isSupported()) {
      this.onStatus('error', 'Este navegador no soporta WebCodecs. Usa Chrome y abre la página desde http://localhost.');
      return;
    }
    this.disconnect();
    this.#url = url;
    this.#shouldRun = true;
    this.#resetWindow();
    this.#statsTimer = setInterval(() => this.#emitStats(), 1000);
    this.#open();
  }

  disconnect() {
    this.#shouldRun = false;
    clearTimeout(this.#retryTimer);
    clearInterval(this.#statsTimer);
    if (this.#ws) {
      this.#ws.onclose = null;
      this.#ws.close();
      this.#ws = null;
    }
    this.#closeDecoder();
    this.onStatus('desconectado');
  }

  #open() {
    this.onStatus('conectando', this.#url);
    const ws = this.createSocket(this.#url);
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => {
      this.#waitingForKeyframe = true;
      this.onStatus('conectado', this.#url);
    };
    ws.onmessage = (event) => this.#onMessage(event.data);
    ws.onclose = (event) => {
      this.#ws = null;
      this.#closeDecoder();
      if (!this.#shouldRun) return;
      if (event?.code === CLOSE_BAD_CODE) {
        // Reintentar no sirve (y el teléfono bloquea tras 5 intentos): esperamos a que lo corrijas.
        this.#shouldRun = false;
        this.onStatus('error', 'Código de acceso incorrecto. Copia la dirección que muestra la app del teléfono.');
        return;
      }
      this.onStatus('reconectando', 'Sin conexión con el teléfono. Reintentando cada segundo…');
      this.#retryTimer = setTimeout(() => this.#open(), RECONNECT_MS);
    };
    this.#ws = ws;
  }

  #onMessage(buffer) {
    let message;
    try {
      message = parseMessage(buffer);
    } catch (e) {
      this.onStatus('error', e.message);
      return;
    }
    const w = this.#window;
    w.received++;
    w.bytes += message.data.length;

    // Sin keyframe no hay nada que decodificar: esperamos.
    if (this.#waitingForKeyframe && !message.isKey) {
      w.skipped++;
      return;
    }

    if (!this.#decoder) {
      const codec = codecFromKeyframe(message.data);
      if (!codec) {
        this.onStatus('error', 'El keyframe no trae SPS; no se puede configurar el decodificador.');
        return;
      }
      this.#createDecoder(codec);
    }

    // Vamos atrasados: soltamos deltas hasta el próximo keyframe.
    if (!message.isKey && this.#decoder.decodeQueueSize > MAX_DECODE_QUEUE) {
      this.#waitingForKeyframe = true;
      w.skipped++;
      return;
    }

    this.#waitingForKeyframe = false;
    this.#arrivals.set(message.timestamp, performance.now());
    this.#decoder.decode(new EncodedVideoChunk({
      type: message.isKey ? 'key' : 'delta',
      timestamp: message.timestamp,
      data: message.data,
    }));
  }

  #createDecoder(codec) {
    this.codec = codec;
    this.#decoder = new VideoDecoder({
      output: (frame) => {
        const w = this.#window;
        w.decoded++;
        const arrived = this.#arrivals.get(frame.timestamp);
        if (arrived !== undefined) {
          w.decodeMsTotal += performance.now() - arrived;
          this.#arrivals.delete(frame.timestamp);
        }
        this.width = frame.displayWidth;
        this.height = frame.displayHeight;
        this.onFrame(frame); // quien recibe el frame debe cerrarlo
      },
      error: (e) => {
        this.onStatus('error', `Error del decodificador (${codec}): ${e.message}`);
        this.#decoder = null; // el próximo keyframe crea uno nuevo
        this.#waitingForKeyframe = true;
      },
    });
    this.#decoder.configure({ codec, optimizeForLatency: true, hardwareAcceleration: this.hardwareAcceleration });
  }

  #closeDecoder() {
    if (this.#decoder && this.#decoder.state !== 'closed') this.#decoder.close();
    this.#decoder = null;
    this.#arrivals.clear();
    this.#waitingForKeyframe = true;
  }

  #resetWindow() {
    this.#window = { received: 0, decoded: 0, skipped: 0, bytes: 0, decodeMsTotal: 0, start: performance.now() };
  }

  #emitStats() {
    const w = this.#window;
    const seconds = (performance.now() - w.start) / 1000;
    this.onStats({
      receivedFps: Math.round(w.received / seconds),
      decodedFps: Math.round(w.decoded / seconds),
      kbps: Math.round((w.bytes * 8) / 1000 / seconds),
      skipped: w.skipped,
      decodeMs: w.decoded ? Math.round(w.decodeMsTotal / w.decoded) : null,
      queue: this.#decoder?.decodeQueueSize ?? 0,
      codec: this.codec,
      width: this.width,
      height: this.height,
    });
    // Si el mapa crece (cuadros que nunca salieron), lo limpiamos.
    if (this.#arrivals.size > 120) this.#arrivals.clear();
    this.#resetWindow();
  }
}
