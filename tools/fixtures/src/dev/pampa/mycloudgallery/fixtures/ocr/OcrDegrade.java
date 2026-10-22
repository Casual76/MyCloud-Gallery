package dev.pampa.mycloudgallery.fixtures.ocr;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Le degradazioni del set OCR (P4: "le fixture degradate sono di P9: sfocatura, rumore, JPEG, prospettiva"), tutte deterministiche
 * (il rumore usa un seme): ruotare, sfocare, aggiungere rumore, comprimere con un JPEG povero, deformare in prospettiva.
 */
final class OcrDegrade {

  private OcrDegrade() {}

  /** I tipi di degradazione; "pulita" non fa niente, "mista" li combina in dosi leggere. */
  enum Kind {
    PULITA("pulita"),
    RUOTATA("ruotata"),
    SFOCATA("sfocata"),
    RUMORE("rumore"),
    JPEG("jpeg"),
    PROSPETTIVA("prospettiva"),
    MISTA("mista");

    final String code;

    Kind(String code) {
      this.code = code;
    }
  }

  // Le dosi (in pixel dell'immagine, a qualunque larghezza: la sfocatura e il rumore pesano di piu' sul testo piccolo, ed e' il punto).
  static final double ROTATION_DEGREES = 8.0;
  static final double BLUR_SIGMA = 1.2;
  static final double NOISE_SIGMA = 16.0;
  static final float JPEG_QUALITY_POOR = 0.30f;
  static final double PERSPECTIVE_SHRINK = 0.12;

  /** L'immagine degradata come JPEG, con le dosi di [kind]; [background] riempie gli angoli scoperti dalla rotazione e dalla prospettiva. */
  static byte[] apply(BufferedImage source, Kind kind, Color background, long seed, float defaultQuality) throws IOException {
    BufferedImage image = source;
    float quality = defaultQuality;
    switch (kind) {
      case PULITA:
        break;
      case RUOTATA:
        image = rotate(image, ROTATION_DEGREES, background);
        break;
      case SFOCATA:
        image = blur(image, BLUR_SIGMA);
        break;
      case RUMORE:
        image = noise(image, NOISE_SIGMA, seed);
        break;
      case JPEG:
        quality = JPEG_QUALITY_POOR;
        break;
      case PROSPETTIVA:
        image = perspective(image, PERSPECTIVE_SHRINK, background);
        break;
      case MISTA:
        image = rotate(image, -4.0, background);
        image = blur(image, 0.8);
        image = noise(image, 8.0, seed);
        quality = 0.45f;
        break;
      default:
        throw new IllegalStateException();
    }
    return jpeg(image, quality);
  }

