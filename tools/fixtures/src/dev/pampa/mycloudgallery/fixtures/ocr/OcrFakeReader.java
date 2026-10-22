package dev.pampa.mycloudgallery.fixtures.ocr;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Il LETTORE FINTO del set OCR: legge il testo disegnato **con errori inventati** che crescono al calare dell'altezza del carattere e
 * con la degradazione. Serve solo a provare il percorso manifesto -> testo letto -> resoconto sulla JVM (il resoconto stampa "FINTO"):
 * non e' ne' ML Kit ne' una stima di ML Kit. La misura vera e' sul dispositivo.
 */
final class OcrFakeReader {

  private OcrFakeReader() {}

  private static final String CONFUSABLE = "eoacilnrstu";
  private static final String SWAP = "coecnaliulsr";

  /** Scrive in [out] una lettura finta di ogni riga del manifesto [manifest]. */
  static void write(File manifest, File out) throws IOException {
    List<String> lines = new ArrayList<>();
    for (OcrReport.Row row : OcrReport.readManifest(manifest)) {
      lines.add(row.id + "\t" + OcrSetGenerator.Tsv.escape(read(row)));
    }
    Files.write(out.toPath(), lines, StandardCharsets.UTF_8);
  }

  /** La lettura finta di una riga: stesso seme, stessa lettura. */
  static String read(OcrReport.Row row) {
    Random random = new Random(row.id.hashCode());
    double p = errorProbability(row.px, row.kind);
    StringBuilder out = new StringBuilder();
    for (String line : row.truth.split("\n")) {
      if (row.px <= 10 && !row.kind.equals("pulita") && random.nextDouble() < 0.4) continue;
      for (char c : line.toCharArray()) {
        double roll = random.nextDouble();
        if (c == ' ' || roll >= p) {
          out.append(c);
        } else if (roll < p / 3) {
          int at = CONFUSABLE.indexOf(Character.toLowerCase(c));
          out.append(at >= 0 ? SWAP.charAt(at) : '?');
        } else if (roll < 2 * p / 3) {
          continue;
        } else {
          out.append(c).append('.');
        }
      }
      out.append('\n');
    }
    return out.toString();
  }

  /** La probabilita' di sbagliare un carattere: poco sopra i 16 px, tanto sotto i 12, di piu' con la degradazione. */
  static double errorProbability(int px, String kind) {
    double small = Math.pow(Math.max(0, 16 - px) / 16.0, 2) * 0.35;
    double base = 0.004 + small;
    double scale = 12.0 / Math.max(px, 8);
    switch (kind) {
      case "ruotata":
        return base + 0.004;
      case "sfocata":
        return base + 0.02 * scale;
      case "rumore":
        return base + 0.015 * scale;
      case "jpeg":
        return base + 0.01 * scale;
      case "prospettiva":
        return base + 0.006;
      case "mista":
        return base + 0.015 * scale;
      default:
        return base;
    }
  }
}
