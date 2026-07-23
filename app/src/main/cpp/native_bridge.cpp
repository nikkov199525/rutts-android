#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <limits>
#include <string>
#include <utility>
#include <vector>

#include "coder.h"
#include "samplerate.h"
#include "synth_engine.h"

namespace {
constexpr int kRulexBufferSize = 256;
constexpr size_t kConfigSampleRateIndex = 2;
constexpr size_t kConfigInterpolationMultiplierIndex = 3;
constexpr size_t kConfigInterpolationAlgorithmIndex = 4;
constexpr size_t kConfigUseRateBoostIndex = 9;
constexpr int kFallbackSampleRate = 10000;
constexpr int kMinSampleRate = 8000;
constexpr int kMaxSampleRate = 192000;
constexpr double kPi = 3.14159265358979323846;
SynthEngine g_engine;

int NormalizeInterpolationMultiplier(int value) {
    if (value < 2) return 1;
    if (value > 2) return 4;
    return 2;
}

class Pcm8ToPcm16Adapter {
public:
    using AudioConsumer = std::function<bool(const std::uint8_t*, size_t)>;

    Pcm8ToPcm16Adapter(
        int source_sample_rate,
        int interpolation_multiplier,
        int interpolation_algorithm,
        int output_sample_rate,
        bool use_soft_resampling,
        AudioConsumer consumer)
        : interpolation_multiplier_(NormalizeInterpolationMultiplier(interpolation_multiplier)),
          input_sample_rate_(std::clamp(
              source_sample_rate * interpolation_multiplier_,
              kMinSampleRate,
              kMaxSampleRate)),
          output_sample_rate_(output_sample_rate),
          use_soft_resampling_(use_soft_resampling),
          consumer_(std::move(consumer)) {
        if (interpolation_multiplier_ != 1) {
            int error = 0;
            interpolation_state_ = src_new(
                interpolation_algorithm == 1 ? SRC_ZERO_ORDER_HOLD : SRC_LINEAR,
                1,
                &error);
            valid_ = interpolation_state_ != nullptr && error == 0;
        }
        if (valid_ && input_sample_rate_ != output_sample_rate_) {
            BuildFilter();
        }
    }

    Pcm8ToPcm16Adapter(const Pcm8ToPcm16Adapter&) = delete;
    Pcm8ToPcm16Adapter& operator=(const Pcm8ToPcm16Adapter&) = delete;

    ~Pcm8ToPcm16Adapter() {
        if (interpolation_state_) {
            src_delete(interpolation_state_);
        }
    }

    bool valid() const {
        return valid_ && static_cast<bool>(consumer_);
    }

    bool Push(const std::uint8_t* pcm8, size_t size) {
        if (!valid() || !pcm8 || size == 0) {
            return size == 0 && valid();
        }

        if (interpolation_multiplier_ == 1 && input_sample_rate_ == output_sample_rate_) {
            return EmitPcm8AsPcm16(pcm8, size);
        }

        interpolation_input_.resize(size);
        for (size_t index = 0; index < size; ++index) {
            interpolation_input_[index] =
                static_cast<float>(static_cast<int>(pcm8[index]) - 128) / 128.0f;
        }
        if (interpolation_multiplier_ == 1) {
            input_.insert(input_.end(), interpolation_input_.begin(), interpolation_input_.end());
            interpolation_input_.clear();
        } else if (!Interpolate(false)) {
            return false;
        }
        return Process(false);
    }

