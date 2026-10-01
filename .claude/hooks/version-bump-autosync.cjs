#!/usr/bin/env node
/**
 * مزامنة رفع الإصدار — PostToolUse على Edit|Write|MultiEdit.
 *
 * رفعُ `versionCode` هنا نشرٌ إلى الإنتاج، ويتبعه شيئان يُنسيان فيقفان في CI:
 *   ١) README يذكر الرقم ⇒ يُصلَح آليّاً بـ`tools/sync_readme_version.py`.
 *   ٢) موجز `ReleaseNotes.kt` ⇒ لا يُكتب آليّاً (كلام بشر)، فيُنبَّه Claude فوراً
 *      بدل أن يكتشفه `pr-check` بعد دورة كاملة.
 * لا يعمل إلا إذا مسّ التعديلُ `app/build.gradle.kts`.
 */
const path = require('path');
const { spawnSync } = require('child_process');

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  let p = {};
  try {
    p = JSON.parse(input || '{}');
  } catch {
    process.exit(0);
  }
  const file = String(p.tool_input?.file_path || '').replace(/\\/g, '/');
  if (!file.endsWith('app/build.gradle.kts')) process.exit(0);

  const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
  const run = (script) => spawnSync('python3', [path.join(root, 'tools', script)], { cwd: root, encoding: 'utf8' });

  const sync = run('sync_readme_version.py');
  const notes = run('check_release_notes.py');
  if (notes.status === 1) {
    console.error((sync.stdout || '').trim() + '\n' + (notes.stderr || '').trim());
    process.exit(2); // يُعاد النصّ إلى Claude ليكمل الموجز في الدور نفسه
  }
  process.exit(0);
});
