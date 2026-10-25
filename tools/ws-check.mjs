#!/usr/bin/env node
// Verifica el stream del teléfono (criterio de término de la fase 1).
//
// Uso:
//   adb forward tcp:5000 tcp:5000
//   node tools/ws-check.mjs [url] [segundos] [--save captura.h264]
//
// Ejemplos:
//   node tools/ws-check.mjs                          # ws://127.0.0.1:5000 durante 600 s
//   node tools/ws-check.mjs ws://127.0.0.1:5000 30   # prueba corta
//   node tools/ws-check.mjs ws://127.0.0.1:5000 10 --save captura.h264
//   ffplay -f h264 captura.h264                      # ver lo guardado (opcional)
//
// Requiere Node 22 o superior (WebSocket global).

import { createWriteStream } from 'node:fs';

const args = process.argv.slice(2);
const saveIndex = args.indexOf('--save');
const savePath = saveIndex >= 0 ? args[saveIndex + 1] : null;
const positional = args.filter((_, i) => saveIndex < 0 || (i !== saveIndex && i !== saveIndex + 1));
const url = positional[0] ?? 'ws://127.0.0.1:5000';
const seconds = Number(positional[1] ?? 600);
const MIN_FPS = 25;

if (typeof WebSocket === 'undefined') {
  console.error('Este script necesita Node 22 o superior (WebSocket global).');
  process.exit(2);
}

const errors = [];
const totals = { frames: 0, keyframes: 0, bytes: 0 };
let win = { frames: 0, keyframes: 0, bytes: 0, maxGapMs: 0 };
let first = true;
let lastPts = -1n;
let lastArrival = 0;
let secondsElapsed = 0;
const out = savePath ? createWriteStream(savePath) : null;

/** Tipos de NAL presentes en un payload Annex-B (5 = IDR, 7 = SPS, 8 = PPS). */
function nalTypes(bytes) {
  const types = [];
  for (let i = 0; i + 3 < bytes.length; i++) {
    if (bytes[i] === 0 && bytes[i + 1] === 0) {
      if (bytes[i + 2] === 1) { types.push(bytes[i + 3] & 0x1f); i += 3; }
      else if (bytes[i + 2] === 0 && bytes[i + 3] === 1 && i + 4 < bytes.length) { types.push(bytes[i + 4] & 0x1f); i += 4; }
    }
  }
  return types;
}

function fail(message) {
  if (errors.length < 20) console.error(`  ✗ ${message}`);
  errors.push(message);
}

const ws = new WebSocket(url);
ws.binaryType = 'arraybuffer';

ws.addEventListener('open', () => console.log(`Conectado a ${url}. Midiendo durante ${seconds} s...\n`));

ws.addEventListener('message', (event) => {
  if (!(event.data instanceof ArrayBuffer)) return fail('Llegó un mensaje de texto; se esperaba binario');
  const view = new DataView(event.data);
  if (view.byteLength < 11) return fail(`Mensaje demasiado corto (${view.byteLength} bytes)`);

  const version = view.getUint8(0);
  const type = view.getUint8(1);
  const pts = view.getBigUint64(2);
  const payload = new Uint8Array(event.data, 10);

  if (version !== 1) fail(`Versión ${version}; se esperaba 1`);
  if (type !== 1 && type !== 2) fail(`Tipo ${type}; se esperaba 1 o 2`);
  if (first && type !== 1) fail('El primer mensaje no es un keyframe');
  if (pts < lastPts) fail(`Timestamp retrocedió: ${lastPts} → ${pts}`);
  if (!(payload[0] === 0 && payload[1] === 0 && (payload[2] === 1 || (payload[2] === 0 && payload[3] === 1)))) {
    fail('El payload no empieza con un start code Annex-B');
  }
  if (type === 1) {
    const types = nalTypes(payload);
    if (!types.includes(7) || !types.includes(8)) fail(`Keyframe sin SPS/PPS (NAL: ${types.join(',')})`);
    if (!types.includes(5)) fail(`Keyframe sin IDR (NAL: ${types.join(',')})`);
  }

  const now = performance.now();
  if (lastArrival > 0) win.maxGapMs = Math.max(win.maxGapMs, now - lastArrival);
  lastArrival = now;
  first = false;
  lastPts = pts;

  win.frames++; totals.frames++;
  win.bytes += payload.length; totals.bytes += payload.length;
  if (type === 1) { win.keyframes++; totals.keyframes++; }
  out?.write(payload);
});

const ticker = setInterval(() => {
  secondsElapsed++;
  console.log(
    `t=${String(secondsElapsed).padStart(4)} s  ${String(win.frames).padStart(3)} fps  ` +
    `${String(Math.round(win.bytes * 8 / 1000)).padStart(6)} kbps  ` +
    `keyframes ${win.keyframes}  pausa máx ${Math.round(win.maxGapMs)} ms`,
  );
  win = { frames: 0, keyframes: 0, bytes: 0, maxGapMs: 0 };
  if (secondsElapsed >= seconds) finish();
}, 1000);

ws.addEventListener('close', () => {
  if (secondsElapsed < seconds) { fail(`La conexión se cerró a los ${secondsElapsed} s`); finish(); }
});
ws.addEventListener('error', () => {
  fail(`No se pudo conectar a ${url}. ¿Corriste "adb forward tcp:5000 tcp:5000" y la app está transmitiendo?`);
  finish();
});

let finished = false;
function finish() {
  if (finished) return;
  finished = true;
  clearInterval(ticker);
  ws.close();
  out?.end();
  const elapsed = Math.max(secondsElapsed, 1);
  const avgFps = totals.frames / elapsed;
  if (totals.frames > 0 && avgFps < MIN_FPS) fail(`Promedio ${avgFps.toFixed(1)} fps; se esperaban al menos ${MIN_FPS}`);
  if (totals.frames === 0) fail('No llegó ningún cuadro');

  console.log('\nResumen');
  console.log(`  Duración:   ${elapsed} s`);
  console.log(`  Cuadros:    ${totals.frames} (${avgFps.toFixed(1)} fps promedio)`);
  console.log(`  Keyframes:  ${totals.keyframes}`);
  console.log(`  Bitrate:    ${Math.round(totals.bytes * 8 / 1000 / elapsed)} kbps promedio`);
  if (savePath) console.log(`  Guardado:   ${savePath} (ffplay -f h264 ${savePath})`);
  console.log(errors.length === 0 ? '\nRESULTADO: OK' : `\nRESULTADO: FALLA (${errors.length} problemas)`);
  process.exit(errors.length === 0 ? 0 : 1);
}
