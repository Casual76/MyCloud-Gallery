package dev.pampa.mycloudgallery.fixtures.ocr;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Disegna una scena del set OCR: una pagina {@code width} x {@code width*4/3} con il testo di un genere, alta {@code fontPx} pixel
 * (l'altezza del carattere come in {@code OcrFixtures} di core-ai: la dimensione dell'em in pixel dell'immagine che si legge).
 * Restituisce l'immagine e il **testo davvero disegnato** (le righe che ci stanno, spezzate per parole): e' la verita' del manifesto.
 */
final class OcrRender {

  private OcrRender() {}

  /** Una scena disegnata: l'immagine e le righe disegnate, nell'ordine di lettura. */
  static final class Scene {
    final BufferedImage image;
    final List<String> lines;
    final Color background;

    Scene(BufferedImage image, List<String> lines, Color background) {
      this.image = image;
      this.lines = lines;
      this.background = background;
    }
  }

  static Scene render(OcrTexts.Genre genre, int width, int fontPx, long seed) {
    int height = width * 4 / 3;
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      switch (genre) {
        case SCONTRINO:
          return receipt(g, image, width, height, fontPx, seed);
        case CHAT:
          return chat(g, image, width, height, fontPx, seed);
        case SCHERMATA:
          return screen(g, image, width, height, fontPx, seed);
        case CARTELLO:
          return sign(g, image, width, height, fontPx, seed);
        default:
          throw new IllegalStateException();
      }
    } finally {
      g.dispose();
    }
  }

  // --- Scontrino: striscia di carta chiara su un tavolo grigio, carattere a spaziatura fissa ----------------------------------

  private static Scene receipt(Graphics2D g, BufferedImage image, int width, int height, int px, long seed) {
    Color table = new Color(0x8A8F94);
    Color paper = new Color(0xFBFAF4);
    fill(g, table, width, height);
    int paperW = (int) (width * 0.74);
    int left = (width - paperW) / 2;
    g.setColor(paper);
    g.fillRect(left, 0, paperW, height);
    Font font = new Font(Font.MONOSPACED, Font.PLAIN, px);
    g.setFont(font);
    g.setColor(new Color(0x1B1B1B));
    int pad = Math.max(6, px / 2);
    List<String> drawn = new ArrayList<>();
    int lineH = Math.round(px * 1.35f);
    int y = pad + px;
    int budget = Math.max(3, (height - 2 * pad) / lineH);
    for (String line : OcrTexts.lines(OcrTexts.Genre.SCONTRINO, seed, budget)) {
      for (String piece : wrap(line, g.getFontMetrics(), paperW - 2 * pad)) {
        if (y > height - pad) return new Scene(image, drawn, paper);
        g.drawString(piece, left + pad, y);
        drawn.add(piece);
        y += lineH;
      }
    }
    return new Scene(image, drawn, paper);
  }

  // --- Chat: fumetti a destra e a sinistra, chiari e colorati --------------------------------------------------------------

  private static Scene chat(Graphics2D g, BufferedImage image, int width, int height, int px, long seed) {
    Color back = new Color(0xECE5DD);
    fill(g, back, width, height);
    Font font = new Font(Font.SANS_SERIF, Font.PLAIN, px);
    g.setFont(font);
    FontMetrics fm = g.getFontMetrics();
    int margin = Math.max(12, width / 14);
    int bubbleMax = (int) (width * 0.68);
    int pad = Math.max(5, px / 2);
    int y = margin;
    List<String> drawn = new ArrayList<>();
    int budget = Math.max(4, height / Math.max(1, Math.round(px * 2.6f)));
    boolean mine = false;
    for (String message : OcrTexts.lines(OcrTexts.Genre.CHAT, seed, budget)) {
      List<String> pieces = wrap(message, fm, bubbleMax - 2 * pad);
      int w = 0;
      for (String p : pieces) w = Math.max(w, fm.stringWidth(p));
      int lineH = Math.round(px * 1.25f);
      int h = pieces.size() * lineH + 2 * pad;
      if (y + h > height - margin) break;
      int x = mine ? width - margin - w - 2 * pad : margin;
      g.setColor(mine ? new Color(0xDCF8C6) : Color.WHITE);
      g.fillRoundRect(x, y, w + 2 * pad, h, pad * 2, pad * 2);
      g.setColor(new Color(0x111111));
      int ty = y + pad + px;
      for (String p : pieces) {
        g.drawString(p, x + pad, ty - px / 6);
        drawn.add(p);
        ty += lineH;
      }
      y += h + Math.max(4, px / 2);
      mine = !mine;
    }
    return new Scene(image, drawn, back);
  }

  // --- Schermata: barra del titolo e righe di un elenco (tema scuro), titolo e dettaglio -----------------------------------

  private static Scene screen(Graphics2D g, BufferedImage image, int width, int height, int px, long seed) {
    Color back = new Color(0x121316);
    fill(g, back, width, height);
    g.setColor(new Color(0x1F2125));
    int bar = Math.round(px * 3.2f);
    g.fillRect(0, 0, width, bar);
    int margin = Math.max(10, width / 24);
    List<String> drawn = new ArrayList<>();
    int budget = Math.max(5, height / Math.max(1, Math.round(px * 1.6f)));
    List<String> source = OcrTexts.lines(OcrTexts.Genre.SCHERMATA, seed, budget);
    Font title = new Font(Font.SANS_SERIF, Font.BOLD, px);
    Font detail = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, Math.round(px * 0.85f)));
    int y = 0;
    for (int i = 0; i < source.size(); i++) {
      boolean isTitle = i == 0 || i % 2 == 1;
      String text = source.get(i);
      Font font = isTitle ? title : detail;
      g.setFont(font);
      FontMetrics fm = g.getFontMetrics();
      List<String> pieces = wrap(text, fm, width - 2 * margin);
      if (i == 0) {
        y = (bar + px) / 2;
      } else if (isTitle) {
        y += Math.round(px * 1.5f);
      } else {
        y += Math.round(px * 1.05f);
      }
      if (y > height - margin) break;
      g.setColor(isTitle ? new Color(0xF2F3F5) : new Color(0x9AA0A6));
      for (String p : pieces) {
        g.drawString(p, margin, y);
        drawn.add(p);
        y += fm.getHeight();
      }
      y -= fm.getHeight();
    }
    return new Scene(image, drawn, back);
  }

  // --- Cartello: testo grande centrato, bianco su verde o blu --------------------------------------------------------------

  private static Scene sign(Graphics2D g, BufferedImage image, int width, int height, int px, long seed) {
    Color back = (seed % 2 == 0) ? new Color(0x0B6B3A) : new Color(0x0A4A8F);
    fill(g, back, width, height);
    g.setColor(new Color(0xF4F4F0));
    g.drawRect(width / 40, width / 40, width - width / 20, height - width / 20);
    Font font = new Font(Font.SANS_SERIF, Font.BOLD, px);
    g.setFont(font);
    FontMetrics fm = g.getFontMetrics();
    int margin = Math.max(12, width / 12);
    int lineH = Math.round(px * 1.6f);
    int budget = Math.max(2, Math.min(6, (height - 2 * margin) / lineH));
    List<String> drawn = new ArrayList<>();
    int y = margin + px;
    for (String sign : OcrTexts.lines(OcrTexts.Genre.CARTELLO, seed, budget)) {
      for (String p : wrap(sign, fm, width - 2 * margin)) {
        if (y > height - margin) return new Scene(image, drawn, back);
        g.drawString(p, (width - fm.stringWidth(p)) / 2, y);
        drawn.add(p);
        y += lineH;
      }
    }
    return new Scene(image, drawn, back);
  }

  private static void fill(Graphics2D g, Color color, int width, int height) {
    g.setColor(color);
    g.fillRect(0, 0, width, height);
  }

  /** [text] spezzato per parole in righe larghe al piu' [maxWidth] (una parola piu' larga di una riga resta intera). */
  static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
    List<String> out = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (String word : text.split(" +")) {
      if (word.isEmpty()) continue;
      String candidate = line.length() == 0 ? word : line + " " + word;
      if (line.length() > 0 && fm.stringWidth(candidate) > maxWidth) {
        out.add(line.toString());
        line = new StringBuilder(word);
      } else {
        line = new StringBuilder(candidate);
      }
    }
    if (line.length() > 0) out.add(line.toString());
    return out;
  }
}
