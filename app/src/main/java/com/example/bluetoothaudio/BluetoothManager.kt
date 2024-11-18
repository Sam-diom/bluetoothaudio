package com.example.bluetoothaudio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import java.util.*

interface DeviceDiscoveryListener {
    fun onDeviceDiscovered(device: BluetoothDevice)
    fun demarrerDecouverteAppareils()
    fun demanderPermissionsBluetooth()
}

class BluetoothManager(private val context: Context, private val listener: DeviceDiscoveryListener) {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val discoveredDevices: MutableList<BluetoothDevice> = mutableListOf()

    init {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw RuntimeException("Bluetooth is not supported on this device")
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!discoveredDevices.contains(device)) {
                            discoveredDevices.add(device)
                            listener.onDeviceDiscovered(device)
                        }
                    }
                }
            }
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            if (isBluetoothEnabled()) {
                context.registerReceiver(discoveryReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
                bluetoothAdapter?.startDiscovery()
            } else {
                Log.w("BluetoothManager", "Bluetooth is disabled. Cannot start discovery.")
            }
        } else {
            Log.w("BluetoothManager", "Bluetooth scan permission not granted.")
            // Demandez la permission si nécessaire
        }
    }


    fun stopDiscovery() {
        try {
            if (ActivityCompat.checkSelfPermission(
                    context, // Use 'context' here instead of 'this'
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // ... (permission handling logic) ...
                return
            }
            bluetoothAdapter?.cancelDiscovery()
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("BluetoothManager", "Discovery receiver was not registered.")
        }
    }

    fun getDiscoveredDevices(): List<BluetoothDevice> = discoveredDevices.toList()

    fun clearDiscoveredDevices() {
        discoveredDevices.clear()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val socket = device.createRfcommSocketToServiceRecord(uuid)
            bluetoothAdapter?.cancelDiscovery() // Arrête la découverte pour économiser la batterie et éviter les conflits
            socket.connect()
            socket
        } catch (e: Exception) {
            Log.e("BluetoothManager", "Erreur lors de la connexion directe, tentative de connexion alternative", e)
            // Tentative de connexion alternative par réflexion
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                val fallbackSocket = method.invoke(device, 1) as BluetoothSocket
                fallbackSocket.connect()
                fallbackSocket
            } catch (ex: Exception) {
                Log.e("BluetoothManager", "Connexion alternative échouée", ex)
                null
            }
        }
    }
}
