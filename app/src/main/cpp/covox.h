/*
 * Reusable Covox output stage for Phonemaphone/ru_tts.
 *
 * SDRV writes signed 8-bit core samples to an unsigned resistor-ladder DAC
 * after adding 0x80. A physical Covox holds every written value until the
 * next timer tick. The functions below preserve that 8-bit quantisation and
 * optionally expand the held levels to a higher output sample rate.
 */
#ifndef RUTTS_COVOX_H
#define RUTTS_COVOX_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uint32_t input_rate;
    uint32_t output_rate;
    uint64_t phase;
    int16_t held_sample;
    uint32_t pending_samples;
} covox_state_t;

/* Streaming linear reconstruction used by the old DOSBox mixer. */
typedef struct {
    uint32_t input_rate;
    uint32_t output_rate;
    uint64_t phase;
    int16_t previous_sample;
    int16_t target_sample;
    uint8_t has_previous;
    uint8_t has_target;
} covox_linear_state_t;

void covox_init(covox_state_t *state, uint32_t input_rate, uint32_t output_rate);
uint8_t covox_latch_value(int8_t sample);
int16_t covox_latch_to_pcm16(uint8_t latch, float volume);

size_t covox_process(
    covox_state_t *state,
    const int8_t *input,
    size_t input_count,
    int16_t *output,
    size_t output_capacity,
    float volume,
    size_t *input_consumed
);

void covox_linear_init(
    covox_linear_state_t *state,
    uint32_t input_rate,
    uint32_t output_rate
);

size_t covox_linear_process(
    covox_linear_state_t *state,
    const int8_t *input,
    size_t input_count,
    int16_t *output,
    size_t output_capacity,
    float volume,
    size_t *input_consumed
);

size_t covox_linear_flush(
    covox_linear_state_t *state,
    int16_t *output,
    size_t output_capacity
);

#ifdef __cplusplus
}
#endif

#endif
