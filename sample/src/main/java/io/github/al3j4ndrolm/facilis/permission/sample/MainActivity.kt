package io.github.al3j4ndrolm.facilis.permission.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.al3j4ndrolm.facilis.permission.Permission
import io.github.al3j4ndrolm.facilis.permission.PermissionStatus
import io.github.al3j4ndrolm.facilis.permission.permissions
import io.github.al3j4ndrolm.facilis.permission.rememberPermission
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { SampleScreen() }
            }
        }
    }
}

private val reasons = mapOf(
    Permission.Camera to "The camera is used to take your profile photo.",
    Permission.Microphone to "The microphone is used to record voice notes.",
    Permission.Location to "Your location is used to find classes near you.",
    Permission.Notifications to "Notifications remind you before class starts.",
    Permission.Photos to "Photos let you pick a profile picture.",
    Permission.Contacts to "Contacts let you invite classmates.",
    Permission.Bluetooth to "Bluetooth connects to nearby devices.",
)

@Composable
private fun SampleScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("FacilisPermission", style = MaterialTheme.typography.headlineMedium)
        Text("Each row is one line: rememberPermission(Permission.X, reason = \"...\").")
        HorizontalDivider()

        Permission.entries.forEach { permission ->
            PermissionRow(permission)
            HorizontalDivider()
        }

        // The coroutine version, asking for two at once.
        val scope = rememberCoroutineScope()
        var result by remember { mutableStateOf("") }
        Button(
            onClick = {
                scope.launch {
                    val granted = permissions.request(Permission.Camera, Permission.Microphone)
                    result = if (granted) "Camera + microphone: granted" else "Camera + microphone: not granted"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Request camera + microphone (coroutine)") }
        if (result.isNotEmpty()) Text(result)

        OutlinedButton(onClick = { permissions.openSettings() }, modifier = Modifier.fillMaxWidth()) {
            Text("Open app settings")
        }
    }
}

@Composable
private fun PermissionRow(permission: Permission) {
    val state = rememberPermission(permission, reason = reasons[permission])
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(permission.name, style = MaterialTheme.typography.titleMedium)
            Text(state.status.label, color = state.status.color, style = MaterialTheme.typography.bodySmall)
        }
        when (state.status) {
            PermissionStatus.Granted -> Unit
            PermissionStatus.PermanentlyDenied -> OutlinedButton(onClick = { state.request() }) { Text("Fix") }
            else -> Button(onClick = { state.request() }) { Text("Request") }
        }
    }
}

private val PermissionStatus.label: String
    get() = when (this) {
        PermissionStatus.Granted -> "Granted"
        PermissionStatus.Limited -> "Limited (approximate / some photos)"
        PermissionStatus.Denied -> "Not granted yet"
        PermissionStatus.PermanentlyDenied -> "Blocked: only Settings can change it"
    }

private val PermissionStatus.color: Color
    get() = when (this) {
        PermissionStatus.Granted -> Color(0xFF2E7D32)
        PermissionStatus.Limited -> Color(0xFFB26A00)
        PermissionStatus.Denied -> Color(0xFF616161)
        PermissionStatus.PermanentlyDenied -> Color(0xFFC62828)
    }
