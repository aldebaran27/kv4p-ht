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

#include <atomic>
#include <stddef.h>
#include <stdint.h>

// Lock-free single-producer / single-consumer byte ring.
//
// The producer (push) and the consumer (read/peek/available/clear) may run on
// different FreeRTOS tasks or cores. Only the producer writes _head and only
// the consumer writes _tail, so no lock is needed. CAPACITY must be a power of
// two so the free-running indices wrap correctly.
template <size_t CAPACITY>
class SpscByteRing {
  static_assert(CAPACITY > 0 && (CAPACITY & (CAPACITY - 1)) == 0,
                "SpscByteRing CAPACITY must be a power of two");

public:
  // Producer side. Copies as many bytes as fit; returns the number stored.
  // Bytes that do not fit are counted in droppedBytes().
  size_t push(const uint8_t *data, size_t len) {
    const size_t head = _head.load(std::memory_order_relaxed);
    const size_t tail = _tail.load(std::memory_order_acquire);
    const size_t freeSpace = CAPACITY - (head - tail);
    const size_t n = len < freeSpace ? len : freeSpace;
    for (size_t i = 0; i < n; i++) {
      _buf[(head + i) & MASK] = data[i];
    }
    _head.store(head + n, std::memory_order_release);
    if (n < len) {
      _dropped.fetch_add((uint32_t)(len - n), std::memory_order_relaxed);
    }
    return n;
  }

  // Consumer side.
  size_t available() const {
    return _head.load(std::memory_order_acquire) - _tail.load(std::memory_order_relaxed);
  }

  int peek() const {
    const size_t tail = _tail.load(std::memory_order_relaxed);
    if (_head.load(std::memory_order_acquire) == tail) {
      return -1;
    }
    return _buf[tail & MASK];
  }

  int read() {
    const size_t tail = _tail.load(std::memory_order_relaxed);
    if (_head.load(std::memory_order_acquire) == tail) {
      return -1;
    }
    const uint8_t b = _buf[tail & MASK];
    _tail.store(tail + 1, std::memory_order_release);
    return b;
  }

  // Discards everything currently buffered. Consumer side only.
  void clear() {
    _tail.store(_head.load(std::memory_order_acquire), std::memory_order_release);
  }

  uint32_t droppedBytes() const {
    return _dropped.load(std::memory_order_relaxed);
  }

  static constexpr size_t capacity() { return CAPACITY; }

private:
  static constexpr size_t MASK = CAPACITY - 1;
  uint8_t _buf[CAPACITY] = {};
  std::atomic<size_t> _head{0};
  std::atomic<size_t> _tail{0};
  std::atomic<uint32_t> _dropped{0};
};
