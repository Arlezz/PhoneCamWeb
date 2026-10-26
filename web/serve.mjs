#!/usr/bin/env node
// Servidor estático mínimo para el visor y la página de prueba.
// WebCodecs solo funciona en "contexto seguro": https o http://localhost.
//
// Uso: node web/serve.mjs [puerto]
//   http://localhost:8080                       → visor (fase 2)
//   http://localhost:8080/web/prueba-camara.html → prueba de la extensión (fase 3)

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url)); // carpeta del proyecto
const allowed = ['web', 'extension'].map((dir) => join(root, dir) + sep); // nada más se publica
const port = Number(process.argv[2] ?? 8080);
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
};

createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  if (path === '/') {
    res.writeHead(302, { Location: '/web/visor.html' }).end();
    return;
  }
  const file = normalize(join(root, path));
  if (!allowed.some((dir) => file.startsWith(dir))) {
    res.writeHead(404).end('No encontrado');
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
  console.log(`Prueba de la extensión en http://localhost:${port}/web/prueba-camara.html`);
});