    bool PushPcm16(const std::uint8_t* pcm16_bytes, size_t size) {
        if (!valid() || !pcm16_bytes || size == 0 || size % sizeof(std::int16_t) != 0) {
            return size == 0 && valid();
        }
        if (interpolation_multiplier_ == 1 && input_sample_rate_ == output_sample_rate_) {
            return consumer_(pcm16_bytes, size);
        }

        const size_t sample_count = size / sizeof(std::int16_t);
        interpolation_input_.resize(sample_count);
        for (size_t index = 0; index < sample_count; ++index) {
            std::int16_t sample = 0;
            std::memcpy(&sample, pcm16_bytes + index * sizeof(sample), sizeof(sample));
            interpolation_input_[index] = static_cast<float>(sample) / 32768.0f;
        }
        if (interpolation_multiplier_ == 1) {
            input_.insert(input_.end(), interpolation_input_.begin(), interpolation_input_.end());
            interpolation_input_.clear();
        } else if (!Interpolate(false)) {
            return false;
        }
        return Process(false);
    }

    bool Finish() {
        if (!valid()) {
            return false;
        }
        if (interpolation_multiplier_ != 1 && !Interpolate(true)) {
            return false;
        }
        return Process(true);
    }

private:
    bool EmitPcm8AsPcm16(const std::uint8_t* pcm8, size_t size) {
        output_.resize(size);
        for (size_t index = 0; index < size; ++index) {
            output_[index] = static_cast<std::int16_t>((static_cast<int>(pcm8[index]) - 128) * 256);
        }
        return EmitPcm16(output_.data(), output_.size());
    }

    void BuildFilter() {
        const double ratio = static_cast<double>(output_sample_rate_) /
            static_cast<double>(input_sample_rate_);
        const double cutoff = (use_soft_resampling_ ? 0.90 : 1.00) * std::min(1.0, ratio);
        coefficients_.resize(kPhaseCount * kTapCount);
        for (int phase = 0; phase < kPhaseCount; ++phase) {
            const double fraction = static_cast<double>(phase) / kPhaseCount;
            double sum = 0.0;
            for (int tap = 0; tap < kTapCount; ++tap) {
                const int offset = tap - kHalfTapCount + 1;
                const double distance = static_cast<double>(offset) - fraction;
                const double edge = std::abs(distance) / kHalfTapCount;
                const double window = edge >= 1.0
                    ? 0.0
                    : 0.42 + 0.5 * std::cos(kPi * edge) + 0.08 * std::cos(2.0 * kPi * edge);
                const double scaled = cutoff * distance;
                const double sinc = std::abs(scaled) < 1e-12
                    ? 1.0
                    : std::sin(kPi * scaled) / (kPi * scaled);
                const double coefficient = cutoff * sinc * window;
                coefficients_[phase * kTapCount + tap] = static_cast<float>(coefficient);
                sum += coefficient;
            }
            for (int tap = 0; tap < kTapCount; ++tap) {
                coefficients_[phase * kTapCount + tap] = static_cast<float>(
                    coefficients_[phase * kTapCount + tap] / sum);
            }
        }
    }

    bool Interpolate(bool end_of_input) {
        if (!interpolation_state_) {
            valid_ = false;
            return false;
        }

        const float end_marker = 0.0f;
        const float* source = end_of_input ? &end_marker : interpolation_input_.data();
        long remaining = end_of_input ? 0L : static_cast<long>(interpolation_input_.size());
        do {
            const size_t output_capacity = std::max<size_t>(
                256,
                (static_cast<size_t>(std::max(remaining, 0L)) + 1U) *
                    static_cast<size_t>(interpolation_multiplier_) + 32U);
            interpolation_output_.resize(output_capacity);
            SRC_DATA data{};
            data.data_in = source;
            data.input_frames = remaining;
            data.data_out = interpolation_output_.data();
            data.output_frames = static_cast<long>(interpolation_output_.size());
            data.src_ratio = static_cast<double>(interpolation_multiplier_);
            data.end_of_input = end_of_input ? 1 : 0;
            if (src_process(interpolation_state_, &data) != 0) {
                valid_ = false;
                return false;
            }
            if (data.output_frames_gen > 0) {
                input_.insert(
                    input_.end(),
                    interpolation_output_.begin(),
                    interpolation_output_.begin() + data.output_frames_gen);
            }
            if (data.input_frames_used > 0) {
                source += data.input_frames_used;
                remaining -= data.input_frames_used;
            }
            if (data.input_frames_used == 0 && data.output_frames_gen == 0) {
                break;
            }
        } while (remaining > 0 || end_of_input);

        interpolation_input_.clear();
        return true;
    }

