/*
KV4P-HT (see http://kv4p.com)
Copyright (C) 2026 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

#include <Arduino.h>
#include <unity.h>

#include "btSppStream.h"
#include "spscByteRing.h"

void setUp() {}
void tearDown() {}

static void test_ring_preserves_order_across_wraparound() {
  SpscByteRing<8> ring;
  uint8_t counter = 0;
  uint8_t expected = 0;
  // Push/read enough times that the free-running indices wrap the buffer many times.
  for (int round = 0; round < 100; round++) {
    uint8_t chunk[5];
    for (auto &b : chunk) b = counter++;
    TEST_ASSERT_EQUAL_UINT32(5, ring.push(chunk, sizeof(chunk)));
    for (int i = 0; i < 5; i++) {
      TEST_ASSERT_EQUAL_INT(expected, ring.peek());
      TEST_ASSERT_EQUAL_INT(expected, ring.read());
      expected++;
    }
  }
  TEST_ASSERT_EQUAL_UINT32(0, ring.available());
  TEST_ASSERT_EQUAL_INT(-1, ring.read());
  TEST_ASSERT_EQUAL_INT(-1, ring.peek());
  TEST_ASSERT_EQUAL_UINT32(0, ring.droppedBytes());
}

static void test_ring_counts_overflow_instead_of_overwriting() {
  SpscByteRing<4> ring;
  const uint8_t data[6] = {1, 2, 3, 4, 5, 6};
  TEST_ASSERT_EQUAL_UINT32(4, ring.push(data, sizeof(data)));
  TEST_ASSERT_EQUAL_UINT32(2, ring.droppedBytes());
  for (int i = 1; i <= 4; i++) {
    TEST_ASSERT_EQUAL_INT(i, ring.read());
  }
}

static void test_ring_clear_discards_buffered_bytes() {
  SpscByteRing<16> ring;
  const uint8_t data[3] = {9, 9, 9};
  ring.push(data, sizeof(data));
  ring.clear();
  TEST_ASSERT_EQUAL_UINT32(0, ring.available());
  const uint8_t next = 42;
  ring.push(&next, 1);
  TEST_ASSERT_EQUAL_INT(42, ring.read());
}

static void test_stream_holds_full_protocol_window_without_loss() {
  // The core's own queue is 512 bytes; the stream must absorb a full 2 KiB window.
  BluetoothSerial bt;
  BtSppStream<4096> stream(bt);
  TEST_ASSERT_TRUE(stream.begin("kv4p-TEST"));
  uint8_t burst[2048];
  for (size_t i = 0; i < sizeof(burst); i++) burst[i] = (uint8_t)(i * 7);
  // Deliver in RFCOMM-sized pieces as the Bluetooth task would.
  for (size_t off = 0; off < sizeof(burst); off += 330) {
    size_t n = sizeof(burst) - off < 330 ? sizeof(burst) - off : 330;
    bt.deliver(burst + off, n);
  }
  TEST_ASSERT_EQUAL_INT(2048, stream.available());
  for (size_t i = 0; i < sizeof(burst); i++) {
    TEST_ASSERT_EQUAL_INT(burst[i], stream.read());
  }
  TEST_ASSERT_EQUAL_UINT32(0, stream.droppedRxBytes());
}

static void test_stream_writes_pass_through_and_reset_clears_rx() {
  BluetoothSerial bt;
  BtSppStream<64> stream(bt);
  stream.begin("kv4p-TEST");
  const uint8_t out[3] = {0xC0, 0x01, 0xC0};
  TEST_ASSERT_EQUAL_UINT32(3, stream.write(out, sizeof(out)));
  TEST_ASSERT_EQUAL_UINT32(1, stream.write((uint8_t)0x55));
  TEST_ASSERT_EQUAL_UINT32(4, bt.written.size());
  TEST_ASSERT_EQUAL_UINT8(0x55, bt.written[3]);

  const uint8_t stale[2] = {1, 2};
  bt.deliver(stale, sizeof(stale));
  stream.resetRx();
  TEST_ASSERT_EQUAL_INT(0, stream.available());

  bt.client = true;
  TEST_ASSERT_TRUE(stream.hasClient());
}

static int runBtSppStreamTests() {
  UNITY_BEGIN();
  RUN_TEST(test_ring_preserves_order_across_wraparound);
  RUN_TEST(test_ring_counts_overflow_instead_of_overwriting);
  RUN_TEST(test_ring_clear_discards_buffered_bytes);
  RUN_TEST(test_stream_holds_full_protocol_window_without_loss);
  RUN_TEST(test_stream_writes_pass_through_and_reset_clears_rx);
  return UNITY_END();
}

#ifdef PIO_NATIVE_TEST
int main(int, char **) {
  return runBtSppStreamTests();
}
#else
void setup() {
  runBtSppStreamTests();
}

void loop() {}
#endif
