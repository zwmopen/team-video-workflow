# -*- coding: utf-8 -*-
"""
C++ 测试闸门体检（DSH-120-B）

背景：仓库里 content_store_tests / send_to_integration_tests 长期用 assert，
而 CI 编的是 Release（预定义 NDEBUG）⇒ assert 被整段编译掉 ⇒ 20 条判据一条没跑，
ctest 每次都报「通过」。这是**空跑的假闸门**，比没有闸门更危险 —— 它给的是假的安心。

本脚本盯的是「闸门是不是真的在跑」，不是「代码写得对不对」：

  P1 测试文件不得调用 assert()          —— Release 下会被编译掉
  P2 测试文件不得 #include <cassert>     —— 同 P1（留着就是给下次犯错铺路）
  P3 必须有一条「失败时返回非 0」的出口   —— 否则 ctest 永远看到通过
  P4 断言数 >= 3                        —— 防止「include 了头但一条断言都没写」
  P5 tests/ 下每个 .cpp 都必须在 CMakeLists 里被 add_executable + add_test 注册
                                        —— 没注册的测试 = 永远不会跑的闸门
  P6 共享断言头 test_check.h 存在且提供 CHECK / CHECK_MSG / Finish

用法：python scripts/check-cpp-test-assertions.py
退出码：0 = 全过；1 = 有闸门失效
"""
import os
import re
import sys

# ⚠️ 本脚本会打印中文。Windows runner（或任何非 UTF-8 locale 的重定向 stdout）下
# 默认按 locale 编码输出（cp1252）⇒ 一打印中文就 `UnicodeEncodeError` 崩掉，
# 而且崩在 output 阶段，看上去像「闸门判定失败」，实际是打印失败。
# 固定 utf-8 + errors=replace，保证在任何 runner 上都不会因为「打印」而失败。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# 本脚本在 scripts/ 下，被测工程在同级目录 windows-native/
ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "windows-native")
TESTS = os.path.join(ROOT, "tests")
CMAKELISTS = os.path.join(ROOT, "CMakeLists.txt")
CHECK_HEADER = os.path.join(TESTS, "test_check.h")
SCRIPTS = os.path.join(os.path.dirname(ROOT), "scripts")  # scripts/ 与 windows-native/ 同级

fails = []


def check(name, ok, detail=""):
    print("  %s  %s%s" % ("PASS" if ok else "FAIL", name, ("  " + detail) if detail and not ok else ""))
    if not ok:
        fails.append(name)


def strip_comments(text):
    """去掉 // 与 /* */ 注释。注意保留字符串字面量里的 //（如 "http://"）。"""
    out = []
    i, n = 0, len(text)
    state = "code"
    while i < n:
        c = text[i]
        if state == "code":
            if c == '/' and i + 1 < n and text[i + 1] == '/':
                state = "line"
                i += 2
                continue
            if c == '/' and i + 1 < n and text[i + 1] == '*':
                state = "block"
                i += 2
                continue
            if c == '"':
                state = "str"
            out.append(c)
        elif state == "line":
            if c == '\n':
                state = "code"
                out.append(c)
        elif state == "block":
            if c == '*' and i + 1 < n and text[i + 1] == '/':
                state = "code"
                i += 2
                continue
            if c == '\n':
                out.append(c)
        elif state == "str":
            if c == '\\':
                if i + 1 < n:
                    out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                state = "code"
            out.append(c)
        i += 1
    return "".join(out)


print("=== P6 共享断言头 ===")
if not os.path.exists(CHECK_HEADER):
    check("tests/test_check.h 存在", False)
    hdr = ""
else:
    hdr = open(CHECK_HEADER, encoding="utf-8").read()
    check("tests/test_check.h 存在", True)
check("提供 CHECK 宏", "#define CHECK(" in hdr)
check("提供 CHECK_MSG 宏", "#define CHECK_MSG(" in hdr)
check("提供 Finish() 收尾", "inline int Finish(" in hdr)
check("头部注释写明「Release 下 assert 会被编译掉」",
      "NDEBUG" in hdr and "assert" in hdr)

print("=== 逐文件体检 ===")
sources = sorted(f for f in os.listdir(TESTS) if f.endswith(".cpp"))
check("tests/ 下至少有 4 个测试源文件", len(sources) >= 4, str(len(sources)))