    bool Process(bool end_of_input) {
        if (input_sample_rate_ == output_sample_rate_) {
            if (input_.empty()) {
                return true;
            }
            output_.resize(input_.size());
            for (size_t index = 0; index < input_.size(); ++index) {
                const auto pcm16 = static_cast<int>(std::lround(input_[index] * 32768.0f));
                output_[index] = static_cast<std::int16_t>(std::clamp(pcm16, -32768, 32767));
            }
            input_.clear();
            return EmitPcm16(output_.data(), output_.size());
        }

        const size_t input_frames = input_.size();
        if (end_of_input && input_frames > 0) {
            input_.insert(input_.end(), kHalfTapCount, 0.0f);
        }

        output_.clear();
        while (true) {
            const auto base = static_cast<std::int64_t>(std::floor(input_position_));
            const bool has_input = end_of_input
                ? base >= 0 && static_cast<size_t>(base) < input_frames
                : base >= 0 && static_cast<size_t>(base + kHalfTapCount) < input_.size();
            if (!has_input) break;

            const double fraction = input_position_ - static_cast<double>(base);
            size_t phase = static_cast<size_t>(fraction * kPhaseCount);
            phase = std::min(phase, static_cast<size_t>(kPhaseCount - 1));
            const float* coefficients = coefficients_.data() + phase * kTapCount;
            double value = 0.0;
            for (int tap = 0; tap < kTapCount; ++tap) {
                const auto input_index = base + tap - kHalfTapCount + 1;
                if (input_index >= 0 && static_cast<size_t>(input_index) < input_.size()) {
                    value += static_cast<double>(input_[static_cast<size_t>(input_index)]) * coefficients[tap];
                }
            }
            const auto pcm16 = static_cast<int>(std::lround(value * 32768.0));
            output_.push_back(static_cast<std::int16_t>(std::clamp(pcm16, -32768, 32767)));
            input_position_ += static_cast<double>(input_sample_rate_) / output_sample_rate_;
        }

        if (!output_.empty() && !EmitPcm16(output_.data(), output_.size())) {
            return false;
        }
        if (end_of_input) {
            input_.clear();
            input_position_ = 0.0;
            return true;
        }

        const auto removable = static_cast<std::int64_t>(std::floor(input_position_)) -
            (kHalfTapCount - 1);
        if (removable > 0) {
            const size_t count = std::min(static_cast<size_t>(removable), input_.size());
            input_.erase(input_.begin(), input_.begin() + static_cast<std::ptrdiff_t>(count));
            input_position_ -= count;
        }
        return true;
    }

    bool EmitPcm16(const std::int16_t* pcm16, size_t sample_count) {
        if (sample_count == 0) {
            return true;
        }
        return consumer_(reinterpret_cast<const std::uint8_t*>(pcm16), sample_count * sizeof(std::int16_t));
    }

