#!/usr/bin/env node
// Servidor estático mínimo para el visor.
// WebCodecs solo funciona en "contexto seguro": https o http://localhost.
//
// Uso: node web/serve.mjs [puerto]   →   http://localhost:8080

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('.', import.meta.url));
const port = Number(process.argv[2] ?? 8080);
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
};

createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  const file = normalize(join(root, path === '/' ? 'visor.html' : path));
  if (!file.startsWith(root.endsWith(sep) ? root : root + sep)) {
    res.writeHead(403).end();
    return;
  }
  try {
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(body);
  } catch {
    res.writeHead(404).end('No encontrado');
  }
}).listen(port, '127.0.0.1', () => {
  console.log(`Visor en http://localhost:${port}`);
});
