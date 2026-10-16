#pragma once

#include <cstddef>
#include <vector>

// Интерполяция «Sample rate factor» (sampleRateFactor) в том виде, как она
// устроена в Nefonit TTS: частота дискретизации умножается на 2 или 4, между
// исходными отсчётами вставляются нули, а полученный сигнал проходит через
// фильтр нижних частот Баттерворта 8-го порядка со срезом 6000 Гц (четыре
// биквада, прямая форма II) с компенсацией громкости. При множителе 1
// сигнал не трогается.
class SampleRateFactorInterpolator {
public:
    // 2 и 4 — как есть, любое другое значение — 1.
    static int NormalizeFactor(int sample_rate_factor);

    SampleRateFactorInterpolator(int sample_rate, int sample_rate_factor);

    int factor() const { return factor_; }
    int output_sample_rate() const { return output_sample_rate_; }
    bool active() const { return factor_ > 1; }

    // Дописывает в output factor() отсчётов на каждый входной. Отсчёты —
    // float в диапазоне [-1, 1].
    void Process(const float* input, size_t count, std::vector<float>& output);

private:
    struct Biquad {
        double b0 = 0.0;
        double b1 = 0.0;
        double b2 = 0.0;
        double a1 = 0.0;
        double a2 = 0.0;
        double w1 = 0.0;
        double w2 = 0.0;

        double Run(double x);
    };

    static constexpr int kSectionCount = 4;

    int factor_;
    int output_sample_rate_;
    double gain_ = 1.0;
    Biquad sections_[kSectionCount];
};
