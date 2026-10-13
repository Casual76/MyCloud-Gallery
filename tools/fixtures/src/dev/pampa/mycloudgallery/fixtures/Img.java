package dev.pampa.mycloudgallery.fixtures;

import java.awt.image.BufferedImage;

/**
 * Un'immagine di prova. Avvolge {@code BufferedImage} perche' il compilatore Kotlin dei test Android non vede java.awt:
 * le firme pubbliche delle fixture parlano di {@code Img}, e solo questo file e {@link ImageFixtures} conoscono AWT.
 */
public final class Img {

  final BufferedImage image;

  Img(BufferedImage image) {
    this.image = image;
  }

  public static Img create(int width, int height) {
    return new Img(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
  }

  public int width() {
    return image.getWidth();
  }

  public int height() {
    return image.getHeight();
  }

  /** Il colore del pixel come 0xRRGGBB. */
  public int rgb(int x, int y) {
    return image.getRGB(x, y) & 0xFFFFFF;
  }

  public void setRgb(int x, int y, int rgb) {
    image.setRGB(x, y, rgb);
  }
}
