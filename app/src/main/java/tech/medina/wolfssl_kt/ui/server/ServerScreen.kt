package tech.medina.wolfssl_kt.ui.server

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.medina.wolfssl_kt.ui.client.ClientViewModel

@Composable
fun ServerScreen(viewModel: ClientViewModel) {
    val deviceName by viewModel.deviceName.collectAsState()
    val isAdvertising by viewModel.isAdvertising.collectAsState()
    val serverConnectionStatus by viewModel.serverConnectionStatus.collectAsState()
    val transportState by viewModel.connectionState.collectAsState()

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
            onClick = viewModel::startAdvertising,
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
        Text(
            text = "Advertising: ${if (isAdvertising) "On" else "Off"}",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Server status: $serverConnectionStatus",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Connection state: $transportState",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
