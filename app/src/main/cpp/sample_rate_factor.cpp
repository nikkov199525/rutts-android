#include "sample_rate_factor.h"

#include <cmath>

namespace {
constexpr double kPi = 3.14159265358979323846;

// Частота среза фильтра восстановления — фиксированная, от частоты
// дискретизации не зависит.
constexpr double kCutoffHz = 6000.0;

// 1/(2Q) секций фильтра Баттерворта 8-го порядка: sin((2k-1)·π/16), в том
// же порядке, в каком секции идут в Nefonit.
constexpr double kSectionDamping[] = {
    0.9807852843222002,
    0.8314696173059154,
    0.5555702326496501,
    0.19509032179250105,
};

// Компенсация громкости после вставки нулей: в Nefonit выход множителя 1
// масштабируется на 32767, множителя 2 — на 61824.528…, множителя 4 —
// на 123184.210…
constexpr double kFactor2Gain = 61824.52830188679 / 32767.0;
constexpr double kFactor4Gain = 123184.21052631579 / 32767.0;
}  // namespace

int SampleRateFactorInterpolator::NormalizeFactor(int sample_rate_factor) {
    return sample_rate_factor == 2 || sample_rate_factor == 4 ? sample_rate_factor : 1;
}

SampleRateFactorInterpolator::SampleRateFactorInterpolator(int sample_rate, int sample_rate_factor)
    : factor_(NormalizeFactor(sample_rate_factor)),
      output_sample_rate_(sample_rate * factor_) {
    if (!active()) {
        return;
    }
    gain_ = factor_ == 2 ? kFactor2Gain : kFactor4Gain;

    const double omega = 2.0 * kPi * kCutoffHz / static_cast<double>(output_sample_rate_);
    const double cos_omega = std::cos(omega);
    const double sin_omega = std::sin(omega);
    for (int index = 0; index < kSectionCount; ++index) {
        const double alpha = sin_omega * kSectionDamping[index];
        const double a0 = 1.0 + alpha;
        Biquad& section = sections_[index];
        section.b0 = (1.0 - cos_omega) * 0.5 / a0;
        section.b1 = (1.0 - cos_omega) / a0;
        section.b2 = section.b0;
        section.a1 = 2.0 * cos_omega / a0;
        section.a2 = -(1.0 - alpha) / a0;
    }
}

double SampleRateFactorInterpolator::Biquad::Run(double x) {
    const double w = x + a1 * w1 + a2 * w2;
    const double y = b0 * w + b1 * w1 + b2 * w2;
    w2 = w1;
    w1 = w;
    return y;
}

void SampleRateFactorInterpolator::Process(
    const float* input,
    size_t count,
    std::vector<float>& output) {
    if (!active()) {
        output.insert(output.end(), input, input + count);
        return;
    }
    output.reserve(output.size() + count * static_cast<size_t>(factor_));
    for (size_t index = 0; index < count; ++index) {
        for (int phase = 0; phase < factor_; ++phase) {
            double value = phase == 0 ? static_cast<double>(input[index]) : 0.0;
            for (Biquad& section : sections_) {
                value = section.Run(value);
            }
            output.push_back(static_cast<float>(value * gain_));
        }
    }
}
