package com.example.gamepadandroid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Gamepad Android v0.2
 * Sender: Wi-Fi TCP or Bluetooth Classic RFCOMM SPP.
 * Receiver: TCP server on port 45820 for testing the real local-network protocol.
 * This does not emulate Bluetooth HID or inject input into unrelated TV apps.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var eventLog: TextView
    private var tcpSocket: Socket? = null
    private var tcpOut: OutputStream? = null
    private var btSocket: BluetoothSocket? = null
    private var btOut: OutputStream? = null
    private var serverSocket: ServerSocket? = null
    @Volatile private var receiverRunning = false
    private var transport = "none"
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildControllerUi()
        requestBluetoothPermissionsIfNeeded()
    }

    private fun baseLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 24, 22, 20)
        setBackgroundColor(0xFF0B0E14.toInt())
    }

    private fun text(value: String, size: Float = 15f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(0xFFEEF2F8.toInt())
        setPadding(0, 8, 0, 8)
    }

    private fun buildControllerUi() {
        val outer = baseLayout()
        status = text("Desconectado · GAMEPAD ANDROID", 18f).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF5EEAD4.toInt())
        }
        outer.addView(status, matchWrap())
        outer.addView(text("Mando para conectar con otro dispositivo compatible. Para probar Wi-Fi, abrí el modo receptor en el dispositivo que recibirá las entradas."), matchWrap())

        hostInput = EditText(this).apply {
            hint = "IP del receptor (ej. 192.168.1.50)"
            setSingleLine()
            setTextColor(0xFFEEF2F8.toInt())
            setHintTextColor(0xFF99A4B7.toInt())
        }
        outer.addView(hostInput, matchWrap())
        portInput = EditText(this).apply {
            hint = "Puerto Wi-Fi (45820)"
            setSingleLine()
            inputType = 2
            setText("45820")
            setTextColor(0xFFEEF2F8.toInt())
        }
        outer.addView(portInput, matchWrap())
        outer.addView(actionButton("Conectar por Wi-Fi") { connectWifi() }, matchWrap())
        outer.addView(actionButton("Conectar por Bluetooth") { showBluetoothDevices() }, matchWrap())
        outer.addView(actionButton("Abrir modo receptor Wi-Fi") { buildReceiverUi() }, matchWrap())
        outer.addView(actionButton("USB: requisitos") {
            AlertDialog.Builder(this)
                .setTitle("USB depende del hardware")
                .setMessage("No se puede convertir cualquier teléfono Android en un mando USB HID solo conectando un cable. Hace falta compatibilidad del dispositivo, definir quién actúa como host/USB device y probarlo en el receptor real.")
                .setPositiveButton("Entendido", null).show()
        }, matchWrap())

        val grid = GridLayout(this).apply { columnCount = 3 }
        val controls = listOf(
            "↑" to "UP", "Y" to "Y", "A" to "A",
            "←" to "LEFT", "●" to "CENTER", "→" to "RIGHT",
            "↓" to "DOWN", "X" to "X", "B" to "B",
            "L1" to "L1", "R1" to "R1", "START" to "START"
        )
        controls.forEach { (label, key) ->
            val b = actionButton(label) {}
            b.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        sendButton(key, true); v.isPressed = true; true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        sendButton(key, false); v.isPressed = false; true
                    }
                    else -> true
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0; height = 62
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 4, 4, 4)
            }
            grid.addView(b, lp)
        }
        outer.addView(grid, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        outer.addView(actionButton("Desconectar") { disconnect() }, matchWrap())
        setContentView(ScrollView(this).apply { addView(outer) })
    }

    private fun buildReceiverUi() {
        disconnect()
        val outer = baseLayout()
        outer.addView(text("RECEPTOR WI-FI · GAMEPAD ANDROID", 20f).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF5EEAD4.toInt())
        }, matchWrap())
        val ip = localIpv4() ?: "No se detectó IP Wi-Fi"
        outer.addView(text("IP de este dispositivo: $ip\nPuerto TCP: 45820\nConectá el mando desde otro dispositivo usando esta IP."), matchWrap())
        status = text("Receptor detenido")
        outer.addView(status, matchWrap())
        outer.addView(actionButton("Iniciar receptor") { startReceiver() }, matchWrap())
        outer.addView(actionButton("Detener receptor") { stopReceiver() }, matchWrap())
        outer.addView(actionButton("Volver al mando") { stopReceiver(); buildControllerUi() }, matchWrap())
        outer.addView(text("Eventos recibidos:", 17f), matchWrap())
        eventLog = text("Todavía no llegaron eventos.")
        outer.addView(eventLog, matchWrap())
        setContentView(ScrollView(this).apply { addView(outer) })
    }

    private fun startReceiver() {
        if (receiverRunning) { status.text = "Receptor activo en puerto 45820"; return }
        receiverRunning = true
        status.text = "Iniciando receptor…"
        thread(name = "gamepad-wifi-receiver") {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(45820))
                serverSocket = server
                runOnUiThread { status.text = "Receptor activo · puerto 45820" }
                while (receiverRunning) {
                    val client = server.accept()
                    thread(name = "gamepad-client") { handleClient(client) }
                }
            } catch (e: Exception) {
                if (receiverRunning) runOnUiThread {
                    status.text = "No se pudo iniciar receptor: ${e.localizedMessage ?: "error"}"
                }
            } finally {
                receiverRunning = false
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
            }
        }
    }

    private fun handleClient(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: "cliente"
        runOnUiThread { appendEvent("Conectado: $peer") }
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            while (receiverRunning) {
                val line = reader.readLine() ?: break
                runOnUiThread { appendEvent(line) }
            }
        } catch (e: Exception) {
            runOnUiThread { appendEvent("Conexión cerrada: ${e.localizedMessage ?: "desconocida"}") }
        } finally {
            try { client.close() } catch (_: Exception) {}
            runOnUiThread { appendEvent("Desconectado: $peer") }
        }
    }

    private fun appendEvent(line: String) {
        if (!::eventLog.isInitialized) return
        val old = eventLog.text.toString()
        val lines = (if (old == "Todavía no llegaron eventos.") emptyList() else old.lines()) + line
        eventLog.text = lines.takeLast(35).joinToString("\n")
    }

    private fun stopReceiver() {
        receiverRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        if (::status.isInitialized) status.text = "Receptor detenido"
    }

    private fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun actionButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        setTextColor(0xFFEEF2F8.toInt())
        setBackgroundColor(0xFF293347.toInt())
        setOnClickListener { click() }
    }

    private fun connectWifi() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull() ?: 45820
        if (host.isBlank()) { status.text = "Escribí la IP del dispositivo receptor."; return }
        status.text = "Conectando por Wi-Fi…"
        thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                val out = s.getOutputStream()
                try { tcpSocket?.close() } catch (_: Exception) {}
                tcpSocket = s; tcpOut = out; transport = "wifi"
                runOnUiThread { status.text = "Conectado por Wi-Fi · $host:$port" }
                sendRaw("{\"type\":\"hello\",\"app\":\"GamepadAndroid\",\"version\":2}")
            } catch (e: Exception) {
                runOnUiThread { status.text = "Wi-Fi no conectado: ${e.localizedMessage ?: "error"}" }
            }
        }
    }

    private fun showBluetoothDevices() {
        val a = adapter
        if (a == null) { status.text = "Este teléfono no tiene Bluetooth."; return }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissionsIfNeeded()
            status.text = "Concedé permiso Bluetooth y volvé a intentarlo."
            return
        }
        if (!a.isEnabled) { status.text = "Activá Bluetooth en Ajustes y volvé a intentarlo."; return }
        try {
            val devices = a.bondedDevices.toList()
            if (devices.isEmpty()) {
                status.text = "No hay dispositivos emparejados. Emparejá primero el receptor en Ajustes."
                return
            }
            val labels = devices.map { "${it.name ?: "Dispositivo"} · ${it.address}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Elegí un receptor Bluetooth")
                .setItems(labels) { _, which -> connectBluetooth(devices[which]) }
                .setNegativeButton("Cancelar", null).show()
        } catch (_: SecurityException) { status.text = "Permiso Bluetooth denegado." }
    }

    private fun connectBluetooth(device: BluetoothDevice) {
        status.text = "Conectando Bluetooth…"
        thread {
            try {
                adapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket.connect()
                try { btSocket?.close() } catch (_: Exception) {}
                btSocket = socket; btOut = socket.outputStream; transport = "bluetooth"
                runOnUiThread { status.text = "Bluetooth RFCOMM conectado · ${device.name ?: device.address}" }
                sendRaw("{\"type\":\"hello\",\"app\":\"GamepadAndroid\",\"version\":2}")
            } catch (e: Exception) {
                runOnUiThread { status.text = "Bluetooth no conectado: ${e.localizedMessage ?: "error"}" }
            }
        }
    }

    private fun sendButton(name: String, down: Boolean) {
        sendRaw("{\"type\":\"button\",\"name\":\"$name\",\"down\":$down}")
        if (down && ::status.isInitialized) status.text = "Entrada: $name"
    }

    private fun sendRaw(message: String) {
        val bytes = (message + "\n").toByteArray(Charsets.UTF_8)
        thread {
            try {
                when (transport) {
                    "wifi" -> synchronized(this) { tcpOut?.write(bytes); tcpOut?.flush() }
                    "bluetooth" -> synchronized(this) { btOut?.write(bytes); btOut?.flush() }
                    else -> { /* Offline preview: no remote transport. */ }
                }
            } catch (e: Exception) {
                runOnUiThread { if (::status.isInitialized) status.text = "Error enviando datos: ${e.localizedMessage ?: "conexión perdida"}" }
            }
        }
    }

    private fun disconnect() {
        transport = "none"
        try { tcpOut?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        try { btOut?.close() } catch (_: Exception) {}
        try { btSocket?.close() } catch (_: Exception) {}
        tcpOut = null; tcpSocket = null; btOut = null; btSocket = null
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31) {
            val missing = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 101)
        }
    }

    override fun onDestroy() {
        stopReceiver()
        disconnect()
        super.onDestroy()
    }
}
