package dev.maulu.launcherpod

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val homeEvents = MutableStateFlow(0)
    private val lockEvents = MutableStateFlow(0)
    private val musicPermissionEvents = MutableStateFlow(0)
    private val homeRoleEvents = MutableStateFlow(0)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) lockEvents.value += 1
        }
    }

    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { homeRoleEvents.value += 1 }
    private val audioPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) musicPermissionEvents.value += 1 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        enableEdgeToEdge()
        hideSystemBars()
        setContent {
            val homeEvent by homeEvents.collectAsState()
            val lockEvent by lockEvents.collectAsState()
            val musicPermissionEvent by musicPermissionEvents.collectAsState()
            val homeRoleEvent by homeRoleEvents.collectAsState()
            val launcherViewModel: LauncherViewModel = viewModel(
                factory = LauncherViewModel.factory(applicationContext)
            )
            LifePodTheme {
                LifePodApp(
                    viewModel = launcherViewModel,
                    homeEvent = homeEvent,
                    lockEvent = lockEvent,
                    musicPermissionEvent = musicPermissionEvent,
                    homeRoleEvent = homeRoleEvent,
                    onRequestHomeRole = ::requestHomeRole,
                    onRequestAudioPermission = ::requestAudioPermission
                )
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeEvents.value += 1
        }
    }

    private fun requestHomeRole() {
        val roleManager = getSystemService(Context.ROLE_SERVICE) as RoleManager
        if (
            roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        ) {
            roleRequest.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            homeRoleEvents.value += 1
        }
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            musicPermissionEvents.value += 1
        } else {
            audioPermissionRequest.launch(permission)
        }
    }

    private fun hideSystemBars() {
        ViewCompat.getWindowInsetsController(window.decorView)?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
