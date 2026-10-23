/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2026 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClassicBluetoothRadioTransportTest {

    @Test
    public void matchesFirmwareDeviceNames() {
        // Firmware formats names as "kv4p-%06X".
        assertTrue(ClassicBluetoothRadioTransport.isKv4pName("kv4p-1A2B3C"));
        assertTrue(ClassicBluetoothRadioTransport.isKv4pName("KV4P-1A2B3C"));
        assertTrue(ClassicBluetoothRadioTransport.isKv4pName("kv4p"));
    }

    @Test
    public void rejectsOtherDevices() {
        assertFalse(ClassicBluetoothRadioTransport.isKv4pName(null));
        assertFalse(ClassicBluetoothRadioTransport.isKv4pName(""));
        assertFalse(ClassicBluetoothRadioTransport.isKv4pName("kv4"));
        assertFalse(ClassicBluetoothRadioTransport.isKv4pName("My Headphones"));
        assertFalse(ClassicBluetoothRadioTransport.isKv4pName("x-kv4p-1A2B3C"));
    }

    @Test
    public void usesStandardSerialPortProfileUuid() {
        assertEquals("00001101-0000-1000-8000-00805f9b34fb",
            ClassicBluetoothRadioTransport.SPP_UUID.toString());
    }
}
