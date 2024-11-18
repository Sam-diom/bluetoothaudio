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
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity(), DeviceDiscoveryListener {

    private lateinit var audioManager: AudioManager
    private lateinit var bluetoothManager: BluetoothManager
    private val audioCapturer = AudioCapturer(ByteArrayOutputStream())
    private val audioPlayer = AudioPlayer(ByteArrayInputStream(ByteArray(0)))

    private lateinit var devicesListView: ListView
    private lateinit var devicesAdapter: ArrayAdapter<String>

    private val discoveredDevices = HashSet<String>() // HashSet pour éviter les doublons

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        bluetoothManager = BluetoothManager(this, this)

        val connectButton: Button = findViewById(R.id.connectButton)
        val startButton: Button = findViewById(R.id.startButton)
        val stopButton: Button = findViewById(R.id.stopButton)

        startButton.text = getString(R.string.rescan) // Changement du texte en "Rescan"
        startButton.isEnabled = false

        connectButton.setOnClickListener {
            Log.d("MainActivity", "ConnectButton clicked.")
            demanderPermissionsBluetooth()
            if (bluetoothManager.isBluetoothEnabled()) {
                connectButton.visibility = View.GONE
                stopButton.visibility = View.VISIBLE
                startButton.isEnabled = true
                Toast.makeText(
                    this,
                    "Bluetooth activé, appuyez sur Rescan pour démarrer la recherche",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        startButton.setOnClickListener {
            Log.d("MainActivity", "StartButton clicked.")
            demarrerDecouverteAppareils()
        }

        stopButton.setOnClickListener {
            Log.d("MainActivity", "StopButton clicked.")
            arreterCaptureEtLecture()
            stopTwoWayAudio()

            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter.isEnabled) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        bluetoothAdapter.disable()
                        Toast.makeText(this, "Bluetooth désactivé", Toast.LENGTH_SHORT).show()
                    } catch (e: SecurityException) {
                        Log.e("MainActivity", "Permission BLUETOOTH_CONNECT non accordée", e)
                        Toast.makeText(this, "Permission Bluetooth manquante", Toast.LENGTH_SHORT)
                            .show()
                    }
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                        BLUETOOTH_PERMISSION_REQUEST_CODE
                    )
                }
            }

            stopButton.visibility = View.GONE
            connectButton.visibility = View.VISIBLE
            startButton.isEnabled = false
        }

        devicesListView = findViewById(R.id.devicesListView)
        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = devicesAdapter

        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = bluetoothManager.getDiscoveredDevices()[position]
            connectAndStartAudio(selectedDevice)
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(discoveryReceiver, filter)

        requestPermissions()
    }

    @SuppressLint("MissingPermission")
    private fun connectAndStartAudio(device: BluetoothDevice) {
        Log.d(
            "MainActivity",
            "Tentative de connexion à l'appareil: ${device.name ?: device.address}"
        )
        try {
            val socket = bluetoothManager.connectToDevice(device)
            if (socket != null) {
                Log.d("MainActivity", "Connexion réussie à ${device.name ?: device.address}")
                Toast.makeText(this, "Connecté à ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
                startTwoWayAudio(socket)
            } else {
                Log.e("MainActivity", "Échec de la connexion à ${device.name ?: device.address}")
                Toast.makeText(this, "Échec de la connexion à ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Permission Bluetooth manquante ou refusée", e)
            Toast.makeText(
                this,
                "La permission Bluetooth est requise pour se connecter.",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur lors de la connexion à l'appareil", e)
            Toast.makeText(this, "Erreur lors de la connexion à l'appareil", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTwoWayAudio(socket: BluetoothSocket) {
        Log.d("MainActivity", "Démarrage de l'audio bidirectionnel")
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true

        val captureThread = Thread {
            audioCapturer.startCapturing()
            while (audioCapturer.isCapturing) {
                val audioData = audioCapturer.readAudioData()
                socket.outputStream.write(audioData)
            }
        }
        val playbackThread = Thread {
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
        Log.d("MainActivity", "Arrêt de l'audio bidirectionnel")
        arreterCaptureEtLecture()
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false

        Toast.makeText(this, "Audio bidirectionnel désactivé", Toast.LENGTH_SHORT).show()
    }

    private fun arreterCaptureEtLecture() {
        try {
            Log.d("MainActivity", "Arrêt de la capture et de la lecture audio")
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
            Log.d("MainActivity", "Bluetooth activé au démarrage")
            findViewById<Button>(R.id.startButton).isEnabled = true
            findViewById<Button>(R.id.connectButton).visibility = View.GONE
            findViewById<Button>(R.id.stopButton).visibility = View.VISIBLE
        } else {
            Log.d("MainActivity", "Bluetooth non activé au démarrage")
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
        Log.d("MainActivity", "Appareil découvert: ${device.name ?: device.address}")
        val deviceAddress = device.address
        if (!discoveredDevices.contains(deviceAddress)) {
            discoveredDevices.add(deviceAddress)
            val deviceName = device.name ?: device.address
            devicesAdapter.add(deviceName)
            devicesAdapter.notifyDataSetChanged()
        }
    }

    override fun demarrerDecouverteAppareils() {
        Log.d("MainActivity", "Démarrage de la découverte d'appareils Bluetooth")
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter.isEnabled) {
            bluetoothManager.startDiscovery()
            Toast.makeText(this, "Recherche d'appareils Bluetooth...", Toast.LENGTH_SHORT).show()
        } else {
            Log.e("MainActivity", "Bluetooth non activé")
            Toast.makeText(this, "Bluetooth non activé", Toast.LENGTH_SHORT).show()
        }
    }

    override fun demanderPermissionsBluetooth() {
        Log.d("MainActivity", "Demande de permissions Bluetooth démarrée")
        val bluetoothPermissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            bluetoothPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            bluetoothPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bluetoothPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            bluetoothPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val permissionsToRequest = bluetoothPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Permissions manquantes: ${permissionsToRequest.joinToString()}")
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                BLUETOOTH_PERMISSION_REQUEST_CODE
            )
        } else {
            Log.d(
                "MainActivity",
                "Toutes les permissions sont accordées, démarrage de la découverte Bluetooth."
            )
            bluetoothManager.startDiscovery()
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        ActivityCompat.requestPermissions(this, permissions, BLUETOOTH_PERMISSION_REQUEST_CODE)
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device: BluetoothDevice =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
                Log.d("MainActivity", "Appareil Bluetooth trouvé: ${device.name}")
                onDeviceDiscovered(device)
            }
        }
    }

    companion object {
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1
    }
}
