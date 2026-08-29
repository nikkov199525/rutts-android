#include "synth_engine.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

extern "C" {
#include "covox.h"
#include "samplerate.h"
#include "ru_tts.h"
#include "sonic.h"
}

namespace {
constexpr int kExpectedConfigSize = 24;
constexpr int kSonicChannelCount = 1;
constexpr int kSonicReadBufferSamples = 4096;
constexpr float kRateBoostFactor = 2.0F;
constexpr int kCovoxInputSampleRate = 10027;
constexpr int kCovoxOutputSampleRate = 48000;

enum ConfigIndex : int {
    CI_SPEECH_RATE = 0,
    CI_VOLUME_PERCENT = 1,
    CI_SAMPLES_PER_SEC = 2,
    CI_INTERPOLATION_MULTIPLIER = 3,
    CI_INTERPOLATION_ALGORITHM = 4,
    CI_WAVE_BUFFER_SIZE = 5,
    CI_SILENCE_AT_BEGIN = 6,
    CI_SILENCE_AT_END = 7,
    CI_USE_ALTERNATIVE_VOICE = 8,
    CI_USE_RATE_BOOST = 9,
    CI_DEC_SEP_POINT = 10,
    CI_DEC_SEP_COMMA = 11,
    CI_GENERAL_GAP_FACTOR = 12,
    CI_COMMA_GAP_FACTOR = 13,
    CI_DOT_GAP_FACTOR = 14,
    CI_SEMICOLON_GAP_FACTOR = 15,
    CI_COLON_GAP_FACTOR = 16,
    CI_QUESTION_GAP_FACTOR = 17,
    CI_EXCLAMATION_GAP_FACTOR = 18,
    CI_INTONATIONAL_GAP_FACTOR = 19,
    CI_VOICE_PITCH = 20,
    CI_INTONATION = 21,
    CI_USE_NEW_RATE_ALGORITHM = 22,
    CI_USE_COVOX = 23
};

struct CallbackState {
    std::vector<std::int8_t>* raw_audio = nullptr;
    std::atomic<std::uint64_t>* generation = nullptr;
    std::uint64_t request_generation = 0;
};

struct StreamingCallbackState {
    const SynthEngine::AudioConsumer* consumer = nullptr;
    std::atomic<std::uint64_t>* generation = nullptr;
    std::uint64_t request_generation = 0;
    int gain_percent = 100;
    bool failed = false;
};

int ClampInt(int value, int min_value, int max_value) {
    return std::clamp(value, min_value, max_value);
}

int NormalizeInterpolationMultiplier(int value) {
    if (value < 2) return 1;
    if (value > 2) return 4;
    return 2;
}

int ToSrcConverter(int algorithm) {
    return algorithm == 1 ? SRC_ZERO_ORDER_HOLD : SRC_LINEAR;
}

bool IsCurrentGeneration(
    const std::atomic<std::uint64_t>* generation,
    std::uint64_t request_generation) {
    return generation &&
        generation->load(std::memory_order_acquire) == request_generation;
}

int CollectAudioCallback(void* buffer, size_t size, void* user_data) {
    CallbackState* state = static_cast<CallbackState*>(user_data);
    if (!state || !state->raw_audio || !state->generation) {
        return 1;
    }
    if (!IsCurrentGeneration(state->generation, state->request_generation)) {
        return 1;
    }
    if (!buffer || size == 0) {
        return 0;
    }

    const auto* bytes = static_cast<const std::int8_t*>(buffer);
    state->raw_audio->insert(state->raw_audio->end(), bytes, bytes + size);
    return 0;
}

std::int16_t ApplyGain(std::int16_t sample, int gain_percent) {
    if (gain_percent <= 0) return 0;
    if (gain_percent >= 100) return sample;

    int scaled = (static_cast<int>(sample) * gain_percent) / 100;
    scaled = ClampInt(scaled, -32768, 32767);
    return static_cast<std::int16_t>(scaled);
}

std::uint8_t ApplyPcm8Gain(std::int8_t sample, int gain_percent) {
    const int scaled = ClampInt(
        (static_cast<int>(sample) * gain_percent) / 100,
        -128,
        127);
    return static_cast<std::uint8_t>(scaled + 128);
}

int StreamAudioCallback(void* buffer, size_t size, void* user_data) {
    auto* state = static_cast<StreamingCallbackState*>(user_data);
    if (!state || !state->consumer || !state->generation) {
        return 1;
    }
    if (!IsCurrentGeneration(state->generation, state->request_generation)) {
        return 1;
    }
    if (!buffer || size == 0) {
        return 0;
    }

    const auto* input = static_cast<const std::int8_t*>(buffer);
    thread_local std::vector<std::uint8_t> pcm;
    pcm.resize(size);
    for (size_t index = 0; index < size; ++index) {
        pcm[index] = ApplyPcm8Gain(input[index], state->gain_percent);
    }

    if (!(*state->consumer)(pcm.data(), pcm.size())) {
        state->failed = true;
        return 1;
    }
    return 0;
}

std::vector<std::int16_t> ConvertRawAudio(const std::vector<std::int8_t>& raw_audio, int gain_percent) {
    std::vector<std::int16_t> output;
    output.reserve(raw_audio.size());
    for (std::int8_t byte : raw_audio) {
        const std::int16_t sample = static_cast<std::int16_t>(static_cast<std::int16_t>(byte) << 8);
        output.push_back(ApplyGain(sample, gain_percent));
    }
    return output;
}

std::vector<std::int16_t> ConvertRawAudioWithSonicRateBoost(
    const std::vector<std::int8_t>& raw_audio,
    int sample_rate,
    int gain_percent) {
    if (raw_audio.empty()) {
        return {};
    }
    if (raw_audio.size() > static_cast<size_t>(std::numeric_limits<int>::max())) {
        return ConvertRawAudio(raw_audio, gain_percent);
    }

    sonicStream stream = sonicCreateStream(sample_rate, kSonicChannelCount);
    if (!stream) {
        return ConvertRawAudio(raw_audio, gain_percent);
    }
    sonicSetSpeed(stream, kRateBoostFactor);

    std::vector<unsigned char> unsigned_audio(raw_audio.size());
    for (size_t i = 0; i < raw_audio.size(); ++i) {
        unsigned_audio[i] = static_cast<unsigned char>(
            static_cast<unsigned char>(raw_audio[i]) ^ 0x80U);
    }

    if (sonicWriteUnsignedCharToStream(
            stream,
            unsigned_audio.data(),
            static_cast<int>(unsigned_audio.size())) == 0 ||
        sonicFlushStream(stream) == 0) {
        sonicDestroyStream(stream);
        return ConvertRawAudio(raw_audio, gain_percent);
    }

    std::vector<std::int16_t> output;
    output.reserve((raw_audio.size() / 2) + 64);
    std::vector<short> read_buffer(kSonicReadBufferSamples);
    while (true) {
        const int samples_read =
            sonicReadShortFromStream(stream, read_buffer.data(), kSonicReadBufferSamples);
        if (samples_read <= 0) {
            break;
        }
        for (int i = 0; i < samples_read; ++i) {
            output.push_back(ApplyGain(static_cast<std::int16_t>(read_buffer[i]), gain_percent));
        }
    }

    sonicDestroyStream(stream);
    return output.empty() ? ConvertRawAudio(raw_audio, gain_percent) : output;
}

std::vector<std::int16_t> ConvertPcm8WithCovox(
    const std::vector<std::int8_t>& input,
    int gain_percent) {
    if (input.empty()) {
        return {};
    }

    covox_linear_state_t state{};
    covox_linear_init(&state, kCovoxInputSampleRate, kCovoxOutputSampleRate);
    const float volume = static_cast<float>(ClampInt(gain_percent, 0, 100)) / 100.0F;
    std::vector<std::int16_t> output;
    output.reserve(
        (input.size() * static_cast<size_t>(kCovoxOutputSampleRate)) /
            static_cast<size_t>(kCovoxInputSampleRate) + 8U);
    std::vector<std::int16_t> block(kSonicReadBufferSamples * 2);
    size_t consumed_total = 0;
    while (consumed_total < input.size()) {
        size_t consumed = 0;
        const size_t produced = covox_linear_process(
            &state,
            input.data() + consumed_total,
            input.size() - consumed_total,
            block.data(),
            block.size(),
            volume,
            &consumed);
        consumed_total += consumed;
        output.insert(output.end(), block.begin(), block.begin() + static_cast<std::ptrdiff_t>(produced));
        if (consumed == 0 && produced == 0) {
            return {};
        }
    }

    const size_t flushed = covox_linear_flush(&state, block.data(), block.size());
    output.insert(output.end(), block.begin(), block.begin() + static_cast<std::ptrdiff_t>(flushed));
    return output;
}

std::vector<std::int16_t> ConvertPcm16WithCovox(
    const std::vector<std::int16_t>& input,
    int gain_percent) {
    std::vector<std::int8_t> pcm8(input.size());
    for (size_t index = 0; index < input.size(); ++index) {
        const std::uint8_t high_byte = static_cast<std::uint8_t>(
            static_cast<std::uint16_t>(input[index]) >> 8U);
        std::memcpy(&pcm8[index], &high_byte, sizeof(high_byte));
    }
    return ConvertPcm8WithCovox(pcm8, gain_percent);
}

std::vector<std::int16_t> ResampleAudio(
    const std::vector<std::int16_t>& input,
    int input_rate,
    int output_rate,
    int converter_type) {
    if (input.empty()) {
        return {};
    }
    if (input_rate == output_rate) {
        return input;
    }

    if (input.size() > static_cast<size_t>(std::numeric_limits<int>::max())) {
        return input;
    }

    std::vector<float> input_float(input.size());
    src_short_to_float_array(input.data(), input_float.data(), static_cast<int>(input.size()));

    const double ratio = static_cast<double>(output_rate) / static_cast<double>(input_rate);
    if (!src_is_valid_ratio(ratio)) {
        return input;
    }

    size_t output_capacity = static_cast<size_t>(std::ceil(static_cast<double>(input.size()) * ratio)) + 64;
    output_capacity = std::max<size_t>(output_capacity, 64);
    if (output_capacity > static_cast<size_t>(std::numeric_limits<long>::max())) {
        return input;
    }

    std::vector<float> output_float(output_capacity);
    SRC_DATA data{};
    data.data_in = input_float.data();
    data.input_frames = static_cast<long>(input.size());
    data.data_out = output_float.data();
    data.output_frames = static_cast<long>(output_capacity);
    data.src_ratio = ratio;
    data.end_of_input = 1;

    if (src_simple(&data, converter_type, 1) != 0 || data.output_frames_gen <= 0) {
        return input;
    }

    const size_t output_frames = static_cast<size_t>(data.output_frames_gen);
    std::vector<std::int16_t> output(output_frames);
    src_float_to_short_array(output_float.data(), output.data(), static_cast<int>(output_frames));
    return output;
}

size_t SilenceSampleCount(int sample_rate, int milliseconds) {
    if (milliseconds <= 0 || sample_rate <= 0) {
        return 0;
    }
    const long long total_samples =
        (static_cast<long long>(sample_rate) * static_cast<long long>(milliseconds)) / 1000LL;
    if (total_samples <= 0) {
        return 0;
    }
    return static_cast<size_t>(total_samples);
}

void PrependSilence(std::vector<std::int16_t>& samples, int sample_rate, int milliseconds) {
    const size_t total_samples = SilenceSampleCount(sample_rate, milliseconds);
    if (total_samples == 0) {
        return;
    }

    std::vector<std::int16_t> output;
    output.reserve(total_samples + samples.size());
    output.insert(output.end(), total_samples, 0);
    output.insert(output.end(), samples.begin(), samples.end());
    samples.swap(output);
}

void AppendSilence(std::vector<std::int16_t>& samples, int sample_rate, int milliseconds) {
    const size_t total_samples = SilenceSampleCount(sample_rate, milliseconds);
    if (total_samples == 0) {
        return;
    }
    samples.insert(samples.end(), total_samples, 0);
}

std::vector<std::uint8_t> PackPcm16(const std::vector<std::int16_t>& samples) {
    std::vector<std::uint8_t> bytes(samples.size() * sizeof(std::int16_t));
    if (!bytes.empty()) {
        std::memcpy(bytes.data(), samples.data(), bytes.size());
    }
    return bytes;
}

bool EmitSilence(
    const SynthEngine::AudioConsumer& consumer,
    int sample_rate,
    int milliseconds,
    std::atomic<std::uint64_t>& generation,
    std::uint64_t request_generation) {
    size_t bytes_left = SilenceSampleCount(sample_rate, milliseconds) * sizeof(std::int16_t);
    if (bytes_left == 0) {
        return true;
    }

    const std::vector<std::uint8_t> silence(1024, 0);
    while (bytes_left > 0) {
        if (generation.load(std::memory_order_acquire) != request_generation) {
            return false;
        }
        const size_t chunk_size = std::min(bytes_left, silence.size());
        if (!consumer(silence.data(), chunk_size)) {
            return false;
        }
        bytes_left -= chunk_size;
    }
    return true;
}

bool EmitPcm8Silence(
    const SynthEngine::AudioConsumer& consumer,
    int sample_rate,
    int milliseconds,
    std::atomic<std::uint64_t>& generation,
    std::uint64_t request_generation) {
    size_t bytes_left = SilenceSampleCount(sample_rate, milliseconds);
    if (bytes_left == 0) {
        return true;
    }

    const std::vector<std::uint8_t> silence(1024, 128);
    while (bytes_left > 0) {
        if (generation.load(std::memory_order_acquire) != request_generation) {
            return false;
        }
        const size_t chunk_size = std::min(bytes_left, silence.size());
        if (!consumer(silence.data(), chunk_size)) {
            return false;
        }
        bytes_left -= chunk_size;
    }
    return true;
}
}  // namespace

