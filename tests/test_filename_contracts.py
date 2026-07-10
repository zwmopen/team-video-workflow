from __future__ import annotations

import ast
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def find_python_file_with_function(function_name: str) -> Path:
    for path in ROOT.rglob("*.py"):
        if ".git" in path.parts or "tests" in path.parts:
            continue
        try:
            tree = ast.parse(path.read_text(encoding="utf-8-sig"))
        except (OSError, SyntaxError, UnicodeDecodeError):
            continue
        if any(isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == function_name for node in tree.body):
            return path
    raise AssertionError(f"Cannot find function {function_name!r} in repository Python files")


def load_contract_function(function_name: str, required_assignments: set[str]):
    path = find_python_file_with_function(function_name)
    source = path.read_text(encoding="utf-8-sig")
    tree = ast.parse(source)
    selected: list[ast.stmt] = []

    for node in tree.body:
        if isinstance(node, ast.Assign):
            names = {target.id for target in node.targets if isinstance(target, ast.Name)}
            if names & required_assignments:
                selected.append(node)
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            if node.target.id in required_assignments:
                selected.append(node)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == function_name:
            selected.append(node)

    namespace = {"Path": Path, "re": re}
    module = ast.Module(body=selected, type_ignores=[])
    ast.fix_missing_locations(module)
    exec(compile(module, str(path), "exec"), namespace)
    return namespace[function_name]


class SceneKeyContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.scene_key_from_path = staticmethod(
            load_contract_function("scene_key_from_path", {"SCENE_KEY_PATTERN"})
        )
        cls.infer_source_scene_ids = staticmethod(
            load_contract_function("infer_source_scene_ids", {"SCENE_ID_PATTERN"})
        )

    def test_scene_key_keeps_cv_source_tail(self) -> None:
        self.assertEqual(
            self.scene_key_from_path("皮划艇_千岛湖_CV022_S004.mp4"),
            "CV022_S004",
        )

    def test_scene_key_supports_plain_video_id(self) -> None:
        self.assertEqual(
            self.scene_key_from_path(Path("草坪游戏_V006_S001_精选.mov")),
            "V006_S001",
        )

    def test_scene_key_returns_empty_for_untraceable_name(self) -> None:
        self.assertEqual(self.scene_key_from_path("无来源尾码素材.mp4"), "")

    def test_source_and_scene_ids_are_inferred(self) -> None:
        self.assertEqual(
            self.infer_source_scene_ids(Path("湖边互动_CV022_S004_皮划艇.mp4")),
            ("CV022", "S004"),
        )

    def test_last_source_scene_pair_wins_after_multiple_renames(self) -> None:
        self.assertEqual(
            self.infer_source_scene_ids(Path("旧_V001_S001_新_CV099_S012.mp4")),
            ("CV099", "S012"),
        )

    def test_inference_fails_closed(self) -> None:
        self.assertEqual(self.infer_source_scene_ids(Path("没有尾码.mp4")), ("", ""))


if __name__ == "__main__":
    unittest.main()
