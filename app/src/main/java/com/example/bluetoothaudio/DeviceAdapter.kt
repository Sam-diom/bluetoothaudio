import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.bluetooth.BluetoothDevice
import com.example.bluetoothaudio.R


class DeviceAdapter(
    context: Context,
    private val devices: List<BluetoothDevice>,
    private val connectCallback: (BluetoothDevice, Button) -> Unit
) : ArrayAdapter<BluetoothDevice>(context, 0, devices) {

    @SuppressLint("MissingPermission")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.device_list_item, parent, false)
        val device = devices[position]
        val deviceNameTextView = view.findViewById<TextView>(R.id.deviceNameTextView)
        val connectButton = view.findViewById<Button>(R.id.connectButton)

        // Afficher le nom ou l'adresse de l'appareil
        deviceNameTextView.text = device.name ?: device.address

        // Gérer le clic sur le bouton Connect
        connectButton.setOnClickListener {
            connectCallback(device, connectButton)
        }

        return view
    }
}
