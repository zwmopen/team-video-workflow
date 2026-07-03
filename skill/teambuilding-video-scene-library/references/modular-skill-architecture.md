# Modular Skill Architecture

Do not grow the video workflow as one giant script. Keep one main orchestrator and split responsibilities into modules. Promote a module to a standalone Skill only after it is used repeatedly and has clear triggers.

## Recommended Split

### 1. Source Ingest

Purpose: collect videos and sidecar text from Jianghu Toolbox/download folders into a clean location/theme source folder.

Owns:

- Location keyword matching before hashtags.
- Duplicate source cleanup.
- Mixed-location/list-video quarantine.
- Source collection reports.

Candidate standalone Skill name later: `teambuilding-video-source-ingest`.

### 2. Scene Library

Purpose: turn source videos into reusable silent vertical shots.

Owns:

- Vertical/horizontal filtering.
- Scene splitting.
- Muting and keyframes.
- Visual-first folder classification.
- Renaming and manifest records.

Current main implementation: `teambuilding-video-scene-library`.

### 3. Visual Audit

Purpose: make messy libraries clean without relying on source narration.

Owns:

- Contact sheets.
- AI visual review.
- Manual correction CSV.
- Locked correction records.

Candidate standalone Skill name later: `teambuilding-video-visual-audit`.

### 4. Script Match

Purpose: read a script and copy matching clips into a numbered Jianying pack.

Owns:

- Script beat splitting.
- Keyword-to-shot matching.
- Clip diversity rules.
- Numbered output and plan CSV.

Candidate standalone Skill name later: `teambuilding-video-script-match`.

### 5. Reference Recompose

Purpose: keep a reference video's audio/script rhythm and replace visuals with vertical library clips.

Owns:

- Reference audio extraction.
- Script/time allocation.
- Visual replacement.
- `rough_cut.mp4`, `reference_audio.m4a`, `recompose_plan.csv`, `jianying_pack`.

Candidate standalone Skill name later: `teambuilding-video-reference-recompose`.

### 6. Template Render

Purpose: produce more polished recurring styles after the rough cut works.

Owns:

- Remotion templates.
- Captions.
- Title cards.
- Brand overlays.
- Covers.

Candidate standalone Skill name later: `teambuilding-video-template-render`.

### 7. Publish QA

Purpose: check deliverables before the user opens Jianying or posts.

Owns:

- Final video exists and is playable.
- 9:16 dimensions.
- Audio present.
- No missing clips.
- Plan/report generated.
- Optional duration and file size checks.

Candidate standalone Skill name later: `teambuilding-video-publish-check`.

## Promotion Rule

Keep a module inside the main Skill while it is changing quickly. Promote to standalone Skill when:

- It has a stable user-facing trigger.
- It has at least one tested script or workflow.
- It can be validated without loading the whole workflow.
- It is useful outside one location or one video.

## Main Orchestrator

The main skill should stay thin:

1. Decide which module applies.
2. Load only the relevant reference file.
3. Run the corresponding script.
4. Verify the output.
5. Report the artifact path.

The main skill should not explain every detail of every module in `SKILL.md`.
