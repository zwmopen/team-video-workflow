#pragma once

#include <atomic>
#include <functional>
#include <string>

void RunLanReceiver(std::atomic<bool>& running,
                    const std::function<void(const std::wstring&)>& status,
                    const std::function<void(const std::wstring&, const std::wstring&)>& log);
