package dev.pampa.mycloudgallery.fixtures.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * Il piano del set OCR sintetico: quali immagini si generano. Ogni caso e' (genere, larghezza, altezza del carattere, degradazione,
 * seme). La larghezza e' quella dell'immagine che il lettore vede (1024 = anteprima di analisi, 2048 = HQ, come in P4) e l'altezza
 * del carattere e' in pixel di quell'immagine: da 10 a 40.
 */
final class OcrCases {

  private OcrCases() {}

  /** Le due larghezze da misurare (§5.3). */
  static final int[] WIDTHS = {1024, 2048};

  /** Le altezze del carattere delle pagine pulite (P4 aveva 8-40 px; qui da 10, come dice il §5.3). */
  static final int[] CLEAN_PX = {10, 12, 14, 16, 20, 24, 28, 32, 40};

  /** Le altezze su cui si provano le degradazioni: piccolo, medio, grande. */
  static final int[] DEGRADED_PX = {12, 20, 32};

  /** Il set ridotto per provare il generatore in pochi secondi. */
  static final int[] QUICK_PX = {12, 20, 32};

  /** Un'immagine del set. */
  static final class Case {
    final OcrTexts.Genre genre;
    final int width;
    final int px;
    final OcrDegrade.Kind kind;
    final long seed;

    Case(OcrTexts.Genre genre, int width, int px, OcrDegrade.Kind kind, long seed) {
      this.genre = genre;
      this.width = width;
      this.px = px;
      this.kind = kind;
      this.seed = seed;
    }

    /** Un nome stabile: genere, larghezza, altezza, degradazione. */
    String id() {
      return genre.code + "-" + width + "-" + px + "px-" + kind.code;
    }
  }

  /** Tutti i casi: la griglia pulita completa e le degradazioni sulle altezze scelte. */
  static List<Case> plan(boolean quick, long seed) {
    List<Case> out = new ArrayList<>();
    int[] clean = quick ? QUICK_PX : CLEAN_PX;
    for (OcrTexts.Genre genre : OcrTexts.Genre.values()) {
      for (int width : WIDTHS) {
        for (int px : clean) out.add(new Case(genre, width, px, OcrDegrade.Kind.PULITA, seed));
        for (OcrDegrade.Kind kind : OcrDegrade.Kind.values()) {
          if (kind == OcrDegrade.Kind.PULITA) continue;
          for (int px : (quick ? new int[] {20} : DEGRADED_PX)) out.add(new Case(genre, width, px, kind, seed));
        }
      }
    }
    return out;
  }
}
