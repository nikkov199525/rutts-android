#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

class SynthEngine {
public:
    using AudioConsumer = std::function<bool(const std::uint8_t*, size_t)>;

    std::vector<std::uint8_t> Synthesize(const std::string& text, const std::vector<int>& config);
    bool SynthesizeStreaming(
        const std::string& text,
        const std::vector<int>& config,
        const AudioConsumer& consumer);
    void RequestStop();

private:
    std::uint64_t BeginRequest();
    bool IsCurrent(std::uint64_t request_generation) const;

    std::atomic<std::uint64_t> generation_{ 0 };
};
