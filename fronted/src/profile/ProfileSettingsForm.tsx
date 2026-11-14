import { useId, useRef } from 'react';
import type { UserProfile } from '../api/types';
import { Avatar } from '../components/Avatar';
import { useProfileEditor } from './useProfileEditor';

/** 资料输入与头像选择共用提交锁，界面只展示计数和必要的结果反馈。 */
export function ProfileSettingsForm({ profile }: { profile: UserProfile }) {
  const editor = useProfileEditor(profile);
  const fileInput = useRef<HTMLInputElement>(null);
  const composing = useRef(false);
  const id = useId();
  const overLimit = editor.count > 50;

  return <div className="profile-settings-content">
    <Avatar src={profile.avatarUrl} size={96} />
    <button type="button" className="profile-avatar-button" disabled={editor.blocked} aria-busy={editor.busy}
      onClick={() => fileInput.current?.click()}>更换头像</button>
    <input type="file" hidden ref={fileInput} accept="image/jpeg,image/png,image/webp" aria-label="选择头像图片"
      disabled={editor.fileBlocked} onChange={(event) => {
        const file = event.target.files?.[0];
        event.target.value = '';
        if (file) void editor.changeAvatar(file);
      }} />

    <form className="profile-bio-form" noValidate aria-busy={editor.busy} onSubmit={(event) => {
      event.preventDefault();
      if (!composing.current) void editor.saveBio();
    }}>
      <label htmlFor={id}>个人简介</label>
      <input id={id} className={`profile-bio-input${overLimit ? ' profile-bio-invalid' : ''}`} type="text" name="bio"
        value={editor.bio} placeholder="介绍一下自己" autoComplete="off" disabled={editor.blocked}
        aria-invalid={overLimit} aria-describedby={`${id}-count`}
        onChange={(event) => editor.editBio(event.target.value)}
        onPaste={(event) => { if (!editor.acceptsPaste(event.clipboardData.getData('text'))) event.preventDefault(); }}
        onCompositionStart={() => { composing.current = true; }} onCompositionEnd={() => { composing.current = false; }}
        onKeyDown={(event) => {
          // 输入法使用Enter确认候选词时，不触发表单的隐式提交。
          if (event.key === 'Enter' && (composing.current || event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
        }} />
      <div id={`${id}-count`} className={`profile-bio-count${overLimit ? ' profile-bio-invalid' : ''}`}>
        {editor.count} / 50
      </div>
      <button type="submit" className="profile-save-button" disabled={editor.blocked || overLimit}>保存修改</button>
    </form>
  </div>;
}
