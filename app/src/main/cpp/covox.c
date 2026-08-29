#include "covox.h"

static int16_t clamp16(float value) {
    if (value > 32767.0f) return 32767;
    if (value < -32768.0f) return -32768;
    return (int16_t)value;
}

void covox_init(covox_state_t *state, uint32_t input_rate, uint32_t output_rate) {
    state->input_rate = input_rate ? input_rate : 10027;
    state->output_rate = output_rate ? output_rate : state->input_rate;
    state->phase = 0;
    state->held_sample = 0;
    state->pending_samples = 0;
}

uint8_t covox_latch_value(int8_t sample) {
    /* This is the ADD AL,80h in SDRV's Covox routine at 0000:A99A. */
    return (uint8_t)((uint8_t)sample + 0x80u);
}

int16_t covox_latch_to_pcm16(uint8_t latch, float volume) {
    int value = (int)latch - 128;
    if (volume < 0.0f) volume = 0.0f;
    if (volume > 1.0f) volume = 1.0f;
    return clamp16((float)(value * 256) * volume);
}

size_t covox_process(
    covox_state_t *state,
    const int8_t *input,
    size_t input_count,
    int16_t *output,
    size_t output_capacity,
    float volume,
    size_t *input_consumed
) {
    size_t in_pos = 0;
    size_t out_pos = 0;
    if (input_consumed) *input_consumed = 0;
    if (!state || !input || !output || !state->input_rate || !state->output_rate)
        return 0;

    while (state->pending_samples && out_pos < output_capacity) {
        output[out_pos++] = state->held_sample;
        state->pending_samples--;
    }
    while (in_pos < input_count && out_pos < output_capacity) {
        state->held_sample = covox_latch_to_pcm16(covox_latch_value(input[in_pos]), volume);
        state->phase += state->output_rate;
        state->pending_samples = (uint32_t)(state->phase / state->input_rate);
        state->phase %= state->input_rate;
        in_pos++;
        while (state->pending_samples && out_pos < output_capacity) {
            output[out_pos++] = state->held_sample;
            state->pending_samples--;
        }
    }
    if (input_consumed) *input_consumed = in_pos;
    return out_pos;
}

void covox_linear_init(
    covox_linear_state_t *state,
    uint32_t input_rate,
    uint32_t output_rate
) {
    if (!state) return;
    state->input_rate = input_rate ? input_rate : 10027;
    state->output_rate = output_rate ? output_rate : state->input_rate;
    state->phase = 0;
    state->previous_sample = 0;
    state->target_sample = 0;
    state->has_previous = 0;
    state->has_target = 0;
}

static int16_t interpolate16(int16_t from, int16_t to, uint64_t phase, uint32_t denominator) {
    int64_t delta = (int32_t)to - (int32_t)from;
    return (int16_t)((int32_t)from + (int32_t)((delta * (int64_t)phase) / denominator));
}

size_t covox_linear_process(
    covox_linear_state_t *state,
    const int8_t *input,
    size_t input_count,
    int16_t *output,
    size_t output_capacity,
    float volume,
    size_t *input_consumed
) {
    size_t in_pos = 0;
    size_t out_pos = 0;
    if (input_consumed) *input_consumed = 0;
    if (!state || !input || !output || !state->input_rate || !state->output_rate)
        return 0;

    while (out_pos < output_capacity) {
        if (!state->has_previous) {
            if (in_pos >= input_count) break;
            state->previous_sample = covox_latch_to_pcm16(
                covox_latch_value(input[in_pos++]), volume);
            state->has_previous = 1;
            state->phase = 0;
        }
        if (!state->has_target) {
            if (in_pos >= input_count) break;
            state->target_sample = covox_latch_to_pcm16(
                covox_latch_value(input[in_pos++]), volume);
            state->has_target = 1;
        }

        while (state->phase < state->output_rate && out_pos < output_capacity) {
            output[out_pos++] = interpolate16(
                state->previous_sample,
                state->target_sample,
                state->phase,
                state->output_rate);
            state->phase += state->input_rate;
        }
        if (state->phase >= state->output_rate) {
            state->phase -= state->output_rate;
            state->previous_sample = state->target_sample;
            state->has_target = 0;
        }
    }

    if (input_consumed) *input_consumed = in_pos;
    return out_pos;
}

size_t covox_linear_flush(
    covox_linear_state_t *state,
    int16_t *output,
    size_t output_capacity
) {
    size_t out_pos = 0;
    if (!state || !output || !state->input_rate || !state->output_rate || !state->has_previous)
        return 0;
    if (!state->has_target) {
        state->target_sample = state->previous_sample;
        state->has_target = 1;
    }
    while (state->phase < state->output_rate && out_pos < output_capacity) {
        output[out_pos++] = interpolate16(
            state->previous_sample,
            state->target_sample,
            state->phase,
            state->output_rate);
        state->phase += state->input_rate;
    }
    if (state->phase >= state->output_rate) {
        state->phase -= state->output_rate;
        state->previous_sample = state->target_sample;
        state->has_target = 0;
        state->has_previous = 0;
    }
    return out_pos;
}
