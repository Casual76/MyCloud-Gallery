package dev.pampa.mycloudgallery.window

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf

/** L'activity vuota della regola di Compose non e' nel manifest di `:app` (nei test): la si registra a mano, come fanno gli altri test di `:app`. */
internal class RegisterComponentActivity : TestWatcher() {
  override fun starting(description: Description) {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val info = ActivityInfo().apply {
      name = ComponentActivity::class.java.name
      packageName = app.packageName
      flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
    }
    shadowOf(app.packageManager).addOrUpdateActivity(info)
  }
}
