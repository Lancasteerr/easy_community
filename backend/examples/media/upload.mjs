/** 两种环境均执行后端返回的描述；调用方仅在用户主动重试时重新申请新ID。 */
export async function uploadMedia(file, purpose, csrf, fetcher = globalThis.fetch) {
  const response = await fetcher('/api/media/uploads', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify({ purpose, filename: file.name, contentType: file.type, size: file.size })
  });
  if (response.status !== 201) throw new Error('UPLOAD_AUTHORIZATION_FAILED');
  return uploadFile(await response.json(), file, fetcher);
}

/** fields原样加入，file必须在最后；multipart boundary由浏览器生成。 */
export async function uploadFile(grant, file, fetcher = globalThis.fetch) {
  const form = new FormData();
  for (const [name, value] of Object.entries(grant.upload.fields)) form.append(name, value);
  form.append('file', file);
  const response = await fetcher(grant.upload.url, {
    method: grant.upload.method, credentials: 'omit', body: form
  });
  let result;
  try { result = await response.json(); }
  catch { throw new Error('STORAGE_UPLOAD_FAILED'); }
  if (response.status !== 200 || result?.success !== true) {
    throw new Error(typeof result?.code === 'string' ? result.code : 'STORAGE_UPLOAD_FAILED');
  }
  return { mediaId: grant.mediaId, publicUrl: grant.publicUrl };
}
