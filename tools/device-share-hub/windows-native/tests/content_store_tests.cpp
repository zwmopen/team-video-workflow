#include "content_store.h"

#include <windows.h>

#include <cassert>
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
    assert(old.size() == 1);
    assert(old.count(L"old-hash") == 1);

    std::filesystem::path source = root / L"Work 2";
    std::filesystem::create_directories(source);
    store.RecordSuccessfulTransfers(L"device-1", L"Phone A", L"USB", L"2026-07-27 12:00:00.000",
        {{L"new-hash", source, 5, 4}});
    auto current = store.PreviousTransfersForDevice(L"device-1", L"Phone A");
    assert(current.size() == 2);
    assert(current.count(L"new-hash") == 1);

    store.SetSetting(L"library_path", L"D:\\素材库");
    assert(store.GetSetting(L"library_path") == L"D:\\素材库");
    assert(store.GetSetting(L"missing") == L"");

    StoredTransferItem archived{L"new-hash", source, 5, 4};
    store.RecordArchiveState(archived, L"archive_ready", root / L"Work 2.zip", L"zip-hash",
                             L"2026-07-27 12:05:00.000", L"压缩包已校验");
    assert(store.StateForFingerprint(L"new-hash") == L"archive_ready");
    store.RecordArchiveState(archived, L"archived", root / L"Work 2.zip", L"zip-hash",
                             L"2026-07-27 12:06:00.000", L"原目录已移入回收站");
    assert(store.StateForFingerprint(L"new-hash") == L"archived");

    std::filesystem::remove_all(root);
    std::wcout << L"content_store_tests passed\n";
    return 0;
}