std::vector<std::uint8_t> SynthEngine::Synthesize(const std::string& text, const std::vector<int>& config) {
    const std::uint64_t request_generation = BeginRequest();
    if (text.empty() || config.size() < kExpectedConfigSize) {
        return {};
    }

    ru_tts_conf_t ru_tts_config{};
    ru_tts_config_init(&ru_tts_config);

    const int speech_rate = ClampInt(config[CI_SPEECH_RATE], 20, 500);
    const bool use_rate_boost = config[CI_USE_RATE_BOOST] != 0;
    const bool use_new_rate_algorithm = config[CI_USE_NEW_RATE_ALGORITHM] != 0;
    ru_tts_config.speech_rate = speech_rate;
    ru_tts_config.voice_pitch = ClampInt(config[CI_VOICE_PITCH], 50, 300);
    ru_tts_config.intonation = ClampInt(config[CI_INTONATION], 0, 140);
    ru_tts_config.general_gap_factor = ClampInt(config[CI_GENERAL_GAP_FACTOR], 0, 5000);
    ru_tts_config.comma_gap_factor = ClampInt(config[CI_COMMA_GAP_FACTOR], 0, 750);
    ru_tts_config.dot_gap_factor = ClampInt(config[CI_DOT_GAP_FACTOR], 0, 500);
    ru_tts_config.semicolon_gap_factor = ClampInt(config[CI_SEMICOLON_GAP_FACTOR], 0, 600);
    ru_tts_config.colon_gap_factor = ClampInt(config[CI_COLON_GAP_FACTOR], 0, 600);
    ru_tts_config.question_gap_factor = ClampInt(config[CI_QUESTION_GAP_FACTOR], 0, 375);
    ru_tts_config.exclamation_gap_factor = ClampInt(config[CI_EXCLAMATION_GAP_FACTOR], 0, 300);
    ru_tts_config.intonational_gap_factor = ClampInt(config[CI_INTONATIONAL_GAP_FACTOR], 0, 1000);
    ru_tts_config.flags = 0;
    if (config[CI_DEC_SEP_POINT] != 0) ru_tts_config.flags |= DEC_SEP_POINT;
    if (config[CI_DEC_SEP_COMMA] != 0) ru_tts_config.flags |= DEC_SEP_COMMA;
    if (config[CI_USE_ALTERNATIVE_VOICE] != 0) ru_tts_config.flags |= USE_ALTERNATIVE_VOICE;
    if (!use_new_rate_algorithm) ru_tts_config.flags |= USE_LEGACY_RATE_ALGO;

    const int volume_percent = ClampInt(config[CI_VOLUME_PERCENT], 0, 100);
    const int samples_per_sec = ClampInt(config[CI_SAMPLES_PER_SEC], 8000, 16000);
    const bool use_covox = config[CI_USE_COVOX] != 0;
    const int interpolation_multiplier = NormalizeInterpolationMultiplier(config[CI_INTERPOLATION_MULTIPLIER]);
    const int output_rate = use_covox
        ? kCovoxOutputSampleRate
        : ClampInt(samples_per_sec * interpolation_multiplier, 8000, 192000);
    const int wave_buffer_size = ClampInt(config[CI_WAVE_BUFFER_SIZE], 128, 1024000);
    const int silence_at_begin = ClampInt(config[CI_SILENCE_AT_BEGIN], 0, 100);
    const int silence_at_end = ClampInt(config[CI_SILENCE_AT_END], 0, 100);

    std::vector<std::uint8_t> wave_buffer(static_cast<size_t>(wave_buffer_size));
    std::vector<std::int8_t> raw_audio;
    raw_audio.reserve(text.size() * 32);

    CallbackState callback_state{};
    callback_state.raw_audio = &raw_audio;
    callback_state.generation = &generation_;
    callback_state.request_generation = request_generation;

    ru_tts_transfer(
        &ru_tts_config,
        text.c_str(),
        wave_buffer.data(),
        wave_buffer.size(),
        CollectAudioCallback,
        &callback_state);

    if (!IsCurrent(request_generation)) {
        return {};
    }

    std::vector<std::int16_t> pcm16;
    if (use_rate_boost) {
        pcm16 = ConvertRawAudioWithSonicRateBoost(
            raw_audio,
            samples_per_sec,
            use_covox ? 100 : volume_percent);
        if (use_covox) {
            pcm16 = ConvertPcm16WithCovox(pcm16, volume_percent);
        }
    } else if (use_covox) {
        pcm16 = ConvertPcm8WithCovox(raw_audio, volume_percent);
    } else {
        pcm16 = ConvertRawAudio(raw_audio, volume_percent);
    }
    if (!use_covox) {
        pcm16 = ResampleAudio(
            pcm16,
            samples_per_sec,
            output_rate,
            ToSrcConverter(config[CI_INTERPOLATION_ALGORITHM]));
    }

    if (!IsCurrent(request_generation)) {
        return {};
    }

    PrependSilence(pcm16, output_rate, silence_at_begin);
    AppendSilence(pcm16, output_rate, silence_at_end);
    return PackPcm16(pcm16);
}

