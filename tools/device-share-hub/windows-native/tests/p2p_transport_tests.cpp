#include "p2p_transport.h"

#include <rtc/rtc.hpp>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <variant>
#include <vector>

namespace {

struct SignalEnvelope {
    std::string type;
    std::string data;
};

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

std::string JsonField(const std::string& json, const std::string& key) {
    const std::string marker = "\"" + key + "\"";
    size_t position = json.find(marker);
    if (position == std::string::npos) return {};
    position = json.find(':', position + marker.size());
    position = json.find('"', position == std::string::npos ? position : position + 1);
    if (position == std::string::npos) return {};
    std::string result;
    bool escaped = false;
    for (size_t i = position + 1; i < json.size(); ++i) {
        const char c = json[i];
        if (escaped) {
            switch (c) {
            case 'n': result.push_back('\n'); break;
            case 'r': result.push_back('\r'); break;
            case 't': result.push_back('\t'); break;
            default: result.push_back(c); break;
            }
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

void ApplyResponderSignal(const SignalEnvelope& signal,
                          const std::shared_ptr<rtc::PeerConnection>& responder,
                          bool& remoteDescriptionSet,
                          std::vector<SignalEnvelope>& pendingCandidates) {
    if (signal.type == "offer") {
        const auto sdp = JsonField(signal.data, "sdp");
        if (sdp.empty()) throw std::runtime_error("loopback offer is empty");
        responder->setRemoteDescription(rtc::Description(sdp, "offer"));
        remoteDescriptionSet = true;
        for (const auto& candidate : pendingCandidates) {
            responder->addRemoteCandidate(rtc::Candidate(
                JsonField(candidate.data, "candidate"), JsonField(candidate.data, "mid")));
        }
        pendingCandidates.clear();
        responder->setLocalDescription();
    } else if (signal.type == "ice") {
        if (!remoteDescriptionSet) {
            pendingCandidates.push_back(signal);
            return;
        }
        responder->addRemoteCandidate(rtc::Candidate(
            JsonField(signal.data, "candidate"), JsonField(signal.data, "mid")));
    }
}

bool CheckFrame(const rtc::binary& frame, std::vector<char>& received, bool& frameError) {
    if (frame.size() < 20
        || std::to_integer<unsigned char>(frame[0]) != 'D'
        || std::to_integer<unsigned char>(frame[1]) != 'S'
        || std::to_integer<unsigned char>(frame[2]) != 'H'
        || std::to_integer<unsigned char>(frame[3]) != 'P') {
        frameError = true;
        return false;
    }
    uint64_t offset = 0;
    for (size_t index = 0; index < 8; ++index) {
        offset = (offset << 8) | std::to_integer<unsigned char>(frame[12 + index]);
    }
    if (offset != received.size()) {
        frameError = true;
        return false;
    }
    received.reserve(received.size() + frame.size() - 20);
    for (size_t index = 20; index < frame.size(); ++index) {
        received.push_back(static_cast<char>(std::to_integer<unsigned char>(frame[index])));
    }
    return true;
}

int Fail(const std::string& message) {
    std::cerr << "p2p_transport_tests: " << message << std::endl;
    return 1;
}

}  // namespace

int main() {
    const auto root = std::filesystem::temp_directory_path() /
        ("device-share-p2p-loopback-" + std::to_string(
            static_cast<unsigned long long>(std::chrono::steady_clock::now().time_since_epoch().count())));
    std::filesystem::create_directories(root);
    const auto file = root / "loopback.bin";
    std::vector<char> expected(256 * 1024 + 137);
    for (size_t index = 0; index < expected.size(); ++index) {
        expected[index] = static_cast<char>((index * 31 + 17) % 251);
    }
    {
        std::ofstream output(file, std::ios::binary);
        output.write(expected.data(), static_cast<std::streamsize>(expected.size()));
    }

    std::mutex mutex;
    std::condition_variable condition;
    std::deque<SignalEnvelope> toResponder;
    std::deque<SignalEnvelope> toSender;
    bool responderDescriptionSet = false;
    std::vector<SignalEnvelope> pendingCandidates;
    bool transferComplete = false;
    bool frameError = false;
    std::vector<char> received;
    std::string responderError;
    bool stopRelay = false;
    uint64_t signalSequence = 0;

    rtc::Preload();
    rtc::Configuration configuration;
    configuration.iceServers.emplace_back("stun:stun.cloudflare.com:3478");
    configuration.maxMessageSize = 48 * 1024 + 20;
    auto responder = std::make_shared<rtc::PeerConnection>(configuration);
    responder->onLocalDescription([&](rtc::Description description) {
        std::ostringstream data;
        data << "{\"type\":\"" << description.typeString()
             << "\",\"sdp\":\"" << JsonEscape(description.generateSdp()) << "\"}";
        std::lock_guard<std::mutex> lock(mutex);
        toSender.push_back({description.typeString(), data.str()});
        condition.notify_all();
    });
    responder->onLocalCandidate([&](rtc::Candidate candidate) {
        std::ostringstream data;
        data << "{\"candidate\":\"" << JsonEscape(candidate.candidate())
             << "\",\"mid\":\"" << JsonEscape(candidate.mid()) << "\"}";
        std::lock_guard<std::mutex> lock(mutex);
        toSender.push_back({"ice", data.str()});
        condition.notify_all();
    });
    responder->onDataChannel([&](std::shared_ptr<rtc::DataChannel> channel) {
        const std::weak_ptr<rtc::DataChannel> weakChannel = channel;
        channel->onMessage([&, weakChannel](rtc::message_variant message) {
            if (std::holds_alternative<rtc::binary>(message)) {
                std::lock_guard<std::mutex> lock(mutex);
                CheckFrame(std::get<rtc::binary>(message), received, frameError);
                condition.notify_all();
                return;
            }
            const auto& text = std::get<rtc::string>(message);
            if (text.find("\"kind\":\"complete\"") != std::string::npos) {
                bool valid = false;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    valid = received == expected && !frameError;
                    if (!valid) responderError = "received P2P bytes do not match the source file";
                }
                if (!valid) {
                    condition.notify_all();
                    return;
                }
                if (const auto activeChannel = weakChannel.lock()) {
                    activeChannel->send("{\"v\":1,\"kind\":\"ack\",\"ok\":true,\"objects\":1,\"bytes\":"
                                        + std::to_string(received.size()) + "}");
                }
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    transferComplete = true;
                    condition.notify_all();
                }
            }
        });
    });

    std::thread relay([&] {
        for (;;) {
            SignalEnvelope signal;
            bool hasSignal = false;
            {
                std::unique_lock<std::mutex> lock(mutex);
                condition.wait_for(lock, std::chrono::milliseconds(5), [&] {
                    return stopRelay || !toResponder.empty();
                });
                if (stopRelay && toResponder.empty()) return;
                if (!toResponder.empty()) {
                    signal = std::move(toResponder.front());
                    toResponder.pop_front();
                    hasSignal = true;
                }
            }
            if (!hasSignal) continue;
            try {
                ApplyResponderSignal(signal, responder, responderDescriptionSet, pendingCandidates);
            } catch (const std::exception& error) {
                std::lock_guard<std::mutex> lock(mutex);
                responderError = error.what();
                condition.notify_all();
            }
        }
    });

    p2p_transport::FileItem item;
    item.path = file;
    item.name = "loopback.bin";
    item.mime = "application/octet-stream";
    item.bytes = expected.size();
    item.sha256 = "loopback-test-hash";
    std::string error;
    const bool sent = p2p_transport::Send(
        "loopback-transfer", "windows-loopback", "mobile-loopback", {item},
        [&](const std::string& type, const std::string& data) {
            std::lock_guard<std::mutex> lock(mutex);
            toResponder.push_back({type, data});
            condition.notify_all();
        },
        [&] {
            std::lock_guard<std::mutex> lock(mutex);
            std::vector<p2p_transport::Signal> signals;
            while (!toSender.empty()) {
                auto signal = std::move(toSender.front());
                toSender.pop_front();
                signals.push_back({signal.type + ":" + std::to_string(signalSequence++),
                                   signal.type, signal.data});
            }
            return signals;
        },
        {}, error);

    {
        std::lock_guard<std::mutex> lock(mutex);
        stopRelay = true;
        condition.notify_all();
    }
    relay.join();
    responder->close();
    responder.reset();
    std::filesystem::remove_all(root);

    if (!sent) return Fail("sender failed: " + error);
    if (!transferComplete) return Fail("responder did not observe completion and ACK");
    if (received != expected) return Fail("received payload differs from source");
    std::cout << "P2P DataChannel loopback transferred bytes and received ACK." << std::endl;
    return 0;
}
