package tech.medina.wolfssl_kt.ui.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ClientScreen(viewModel: ClientViewModel) {
    val availableServers by viewModel.availableServers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val clientConnectionStatus by viewModel.clientConnectionStatus.collectAsState()
    val transportState by viewModel.connectionState.collectAsState()
    val latestOutputValue by viewModel.clientOutputCharacteristicValue.collectAsState()
    val writeStatus by viewModel.clientWriteStatus.collectAsState()
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Client",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Button(
            onClick = viewModel::scanForServers,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Scan for servers")
        }
        Text(
            text = "Available servers",
            style = MaterialTheme.typography.titleMedium
        )
        if (availableServers.isEmpty()) {
            Text(
                text = "No servers found. Run a scan first.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(availableServers) { server ->
                    val isSelected = selectedServer == server
                    Card(
                        modifier = Modifier
                            .clickable { viewModel.selectServer(server) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Text(
                            text = server,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = viewModel::connectToSelectedServer,
                enabled = selectedServer != null,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Connect")
            }
            OutlinedButton(
                onClick = viewModel::disconnect,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Disconnect")
            }
        }
        Text(
            text = "Selected server: ${selectedServer ?: "None"}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "Client status: $clientConnectionStatus",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Connection state: $transportState",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("Input characteristic value (client -> server)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { viewModel.sendClientInputCharacteristic(inputText) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Write Input Characteristic")
        }
        Text(
            text = "Input write state: $writeStatus",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "Latest output characteristic: $latestOutputValue",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