bool SynthEngine::SynthesizeStreaming(
    const std::string& text,
    const std::vector<int>& config,
    const AudioConsumer& consumer) {
    const std::uint64_t request_generation = BeginRequest();
    if (text.empty() || config.size() < kExpectedConfigSize || !consumer) {
        return false;
    }

    ru_tts_conf_t ru_tts_config{};
    ru_tts_config_init(&ru_tts_config);
    ru_tts_config.speech_rate = ClampInt(config[CI_SPEECH_RATE], 20, 500);
    ru_tts_config.voice_pitch = ClampInt(config[CI_VOICE_PITCH], 50, 300);
    ru_tts_config.intonation = ClampInt(config[CI_INTONATION], 0, 140);
    ru_tts_config.general_gap_factor = ClampInt(config[CI_GENERAL_GAP_FACTOR], 0, 5000);
    ru_tts_config.comma_gap_factor = ClampInt(config[CI_COMMA_GAP_FACTOR], 0, 750);
    ru_tts_config.dot_gap_factor = ClampInt(config[CI_DOT_GAP_FACTOR], 0, 500);
    ru_tts_config.semicolon_gap_factor = ClampInt(config[CI_SEMICOLON_GAP_FACTOR], 0, 600);
    ru_tts_config.colon_gap_factor = ClampInt(config[CI_COLON_GAP_FACTOR], 0, 600);
    ru_tts_config.question_gap_factor = ClampInt(config[CI_QUESTION_GAP_FACTOR], 0, 375);
    ru_tts_config.exclamation_gap_factor = ClampInt(config[CI_EXCLAMATION_GAP_FACTOR], 0, 300);
    ru_tts_config.intonational_gap_factor = ClampInt(config[CI_INTONATIONAL_GAP_FACTOR], 0, 1000);
    ru_tts_config.flags = 0;
    if (config[CI_DEC_SEP_POINT] != 0) ru_tts_config.flags |= DEC_SEP_POINT;
    if (config[CI_DEC_SEP_COMMA] != 0) ru_tts_config.flags |= DEC_SEP_COMMA;
    if (config[CI_USE_ALTERNATIVE_VOICE] != 0) ru_tts_config.flags |= USE_ALTERNATIVE_VOICE;
    if (config[CI_USE_NEW_RATE_ALGORITHM] == 0) ru_tts_config.flags |= USE_LEGACY_RATE_ALGO;

    const int sample_rate = ClampInt(config[CI_SAMPLES_PER_SEC], 8000, 16000);
    const int volume_percent = ClampInt(config[CI_VOLUME_PERCENT], 0, 100);
    const bool use_rate_boost = config[CI_USE_RATE_BOOST] != 0;
    const int wave_buffer_size = ClampInt(config[CI_WAVE_BUFFER_SIZE], 128, 1024000);
    std::vector<std::uint8_t> wave_buffer(static_cast<size_t>(wave_buffer_size));

    const int silence_at_begin = ClampInt(config[CI_SILENCE_AT_BEGIN], 0, 100);
    const bool emitted_begin_silence = use_rate_boost
        ? EmitSilence(consumer, sample_rate, silence_at_begin, generation_, request_generation)
        : EmitPcm8Silence(consumer, sample_rate, silence_at_begin, generation_, request_generation);
    if (!emitted_begin_silence) {
        return false;
    }

    sonicStream rate_boost_stream = nullptr;
    std::vector<short> rate_boost_output(kSonicReadBufferSamples);
    AudioConsumer speech_consumer = consumer;
    if (use_rate_boost) {
        rate_boost_stream = sonicCreateStream(sample_rate, kSonicChannelCount);
        if (!rate_boost_stream) {
            return false;
        }
        sonicSetSpeed(rate_boost_stream, kRateBoostFactor);
        speech_consumer = [&](const std::uint8_t* pcm8, size_t size) {
            if (!pcm8 || size == 0 || size > static_cast<size_t>(std::numeric_limits<int>::max())) {
                return size == 0;
            }
            if (sonicWriteUnsignedCharToStream(
                    rate_boost_stream,
                    pcm8,
                    static_cast<int>(size)) == 0) {
                return false;
            }
            while (sonicSamplesAvailable(rate_boost_stream) > 0) {
                const int samples_read = sonicReadShortFromStream(
                    rate_boost_stream,
                    rate_boost_output.data(),
                    static_cast<int>(rate_boost_output.size()));
                if (samples_read <= 0) {
                    return false;
                }
                for (int index = 0; index < samples_read; ++index) {
                    rate_boost_output[static_cast<size_t>(index)] = ApplyGain(
                        static_cast<std::int16_t>(rate_boost_output[static_cast<size_t>(index)]),
                        volume_percent);
                }
                if (!consumer(
                        reinterpret_cast<const std::uint8_t*>(rate_boost_output.data()),
                        static_cast<size_t>(samples_read) * sizeof(short))) {
                    return false;
                }
            }
            return true;
        };
    }

    StreamingCallbackState callback_state{};
    callback_state.consumer = &speech_consumer;
    callback_state.generation = &generation_;
    callback_state.request_generation = request_generation;
    callback_state.gain_percent = use_rate_boost ? 100 : volume_percent;
    ru_tts_transfer(
        &ru_tts_config,
        text.c_str(),
        wave_buffer.data(),
        wave_buffer.size(),
        StreamAudioCallback,
        &callback_state);

    bool completed = !callback_state.failed && IsCurrent(request_generation);
    if (completed && rate_boost_stream) {
        completed = sonicFlushStream(rate_boost_stream) != 0;
        while (completed && sonicSamplesAvailable(rate_boost_stream) > 0) {
            const int samples_read = sonicReadShortFromStream(
                rate_boost_stream,
                rate_boost_output.data(),
                static_cast<int>(rate_boost_output.size()));
            if (samples_read <= 0) {
                completed = false;
                break;
            }
            for (int index = 0; index < samples_read; ++index) {
                rate_boost_output[static_cast<size_t>(index)] = ApplyGain(
                    static_cast<std::int16_t>(rate_boost_output[static_cast<size_t>(index)]),
                    volume_percent);
            }
            completed = consumer(
                reinterpret_cast<const std::uint8_t*>(rate_boost_output.data()),
                static_cast<size_t>(samples_read) * sizeof(short));
        }
    }
    if (rate_boost_stream) {
        sonicDestroyStream(rate_boost_stream);
    }
    if (!completed) {
        return false;
    }
    const int silence_at_end = ClampInt(config[CI_SILENCE_AT_END], 0, 100);
    return use_rate_boost
        ? EmitSilence(consumer, sample_rate, silence_at_end, generation_, request_generation)
        : EmitPcm8Silence(consumer, sample_rate, silence_at_end, generation_, request_generation);
}

void SynthEngine::RequestStop() {
    generation_.fetch_add(1, std::memory_order_acq_rel);
}

std::uint64_t SynthEngine::BeginRequest() {
    return generation_.fetch_add(1, std::memory_order_acq_rel) + 1U;
}

bool SynthEngine::IsCurrent(std::uint64_t request_generation) const {
    return generation_.load(std::memory_order_acquire) == request_generation;
}
