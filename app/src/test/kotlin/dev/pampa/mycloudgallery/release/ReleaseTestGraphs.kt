package dev.pampa.mycloudgallery.release

import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseDiag
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Lo stato del manifest per le prove delle correzioni della Fase 10, pacchetto A: lo [status] lo decide il test, e la lettura dello stato salvato ([load]) finisce solo
 * quando il test apre [loadGate] (la finestra "stato non ancora letto" di CA-3, a comando). Nessuna firma: le prove con i manifest firmati sono in `ReleaseGatesAppTest`.
 */
internal class GatedReleaseState(status: ReleaseStatus = ReleaseStatus.Normal, open: Boolean = true) : ReleaseState {
  val loadGate = CompletableDeferred<Unit>().apply { if (open) complete(Unit) }

  override val status = MutableStateFlow(status)

  override val diag = MutableStateFlow(ReleaseDiag(0, null, status, 0, emptyMap()))

  override suspend fun load() = loadGate.await()

  override suspend fun offer(bytes: ByteArray, origin: ManifestOrigin): OfferResult = OfferResult.REJECTED

  override fun extraUrls(id: String, sha256: String): List<String> = emptyList()

  override suspend fun clear() = Unit
}

/** Un grafo del manifest su [state], con i file in [dir] e l'I/O su [io] (nelle prove a tempo virtuale il dispatcher del test). */
internal fun testReleaseGraph(
  dir: File,
  state: ReleaseState,
  io: CoroutineDispatcher = Dispatchers.IO,
  wallNowMs: () -> Long = { System.currentTimeMillis() },
): ReleaseGraph = ReleaseGraph(dir, AppVersion(7, "1.0.0", ReleaseChannel.STABLE), state, wallNowMs, io)

/** Un grafo del manifest gia' letto, con lo stato [status]. */
internal suspend fun loadedRelease(dir: File, status: ReleaseStatus): ReleaseGraph =
  testReleaseGraph(dir, GatedReleaseState(status)).also { it.load() }
