package dev.pampa.mycloudgallery.fixtures.ocr;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Il RESOCONTO dell'errore per carattere (§5.3, P9): dal manifesto (il testo disegnato) e dal testo letto da un lettore, l'errore per
 * carattere e la ricerca per parola per altezza e larghezza (immagini pulite), per degradazione e per genere. Solo numeri e nomi di
 * condizioni: nessun testo. Scrive {@code resoconto-ocr.md} e {@code resoconto-ocr.tsv} accanto al manifesto.
 */
final class OcrReport {

  private OcrReport() {}

  /** Una riga del manifesto. */
  static final class Row {
    final String id;
    final String genre;
    final int width;
    final int px;
    final String kind;
    final String truth;

    Row(String id, String genre, int width, int px, String kind, String truth) {
      this.id = id;
      this.genre = genre;
      this.width = width;
      this.px = px;
      this.kind = kind;
      this.truth = truth;
    }
  }

  static List<Row> readManifest(File manifest) throws IOException {
    List<Row> rows = new ArrayList<>();
    List<String> lines = Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8);
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).isEmpty()) continue;
      String[] c = lines.get(i).split("\t", -1);
      if (c.length < 7) throw new IOException("Riga del manifesto non valida: " + (i + 1));
      rows.add(new Row(c[0], c[2], Integer.parseInt(c[3]), Integer.parseInt(c[4]), c[5], OcrSetGenerator.Tsv.unescape(c[6])));
    }
    return rows;
  }

  /** id -> testo letto. Un'immagine senza riga e' "non letta" (testo vuoto: conta come tutto sbagliato, e il resoconto la elenca). */
  static Map<String, String> readRead(File file) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
      if (line.isEmpty()) continue;
      int tab = line.indexOf('\t');
      if (tab < 0) out.put(line, "");
      else out.put(line.substring(0, tab), OcrSetGenerator.Tsv.unescape(line.substring(tab + 1)));
    }
    return out;
  }

  /** Scrive il resoconto e ne restituisce il testo. [label] dice chi ha letto (ML Kit sul dispositivo, o il lettore finto). */
  static String write(File dir, File readFile, String label) throws IOException {
    List<Row> rows = readManifest(new File(dir, "manifest.tsv"));
    Map<String, String> read = readRead(readFile);
    StringBuilder md = new StringBuilder();
    StringBuilder tsv = new StringBuilder("gruppo\tchiave\timmagini\terrore_carattere\tricerca_parola\n");
    md.append("# Resoconto OCR sul set sintetico\n\n");
    md.append("Lettore: ").append(label).append("\n\n");
    int missing = 0;
    Map<String, OcrErrorRate.Score> byKey = new LinkedHashMap<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Row r : rows) {
      String text = read.get(r.id);
      if (text == null) {
        missing++;
        text = "";
      }
      OcrErrorRate.Score score = OcrErrorRate.score(r.truth, text);
      add(byKey, counts, "totale|tutte", score);
      add(byKey, counts, "genere|" + r.genre, score);
      if (r.px == 12 || r.px == 20 || r.px == 32) add(byKey, counts, "degradazione|" + r.width + " " + r.kind, score);
      if (r.kind.equals("pulita")) add(byKey, counts, "pulita|" + r.width + " " + pad(r.px), score);
    }
    md.append("Immagini nel manifesto: ").append(rows.size()).append("; senza lettura: ").append(missing).append(".\n\n");
    section(md, tsv, byKey, counts, "totale", "Totale");
    section(md, tsv, byKey, counts, "pulita", "Immagini pulite: larghezza e altezza del carattere (px)");
    section(md, tsv, byKey, counts, "degradazione", "Per degradazione (altezze 12, 20 e 32 px insieme; la pulita e' a parte)");
    section(md, tsv, byKey, counts, "genere", "Per genere");
    Files.write(new File(dir, "resoconto-ocr.md").toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
    Files.write(new File(dir, "resoconto-ocr.tsv").toPath(), tsv.toString().getBytes(StandardCharsets.UTF_8));
    return md.toString();
  }

  private static void add(Map<String, OcrErrorRate.Score> byKey, Map<String, Integer> counts, String key, OcrErrorRate.Score score) {
    byKey.merge(key, score, OcrErrorRate.Score::plus);
    counts.merge(key, 1, Integer::sum);
  }

  private static void section(StringBuilder md, StringBuilder tsv, Map<String, OcrErrorRate.Score> byKey, Map<String, Integer> counts, String group, String title) {
    md.append("## ").append(title).append("\n\n| condizione | immagini | errore per carattere | parole ritrovate |\n|---|---|---|---|\n");
    List<String> keys = new ArrayList<>();
    for (String k : byKey.keySet()) if (k.startsWith(group + "|")) keys.add(k);
    java.util.Collections.sort(keys);
    for (String k : keys) {
      String name = k.substring(group.length() + 1).replaceFirst("^(\\d+) 0*(\\d)", "$1 px $2");
      OcrErrorRate.Score s = byKey.get(k);
      md.append("| ").append(name).append(" | ").append(counts.get(k)).append(" | ").append(percent(s.characterErrorRate()))
          .append(" | ").append(percent(s.wordRecall())).append(" |\n");
      tsv.append(group).append('\t').append(name).append('\t').append(counts.get(k)).append('\t')
          .append(number(s.characterErrorRate())).append('\t').append(number(s.wordRecall())).append('\n');
    }
    md.append('\n');
  }

  private static String pad(int px) {
    return px < 10 ? "0" + px : String.valueOf(px);
  }

  private static String percent(double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ITALY, "%.1f%%", v * 100);
  }

  private static String number(double v) {
    return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.4f", v);
  }
}
