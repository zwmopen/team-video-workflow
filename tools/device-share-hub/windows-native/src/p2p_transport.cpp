#include "p2p_transport.h"

#include <rtc/rtc.hpp>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <condition_variable>
#include <fstream>
#include <mutex>
#include <set>
#include <sstream>
#include <stdexcept>
#include <thread>

namespace p2p_transport {
namespace {

constexpr size_t kChunkBytes = 48 * 1024;
constexpr size_t kBufferedLimit = 4 * 1024 * 1024;
constexpr auto kConnectTimeout = std::chrono::seconds(20);
constexpr auto kAckTimeout = std::chrono::seconds(20);
constexpr auto kBackpressureStallTimeout = std::chrono::seconds(20);

std::string JsonEscape(const std::string& value) {
    std::string output;
    for (unsigned char c : value) {
        switch (c) {
        case '"': output += "\\\""; break;
        case '\\': output += "\\\\"; break;
        case '\n': output += "\\n"; break;
        case '\r': output += "\\r"; break;
        case '\t': output += "\\t"; break;
        default: output.push_back(static_cast<char>(c)); break;
        }
    }
    return output;
}

std::string JsonString(const std::string& value) { return "\"" + JsonEscape(value) + "\""; }

std::string JsonField(const std::string& json, const std::string& key) {
    const std::string marker = "\"" + key + "\"";
    size_t position = json.find(marker);
    if (position == std::string::npos) return {};
    position = json.find(':', position + marker.size());
    if (position == std::string::npos) return {};
    position = json.find('"', position + 1);
    if (position == std::string::npos) return {};
    std::string result;
    bool escaped = false;
    for (size_t i = position + 1; i < json.size(); ++i) {
        const char c = json[i];
        if (escaped) {
            result.push_back(c == 'n' ? '\n' : c == 'r' ? '\r' : c == 't' ? '\t' : c);
            escaped = false;
        } else if (c == '\\') {
            escaped = true;
        } else if (c == '"') {
            break;
        } else {
            result.push_back(c);
        }
    }
    return result;
}

std::string Manifest(const std::string& transferId,
                     const std::string& senderDeviceId,
                     const std::string& recipientDeviceId,
                     const std::vector<FileItem>& files) {
    uint64_t total = 0;
    std::ostringstream output;
    output << "{\"v\":1,\"kind\":\"manifest\",\"transferId\":"
           << JsonString(transferId) << ",\"senderDeviceId\":"
           << JsonString(senderDeviceId) << ",\"recipientDeviceId\":"
           << JsonString(recipientDeviceId) << ",\"objects\":[";
    for (size_t i = 0; i < files.size(); ++i) {
        const auto& file = files[i];
        if (i) output << ',';
        output << "{\"index\":" << i << ",\"bytes\":" << file.bytes
               << ",\"sha256\":" << JsonString(file.sha256)
               << ",\"name\":" << JsonString(file.name)
               << ",\"mime\":" << JsonString(file.mime) << '}';
        total += file.bytes;
    }
    output << "],\"totalBytes\":" << total << '}';
    return output.str();
}

rtc::binary Chunk(uint32_t index, uint64_t offset,
                  const std::vector<char>& payload, size_t count) {
    rtc::binary frame(20 + count);
    frame[0] = static_cast<std::byte>('D'); frame[1] = static_cast<std::byte>('S');
    frame[2] = static_cast<std::byte>('H'); frame[3] = static_cast<std::byte>('P');
    frame[4] = static_cast<std::byte>(1); frame[5] = static_cast<std::byte>(1);
    frame[6] = static_cast<std::byte>(0); frame[7] = static_cast<std::byte>(0);
    for (int shift = 0; shift < 4; ++shift) frame[8 + shift] =
        static_cast<std::byte>(index >> (24 - shift * 8));
    for (int shift = 0; shift < 8; ++shift) frame[12 + shift] =
        static_cast<std::byte>(offset >> (56 - shift * 8));
    for (size_t index = 0; index < count; ++index) {
        frame[20 + index] = static_cast<std::byte>(static_cast<unsigned char>(payload[index]));
    }
    return frame;
}

void ApplySignal(const std::shared_ptr<rtc::PeerConnection>& peer, const Signal& signal) {
    if (signal.type == "answer") {
        const auto sdp = JsonField(signal.data, "sdp");
        if (sdp.empty()) throw std::runtime_error("P2P answer 为空");
        peer->setRemoteDescription(rtc::Description(sdp, "answer"));
    } else if (signal.type == "ice") {
        const auto candidate = JsonField(signal.data, "candidate");
        const auto mid = JsonField(signal.data, "mid");
        if (candidate.empty() || mid.empty()) throw std::runtime_error("P2P ICE 候选无效");
        peer->addRemoteCandidate(rtc::Candidate(candidate, mid));
    }
}

}  // namespace

bool Send(const std::string& transferId,
          const std::string& senderDeviceId,
          const std::string& recipientDeviceId,
          const std::vector<FileItem>& files,
          const SendSignal& sendSignal,
          const PollSignals& pollSignals,
          const ProgressCallback& progress,
          std::string& error) {
    std::shared_ptr<rtc::PeerConnection> peer;
    std::shared_ptr<rtc::DataChannel> channel;
    const auto closing = std::make_shared<std::atomic<bool>>(false);
    const auto cleanup = [&]() {
        closing->store(true, std::memory_order_release);
        if (channel) {
            try { channel->close(); } catch (...) { }
            channel.reset();
        }
        if (peer) {
            try { peer->close(); } catch (...) { }
            peer.reset();
        }
    };
    try {
        if (files.empty() || !sendSignal || !pollSignals) throw std::runtime_error("P2P 传输参数不完整");
        rtc::Preload();
        rtc::Configuration configuration;
        configuration.iceServers.emplace_back("stun:stun.cloudflare.com:3478");
        configuration.maxMessageSize = kChunkBytes + 20;
        peer = std::make_shared<rtc::PeerConnection>(configuration);
        std::mutex mutex;
        std::condition_variable condition;
        bool opened = false;
        bool failed = false;
        bool acknowledged = false;
        std::string failureReason;
        std::set<std::string> appliedSignals;
        peer->onLocalDescription([&](rtc::Description description) {
            std::ostringstream data;
            data << "{\"type\":\"" << description.typeString()
                 << "\",\"sdp\":" << JsonString(description.generateSdp()) << '}';
            sendSignal(description.typeString(), data.str());
        });
        peer->onLocalCandidate([&](rtc::Candidate candidate) {
            std::ostringstream data;
            data << "{\"candidate\":" << JsonString(candidate.candidate())
                 << ",\"mid\":" << JsonString(candidate.mid()) << '}';
            sendSignal("ice", data.str());
        });
        peer->onStateChange([&, closing](rtc::PeerConnection::State state) {
            if (closing->load(std::memory_order_acquire)) return;
            if (state == rtc::PeerConnection::State::Failed || state == rtc::PeerConnection::State::Closed) {
                std::lock_guard<std::mutex> lock(mutex);
                failed = true;
                failureReason = "P2P 连接失败";
                condition.notify_all();
            }
        });
        channel = peer->createDataChannel("album-transfer-v1");
        channel->onOpen([&, closing] {
            if (closing->load(std::memory_order_acquire)) return;
            std::lock_guard<std::mutex> lock(mutex); opened = true; condition.notify_all();
        });
        channel->onClosed([&, closing] {
            if (closing->load(std::memory_order_acquire)) return;
            std::lock_guard<std::mutex> lock(mutex);
            if (!acknowledged) { failed = true; failureReason = "P2P 数据通道已关闭"; }
            condition.notify_all();
        });
        channel->onError([&, closing](std::string message) {
            if (closing->load(std::memory_order_acquire)) return;
            std::lock_guard<std::mutex> lock(mutex);
            failed = true; failureReason = message.empty() ? "P2P 数据通道错误" : message;
            condition.notify_all();
        });
        channel->onMessage([&, closing](rtc::message_variant message) {
            if (closing->load(std::memory_order_acquire)) return;
            if (!std::holds_alternative<rtc::string>(message)) return;
            const auto& text = std::get<rtc::string>(message);
            if (text.find("\"kind\":\"ack\"") == std::string::npos) return;
            std::lock_guard<std::mutex> lock(mutex);
            acknowledged = text.find("\"ok\":true") != std::string::npos;
            if (!acknowledged) { failed = true; failureReason = "手机拒绝了 P2P 文件校验"; }
            condition.notify_all();
        });
        peer->setLocalDescription();
        auto deadline = std::chrono::steady_clock::now() + kConnectTimeout;
        while (std::chrono::steady_clock::now() < deadline) {
            for (const auto& signal : pollSignals()) {
                if (!appliedSignals.insert(signal.key).second) continue;
                ApplySignal(peer, signal);
            }
            std::unique_lock<std::mutex> lock(mutex);
            if (opened || failed) break;
            condition.wait_for(lock, std::chrono::milliseconds(100));
        }
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!opened) throw std::runtime_error(failureReason.empty() ? "P2P 建连超时" : failureReason);
        }
        channel->send(Manifest(transferId, senderDeviceId, recipientDeviceId, files));
        uintmax_t sentTotal = 0;
        uintmax_t totalBytes = 0;
        for (const auto& file : files) totalBytes += file.bytes;
        std::vector<char> buffer(kChunkBytes);
        for (size_t index = 0; index < files.size(); ++index) {
            std::ostringstream start;
            start << "{\"v\":1,\"kind\":\"object-start\",\"index\":" << index << '}';
            channel->send(start.str());
            std::ifstream input(files[index].path, std::ios::binary);
            if (!input) throw std::runtime_error("无法读取 P2P 作品文件");
            uint64_t offset = 0;
            while (input) {
                input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
                const auto count = static_cast<size_t>(input.gcount());
                if (count == 0) continue;
                auto drainDeadline = std::chrono::steady_clock::now() + kBackpressureStallTimeout;
                auto previousBuffered = channel->bufferedAmount();
                while (previousBuffered > kBufferedLimit) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(10));
                    const auto buffered = channel->bufferedAmount();
                    if (buffered < previousBuffered) {
                        drainDeadline = std::chrono::steady_clock::now() + kBackpressureStallTimeout;
                    }
                    previousBuffered = buffered;
                    if (std::chrono::steady_clock::now() >= drainDeadline) {
                        throw std::runtime_error("P2P 数据通道背压停滞");
                    }
                }
                auto frame = Chunk(static_cast<uint32_t>(index), offset, buffer, count);
                channel->send(std::move(frame));
                offset += count; sentTotal += count;
                if (progress) progress(sentTotal, totalBytes);
            }
            std::ostringstream end;
            end << "{\"v\":1,\"kind\":\"object-end\",\"index\":" << index << '}';
            channel->send(end.str());
        }
        std::ostringstream complete;
        complete << "{\"v\":1,\"kind\":\"complete\",\"transferId\":" << JsonString(transferId) << '}';
        channel->send(complete.str());
        const auto ackDeadline = std::chrono::steady_clock::now() + kAckTimeout;
        while (std::chrono::steady_clock::now() < ackDeadline) {
            for (const auto& signal : pollSignals()) {
                if (!appliedSignals.insert(signal.key).second) continue;
                ApplySignal(peer, signal);
            }
            std::unique_lock<std::mutex> lock(mutex);
            if (acknowledged || failed) break;
            condition.wait_for(lock, std::chrono::milliseconds(100));
        }
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!acknowledged) throw std::runtime_error(failureReason.empty() ? "P2P 文件确认超时" : failureReason);
        }
        cleanup();
        return true;
    } catch (const std::exception& exception) {
        cleanup();
        error = exception.what();
        return false;
    }
}

}  // namespace p2p_transport
