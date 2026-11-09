package dev.pampa.mycloudgallery.core.rules.platform.crash

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Test

/** La traccia di un ANR (C472): del thread principale restano solo le cornici nostre, ricostruite da zero; del resto della traccia niente. */
class AnrTraceTest {

  private fun frames(text: String) = AnrTrace.mainThreadFrames(ByteArrayInputStream(text.toByteArray()))

  private val trace = """
    ----- pid 12345 at 2026-11-09 10:00:00.000 -----
    Cmd line: dev.pampa.mycloudgallery

    "Signal Catcher" daemon prio=10 tid=3 Runnable
      | group="system" sCount=0 ucsCount=0 flags=0 obj=0x12c5a4f0 self=0x7b5c9a2c00
      at dev.pampa.mycloudgallery.NotMain.run(NotMain.kt:1)

    "main" prio=5 tid=1 Blocked
      | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72f2a5f0 self=0x7b5c1b6c00
      | sysTid=12345 nice=-10 cgrp=top-app sched=0/0 handle=0x7b5ee6f4f8
      native: #00 pc 000000000009d7c4  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+36)
      at java.lang.Object.wait(Native Method)
      at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:93)
      at dev.pampa.mycloudgallery.feature.library.LibraryViewModel${'$'}load${'$'}1.invokeSuspend(LibraryViewModel.kt:120)
      at dev.pampa.mycloudgallery.AppRoot.draw(AppRoot.kt:45)
      at dev.antigravity.fluidengine.ui.FluidList.measure(FluidList.kt:7)
      - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 5 (Mare 2019/IMG_0001.jpg)
      at android.os.Handler.dispatchMessage(Handler.java:106)

    "Binder:12345_1" prio=5 tid=7 Native
      at dev.pampa.mycloudgallery.AlsoNotMain.run(AlsoNotMain.kt:2)
  """.trimIndent()

  @Test
  fun delThreadPrincipaleRestanoSoloLeCornicheNostre() {
    assertThat(frames(trace)).containsExactly(
      "dev.pampa.mycloudgallery.feature.library.LibraryViewModel\$load\$1.invokeSuspend(LibraryViewModel.kt:120)",
      "dev.pampa.mycloudgallery.AppRoot.draw(AppRoot.kt:45)",
      "dev.antigravity.fluidengine.ui.FluidList.measure(FluidList.kt:7)",
    ).inOrder()
  }

  @Test
  fun nienteDelRestoDellaTracciaPassa_NeNomiDiFileNeIndirizzi() {
    val text = frames(trace).joinToString("\n")

    for (leak in listOf("Mare 2019", "IMG_0001", "0x0a1b2c3d", "sysTid", "libc.so", "/apex", "NotMain", "AlsoNotMain", "Signal Catcher")) {
      assertThat(text).doesNotContain(leak)
    }
  }

  @Test
  fun senzaIlThreadPrincipaleOSenzaCornicheNostreNonRestaNiente() {
    assertThat(frames("")).isEmpty()
    assertThat(frames("\"Signal Catcher\" daemon prio=10\n  at dev.pampa.mycloudgallery.A.b(A.kt:1)\n")).isEmpty()
    assertThat(frames("\"main\" prio=5 tid=1 Blocked\n  at java.lang.Object.wait(Native Method)\n")).isEmpty()
  }

  @Test
  fun leCornichePiuDelTettoSiFermanoAlTetto() {
    val many = "\"main\" prio=5 tid=1 Blocked\n" + (1..200).joinToString("\n") { "  at dev.pampa.mycloudgallery.A$it.m(A.kt:$it)" }

    assertThat(frames(many)).hasSize(CrashDigest.MAX_FRAMES)
  }

  @Test
  fun unaTracciaCheNonSiLegge_FinoInFondo_ValgonoLeCorniceRaccolte() {
    val broken = object : InputStream() {
      private val head = "\"main\" prio=5 tid=1 Blocked\n  at dev.pampa.mycloudgallery.A.b(A.kt:1)\n".toByteArray()
      private var i = 0

      override fun read(): Int = if (i < head.size) head[i++].toInt() and 0xff else throw IOException("disco rotto in /data/anr/mario")
    }

    assertThat(AnrTrace.mainThreadFrames(broken)).containsExactly("dev.pampa.mycloudgallery.A.b(A.kt:1)")
  }
}
