package dev.pampa.mycloudgallery.core.prepare.testing

import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.IndexPublisher
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnits
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.prepare.contract.DecodedFacts
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepInputs
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepStop
import dev.pampa.mycloudgallery.core.prepare.contract.PreparationRun
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.prepare.contract.RunResult
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.PrepFailure
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.security.MessageDigest
import kotlin.time.Duration

// I fake di core-prepare (P0): in src/main per i test di P6, P11 e di chi sta a valle. Mai nella release.

/**
 * Un preparatore finto e **deterministico**: per le foto legge l'intero contenuto (per l'hash completo, come quello vero
 * fino a 64 MiB), per i video **solo** testa e coda (mai il file intero, E-M7), e produce "immagini" che iniziano con le
 * firme vere (WebP `RIFF....WEBP`, JPEG `FF D8 FF`) cosi' i controlli di formato di P3 e P4 le riconoscono. Non decodifica
 * nulla: le dimensioni sono quelle chieste da [PrepSpec]. Con [failures] si programmano i fallimenti per tipo di media.
 */
class FakePreparer(
  val failures: MutableMap<MediaKind, PrepResult.Failed> = HashMap(),
) : Preparer {

  /** Byte letti in tutto (per provare "video mai scaricati interi"). */
  @Volatile var bytesRead: Long = 0
    private set

  val prepared: MutableList<PrepareSource> = mutableListOf()

  override suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult {
    failures[source.kind]?.let { return it }
    val bytes = when (source) {
      is PrepareSource.Bytes -> source.bytes
      is PrepareSource.Local -> return PrepResult.Failed(PrepFailCode.TOO_LARGE, retryable = false)
    }
    prepared += source
    val video = source.kind.isVideo
    val fullHash: Sha256?
    val seed: ByteArray
    if (video) {
      val head = bytes.read(0L until minOf(bytes.size, 65_536L))
      val tail = bytes.read(maxOf(0L, bytes.size - 65_536L) until bytes.size)
      bytesRead += head.size + tail.size
      seed = head + tail
      fullHash = null
    } else {
      val all = bytes.read(0L until bytes.size)
      bytesRead += all.size
      seed = all
      fullHash = Sha256.of(all)
    }
    val tag = MessageDigest.getInstance("SHA-256").digest(seed).copyOf(8)
    val outputs = ArrayList<PrepOutput>()
    for (cls in spec.classes) {
      when (cls) {
        SegmentClass.THUMB_S -> outputs += PrepOutput.Image(cls, webp(tag), spec.thumbS, spec.thumbS)
        SegmentClass.THUMB_M -> outputs += PrepOutput.Image(cls, webp(tag), spec.thumbM, spec.thumbM)
        SegmentClass.ANALYSIS -> outputs += PrepOutput.Image(cls, jpeg(tag), spec.analysis, spec.analysis * 3 / 4)
        SegmentClass.HQ -> outputs += PrepOutput.Image(cls, webp(tag), spec.hq, spec.hq * 3 / 4)
        SegmentClass.FRAMES -> if (video) outputs += PrepOutput.Frames(listOf(VideoFrame(0, jpeg(tag)), VideoFrame(spec.frameEverySec * 1000, jpeg(tag))))
        else -> Unit
      }
    }
    return PrepResult.Done(outputs, fullHash, DecodedFacts(spec.hq, spec.hq * 3 / 4, if (video) 60_000 else null), thumbHash = null)
  }

  private companion object {
    fun webp(tag: ByteArray): ByteArray =
      byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x10, 0, 0, 0, 0x57, 0x45, 0x42, 0x50) + tag

    fun jpeg(tag: ByteArray): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + tag
  }
}

/** Gli originali finti: per ogni chiave di contenuto il test mette un [PrepOpen]. */
class FakePrepInputs(val opens: MutableMap<String, PrepOpen> = HashMap()) : PrepInputs {
  val requests: MutableList<WorkItem> = mutableListOf()

  override suspend fun open(share: String, item: WorkItem): PrepOpen {
    requests += item
    return opens[item.contentKey.hex] ?: PrepOpen.Unavailable(PrepFailCode.NAS_UNREACHABLE)
  }
}

/**
 * Un giro di preparazione **semplice ma vero** sopra i contratti (unita' → apertura → preparazione → stage → flush →
 * finish): serve a :app e ai test d'integrazione finche' P6 non consegna `PreparationRun`. Non gestisce tempi, regole ne'
 * rinnovi a 20 minuti (rinnova una volta per unita'); il record pubblicato porta solo le classi preparate.
 *
 * @param recordFor costruisce il `MetaFile` del media preparato (in :app viene dal catalogo).
 */
class SimplePreparationRun(
  private val workUnits: WorkUnits,
  private val inputs: PrepInputs,
  private val preparer: Preparer,
  private val publisher: IndexPublisher,
  private val recordFor: (WorkItem, PrepResult.Done?, PrepResult.Failed?) -> MetaFile?,
  private val decoderFamily: String = "fake",
) : PreparationRun {

  override suspend fun run(share: String, budget: Duration, priority: UnitPriority): RunResult {
    var unitCount = 0
    var prepared = 0
    var failed = 0
    var handedOver = 0
    while (true) {
      var claim = workUnits.next(share, WorkPass.PREP, priority) ?: break
      unitCount++
      var unitPrepared = 0
      var unitFailed = 0
      for (item in claim.items) {
        val open = inputs.open(share, item)
        if (open !is PrepOpen.Ready) {
          unitFailed++
          continue
        }
        val classes = item.missing.filterTo(LinkedHashSet()) { it.isContentBlob }.ifEmpty { PrepSpec.DEFAULT_CLASSES }
        val result = try {
          preparer.prepare(open.source, PrepSpec(classes = classes))
        } finally {
          open.close()
        }
        when (result) {
          is PrepResult.Done -> {
            val blobs = result.outputs.map { output ->
              when (output) {
                is PrepOutput.Image -> BlobPut.Image(output.cls, item.contentKey, output.bytes)
                is PrepOutput.Frames -> BlobPut.Frames(item.contentKey, output.frames)
              }
            }
            val record = recordFor(item, result, null)?.copy(prepared = SegmentClass.maskOf(result.outputs.map { it.cls }))
            publisher.stage(share, listOfNotNull(record?.let { MetaChange.Put(item.path, it) }), blobs)
            unitPrepared++
          }
          is PrepResult.Failed -> {
            if (result.decoderMissing) {
              handedOver++
              recordFor(item, null, result)?.copy(failure = PrepFailure(result.code.name, result.decoderFamily ?: decoderFamily))
                ?.let { publisher.stage(share, listOf(MetaChange.Put(item.path, it)), emptyList()) }
            }
            unitFailed++
          }
        }
      }
      val renewed = workUnits.keepAlive(claim)
      if (renewed is KeepAliveResult.Renewed) claim = renewed.claim
      publisher.flush(share, FlushReason.UNIT_FINISHED)
      workUnits.finish(claim, UnitOutcome.Completed(unitPrepared, unitFailed))
      prepared += unitPrepared
      failed += unitFailed
    }
    return RunResult(unitCount, prepared, failed, handedOver, bytesRead = 0, stoppedBy = PrepStop.NO_WORK)
  }
}
