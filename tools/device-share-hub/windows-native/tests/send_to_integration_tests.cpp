#include "send_to_integration.h"

#include <cassert>
#include <iostream>

int wmain() {
    const std::wstring original = L"device-测试-01";
    const std::wstring encoded = send_to::EncodeDeviceId(original);
    auto decoded = send_to::DecodeDeviceId(encoded);
    assert(decoded && *decoded == original);
    assert(!send_to::DecodeDeviceId(L"not-hex"));

    auto none = send_to::ParseArguments({L"ordinary-file.txt"});
    assert(!none.requested && !none.invocation);

    send_to::Invocation invocation;
    invocation.deviceId = original;
    invocation.paths = {L"C:\\临时\\一个文件.txt", L"D:\\有 空格\\文件夹"};
    auto payload = send_to::Serialize(invocation);
    auto restored = send_to::Deserialize(payload.data(), payload.size() * sizeof(wchar_t));
    assert(restored);
    assert(restored->deviceId == invocation.deviceId);
    assert(restored->paths == invocation.paths);
    assert(!send_to::Deserialize(payload.data(), payload.size() * sizeof(wchar_t) - 1));

    std::wcout << L"send_to_integration_tests passed\n";
    return 0;
}
