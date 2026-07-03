# 飞书审片板工作流

Use this reference when script-to-shot matching, storyboard planning, or rough-cut review should happen in Feishu/Lark.

## Default Board

Current user-owned review document:

```text
https://my.feishu.cn/docx/YanldTyg5oPwgnxahn6cCIGdnbd
```

Editable user-owned style/template copy provided by the user:

```text
https://my.feishu.cn/wiki/JbQ3wak0viZRbzkhc1fcBY7onTf
```

This wiki link currently resolves through `docs +fetch` to document token `RdGSd8ASDowK37xbtMFcqbVyn4f` and preserves the Storyboard sample layout.

The original reference sample at `https://bytedance.us.larkoffice.com/docx/S09EdCBO1oF2MNxcNq6u8lSJsgc` is readable but not editable by the current user. Learn layout and rhythm from the user-owned wiki copy rather than editing the original.

## Required Columns

- `镜号`: stable beat number, matching rough-cut order.
- `台词/文案`: the exact spoken line or script beat.
- `关键词/画面需求`: direct visual keywords, such as `大巴出发`, `农家菜`, `峡谷玩水`, `漂流`, `篝火`, `烟花`.
- `素材截图/画面`: representative keyframe or contact-sheet thumbnail.
- `选择理由`: why this clip matches the line, including visual evidence.
- `状态`: `待匹配`, `待审`, `通过`, `替换`, `废料`, `重配`.

## Operating Policy

1. Use `lark-cli` and `--as user` after OAuth login. Bot identity can read some docs but often cannot edit user-owned docs.
2. If a target document returns `No permission to operate`, create a new user-owned review doc and report that URL.
3. Feishu is the review interface; local CSV/JSON remain the machine trace.
4. Do not upload full videos by default. Upload one keyframe per selected clip first, and place the image directly inside the table cell.
5. Do not show local clip paths or screenshot paths in the Feishu table unless the user explicitly asks for them. Keep paths in local CSV/JSON machine records.
6. Rows must make wrong matches obvious: the script line, visual keyword, screenshot, and reason should be visible together.
7. When building a rough cut, each line should prefer multiple short visual candidates. Repeating one 1-2 second clip is a failure unless marked as intentional.
8. After user feedback in Feishu, update local correction records and rerun matching before final export.
9. For storyboard layout, follow the copied sample's clear side-by-side idea: left side is spoken line or script beat, right side is the visual plan or material preview. For the user's团建视频, replace abstract HTML demo language with real clip keyframes, visual keywords, and selection reasons.

## Minimum First Pass

For a new script or reference video:

1. Split the copy into natural beats.
2. For each beat, infer concrete visual keywords.
3. Select candidate clips from the clean visual library.
4. Extract one representative frame per candidate.
5. Write a Feishu row with text, keyword, image-in-cell, selection reason, and status.
6. Render or prepare Jianying pack only after the review table is coherent.
