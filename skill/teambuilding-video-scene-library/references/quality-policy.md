# Quality Policy

Extract three keyframes at 20%, 50%, and 80% of each clip.

Measure:

- Sharpness using Laplacian variance.
- Average brightness.
- Black screen ratio.
- Overexposure ratio.
- Duration and resolution.

Levels:

- `S`: strong picture, clean enough for opening or highlight.
- `A`: normal usable material.
- `B`: usable but ordinary.
- `C`: low quality, too short, black, heavily blurred, or unusable.

Default behavior:

- Output `S`, `A`, and `B` clips.
- Do not output `C` clips to a user-facing category.
- Record skipped low-quality clips in `._系统记录/scenes.csv`.
- Only output low-quality debug files when the user explicitly asks for `--keep-low-quality`.
