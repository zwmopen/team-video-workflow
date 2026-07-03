# Review Corrections

Manual review is part of the workflow.

When a user moves a clip from `90_待人工分类` into a real category, future versions should record:

```json
{
  "original_prediction": "90_待人工分类",
  "human_correction": "05_项目活动/皮划艇",
  "manual_locked": true,
  "reason": "多人持桨在湖面划船"
}
```

Current implementation records the required fields and respects `manual_locked=true` during keyword refinement.

Future behavior:

- Do not overwrite manually moved clips during incremental reruns.
- Add corrected examples to classification prompts/rules.
- When several corrections come from the same wrong folder, run a focused audit of nearby clips in that folder/source sequence before asking the user to continue manual review.
- Treat user-confirmed correct samples as positive locked examples, not only wrong predictions as negative examples.
