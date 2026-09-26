// DSH Windows 原生测试的统一断言头。
//
// 为什么不用 assert：CI 编的是 Release（预定义了 NDEBUG），`assert(...)` 会被整段编译掉，
// 测试就变成「永远绿色」的假闸门 —— 挂了也不知道，而且连「装没装闸门」都看不出来。
//
// 历史教训（DSH-119-B / DSH-120-B）：仓库里 content_store_tests 与 send_to_integration_tests
// 长期用 assert，共 20 条判据实际一条都没跑。CI 每次报告 4/4 passed，是假绿。
//
// 用法：
//   #include "test_check.h"
//   ...
//   CHECK(a == b);
//   CHECK_MSG(a == b, "这里要说明为什么必须相等");
//   return dsh_test::Finish("my_test");   // 有失败返回 1，全过返回 0
//
// ⚠️ 收尾必须走 Finish() 或自己判 FailureCount()，否则 ctest 永远看到 0（通过）。
#pragma once

#include <iostream>

namespace dsh_test {

inline int& FailureCount() {
    static int count = 0;
    return count;
}

inline void Check(bool condition, const char* expression, const char* file, int line) {
    if (condition) return;
    ++FailureCount();
    std::cerr << "  [FAIL] " << file << ":" << line << ": " << expression << "\n";
}

inline void CheckWithHint(bool condition, const char* expression, const char* file, int line,
                          const char* hint) {
    if (condition) return;
    ++FailureCount();
    std::cerr << "  [FAIL] " << file << ":" << line << ": " << expression << "\n"
              << "         判据含义: " << hint << "\n";
}

// 收尾：统一打印结果，并把「有没有失败」翻译成给 ctest 的退出码。
inline int Finish(const char* testName) {
    if (FailureCount()) {
        std::cerr << testName << " FAILED (" << FailureCount() << " 项)\n";
        return 1;
    }
    std::cout << testName << " passed\n";
    return 0;
}

}  // namespace dsh_test

#define CHECK(expression) \
    ::dsh_test::Check(static_cast<bool>(expression), #expression, __FILE__, __LINE__)

#define CHECK_MSG(expression, hint) \
    ::dsh_test::CheckWithHint(static_cast<bool>(expression), #expression, __FILE__, __LINE__, hint)
