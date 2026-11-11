package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.work.PreparationWorker
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class HrPreparationTriggerTest {
  @get:Rule val temp = TemporaryFolder()
  @Test fun miniaturePronteRiattivanoIlConfrontoComeLaReplica() = runBlocking {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val rig = AiRig(app, temp.root)
    try {
      val device = rig.founder()
      val worker = TestListenableWorkerBuilder<PreparationWorker>(app).setWorkerFactory(object : WorkerFactory() {
        override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker =
          PreparationWorker(context, parameters, device.gallery, device.test.graph)
      }).build()
      val flag = PreparationWorker::class.java.getDeclaredMethod("getTriggersSimilarCopies").apply { isAccessible = true }
      assertThat(flag.invoke(worker)).isEqualTo(true)
    } finally { rig.close() }
  }
}
