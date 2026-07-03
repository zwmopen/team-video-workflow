# Naming Policy

Use names that are readable for editing and still preserve source traceability.

Final library clips should use:

```text
分组序号_地点_具体关键词__质量_来源视频ID_镜头ID.mp4
```

Example:

```text
001_安吉_水上拓展__S_V011_S003.mp4
```

This is the preferred post-refinement name because the editor can scan the keyword immediately, while `V011_S003` still links back to the source video and shot record.

Initial split output may use the longer traceable form before keyword refinement:

```text
质量_地点_主分类_细分分类_流水号__来源视频ID_镜头ID.mp4
```

Example:

```text
A_千岛湖_项目活动_皮划艇_0001__V012_S003.mp4
```

Rules:

- Remove Windows-invalid characters: `\ / : * ? " < > |`.
- Keep the original video untouched.
- Store full source path, source hash, source timecode, and output path in records.
- Do not rely on file name alone for provenance; CSV/JSON/SQLite are the source of truth.
- After renaming clips, update `project.sqlite`, `scenes.csv`, `project.json`, and write `clip_rename.csv`.