    int interpolation_multiplier_;
    int input_sample_rate_;
    int output_sample_rate_;
    bool use_soft_resampling_;
    AudioConsumer consumer_;
    bool valid_ = true;
    SRC_STATE* interpolation_state_ = nullptr;
    static constexpr int kHalfTapCount = 16;
    static constexpr int kTapCount = kHalfTapCount * 2;
    static constexpr int kPhaseCount = 512;
    std::vector<float> input_;
    std::vector<float> interpolation_input_;
    std::vector<float> interpolation_output_;
    std::vector<float> coefficients_;
    std::vector<std::int16_t> output_;
    double input_position_ = 0.0;
};

std::vector<int> ReadConfig(JNIEnv* env, jintArray configArray) {
    if (!env || !configArray) {
        return {};
    }
    const jsize length = env->GetArrayLength(configArray);
    std::vector<int> values(static_cast<size_t>(length));
    env->GetIntArrayRegion(configArray, 0, length, reinterpret_cast<jint*>(values.data()));
    return values;
}

std::string ReadBytes(JNIEnv* env, jbyteArray byteArray) {
    if (!env || !byteArray) {
        return {};
    }
    const jsize length = env->GetArrayLength(byteArray);
    std::string bytes(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(byteArray, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}

jbyteArray WriteBytes(JNIEnv* env, const char* data, size_t size) {
    if (!env) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    if (!result || size == 0) {
        return result;
    }

    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(size),
        reinterpret_cast<const jbyte*>(data));
    return result;
}
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_hlebushek_RuTTS_engine_NativeRutts_nativeSynthesizeRaw(
    JNIEnv* env,
    jclass,
    jbyteArray textArray,
    jintArray configArray) {
    if (!env) {
        return nullptr;
    }

    const std::string text = ReadBytes(env, textArray);
    const std::vector<int> config = ReadConfig(env, configArray);
    const std::vector<std::uint8_t> audio = g_engine.Synthesize(text, config);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(audio.size()));
    if (!result || audio.empty()) {
        return result;
    }

    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(audio.size()),
        reinterpret_cast<const jbyte*>(audio.data()));
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_hlebushek_RuTTS_engine_NativeRutts_nativeSynthesizeStreamingRaw(
    JNIEnv* env,
    jclass,
    jbyteArray textArray,
    jintArray configArray,
    jobject callback,
    jint outputSampleRate,
    jint maxChunkSize,
    jboolean useSoftResampling) {
    if (!env || !callback) {
        return JNI_FALSE;
    }

    const jsize chunkCapacity = std::max<jsize>(2, maxChunkSize & ~1);
    jbyteArray reusableChunk = env->NewByteArray(chunkCapacity);
    if (!reusableChunk || env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    jclass callbackClass = env->GetObjectClass(callback);
    if (!callbackClass) {
        env->DeleteLocalRef(reusableChunk);
        return JNI_FALSE;
    }
    jmethodID audioAvailable = env->GetMethodID(callbackClass, "audioAvailable", "([BII)I");
    env->DeleteLocalRef(callbackClass);
    if (!audioAvailable || env->ExceptionCheck()) {
        env->DeleteLocalRef(reusableChunk);
        return JNI_FALSE;
    }

    const std::string text = ReadBytes(env, textArray);
    const std::vector<int> config = ReadConfig(env, configArray);
    const auto emitChunk = [&](const std::uint8_t* data, size_t size) -> bool {
        if (!data || size == 0 || size % sizeof(std::int16_t) != 0) {
            return size == 0;
        }
        env->SetByteArrayRegion(
            reusableChunk,
            0,
            static_cast<jsize>(size),
            reinterpret_cast<const jbyte*>(data));
        if (env->ExceptionCheck()) {
            return false;
        }

        const jint result = env->CallIntMethod(
            callback,
            audioAvailable,
            reusableChunk,
            static_cast<jint>(0),
            static_cast<jsize>(size));
        return !env->ExceptionCheck() && result == 0;
    };
    std::vector<std::uint8_t> pendingChunk;
    pendingChunk.reserve(static_cast<size_t>(chunkCapacity));
    const auto emitPcm16 = [&](const std::uint8_t* data, size_t size) -> bool {
        if (!data || size == 0 || size % sizeof(std::int16_t) != 0) {
            return size == 0;
        }
        size_t offset = 0;
        while (offset < size) {
            const size_t available = static_cast<size_t>(chunkCapacity) - pendingChunk.size();
            const size_t copySize = std::min(available, size - offset);
            pendingChunk.insert(pendingChunk.end(), data + offset, data + offset + copySize);
            offset += copySize;
            if (pendingChunk.size() == static_cast<size_t>(chunkCapacity)) {
                if (!emitChunk(pendingChunk.data(), pendingChunk.size())) {
                    return false;
                }
                pendingChunk.clear();
            }
        }
        return true;
    };

    const int sourceSampleRate = config.size() > kConfigSampleRateIndex
        ? std::clamp(config[kConfigSampleRateIndex], kMinSampleRate, kMaxSampleRate)
        : kFallbackSampleRate;
    const int interpolationMultiplier = config.size() > kConfigInterpolationMultiplierIndex
        ? NormalizeInterpolationMultiplier(config[kConfigInterpolationMultiplierIndex])
        : 1;
    const int interpolationAlgorithm = config.size() > kConfigInterpolationAlgorithmIndex
        ? config[kConfigInterpolationAlgorithmIndex]
        : 0;
    const bool useRateBoost = config.size() > kConfigUseRateBoostIndex &&
        config[kConfigUseRateBoostIndex] != 0;
    const int outputSampleRateClamped = std::clamp(
        static_cast<int>(outputSampleRate),
        kMinSampleRate,
        kMaxSampleRate);
    Pcm8ToPcm16Adapter adapter(
        sourceSampleRate,
        interpolationMultiplier,
        interpolationAlgorithm,
        outputSampleRateClamped,
        useSoftResampling == JNI_TRUE,
        emitPcm16);
    if (!adapter.valid()) {
        env->DeleteLocalRef(reusableChunk);
        return JNI_FALSE;
    }

    bool completed = g_engine.SynthesizeStreaming(
        text,
        config,
        [&adapter, useRateBoost](const std::uint8_t* data, size_t size) {
            return useRateBoost ? adapter.PushPcm16(data, size) : adapter.Push(data, size);
        });
    if (completed) {
        completed = adapter.Finish();
    }
    if (completed && !pendingChunk.empty()) {
        completed = emitChunk(pendingChunk.data(), pendingChunk.size());
    }
    env->DeleteLocalRef(reusableChunk);
    return completed ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_hlebushek_RuTTS_engine_NativeRutts_nativeRequestStopRaw(
    JNIEnv*,
    jclass) {
    g_engine.RequestStop();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_hlebushek_RuTTS_engine_RulexCodec_nativePackDataRaw(
    JNIEnv* env,
    jclass,
    jbyteArray keyArray,
    jbyteArray valueArray) {
    if (!env) {
        return nullptr;
    }

    const std::string key = ReadBytes(env, keyArray);
    const std::string value = ReadBytes(env, valueArray);
    if (key.empty() || value.empty()) {
        return nullptr;
    }

    std::vector<char> diffs(static_cast<size_t>(kRulexBufferSize), '\0');
    const int length = pack_data(key.c_str(), value.c_str(), diffs.data());
    if (length < 0) {
        return nullptr;
    }

    return WriteBytes(env, diffs.data(), static_cast<size_t>(length));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_hlebushek_RuTTS_engine_RulexCodec_nativeApplyDiffsRaw(
    JNIEnv* env,
    jclass,
    jbyteArray baseArray,
    jbyteArray diffsArray) {
    if (!env) {
        return nullptr;
    }

    const std::string base = ReadBytes(env, baseArray);
    const std::string diffs = ReadBytes(env, diffsArray);

    std::vector<char> buffer(static_cast<size_t>(kRulexBufferSize), '\0');
    if (!base.empty()) {
        const size_t copySize = std::min(base.size(), buffer.size() - 1);
        std::memcpy(buffer.data(), base.data(), copySize);
    }
    if (!diffs.empty()) {
        unpack_data(buffer.data(), diffs.data(), static_cast<int>(diffs.size()));
    }

    return WriteBytes(env, buffer.data(), std::strlen(buffer.data()));
}
