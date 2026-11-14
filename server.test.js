const test = require('node:test');
const assert = require('node:assert/strict');
const { createServer, resolveSafePath } = require('./server');

async function startTestServer() {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  return { server, baseUrl: `http://127.0.0.1:${address.port}` };
}

test('GET /health returns ok JSON for quantanium-web-preview', async () => {
  const { server, baseUrl } = await startTestServer();
  try {
    const res = await fetch(`${baseUrl}/health`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.deepEqual(body, { status: 'ok', app: 'quantanium-web-preview' });
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('GET / serves Quantanium index.html', async () => {
  const { server, baseUrl } = await startTestServer();
  try {
    const res = await fetch(`${baseUrl}/`);
    assert.equal(res.status, 200);
    const html = await res.text();
    assert.match(html, /Quantanium/);
    assert.match(html, /com\.quantanium/);
    assert.doesNotMatch(html, /AntiGem/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('GET /style.css and /main.js serve static assets', async () => {
  const { server, baseUrl } = await startTestServer();
  try {
    const css = await fetch(`${baseUrl}/style.css`);
    assert.equal(css.status, 200);
    assert.match(css.headers.get('content-type') || '', /text\/css/);

    const js = await fetch(`${baseUrl}/main.js`);
    assert.equal(js.status, 200);
    assert.match(js.headers.get('content-type') || '', /text\/javascript/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('GET missing image asset returns 404', async () => {
  const { server, baseUrl } = await startTestServer();
  try {
    const res = await fetch(`${baseUrl}/resources/does-not-exist.png`);
    assert.equal(res.status, 404);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('resolveSafePath blocks traversal outside docs directory', () => {
  assert.equal(resolveSafePath('/../package.json'), null);
  assert.equal(resolveSafePath('/%2e%2e/server.js'), null);
  assert.ok(resolveSafePath('/index.html'));
});

test('GET traversal path returns 403', async () => {
  const http = require('node:http');
  const { server } = await startTestServer();
  try {
    const port = server.address().port;
    const status = await new Promise((resolve, reject) => {
      const req = http.request({
        hostname: '127.0.0.1',
        port,
        path: '/%2e%2e/package.json',
        method: 'GET'
      }, (res) => {
        res.resume();
        resolve(res.statusCode);
      });
      req.on('error', reject);
      req.end();
    });
    assert.equal(status, 403);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
