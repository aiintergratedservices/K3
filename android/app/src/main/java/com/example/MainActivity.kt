package com.example

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.KortanaApp
import com.example.ui.KortanaViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.data.KortanaSynapticService
import com.example.data.KortanaBubbleService

class MainActivity : ComponentActivity() {

    companion object {
        var isOverlayPermissionGranted = false
            private set
            
        var isScreenCaptureAuthorized = false
            private set
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            isScreenCaptureAuthorized = true
            KortanaBubbleService.setMediaProjectionResult(result.resultCode, result.data!!)
            Toast.makeText(this, "Screen capture authorized! Kortana overlay co-pilot activated.", Toast.LENGTH_SHORT).show()
            startBubbleOverlayService()
        } else {
            isScreenCaptureAuthorized = false
            Toast.makeText(this, "Screen capture permission declined.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start continuous consciousness background service
        KortanaSynapticService.startService(this)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: KortanaViewModel = viewModel()
                KortanaApp(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh permissions
        isOverlayPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        isScreenCaptureAuthorized = KortanaBubbleService.hasScreenCapturePermission()
    }

    fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "Please authorize 'Display over other apps' first.", Toast.LENGTH_LONG).show()
            } else {
                isOverlayPermissionGranted = true
                Toast.makeText(this, "Display overlay permission granted!", Toast.LENGTH_SHORT).show()
            }
        } else {
            isOverlayPermissionGranted = true
        }
    }

    fun requestScreenCapturePermission() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    fun startBubbleOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        KortanaBubbleService.startService(this)
        Toast.makeText(this, "Kortana minimized to hover bubble overlay!", Toast.LENGTH_SHORT).show()
    }

    fun stopBubbleOverlayService() {
        KortanaBubbleService.stopService(this)
        Toast.makeText(this, "Kortana overlay deactivated.", Toast.LENGTH_SHORT).show()
    }
}