for name in sources:
    path = os.path.join(TESTS, name)
    raw = open(path, encoding="utf-8").read()
    code = strip_comments(raw)

    # P1: assert() 调用（static_assert 是编译期的，Release 下依然生效，不算违规）
    calls = re.findall(r'(?<![\w:])assert\s*\(', code)
    check("%s P1 未调用 assert()" % name, not calls, "%d 处" % len(calls))

    # P2: #include <cassert>
    inc = re.findall(r'#\s*include\s*<cassert>', code)
    check("%s P2 未 include <cassert>" % name, not inc)

    # P3: 必须有一条「失败时返回非 0」的出口。
    #     放行三种口径：共享头的 Finish()、自写的 Fail()、显式 return 1。
    #     判据是「存在一条 return 的右边不是字面量 0」。
    returns = re.findall(r'\breturn\s+([^;]+);', code)
    nonzero = [r.strip() for r in returns if r.strip() not in ("0", "EXIT_SUCCESS")]
    check("%s P3 有「失败返回非 0」的出口" % name, bool(nonzero), str(returns[:3]))

    # P4: 断言条数（CHECK / CHECK_MSG / Fail / 兜底 assert 都算）
    total = (len(re.findall(r'(?<![\w:])CHECK\s*\(', code))
             + len(re.findall(r'(?<![\w:])CHECK_MSG\s*\(', code))
             + len(re.findall(r'(?<![\w:])Fail\s*\(', code))
             + len(re.findall(r'(?<![\w:])assert\s*\(', code)))
    check("%s P4 断言数 >= 3" % name, total >= 3, "只有 %d 条" % total)

def strip_cmake_comments(text):
    """去掉 CMake 的行注释。

    ⚠️ 这个不起眼的步骤救过一次命：早期版本直接对原文跑正则，
    结果把 `add_test(...)` 注释掉之后照样匹配得到 ⇒ P5 变成「永远绿色」的假绿
    （变异自检时才发现：退出码居然是 0）。
    扫配置文件判「有没有」之前，必须先把注释摘干净 —— 注释掉的配置等于没有。
    """
    out = []
    for line in text.split("\n"):
        idx = line.find('#')
        out.append(line if idx < 0 else line[:idx])
    return "\n".join(out)


print("=== P5 CMakeLists 注册 ===")
cm_raw = open(CMAKELISTS, encoding="utf-8").read() if os.path.exists(CMAKELISTS) else ""
cm = strip_cmake_comments(cm_raw)
check("CMakeLists.txt 存在", bool(cm_raw))
exe_names = set(re.findall(r'add_executable\(\s*(\w+)', cm))
test_names = set(re.findall(r'add_test\(\s*NAME\s+(\w+)', cm))
check("CMakeLists 里有 add_test", bool(test_names), str(test_names))

for name in sources:
    stem = name[:-4]  # xxx.cpp -> xxx
    check("%s P5 已 add_executable" % stem, stem in exe_names, str(sorted(exe_names)))
    check("%s P5 已 add_test" % stem, stem in test_names, str(sorted(test_names)))

# 反向：注册了但源文件不存在（删了测试忘了摘注册）
for t in sorted(test_names):
    check("add_test(%s) 对应源文件存在" % t, os.path.exists(os.path.join(TESTS, t + ".cpp")))

print("=== P7 Python 脚本的 UTF-8 输出兜底 ===")


def has_cjk(text):
    return any('\u4e00' <= ch <= '\u9fff' for ch in text)


# ⚠️ Windows runner 上 Python 的默认 stdout 编码是 cp1252，一打印中文就
#    UnicodeEncodeError 崩掉 —— 而且崩在 output 阶段，看上去像「闸门判定失败」，
#    实际根本没跑到判定（DSH-120-B 第五轮 CI 就是这么红的）。
#    只要有中文且会 print，就必须在开头 reconfigure 成 utf-8。
for directory in (SCRIPTS, TESTS):
    if not os.path.isdir(directory):
        continue
    for name in sorted(f for f in os.listdir(directory) if f.endswith(".py")):
        path = os.path.join(directory, name)
        text = open(path, encoding="utf-8", errors="replace").read()
        if not (has_cjk(text) and "print(" in text):
            continue  # 不打印中文的脚本不受这个约束
        guarded = ("reconfigure(encoding=\"utf-8\"" in text
                   or "reconfigure(encoding='utf-8'" in text
                   or "PYTHONIOENCODING" in text)
        check("%s P7 有 UTF-8 输出兜底（会打印中文）" % name, guarded,
              "需在开头加 sys.stdout.reconfigure(encoding=\"utf-8\", errors=\"replace\")")

print()
if fails:
    print("❌ FAIL %d 项：%s" % (len(fails), fails))
    sys.exit(1)
print("✅ 全部通过 —— 这些测试在 Release 下也是真闸门")
