// ⚠️ 这里以前用 assert —— CI 编的是 Release（预定义 NDEBUG），assert 会被整段编译掉，
//    下面这 12 条判据一条都没真正跑过，而 ctest 每次都报「通过」（DSH-120-B）。
//    现在统一走 tests/test_check.h 的 CHECK，Release 下同样生效。
//
// 这套测的是「右键发送到设备」那条链路的序列化边界：
//   - 中文设备名编解码往返（设备名带中文是常态，不是边缘情况）
//   - 命令行参数识别（双击打开 vs 右键「发送到」）
//   - 跨进程载荷完整性（长度差一个字节就必须判废，不能解出半个 wchar_t）
#include "send_to_integration.h"
#include "test_check.h"

#include <filesystem>
#include <iostream>
#include <string>

int wmain() {
    const std::wstring original = L"device-测试-01";
    const std::wstring encoded = send_to::EncodeDeviceId(original);
    auto decoded = send_to::DecodeDeviceId(encoded);
    CHECK_MSG(decoded && *decoded == original, "中文设备名必须能编解码往返");
    CHECK_MSG(!send_to::DecodeDeviceId(L"not-hex"), "非十六进制输入要判废，不能解出半个 wchar_t");

    // 不带 --send-to-picker 的普通启动：不能误判成「发送到设备」
    auto none = send_to::ParseArguments({L"ordinary-file.txt"});
    CHECK_MSG(!none.requested && !none.invocation, "普通启动不能被当成 send-to 调用");

    auto picker =
        send_to::ParseArguments({L"--send-to-picker", std::filesystem::current_path().wstring()});
    CHECK(picker.requested && picker.invocation);
    CHECK(picker.invocation->deviceId.empty());
    CHECK(picker.invocation->paths.size() == 1);

    auto pickerPayload = send_to::Serialize(*picker.invocation);
    auto restoredPicker =
        send_to::Deserialize(pickerPayload.data(), pickerPayload.size() * sizeof(wchar_t));
    CHECK(restoredPicker && restoredPicker->deviceId.empty());
    CHECK(restoredPicker->paths == picker.invocation->paths);

    // 路径带中文 + 带空格：这是 Windows 上的常态，不是边缘情况
    send_to::Invocation invocation;
    invocation.deviceId = original;
    invocation.paths = {L"C:\\临时\\一个文件.txt", L"D:\\有 空格\\文件夹"};
    auto payload = send_to::Serialize(invocation);
    auto restored = send_to::Deserialize(payload.data(), payload.size() * sizeof(wchar_t));
    CHECK(restored);
    CHECK_MSG(restored->deviceId == invocation.deviceId, "设备名往返后必须逐字相同");
    CHECK_MSG(restored->paths == invocation.paths, "中文/带空格路径往返后必须逐字相同");
    // 少一个字节 ⇒ 最后一个 wchar_t 只剩半截，宁可判废也不能解出半个字符
    CHECK_MSG(!send_to::Deserialize(payload.data(), payload.size() * sizeof(wchar_t) - 1),
              "载荷长度不是 wchar_t 整数倍时必须判废");

    return dsh_test::Finish("send_to_integration_tests");
}
