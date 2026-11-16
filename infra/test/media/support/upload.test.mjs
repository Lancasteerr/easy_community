import test from 'node:test';
import assert from 'node:assert/strict';
import { uploadFile } from './upload.mjs';

const grant = { mediaId: 'id', publicUrl: 'https://images.example.com/public/id',
  upload: { method: 'POST', url: 'https://dynamic.example.com', fields: { key: 'temp/id', 'Content-Type': 'image/png' } } };
const file = new File(['image'], 'a.png', { type: 'image/png' });

test('原样使用动态入口、原始字段及统一JSON结果', async () => {
  const value = await uploadFile(grant, file, async (url, request) => {
    assert.equal(url, grant.upload.url);
    assert.equal(request.credentials, 'omit');
    assert.equal(request.headers, undefined);
    assert.deepEqual([...request.body.keys()], ['key', 'Content-Type', 'file']);
    return new Response(JSON.stringify({ success: true, stage: 'COMPLETE', code: 'SUCCESS' }), { status: 200 });
  });
  assert.deepEqual(value, { mediaId: grant.mediaId, publicUrl: grant.publicUrl });
});

for (const [status, body] of [[203, '{"success":true}'], [203, '<Error><Code>CallbackFailed</Code></Error>'], [504, ''],
  [200, ''], [200, '<Error/>'], [200, '{"success":false,"code":"COPY_FAILED"}']]) {
  test(`HTTP ${status}异常结果不能成功或自动重试：${body}`, async () => {
    let calls = 0;
    await assert.rejects(uploadFile(grant, file, async () => { calls++; return new Response(body, { status }); }));
    assert.equal(calls, 1);
  });
}
