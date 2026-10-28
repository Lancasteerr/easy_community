import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { uploadFile } from './upload.mjs';

/** 显式运行的生产契约验收，使用专用测试账号；不绑定头像，成功孤儿交给正常GC。 */
const required = name => {
  const value = process.env[name];
  if (!value) throw new Error(`必须设置 ${name}`);
  return value;
};
const apiBase = new URL(required('IT_COS_API_URL'));
assert.equal(apiBase.protocol, 'https:');
const username = required('IT_COS_USERNAME');
const password = required('IT_COS_PASSWORD');
const small = new File([await readFile(required('IT_COS_SMALL_IMAGE'))], 'cos-test.png', { type: 'image/png' });
const large = new File([await readFile(required('IT_COS_LARGE_IMAGE'))], 'cos-large.jpg', { type: 'image/jpeg' });
assert.ok(small.size > 0 && small.size <= 2 * 1024 * 1024);
assert.equal(large.size, 10 * 1024 * 1024, '大图验收文件必须恰好10MiB，以覆盖上传上限');
let cookie = '';
let csrf;

/** Cookie只发社区API，不向授权中的对象存储地址附加账号凭证。 */
async function api(path, method = 'GET', body) {
  const headers = { 'Content-Type': 'application/json' };
  if (cookie) headers.Cookie = cookie;
  if (method !== 'GET') headers[csrf.headerName] = csrf.token;
  const response = await fetch(new URL(path, apiBase), { method, headers, redirect: 'error', body: body === undefined ? undefined : JSON.stringify(body) });
  for (const value of response.headers.getSetCookie()) {
    if (value.startsWith('SESSION=')) cookie = value.split(';', 1)[0];
  }
  return response;
}
csrf = await (await api('/api/auth/csrf')).json();
assert.equal((await api('/api/auth/login', 'POST', { username, password })).status, 200);
csrf = await (await api('/api/auth/csrf')).json();

async function grant(file, purpose) {
  const response = await api('/api/media/uploads', 'POST', { purpose, filename: file.name, contentType: file.type, size: file.size });
  assert.equal(response.status, 201);
  const value = await response.json();
  assert.equal(value.upload.method, 'POST');
  assert.equal(value.upload.fields['x-cos-forbid-overwrite'], 'true');
  return value;
}
const smallGrant = await grant(small, 'AVATAR');
const uploaded = await uploadFile(smallGrant, small);
assert.equal((await fetch(uploaded.publicUrl, { credentials: 'omit' })).status, 200);

// COS原生防覆盖必须给出409；仅仅第二次业务失败不足以证明临时源不可覆盖。
const repeated = new FormData();
for (const [name, value] of Object.entries(smallGrant.upload.fields)) repeated.append(name, value);
repeated.append('file', small);
assert.equal((await fetch(smallGrant.upload.url, { method: 'POST', credentials: 'omit', body: repeated })).status, 409);

const tampered = await grant(small, 'AVATAR');
tampered.upload.fields['x-cos-callback'] = btoa('{}');
await assert.rejects(uploadFile(tampered, small));
await uploadFile(await grant(large, 'ARTICLE_IMAGE'), large);
console.log('COS成功上传、公开可读、原生防覆盖、回调参数签名约束及大图审核验收通过；协作式截止和回调504到上传端203仍须另行故障验收并核对服务端日志。');
