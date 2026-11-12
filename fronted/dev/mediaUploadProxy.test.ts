// @vitest-environment node
import http from 'node:http';
import { once } from 'node:events';
import { afterEach, expect, it } from 'vitest';
import { mediaUploadHandler } from './mediaUploadProxy';

const servers: http.Server[] = [];
afterEach(async () => { await Promise.all(servers.splice(0).map((server) => new Promise<void>((resolve) => { server.closeAllConnections(); server.close(() => resolve()); }))); });
async function listen(handler: http.RequestListener) {
  const server = http.createServer(handler); servers.push(server); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  return `http://127.0.0.1:${(server.address() as import('node:net').AddressInfo).port}`;
}
it('固定桶原样转发正文，仅确认 200 与存储头后转换 JSON，不泄露社区 Cookie', async () => {
  let received = '';
  const storage = await listen((request, response) => {
    expect(request.url).toBe('/test-bucket'); expect(request.headers.cookie).toBeUndefined(); expect(request.headers.authorization).toBeUndefined();
    request.on('data', (chunk) => { received += chunk; });
    request.on('end', () => { response.writeHead(200, { ETag: 'opaque', 'X-Amz-Request-Id': 'request-1' }); response.end(); });
  });
  const handler = mediaUploadHandler({ target: storage, bucket: 'test-bucket' });
  const proxy = await listen((request, response) => handler(request, response, () => { response.writeHead(404).end(); }));
  const response = await fetch(`${proxy}/__media/upload/test-bucket`, { method: 'POST', headers: { Cookie: 'secret', Authorization: 'secret', 'Content-Type': 'multipart/form-data; boundary=test' }, body: 'unaltered-form' });
  expect(response.status).toBe(200); expect(await response.json()).toEqual({ success: true, stage: 'COMPLETE', code: 'SUCCESS' });
  expect(received).toBe('unaltered-form');
});
it('存储失败和缺少确认头均不能转换为成功，拒绝其他桶与 GET', async () => {
  let status = 403;
  const storage = await listen((_request, response) => { response.writeHead(status); response.end('storage detail'); });
  const handler = mediaUploadHandler({ target: storage, bucket: 'test-bucket' });
  const proxy = await listen((request, response) => handler(request, response, () => response.writeHead(404).end()));
  const first = await fetch(`${proxy}/__media/upload/test-bucket`, { method: 'POST', body: 'x' });
  expect(first.status).toBe(403); expect(await first.json()).toMatchObject({ success: false });
  status = 200;
  expect((await fetch(`${proxy}/__media/upload/test-bucket`, { method: 'POST', body: 'x' })).status).toBe(502);
  expect((await fetch(`${proxy}/__media/upload/other`, { method: 'POST' })).status).toBe(404);
  expect((await fetch(`${proxy}/__media/upload/test-bucket`)).status).toBe(405);
});
it('连接中断返回失败，不重放上传', async () => {
  let calls = 0;
  const storage = await listen((request) => { calls++; request.socket.destroy(); });
  const handler = mediaUploadHandler({ target: storage, bucket: 'test-bucket' });
  const proxy = await listen((request, response) => handler(request, response, () => response.end()));
  expect((await fetch(`${proxy}/__media/upload/test-bucket`, { method: 'POST', body: 'x' })).status).toBe(502);
  expect(calls).toBe(1);
});