  /** Il JPEG di [image] con la qualita' [quality] (0..1). */
  static byte[] jpeg(BufferedImage image, float quality) throws IOException {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) throw new IOException("Nessun codificatore JPEG nel JDK.");
    ImageWriter writer = writers.next();
    ImageWriteParam param = writer.getDefaultWriteParam();
    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    param.setCompressionQuality(quality);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
      writer.setOutput(stream);
      writer.write(null, new IIOImage(image, null, null), param);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
  }

  static BufferedImage rotate(BufferedImage source, double degrees, Color background) {
    int w = source.getWidth();
    int h = source.getHeight();
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g.setColor(background);
      g.fillRect(0, 0, w, h);
      g.drawImage(source, AffineTransform.getRotateInstance(Math.toRadians(degrees), w / 2.0, h / 2.0), null);
    } finally {
      g.dispose();
    }
    return out;
  }

  /** Sfocatura gaussiana separabile (raggio 3 sigma, bordi replicati). */
  static BufferedImage blur(BufferedImage source, double sigma) {
    int radius = Math.max(1, (int) Math.ceil(sigma * 3));
    float[] kernel = new float[2 * radius + 1];
    float sum = 0;
    for (int i = -radius; i <= radius; i++) {
      kernel[i + radius] = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
      sum += kernel[i + radius];
    }
    for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;
    int w = source.getWidth();
    int h = source.getHeight();
    int[] pixels = source.getRGB(0, 0, w, h, null, 0, w);
    int[] horizontal = new int[pixels.length];
    convolve(pixels, horizontal, w, h, kernel, radius, true);
    int[] vertical = new int[pixels.length];
    convolve(horizontal, vertical, w, h, kernel, radius, false);
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    out.setRGB(0, 0, w, h, vertical, 0, w);
    return out;
  }

  private static void convolve(int[] in, int[] out, int w, int h, float[] kernel, int radius, boolean horizontal) {
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        float r = 0, gr = 0, b = 0;
        for (int k = -radius; k <= radius; k++) {
          int sx = horizontal ? Math.min(w - 1, Math.max(0, x + k)) : x;
          int sy = horizontal ? y : Math.min(h - 1, Math.max(0, y + k));
          int p = in[sy * w + sx];
          float weight = kernel[k + radius];
          r += ((p >> 16) & 0xFF) * weight;
          gr += ((p >> 8) & 0xFF) * weight;
          b += (p & 0xFF) * weight;
        }
        out[y * w + x] = (clamp(r) << 16) | (clamp(gr) << 8) | clamp(b);
      }
    }
  }

  /** Rumore gaussiano sulla luminosita' (lo stesso scarto su R, G e B: grana, non colore). */
  static BufferedImage noise(BufferedImage source, double sigma, long seed) {
    Random random = new Random(seed ^ 0x5DEECE66DL);
    int w = source.getWidth();
    int h = source.getHeight();
    int[] pixels = source.getRGB(0, 0, w, h, null, 0, w);
    for (int i = 0; i < pixels.length; i++) {
      int delta = (int) Math.round(random.nextGaussian() * sigma);
      int p = pixels[i];
      pixels[i] = (clamp(((p >> 16) & 0xFF) + delta) << 16) | (clamp(((p >> 8) & 0xFF) + delta) << 8) | clamp((p & 0xFF) + delta);
    }
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    out.setRGB(0, 0, w, h, pixels, 0, w);
    return out;
  }

  /**
   * Prospettiva: il bordo alto si accorcia di {@code shrink} per lato (il foglio fotografato dal basso). Mappa inversa con
   * interpolazione bilineare: il punto (u, v) dell'uscita viene dalla riga v dell'originale, allargata verso il basso.
   */
  static BufferedImage perspective(BufferedImage source, double shrink, Color background) {
    int w = source.getWidth();
    int h = source.getHeight();
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    int bg = background.getRGB() & 0xFFFFFF;
    for (int y = 0; y < h; y++) {
      double t = y / (double) (h - 1);
      // Larghezza dell'originale visibile in questa riga: ridotta in alto, intera in basso.
      double rowScale = 1.0 - 2 * shrink * (1.0 - t);
      for (int x = 0; x < w; x++) {
        double u = (x - w / 2.0) / (w / 2.0);
        double sx = w / 2.0 + u * (w / 2.0) / rowScale;
        if (sx < 0 || sx > w - 1) {
          out.setRGB(x, y, bg);
          continue;
        }
        out.setRGB(x, y, bilinear(source, sx, y));
      }
    }
    return out;
  }

  private static int bilinear(BufferedImage image, double x, double y) {
    int x0 = (int) Math.floor(x);
    int y0 = (int) Math.floor(y);
    int x1 = Math.min(image.getWidth() - 1, x0 + 1);
    int y1 = Math.min(image.getHeight() - 1, y0 + 1);
    double fx = x - x0;
    double fy = y - y0;
    int p00 = image.getRGB(x0, y0);
    int p10 = image.getRGB(x1, y0);
    int p01 = image.getRGB(x0, y1);
    int p11 = image.getRGB(x1, y1);
    int rgb = 0;
    for (int shift = 16; shift >= 0; shift -= 8) {
      double top = ((p00 >> shift) & 0xFF) * (1 - fx) + ((p10 >> shift) & 0xFF) * fx;
      double bottom = ((p01 >> shift) & 0xFF) * (1 - fx) + ((p11 >> shift) & 0xFF) * fx;
      rgb |= clamp(top * (1 - fy) + bottom * fy) << shift;
    }
    return rgb;
  }

  private static int clamp(float v) {
    return Math.max(0, Math.min(255, Math.round(v)));
  }

  private static int clamp(double v) {
    return Math.max(0, Math.min(255, (int) Math.round(v)));
  }
}
