package tech.medina.wolfssl_kt.ui.server

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun ServerScreen(viewModel: ServerViewModel) {
    val context = LocalContext.current
    val deviceName by viewModel.deviceName.collectAsState()
    val isAdvertising by viewModel.isAdvertising.collectAsState()
    val serverConnectionStatus by viewModel.serverConnectionStatus.collectAsState()
    val transportState by viewModel.connectionState.collectAsState()
    val latestInputValue by viewModel.serverInputCharacteristicValue.collectAsState()
    val writeStatus by viewModel.serverWriteStatus.collectAsState()
    var outputText by remember { mutableStateOf("") }
    val advertisePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            emptyArray()
        }
    }
    val advertisePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = advertisePermissions.all { permission ->
            permissions[permission] == true || ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            viewModel.startAdvertising()
        } else {
            viewModel.onAdvertisingPermissionDenied()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Server",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = viewModel::updateDeviceName,
            label = { Text("Device name") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (hasPermissions(context, advertisePermissions)) {
                    viewModel.startAdvertising()
                } else {
                    advertisePermissionLauncher.launch(advertisePermissions)
                }
            },
            enabled = !isAdvertising,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Start advertising")
        }
        OutlinedButton(
            onClick = viewModel::stopAdvertising,
            enabled = isAdvertising,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Stop advertising")
        }
        OutlinedTextField(
            value = outputText,
            onValueChange = { outputText = it },
            label = { Text("Output characteristic value (server -> client)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { viewModel.sendServerOutputCharacteristic(outputText) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Write Output Characteristic")
        }
        Text(
            text = "Advertising: ${if (isAdvertising) "On" else "Off"}",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Server status: $serverConnectionStatus",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Output write state: $writeStatus",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "Latest input characteristic: $latestInputValue",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "Connection state: $transportState",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
    return permissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
