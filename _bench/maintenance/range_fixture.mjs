import http from 'node:http';
import crypto from 'node:crypto';
import { performance } from 'node:perf_hooks';

const port = Number(process.argv[2] || 8843);
const size = 16 * 1024 * 1024;
const rate = 200 * 1024;
// Deterministic test bytes, not encryption of user content.
const cipher = crypto.createCipheriv('aes-256-ctr', crypto.createHash('sha256').update('yunget-range-fixture-v1').digest(), Buffer.alloc(16));
const payload = Buffer.concat([cipher.update(Buffer.alloc(size)), cipher.final()]);
const sha256 = crypto.createHash('sha256').update(payload).digest('hex');
let active = 0, peak = 0, requests = 0, sent = 0;

const server = http.createServer(async (req, res) => {
  const path = new URL(req.url, 'http://127.0.0.1').pathname;
  if (path === '/info') return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ size, rate, sha256, active, peak, requests, sent }));
  if (path === '/reset' && req.method === 'POST') {
    if (active) return res.writeHead(409).end();
    peak = 0; requests = 0; sent = 0;
    return res.writeHead(204).end();
  }
  if (path !== '/fixture.bin' || !['GET', 'HEAD'].includes(req.method)) return res.writeHead(404).end();
  let start = 0, end = size - 1;
  const range = req.headers.range;
  if (range) {
    const match = /^bytes=(\d+)-(\d*)$/.exec(range);
    if (!match) return res.writeHead(416, { 'Content-Range': `bytes */${size}` }).end();
    start = Number(match[1]);
    end = match[2] ? Math.min(Number(match[2]), size - 1) : end;
    if (start >= size || end < start) return res.writeHead(416, { 'Content-Range': `bytes */${size}` }).end();
  }
  const headers = { 'Content-Length': String(end - start + 1), 'Content-Type': 'application/octet-stream', 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store' };
  if (range) headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
  res.writeHead(range ? 206 : 200, headers);
  if (req.method === 'HEAD') return res.end();
  requests++; active++; peak = Math.max(active, peak);
  let released = false;
  const release = () => { if (!released) { released = true; active--; } };
  res.once('close', release); res.once('finish', release);
  const started = performance.now();
  let transferred = 0;
  try {
    while (start + transferred <= end && !res.destroyed) {
      const count = Math.min(16 * 1024, end - start - transferred + 1);
      // Pace BEFORE each write, including the final chunk; many short ranges
      // must not bypass the per-connection rate through an initial burst.
      const delay = (transferred + count) * 1000 / rate - (performance.now() - started);
      if (delay > 0) await new Promise(resolve => setTimeout(resolve, delay));
      if (res.destroyed) break;
      const ready = res.write(payload.subarray(start + transferred, start + transferred + count));
      transferred += count; sent += count;
      if (!ready) await new Promise(resolve => {
        const cleanup = () => { res.off('drain', cleanup); res.off('close', cleanup); resolve(); };
        res.once('drain', cleanup); res.once('close', cleanup);
      });
    }
    res.end();
  } catch { res.destroy(); }
});

server.listen(port, '127.0.0.1', () => console.log(JSON.stringify({ port, size, rate, sha256 })));
