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
#pragma once

#include <Arduino.h>
#include <BluetoothSerial.h>
#include "spscByteRing.h"

// Classic Bluetooth SPP (RFCOMM) stream used by the kv4p protocol session.
//
// The Arduino core's BluetoothSerial keeps received bytes in a fixed 512-byte
// FreeRTOS queue and silently discards anything that does not fit. The kv4p
// protocol lets a host keep a full flow-control window (2 KiB) in flight, so a
// burst of TX voice frames from Android could overflow that queue and corrupt
// audio. This wrapper registers BluetoothSerial::onData() and copies incoming
// bytes into a larger lock-free ring instead, which the protocol parser reads
// from the main loop. Writes go straight to BluetoothSerial, whose own TX path
// already coalesces into RFCOMM-sized packets.
template <size_t RX_CAPACITY>
class BtSppStream : public Stream {
public:
  explicit BtSppStream(BluetoothSerial &bt) : _bt(bt) {}

  bool begin(const char *deviceName) {
    _bt.onData([this](const uint8_t *data, size_t len) {
      // Runs on the Bluetooth host task.
      _rx.push(data, len);
    });
    return _bt.begin(deviceName);
  }

  bool hasClient() {
    return _bt.hasClient();
  }

  // Drop any stale bytes left over from a previous client.
  void resetRx() {
    _rx.clear();
  }

  uint32_t droppedRxBytes() const {
    return _rx.droppedBytes();
  }

  static constexpr size_t rxCapacity() {
    return RX_CAPACITY;
  }

  int available() override {
    return (int)_rx.available();
  }

  int read() override {
    return _rx.read();
  }

  int peek() override {
    return _rx.peek();
  }

  size_t write(uint8_t b) override {
    return _bt.write(&b, 1);
  }

  size_t write(const uint8_t *buffer, size_t size) override {
    return _bt.write(buffer, size);
  }

  // BluetoothSerial::flush() polls its TX queue in 100 ms sleeps, which would
  // stall RX audio. The core drains the queue on its own, so this is a no-op.
  void flush() override {}

private:
  BluetoothSerial &_bt;
  SpscByteRing<RX_CAPACITY> _rx;
};
