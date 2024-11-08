package com.example.bluetoothaudio

import AudioCapturer
import AudioPlayer
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(), DeviceDiscoveryListener {

    private lateinit var audioManager: AudioManager
    private lateinit var bluetoothManager: BluetoothManager
    private val audioCapturer = AudioCapturer(ByteArrayOutputStream())
    private val audioPlayer = AudioPlayer(ByteArrayInputStream(ByteArray(0)))

    private lateinit var devicesListView: ListView
    private lateinit var devicesAdapter: ArrayAdapter<String>

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            bluetoothManager.startDiscovery()
            Toast.makeText(this, "Découverte Bluetooth lancée", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth non activé", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        bluetoothManager = BluetoothManager(this, this)

        val connectButton: Button = findViewById(R.id.connectButton)
        val startButton: Button = findViewById(R.id.startButton)
        val stopButton: Button = findViewById(R.id.stopButton)

        startButton.isEnabled = false

        connectButton.setOnClickListener {
            demanderPermissionsBluetooth()
            if (bluetoothManager.isBluetoothEnabled()) {
                connectButton.visibility = View.GONE
                stopButton.visibility = View.VISIBLE
                startButton.isEnabled = true
                Toast.makeText(this, "Bluetooth activé, appuyez sur Start pour démarrer la recherche", Toast.LENGTH_SHORT).show()
            }
        }

        startButton.setOnClickListener {
            demarrerDecouverteAppareils()
        }

        stopButton.setOnClickListener {
            arreterCaptureEtLecture()
            stopTwoWayAudio()

            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter.isEnabled) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        bluetoothAdapter.disable()
                        Toast.makeText(this, "Bluetooth désactivé", Toast.LENGTH_SHORT).show()
                    } catch (e: SecurityException) {
                        Log.e("MainActivity", "Permission BLUETOOTH_CONNECT non accordée pour désactiver le Bluetooth", e)
                        Toast.makeText(this, "Permission Bluetooth manquante pour désactiver", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_PERMISSION_REQUEST_CODE)
                }
            }

            stopButton.visibility = View.GONE
            connectButton.visibility = View.VISIBLE
            startButton.isEnabled = false
        }

        devicesListView = findViewById(R.id.devicesListView)
        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = devicesAdapter

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(discoveryReceiver, filter)

        requestPermissions()
    }

    @SuppressLint("MissingPermission")
    private fun connectAndStartAudio(device: BluetoothDevice) {
        try {
            // Établir la connexion Bluetooth avec l'appareil sélectionné
            val socket = bluetoothManager.connectToDevice(device)
            if (socket != null) {
                Toast.makeText(this, "Connecté à ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()

                // Configurer et démarrer la communication audio bidirectionnelle
                startTwoWayAudio(socket)
            } else {
                Toast.makeText(this, "Échec de la connexion à ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur lors de la connexion à l'appareil", e)
            Toast.makeText(this, "Erreur lors de la connexion à l'appareil", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTwoWayAudio(socket: BluetoothSocket) {
        // Configurer l'AudioManager pour la connexion Bluetooth
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true

        // Lancer les threads de capture et de lecture audio
        val captureThread = Thread {
            try {
                // Capturer l'audio depuis le microphone et l'envoyer via le socket Bluetooth
                audioCapturer.startCapturing()
                val buffer = ByteArray(1024) // Adjust buffer size as needed
                while (audioCapturer.isCapturing) {
                    val bytesRead = audioCapturer.readAudioData(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        socket.outputStream.write(buffer, 0, bytesRead)
                    } else {
                        // Handle case where no audio data is read
                        Log.d("AudioCapture", "No audio data read")
                        // You might want to introduce a small delay here to avoid busy-waiting
                        Thread.sleep(10)
                    }
                }
            } catch (e: IOException) {
                Log.e("AudioCapture", "Error sending audio data: ${e.message}")
                // Handle the exception, e.g., stop capturing, close socket
            } finally {
                // Close resources in the finally block to ensure they are released
                try {
                    audioCapturer.stopCapturing()
                    socket.close()
                } catch (e: IOException) {
                    Log.e("AudioCapture", "Error closing resources: ${e.message}")
                }
            }
        }
        val playbackThread = Thread {
            // Lire l'audio reçu via le socket Bluetooth et le jouer sur le haut-parleur
            while (true) {
                val audioData = ByteArray(1024)
                val bytesRead = socket.inputStream.read(audioData)
                if (bytesRead > 0) {
                    audioPlayer.playAudio(audioData, 0, bytesRead)
                }
            }
        }

        captureThread.start()
        playbackThread.start()

        Toast.makeText(this, "Audio bidirectionnel activé", Toast.LENGTH_SHORT).show()
    }

    private fun stopTwoWayAudio() {
        // Arrêter la capture et la lecture audio
        arreterCaptureEtLecture()

        // Arrêter la connexion audio Bluetooth
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false

        Toast.makeText(this, "Audio bidirectionnel désactivé", Toast.LENGTH_SHORT).show()
    }

    private fun arreterCaptureEtLecture() {
        try {
            audioCapturer.stopCapturing()
            audioPlayer.stopPlaying()
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur lors de l'arrêt de la capture/lecture audio", e)
            Toast.makeText(this, "Erreur lors de l'arrêt de la capture/lecture audio", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
            findViewById<Button>(R.id.startButton).isEnabled = true
            findViewById<Button>(R.id.connectButton).visibility = View.GONE
            findViewById<Button>(R.id.stopButton).visibility = View.VISIBLE
        } else {
            findViewById<Button>(R.id.startButton).isEnabled = false
            findViewById<Button>(R.id.connectButton).visibility = View.VISIBLE
            findViewById<Button>(R.id.stopButton).visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTwoWayAudio()
        bluetoothManager.stopDiscovery()
        unregisterReceiver(discoveryReceiver)
    }

    @SuppressLint("MissingPermission")
    override fun onDeviceDiscovered(device: BluetoothDevice) {
        runOnUiThread {
            val deviceName = device.name ?: device.address
            devicesAdapter.add(deviceName)
            devicesAdapter.notifyDataSetChanged()

            // Afficher un menu permettant de se connecter à l'appareil sélectionné
            devicesListView.setOnItemClickListener { _, _, position, _ ->
                val selectedDevice = bluetoothManager.getDiscoveredDevices()[position]
                connectAndStartAudio(selectedDevice)
            }
        }
    }

    override fun demarrerDecouverteAppareils() {
        TODO("Not yet implemented")
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), BLUETOOTH_PERMISSION_REQUEST_CODE)
        }
    }

    private fun demanderPermissionsBluetooth() {
        if (bluetoothManager.isBluetoothEnabled()) {
            bluetoothManager.startDiscovery()
            Toast.makeText(this, "Découverte Bluetooth lancée", Toast.LENGTH_SHORT).show()
        } else {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        devicesAdapter.add(it.name ?: it.address)
                        devicesAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    companion object {
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1
    }
}