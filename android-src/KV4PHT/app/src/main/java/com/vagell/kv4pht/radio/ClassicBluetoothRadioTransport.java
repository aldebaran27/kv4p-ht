package com.vagell.kv4pht.radio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Classic Bluetooth (BR/EDR) Serial Port Profile transport.
 *
 * <p>The firmware runs an SPP server named {@code kv4p-XXXXXX} alongside USB and BLE, and
 * carries the full kv4p protocol over it: control, RX audio and TX voice. This transport
 * connects to a radio the user has already paired in Android's Bluetooth settings.
 *
 * <p>RFCOMM connect, read and write all block, so each runs on its own thread. Results are
 * delivered to the listener on {@code handler}, matching the USB and BLE transports.
 */
final class ClassicBluetoothRadioTransport implements RadioTransport {
    private static final String TAG = ClassicBluetoothRadioTransport.class.getSimpleName();
    /** Standard Serial Port Profile service UUID, advertised by ESP32 BluetoothSerial. */
    static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    /** Firmware names itself "kv4p-" followed by six hex digits (see formatBluetoothDeviceName). */
    static final String DEVICE_NAME_PREFIX = "kv4p";
    private static final int READ_BUFFER_SIZE = 1024;
    private static final byte[] WRITER_STOP = new byte[0];

    private final Context context;
    private final Handler handler;
    private final BluetoothDevice device;
    private final LinkedBlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();

    private volatile Listener listener;
    private volatile BluetoothSocket socket;
    private volatile boolean closed = false;
    private volatile boolean ready = false;
    private Thread connectThread;
    private Thread readThread;
    private Thread writeThread;

