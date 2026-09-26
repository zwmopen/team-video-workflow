// ⚠️ 这里以前用 assert —— CI 编的是 Release（预定义 NDEBUG），assert 会被整段编译掉，
//    下面这 8 条判据一条都没真正跑过，而 ctest 每次都报「通过」（DSH-120-B）。
//    现在统一走 tests/test_check.h 的 CHECK，Release 下同样生效。
#include "content_store.h"
#include "test_check.h"

#include <windows.h>

#include <filesystem>
#include <fstream>
#include <iostream>

int wmain() {
    std::filesystem::path root = std::filesystem::temp_directory_path() /
        (L"device-share-store-test-" + std::to_wstring(GetCurrentProcessId()));
    std::filesystem::remove_all(root);
    std::filesystem::create_directories(root);
    std::filesystem::path legacy = root / L"transfer-history.tsv";
    {
        std::ofstream output(legacy, std::ios::binary);
        output << "old-hash\tdevice-1\t2026-07-01 10:00:00.000\tPhone A\tWork 1\tWi-Fi\t3\t2\n";
    }

    ContentStore store(root / L"content-history.db");
    store.Initialize(legacy);
    store.Initialize(legacy);
    auto old = store.PreviousTransfersForDevice(L"device-1", L"Phone A");
    // 上面 Initialize 连调了两次：历史记录不能被重复导入
    CHECK_MSG(old.size() == 1, "重复 Initialize 必须幂等，历史记录不能翻倍");
    CHECK(old.count(L"old-hash") == 1);

    std::filesystem::path source = root / L"Work 2";
    std::filesystem::create_directories(source);
    store.RecordSuccessfulTransfers(L"device-1", L"Phone A", L"USB", L"2026-07-27 12:00:00.000",
        {{L"new-hash", source, 5, 4}});
    auto current = store.PreviousTransfersForDevice(L"device-1", L"Phone A");
    CHECK(current.size() == 2);
    CHECK(current.count(L"new-hash") == 1);

    store.SetSetting(L"library_path", L"D:\\素材库");
    CHECK(store.GetSetting(L"library_path") == L"D:\\素材库");
    // 没写过的 key 要给空串，不能抛异常、不能返回垃圾
    CHECK_MSG(store.GetSetting(L"missing") == L"", "未设置的 key 必须返回空串");

    StoredTransferItem archived{L"new-hash", source, 5, 4};
    store.RecordArchiveState(archived, L"archive_ready", root / L"Work 2.zip", L"zip-hash",
                             L"2026-07-27 12:05:00.000", L"压缩包已校验");
    // 归档状态要能被后一次覆盖（打包完成 → 已归档），否则状态机会卡在半路
    CHECK_MSG(store.StateForFingerprint(L"new-hash") == L"archive_ready",
              "第一次记录归档状态后必须能查到");
    store.RecordArchiveState(archived, L"archived", root / L"Work 2.zip", L"zip-hash",
                             L"2026-07-27 12:06:00.000", L"原目录已移入回收站");
    CHECK_MSG(store.StateForFingerprint(L"new-hash") == L"archived",
              "后一次归档状态必须覆盖前一次");

    std::filesystem::remove_all(root);
    return dsh_test::Finish("content_store_tests");
}
