import http from 'node:http';
import crypto from 'node:crypto';
import { performance } from 'node:perf_hooks';

const port = Number(process.argv[2] || 8845);
function fixture(size, seed) {
  const cipher = crypto.createCipheriv('aes-256-ctr', crypto.createHash('sha256').update(seed).digest(), Buffer.alloc(16));
  return Buffer.concat([cipher.update(Buffer.alloc(size)), cipher.final()]);
}
const profiles = {
  unlimited: { bytes: fixture(32 * 1024 * 1024, 'range-unlimited-v1') },
  paced: { bytes: fixture(16 * 1024 * 1024, 'range-paced-v1'), rate: 200 * 1024 },
  latency: { bytes: fixture(1024 * 1024, 'range-latency-v1'), delay: 240 },
  tail: { bytes: fixture(48 * 1024, 'range-tail-v1'), slowFirst: true },
  straggler: { bytes: fixture(16 * 1024 * 1024, 'range-straggler-v1'), slowConnection: true },
};
if (process.argv.includes('--large')) {
  profiles.large = { bytes: fixture(256 * 1024 * 1024, 'range-large-v1') };
}
const sessions = new Map();
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (url.pathname === '/info') {
    const values = Object.fromEntries(Object.entries(profiles).map(([name, p]) => [name, {
      size: p.bytes.length, sha256: crypto.createHash('sha256').update(p.bytes).digest('hex'),
      rate: p.rate || 0, delay_ms: p.delay || 0,
    }]));
    return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(values));
  }
  const run = url.searchParams.get('run');
  if (url.pathname === '/stats') {
    return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(sessions.get(run) || {}));
  }
  const profile = profiles[url.pathname.slice(1).replace(/\.bin$/, '')];
  if (!profile || !run || !['GET', 'HEAD'].includes(req.method)) return res.writeHead(404).end();
  const stats = sessions.get(run) || { requests: 0, active: 0, peak: 0, sent: 0, first_request_ms: performance.now() };
  sessions.set(run, stats);
  let start = 0, end = profile.bytes.length - 1;
  const range = req.headers.range;
  if (range) {
    const match = /^bytes=(\d+)-(\d*)$/.exec(range);
    if (!match) return res.writeHead(416).end();
    start = Number(match[1]); end = match[2] ? Math.min(Number(match[2]), end) : end;
    if (start > end || start >= profile.bytes.length) return res.writeHead(416).end();
  }
  const headers = { 'Content-Length': String(end - start + 1), 'Content-Type': 'application/octet-stream',
    'Accept-Ranges': 'bytes', ETag: '"range-profile-v1"', 'Cache-Control': 'no-store' };
  if (range) headers['Content-Range'] = `bytes ${start}-${end}/${profile.bytes.length}`;
  res.writeHead(range ? 206 : 200, headers);
  if (req.method === 'HEAD') return res.end();
  stats.requests++; stats.active++; stats.peak = Math.max(stats.active, stats.peak);
  // Only a real data request claims the slow socket; metadata probes do not.
  if (profile.slowConnection && end > start && stats.slow_socket === undefined) {
    stats.slow_socket = req.socket.remotePort;
  }
  const rate = profile.rate || (profile.slowConnection && req.socket.remotePort === stats.slow_socket ? 200 * 1024 : 0);
  let released = false;
  const release = () => { if (!released) { released = true; stats.active--; stats.last_response_ms = performance.now(); } };
  res.once('close', release); res.once('finish', release);
  res.flushHeaders();
  if (profile.delay) await wait(profile.delay);
  const begun = performance.now();
  let offset = start;
  try {
    while (offset <= end && !res.destroyed) {
      const slow = profile.slowFirst && start === 0;
      const count = Math.min(slow ? 1024 : 16 * 1024, end - offset + 1);
      if (slow) await wait(30);
      else if (rate) {
        const delay = (offset - start + count) * 1000 / rate - (performance.now() - begun);
        if (delay > 0) await wait(delay);
      }
      if (res.destroyed) break;
      if (stats.first_byte_ms === undefined) stats.first_byte_ms = performance.now();
      const ready = res.write(profile.bytes.subarray(offset, offset + count));
      stats.sent += count; offset += count;
      if (!ready) await new Promise(resolve => {
        const finish = () => { res.off('close', finish); res.off('drain', finish); resolve(); };
        res.once('close', finish); res.once('drain', finish);
      });
    }
    res.end();
  } catch { res.destroy(); }
});
server.listen(port, '127.0.0.1', () => console.log(JSON.stringify({ port, profiles: Object.keys(profiles) })));
process.on('SIGINT', () => { server.closeAllConnections(); server.close(() => process.exit(0)); });