    ClassicBluetoothRadioTransport(Context context, Handler handler, BluetoothDevice device) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.device = device;
    }

    /**
     * Returns the first bonded Classic-capable Bluetooth device whose name looks like a kv4p
     * radio, or {@code null} if there is none, Bluetooth is off, or permission is missing.
     */
    @Nullable
    @SuppressLint("MissingPermission")
    static BluetoothDevice findBondedRadio(Context context) {
        if (!hasConnectPermission(context)) {
            return null;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            return null;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) {
                return null;
            }
            for (BluetoothDevice candidate : bonded) {
                if (isKv4pName(candidate.getName())
                    && candidate.getType() != BluetoothDevice.DEVICE_TYPE_LE) {
                    return candidate;
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to list bonded Bluetooth devices", e);
        }
        return null;
    }

    static boolean isKv4pName(@Nullable String name) {
        return name != null && name.regionMatches(true, 0, DEVICE_NAME_PREFIX, 0, DEVICE_NAME_PREFIX.length());
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        if (!hasConnectPermission(context)) {
            listener.onError(new SecurityException("Missing Bluetooth connect permission"));
            return;
        }
        connectThread = new Thread(this::connectAndRun, "kv4p-spp-connect");
        connectThread.start();
    }

    @SuppressLint("MissingPermission")
    private void connectAndRun() {
        String name = safeDeviceName();
        Log.i(TAG, "Connecting to " + name + " over Bluetooth SPP");
        cancelDiscovery();
        BluetoothSocket connected = null;
        IOException lastError = null;
        // Secure RFCOMM works for a bonded radio; fall back to insecure for stacks that
        // reject the secure socket even though the device is paired.
        for (int attempt = 0; attempt < 2 && !closed && connected == null; attempt++) {
            BluetoothSocket candidate = null;
            try {
                candidate = attempt == 0
                    ? device.createRfcommSocketToServiceRecord(SPP_UUID)
                    : device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                socket = candidate;
                if (closed) {
                    // close() may have run before socket was published.
                    closeQuietly(candidate);
                    break;
                }
                candidate.connect();
                connected = candidate;
            } catch (IOException | SecurityException e) {
                lastError = e instanceof IOException ? (IOException) e : new IOException(e);
                closeQuietly(candidate);
                socket = null;
            }
        }
        if (connected == null || closed) {
            closeQuietly(connected);
            if (!closed) {
                Log.i(TAG, "Bluetooth SPP connect to " + name + " failed: "
                    + (lastError != null ? lastError.getMessage() : "unknown"));
                postDisconnected();
            }
            return;
        }

        final InputStream in;
        final OutputStream out;
        try {
            in = connected.getInputStream();
            out = connected.getOutputStream();
        } catch (IOException e) {
            closeQuietly(connected);
            postError(e);
            return;
        }
        Log.i(TAG, "Bluetooth SPP connected to " + name);
        // The firmware sends HELLO as soon as the link comes up. Post onReady() before the
        // reader starts so the service is already waiting for HELLO when those bytes arrive;
        // the handler runs posts in order, and unread bytes wait in the socket meanwhile.
        handler.post(() -> {
            Listener l = listener;
            if (!closed && l != null) {
                ready = true;
                l.onReady();
            }
        });
        writeThread = new Thread(() -> writeLoop(out), "kv4p-spp-write");
        writeThread.start();
        readThread = new Thread(() -> readLoop(in), "kv4p-spp-read");
        readThread.start();
    }

    private void readLoop(InputStream in) {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try {
            while (!closed) {
                int n = in.read(buffer);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                byte[] copy = Arrays.copyOf(buffer, n);
                handler.post(() -> {
                    Listener l = listener;
                    if (!closed && l != null) {
                        l.onBytes(copy);
                    }
                });
            }
            if (!closed) {
                postDisconnected();
            }
        } catch (IOException e) {
            if (!closed) {
                postError(e);
            }
        }
    }

    private void writeLoop(OutputStream out) {
        try {
            while (!closed) {
                byte[] bytes = writeQueue.take();
                if (bytes == WRITER_STOP || closed) {
                    return;
                }
                out.write(bytes);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed) {
                postError(e);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        ready = false;
        listener = null;
        writeQueue.clear();
        writeQueue.offer(WRITER_STOP);
        // Closing the socket unblocks connect() and read() on their threads.
        closeQuietly(socket);
        socket = null;
        interruptQuietly(connectThread);
        interruptQuietly(writeThread);
        connectThread = null;
        readThread = null;
        writeThread = null;
    }

    @Override
    public boolean isReady() {
        BluetoothSocket s = socket;
        return ready && !closed && s != null && s.isConnected();
    }

    @Override
    public void writeAsync(byte[] bytes) {
        if (closed || bytes == null || bytes.length == 0) {
            return;
        }
        // Protocol.Sender bounds bytes in flight with the firmware's flow-control window,
        // so this queue stays small even though it is unbounded.
        writeQueue.offer(bytes.clone());
    }

    @Override
    public boolean supportsFirmwareFlashing() {
        return false;
    }

    @Override
    public boolean prepareForFirmwareFlashing() {
        return false;
    }

    @Override
    public String getName() {
        return "Bluetooth serial";
    }

    private void postDisconnected() {
        handler.post(() -> {
            Listener l = listener;
            if (!closed && l != null) {
                l.onDisconnected();
            }
        });
    }

    private void postError(Exception error) {
        handler.post(() -> {
            Listener l = listener;
            if (!closed && l != null) {
                l.onError(error);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void cancelDiscovery() {
        // An in-progress inquiry slows RFCOMM connects considerably.
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            if (adapter != null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // BLUETOOTH_SCAN not granted; connecting still works, just slower.
        }
    }

    @SuppressLint("MissingPermission")
    private String safeDeviceName() {
        try {
            String name = device.getName();
            return name != null ? name : device.getAddress();
        } catch (SecurityException e) {
            return "unknown";
        }
    }

    private static boolean hasConnectPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        }
        // BLUETOOTH is an install-time permission below Android 12.
        return true;
    }

    private static void closeQuietly(@Nullable BluetoothSocket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // Best-effort teardown.
        }
    }

    private static void interruptQuietly(@Nullable Thread t) {
        if (t != null) {
            t.interrupt();
        }
    }
}
