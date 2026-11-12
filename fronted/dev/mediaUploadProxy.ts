import http from 'node:http';
import https from 'node:https';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Plugin } from 'vite';

export interface MediaProxyOptions { target: string; bucket: string }
/** 开发代理仅转发固定桶 POST，不提供任意地址代理，也不参与后端文件发布。 */
export function mediaUploadHandler(options: MediaProxyOptions) {
  const target = new URL(options.target);
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password || target.pathname !== '/' || target.search || target.hash) throw new Error('MEDIA_DEV_MINIO_TARGET 必须为无路径的 HTTP(S) 存储端点');
  if (!/^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(options.bucket)) throw new Error('MEDIA_DEV_BUCKET 格式无效');
  const path = `/__media/upload/${options.bucket}`;
  return (request: IncomingMessage, response: ServerResponse, next: () => void) => {
    if (!request.url?.startsWith('/__media/upload')) { next(); return; }
    const fail = (status: number) => {
      if (response.writableEnded || response.destroyed) return;
      response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      response.end(JSON.stringify({ success: false, stage: 'UPLOAD', code: 'STORAGE_UPLOAD_FAILED' }));
    };
    if (request.url !== path) { fail(404); return; }
    if (request.method !== 'POST') { fail(405); return; }
    const maximum = 11 * 1024 * 1024;
    if (Number(request.headers['content-length']) > maximum) { fail(413); request.resume(); return; }
    // 存储身份由签名表单提供；社区 Cookie、Authorization 等头不转发到 MinIO。
    const upstream = (target.protocol === 'https:' ? https : http).request(new URL(`/${options.bucket}`, target), {
      method: 'POST', headers: {
        ...(request.headers['content-type'] ? { 'Content-Type': request.headers['content-type'] } : {}),
        ...(request.headers['content-length'] ? { 'Content-Length': request.headers['content-length'] } : {}),
      },
    }, (result) => {
      result.resume();
      result.on('error', () => fail(502));
      result.on('end', () => {
        if (response.writableEnded || response.destroyed) return;
        if (result.statusCode !== 200 || !result.headers.etag || !result.headers['x-amz-request-id']) { fail(result.statusCode && result.statusCode >= 400 ? result.statusCode : 502); return; }
        response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
          'X-Amz-Request-Id': result.headers['x-amz-request-id'] });
        response.end(JSON.stringify({ success: true, stage: 'COMPLETE', code: 'SUCCESS' }));
      });
    });
    upstream.setTimeout(120_000, () => { fail(504); upstream.destroy(); });
    upstream.on('error', () => fail(502));
    let received = 0;
    request.on('data', (chunk: Buffer) => { received += chunk.length; if (received > maximum) { fail(413); request.unpipe(upstream); upstream.destroy(); } });
    request.on('aborted', () => upstream.destroy());
    response.on('close', () => { if (!response.writableEnded) upstream.destroy(); });
    request.pipe(upstream);
  };
}
export function mediaUploadProxy(options: MediaProxyOptions): Plugin {
  return { name: 'community-dev-media-upload', apply: 'serve', configureServer(server) { server.middlewares.use(mediaUploadHandler(options)); } };
}
