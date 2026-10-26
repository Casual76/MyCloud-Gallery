package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.rules.WaitReason
import java.io.File
import java.io.IOException

/**
 * L'ultimo stato del calcolo AI che questo dispositivo ha avuto, in un file di `noBackupFilesDir` (IX-2 della verifica funzionale della Fase 6).
 * Lo stato vive in memoria e nasce `Off`: un'app riaperta dopo la notte (il calcolo gira di solito in un altro processo, e un'attesa delle
 * regole prima di partire non passava mai dal motore) diceva a un Indicizzatore "Questo dispositivo non analizza le foto". Adesso la pagina
 * parte dall'ultimo stato visto: "Tutto analizzato", "In attesa della fascia", "Telefono caldo", "Libera spazio"...
 *
 * **Solo codici**: `idle`, `model_missing`, `waiting <motivo>`, `problem <codice>`, `buffer <n>` (D40). Lo stato `Running` e `Off` non si ricordano
 * (vale il motore, o il ruolo). Un file illeggibile vale come nessuno stato.
 */
class AiStatusMemory(private val file: File?) {
  private val lock = Any()
  private var last: String? = null

  fun load(): AiStatus? = synchronized(lock) {
    val source = file ?: return null
    val line = try {
      if (source.isFile && source.length() < 256) source.readText(Charsets.UTF_8).trim() else return null
    } catch (e: IOException) {
      return null
    }
    last = line
    parse(line)
  }

  /** Ricorda [status] (se e' uno stato da ricordare). Scrive solo se cambia. */
  fun save(status: AiStatus) = synchronized(lock) {
    val target = file ?: return
    val line = encode(status) ?: return
    if (line == last) return
    last = line
    try {
      target.parentFile?.mkdirs()
      val tmp = File(target.parentFile, target.name + ".tmp")
      tmp.writeText(line, Charsets.UTF_8)
      if (!tmp.renameTo(target)) {
        target.delete()
        tmp.renameTo(target)
      }
    } catch (e: IOException) {
      // Solo una comodita' della pagina: resta il valore in memoria.
    }
  }

  fun clear() = synchronized(lock) {
    last = null
    file?.delete()
  }

  companion object {
    const val FILE = "ai-status"

    internal fun encode(status: AiStatus): String? = when (status) {
      AiStatus.Off, is AiStatus.Running -> null
      AiStatus.ModelMissing -> "model_missing"
      is AiStatus.Idle -> "idle"
      is AiStatus.Waiting -> "waiting ${status.reason.name}"
      is AiStatus.BufferOnlyDone -> "buffer ${status.items}"
      is AiStatus.Problem -> "problem ${status.code.id}"
    }

    internal fun parse(line: String): AiStatus? {
      val parts = line.split(' ')
      return when (parts[0]) {
        "idle" -> AiStatus.Idle(null)
        "model_missing" -> AiStatus.ModelMissing
        "waiting" -> AiWaitReason.entries.firstOrNull { it.name == parts.getOrNull(1) }?.let { AiStatus.Waiting(it) }
        "buffer" -> parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 }?.let { AiStatus.BufferOnlyDone(it) }
        "problem" -> parts.getOrNull(1)?.takeIf { CODE.matches(it) }?.let { AiStatus.Problem(DiagCode(it)) }
        else -> null
      }
    }

    private val CODE = Regex("^[A-Z0-9_]{1,64}$")

    /** Perche' un'attesa delle regole in parole della pagina: il caldo e' "in pausa", la rete "in attesa di una rete", il resto "le regole". */
    fun waitReasonOf(reason: WaitReason): AiWaitReason = when (reason) {
      is WaitReason.Thermal -> AiWaitReason.HOT
      is WaitReason.NetworkNotAllowed, WaitReason.Offline, WaitReason.Roaming, WaitReason.HeavyNeedsHomeWifi, WaitReason.NetworkNotTrusted ->
        AiWaitReason.NO_NETWORK
      WaitReason.NasUnreachable, is WaitReason.Frozen -> AiWaitReason.NAS_UNREACHABLE
      else -> AiWaitReason.RULES
    }
  }
}
