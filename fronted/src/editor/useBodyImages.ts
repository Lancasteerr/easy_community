import { useEffect, useRef, useState, type RefObject } from 'react';
import type { Editor } from '@tiptap/react';
import { ApiError } from '../api/client';
import { imageFileError, uploadArticleImage, uploadError } from '../api/media';
import { imageCount } from './model';
import { uploadAnchorKey } from './uploadAnchors';

interface ImageTask { id: string; file: File; status: 'queued' | 'uploading' | 'failed'; percent: number; error: string }
/** 顺序上传控制授权频率；失败任务保留原文件，只有用户操作才重新申请媒体 ID。 */
export function useBodyImages(editor: RefObject<Editor | null>, disabled: boolean, onUnauthorized: () => void) {
  const [tasks, setTasks] = useState<ImageTask[]>([]);
  const [message, setMessage] = useState('');
  const current = useRef(tasks); current.current = tasks;
  const active = useRef<{ id: string; controller: AbortController } | null>(null);
  const callbacks = useRef({ onUnauthorized }); callbacks.current = { onUnauthorized };
  useEffect(() => () => { active.current?.controller.abort(); active.current = null; }, []);
  useEffect(() => {
    const task = tasks.find((item) => item.status === 'queued');
    if (disabled || active.current || !task) return;
    const controller = new AbortController(); active.current = { id: task.id, controller };
    const update = (patch: Partial<ImageTask>) => setTasks((items) => items.map((item) => item.id === task.id ? { ...item, ...patch } : item));
    update({ status: 'uploading' });
    uploadArticleImage(task.file, 10 * 1024 * 1024, controller.signal, (percent) => update({ percent })).then((uploaded) => {
      if (controller.signal.aborted || !editor.current || editor.current.isDestroyed) return;
      const instance = editor.current;
      const anchor = uploadAnchorKey.getState(instance.state)?.find(undefined, undefined, (spec) => spec.id === task.id)[0];
      if (anchor) instance.chain().insertContentAt(anchor.from, { type: 'image', attrs: { fileId: uploaded.mediaId, src: uploaded.publicUrl, alt: '', title: '' } }).run();
      else setMessage('上传位置已移除，图片未插入；如需使用请重新选择。');
      instance.view.dispatch(instance.state.tr.setMeta(uploadAnchorKey, { remove: task.id }));
      setTasks((items) => items.filter((item) => item.id !== task.id));
    }).catch((error) => {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) callbacks.current.onUnauthorized();
      update({ status: 'failed', error: uploadError(error) });
    }).finally(() => {
      if (active.current?.controller === controller) { active.current = null; setTasks((items) => [...items]); }
    });
  }, [tasks, disabled, editor]);
  function add(files: File[], position: number) {
    const instance = editor.current;
    if (disabled || !instance || instance.isDestroyed) return;
    const available = 50 - imageCount(instance.getJSON()) - current.current.length;
    if (files.length > available) { setMessage('正文最多插入 50 张图片（含上传任务）'); return; }
    const pending: ImageTask[] = [];
    for (const file of files) {
      const invalid = imageFileError(file, 10 * 1024 * 1024);
      if (invalid) { setMessage(invalid); continue; }
      const id = crypto.randomUUID();
      instance.view.dispatch(instance.state.tr.setMeta(uploadAnchorKey, { add: { id, position } }));
      pending.push({ id, file, status: 'queued', percent: 0, error: '' });
    }
    setTasks((items) => [...items, ...pending]);
  }
  function remove(id: string) {
    if (active.current?.id === id) { active.current.controller.abort(); active.current = null; }
    const instance = editor.current;
    if (instance && !instance.isDestroyed) instance.view.dispatch(instance.state.tr.setMeta(uploadAnchorKey, { remove: id }));
    setTasks((items) => items.filter((item) => item.id !== id));
  }
  return { tasks, message, add, remove, blocked: tasks.length > 0,
    retry: (id: string) => setTasks((items) => items.map((item) => item.id === id ? { ...item, status: 'queued', error: '', percent: 0 } : item)) };
}
