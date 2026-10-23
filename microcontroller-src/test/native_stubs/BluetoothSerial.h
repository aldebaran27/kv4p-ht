#pragma once

#include <functional>
#include <stddef.h>
#include <stdint.h>
#include <vector>

// Minimal host-side stand-in for the ESP32 Arduino BluetoothSerial class.
// Tests call deliver() to simulate bytes arriving from the RFCOMM link.
typedef std::function<void(const uint8_t *buffer, size_t size)> BluetoothSerialDataCb;

class BluetoothSerial {
public:
  bool begin(const char *) { return beginResult; }
  void onData(BluetoothSerialDataCb cb) { dataCb = cb; }
  bool hasClient() { return client; }
  size_t write(const uint8_t *buffer, size_t size) {
    written.insert(written.end(), buffer, buffer + size);
    return size;
  }
  void deliver(const uint8_t *buffer, size_t size) {
    if (dataCb) dataCb(buffer, size);
  }

  bool beginResult = true;
  bool client = false;
  BluetoothSerialDataCb dataCb;
  std::vector<uint8_t> written;
};
