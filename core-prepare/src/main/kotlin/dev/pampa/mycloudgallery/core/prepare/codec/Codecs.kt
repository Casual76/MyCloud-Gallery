package dev.pampa.mycloudgallery.core.prepare.codec

import android.media.MediaCodecList
import android.os.Build
import java.util.Locale

// I decoder di QUESTO dispositivo (D14, E-M13): quando un media non si decodifica, la preparazione distingue "il file e'
// rovinato" da "qui manca il decoder" (APV, HEVC tolto dal produttore, 8K...). Nel secondo caso il fallimento si
// pubblica con la famiglia del decoder e un altro Preparatore ci prova una volta.

/** Se il dispositivo ha un decoder per un tipo MIME (`video/hevc`, `video/apv`...). Si interroga solo quando una decodifica fallisce. */
fun interface CodecAvailability {
  fun hasDecoder(mime: String): Boolean
}

/** `MediaCodecList` (decoder hardware e software), letto una volta: l'elenco dei tipi decodificabili non cambia mentre l'app gira. */
object PlatformCodecs : CodecAvailability {
  private val decodable: Set<String> by lazy {
    try {
      MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { !it.isEncoder }
        .flatMap { it.supportedTypes.toList() }
        .mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    } catch (_: RuntimeException) {
      emptySet()
    }
  }

  /**
   * Un elenco vuoto (l'enumerazione e' fallita: nessun telefono vero non ha decoder) vale "non lo so", cioe' "si': lascia
   * provare al sistema". Dire "manca" per sbaglio pubblicherebbe un `fail` per ogni video della famiglia; dire "c'e'" costa
   * al piu' un tentativo in piu' prima di `DECODE_FAILED`.
   */
  override fun hasDecoder(mime: String): Boolean = decodable.isEmpty() || mime.lowercase(Locale.ROOT) in decodable
}

/**
 * La "famiglia" del decoder di un dispositivo, scritta nel campo `fail.dec` del record (`^[a-z0-9][a-z0-9_.-]{0,31}$`): il
 * produttore del SoC (`c2_qualcomm`, `c2_samsung`, `c2_mediatek`). Due telefoni con lo stesso SoC hanno gli stessi decoder:
 * ritentare con la stessa famiglia sarebbe lavoro perso (D14).
 */
object DecoderFamily {
  fun of(socManufacturer: String?, hardware: String?): String {
    val raw = socManufacturer?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
      ?: hardware?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
      ?: "generic"
    val cleaned = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
    return "c2_" + cleaned.ifEmpty { "generic" }
  }

  /** La famiglia di questo dispositivo. */
  fun ofDevice(): String = of(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null, Build.HARDWARE)
}

/** Dal marchio del sample entry di un MP4 al tipo MIME del decoder che serve (null = non lo sappiamo: si lascia provare al sistema). */
object VideoCodecs {
  private val ENTRIES: Map<String, List<String>> = mapOf(
    "avc1" to listOf("video/avc"),
    "avc3" to listOf("video/avc"),
    "hvc1" to listOf("video/hevc"),
    "hev1" to listOf("video/hevc"),
    // Dolby Vision profilo 8 e' compatibile con HEVC: va bene un decoder dell'uno o dell'altro.
    "dvh1" to listOf("video/dolby-vision", "video/hevc"),
    "dvhe" to listOf("video/dolby-vision", "video/hevc"),
    "av01" to listOf("video/av01"),
    "vp09" to listOf("video/x-vnd.on2.vp9"),
    "vp08" to listOf("video/x-vnd.on2.vp8"),
    "mp4v" to listOf("video/mp4v-es"),
    "s263" to listOf("video/3gpp"),
    "apv1" to listOf("video/apv"),
  )

  /** I tipi MIME accettabili per il marchio; vuoto se il marchio non e' tra quelli noti. */
  fun mimesFor(sampleEntry: String): List<String> = ENTRIES[sampleEntry.lowercase(Locale.ROOT)].orEmpty()

  /** Vero se per questo marchio sappiamo che serve un decoder e il dispositivo non ce l'ha. */
  fun isMissing(sampleEntry: String, codecs: CodecAvailability): Boolean {
    val mimes = mimesFor(sampleEntry)
    return mimes.isNotEmpty() && mimes.none { codecs.hasDecoder(it) }
  }
}
