package com.nwbort.gview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nwbort.gview.ui.AppViewModel
import com.nwbort.gview.ui.GviewRoot
import com.nwbort.gview.ui.theme.GviewTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crash = CrashReport.read(this)
        setContent {
            // After a crash, show what happened before touching any app state.
            var report by remember { mutableStateOf(crash) }
            val shown = report
            if (shown != null) {
                CrashScreen(shown, onDismiss = { CrashReport.clear(this); report = null })
                return@setContent
            }
            GviewTheme {
                LifecycleResumeEffect(Unit) {
                    vm.onResume()
                    onPauseOrDispose {}
                }
                GviewRoot(vm)
            }
        }
    }
}
