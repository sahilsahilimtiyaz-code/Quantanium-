const http = require('http');
const fs = require('fs');
const path = require('path');

const defaultPort = Number(process.env.PREVIEW_PORT) || 3000;
const defaultHost = process.env.HOST || '0.0.0.0';
const docsDir = path.resolve(__dirname, 'docs');

const mimeTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.md': 'text/markdown; charset=utf-8',
  '.woff2': 'font/woff2'
};

function resolveSafePath(reqPath) {
  let decodedPath = '/';
  try {
    decodedPath = decodeURIComponent(reqPath);
  } catch {
    return null;
  }
  const cleanPath = decodedPath.replace(/^\/+/, '');
  const relative = cleanPath === '' ? 'index.html' : cleanPath;
  const resolved = path.resolve(docsDir, relative);
  if (resolved !== docsDir && !resolved.startsWith(docsDir + path.sep)) {
    return null;
  }
  return resolved;
}

function createServer() {
  return http.createServer((req, res) => {
    const rawUrl = req.url || '/';
    const reqPath = rawUrl.split('?')[0];

    if (reqPath === '/health') {
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store'
      });
      res.end(JSON.stringify({ status: 'ok', app: 'quantanium-web-preview' }));
      return;
    }

    const filePath = resolveSafePath(reqPath);
    if (!filePath) {
      res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Forbidden');
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = mimeTypes[ext] || 'application/octet-stream';

    fs.stat(filePath, (statErr, stats) => {
      const targetPath = !statErr && stats.isDirectory()
        ? path.join(filePath, 'index.html')
        : filePath;
      const targetExt = path.extname(targetPath).toLowerCase();
      const targetType = mimeTypes[targetExt] || contentType;

      fs.readFile(targetPath, (err, content) => {
        if (err) {
          const isAssetRequest = ext !== '' && ext !== '.html';
          if (isAssetRequest) {
            res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('Not Found');
            return;
          }
          fs.readFile(path.join(docsDir, 'index.html'), (fallbackErr, fallback) => {
            if (fallbackErr) {
              res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
              res.end('Not Found');
            } else {
              res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
              res.end(fallback);
            }
          });
          return;
        }
        res.writeHead(200, { 'Content-Type': targetType });
        res.end(content);
      });
    });
  });
}

if (require.main === module) {
  const server = createServer();
  server.listen(defaultPort, defaultHost, () => {
    console.log(`Quantanium preview server active on http://${defaultHost}:${defaultPort}`);
  });
}

module.exports = { createServer, resolveSafePath };
