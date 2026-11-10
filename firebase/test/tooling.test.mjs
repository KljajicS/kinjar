import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const require = createRequire(import.meta.url);
const cli = createRequire(require.resolve('firebase-tools'));

test('Firebase file discovery preserves ignores and hidden files with patched glob', async t => {
  const root = await mkdtemp(join(tmpdir(), 'kinjar-tooling-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(join(root, 'nested'));
  for (const name of ['visible.txt', '.hidden', 'firebase-debug.log', 'nested/keep.txt', 'nested/skip.txt']) {
    await writeFile(join(root, name), 'fixture');
  }
  const { listFiles } = require('firebase-tools/lib/listFiles');
  assert.deepEqual(listFiles(root, ['**/skip.txt']).sort(), ['.hidden', 'nested/keep.txt', 'visible.txt']);
  const { glob } = cli('glob');
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(glob('**/*', { cwd: root, signal: controller.signal }), { name: 'AbortError' });
});

test('Firebase HTTP client produces valid multipart boundaries with patched uuid', async () => {
  const { Gaxios } = cli('gaxios');
  const client = new Gaxios({ adapter: async config => {
    const parts = [];
    for await (const part of config.body) parts.push(Buffer.from(part));
    const body = Buffer.concat(parts).toString();
    const boundary = config.headers['Content-Type'].split('boundary=')[1];
    assert.match(boundary, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.ok(body.includes(`--${boundary}`));
    assert.ok(body.includes('fixture'));
    return { config, status: 200, statusText: 'OK', headers: {}, data: 'ok' };
  }});
  const response = await client.request({ url: 'https://example.invalid', method: 'POST',
    multipart: [{ headers: { 'Content-Type': 'text/plain' }, content: 'fixture' }] });
  assert.equal(response.data, 'ok');
  const uuid = createRequire(cli.resolve('gaxios'))('uuid');
  assert.throws(() => uuid.v5('fixture', uuid.v5.DNS, new Uint8Array(1)), RangeError);
});

test('Firebase PubSub tracing preserves propagation with patched OpenTelemetry core', () => {
  const pubsub = createRequire(cli.resolve('@google-cloud/pubsub'));
  const telemetry = pubsub('./telemetry-tracing');
  const traceId = 'a'.repeat(32);
  const spanId = '12345678901234ab';
  telemetry.setGloballyEnabled(true);
  try {
    const message = {};
    telemetry.injectSpan({ spanContext: () => ({ traceId, spanId, traceFlags: 1 }) }, message);
    assert.equal(message.attributes[telemetry.modernAttributeName], `00-${traceId}-${spanId}-01`);
    const { ROOT_CONTEXT, trace } = pubsub('@opentelemetry/api');
    const { W3CTraceContextPropagator } = pubsub('@opentelemetry/core');
    const context = new W3CTraceContextPropagator().extract(ROOT_CONTEXT, message, telemetry.pubsubGetter);
    assert.equal(trace.getSpanContext(context).traceId, traceId);
  } finally {
    telemetry.setGloballyEnabled(false);
  }
});
