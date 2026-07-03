from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


if __name__ == "__main__":
    raise SystemExit(
        subprocess.call([sys.executable, str(ROOT / "main.py"), "check-env"], cwd=str(ROOT))
    )
