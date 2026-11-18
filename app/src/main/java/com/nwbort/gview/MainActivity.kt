package com.nwbort.gview

import android.content.Context
import android.content.Intent
import android.net.Uri
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
    /** An activity a widget asked to open, until the screen has opened it. */
    private var pending by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pending = activityId(intent)
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
                GviewRoot(vm, openActivity = pending, onOpened = { pending = null })
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activityId(intent)?.let { pending = it }
    }

    companion object {
        private const val EXTRA_ACTIVITY = "activity_id"

        private fun activityId(intent: Intent?): Long? =
            intent?.getLongExtra(EXTRA_ACTIVITY, -1L)?.takeIf { it >= 0 }

        /** Opens the app as it was left. */
        fun home(context: Context): Intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens the app on one activity's page. The data URI keeps each id's PendingIntent distinct. */
        fun openActivity(context: Context, id: Long): Intent =
            home(context)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse("gview://activity/$id"))
                .putExtra(EXTRA_ACTIVITY, id)
    }
}
