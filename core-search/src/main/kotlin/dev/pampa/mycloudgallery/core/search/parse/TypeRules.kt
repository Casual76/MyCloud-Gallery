package dev.pampa.mycloudgallery.core.search.parse

import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchFlag
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchPresence

// Le regole di tipo, sorgente e stato (P7, §2.5): foto, video, screenshot, panorami, foto in movimento, documenti, WhatsApp,
// Quick Share, preferiti, modificate, "sul telefono", "sul NAS", "solo sul NAS".

internal class TypeRules(private val c: RuleContext) {
  private val onWords = setOf("sul", "nel", "su", "dal", "sullo", "nello")

  fun match(i: Int): RuleMatch? {
    val a = c.w(i) ?: return null
    return when (a) {
      "foto" -> when {
        c.w(i + 1) == "in" && c.w(i + 2) == "movimento" -> motion(3)
        c.w(i + 1) == "animate" -> motion(2)
        else -> type(1, SearchMediaType.PHOTO, "Foto")
      }
      "fotografia", "fotografie" -> type(1, SearchMediaType.PHOTO, "Foto")
      "motion" -> if (c.w(i + 1) == "photo") motion(2) else null
      "video", "filmato", "filmati" -> type(1, SearchMediaType.VIDEO, "Video")
      "screenshot", "screenshots", "schermata", "schermate" -> type(1, SearchMediaType.SCREENSHOT, "Screenshot")
      "panorami", "panoramica", "panoramiche" -> type(1, SearchMediaType.PANORAMA, "Panorami")
      "documenti", "documento" -> type(1, SearchMediaType.DOCUMENT, "Documenti")
      "whatsapp" -> RuleMatch(1, SearchChip.Source(MediaSource.WHATSAPP, "WhatsApp"))
      "quickshare" -> RuleMatch(1, SearchChip.Source(MediaSource.QUICK_SHARE, "Quick Share"))
      "quick" -> if (c.w(i + 1) == "share") RuleMatch(2, SearchChip.Source(MediaSource.QUICK_SHARE, "Quick Share")) else null
      "preferiti", "preferite", "preferito", "preferita" -> RuleMatch(1, SearchChip.Flag(SearchFlag.FAVORITE, "Preferiti"))
      "modificate", "modificati", "modificata", "modificato" -> RuleMatch(1, SearchChip.Flag(SearchFlag.EDITED, "Modificate"))
      "solo", "soltanto" -> if (c.w(i + 1) in onWords && c.w(i + 2) == "nas") {
        RuleMatch(3, SearchChip.Presence(SearchPresence.NAS_ONLY, "Solo sul NAS"))
      } else null
      in onWords -> when (c.w(i + 1)) {
        "telefono" -> RuleMatch(2, SearchChip.Presence(SearchPresence.ON_PHONE, "Sul telefono"))
        "nas" -> RuleMatch(2, SearchChip.Presence(SearchPresence.ON_NAS, "Sul NAS"))
        else -> null
      }
      else -> null
    }
  }

  private fun type(len: Int, type: SearchMediaType, label: String) = RuleMatch(len, SearchChip.MediaType(type, label))

  private fun motion(len: Int) = type(len, SearchMediaType.MOTION_PHOTO, "Foto in movimento")
}
