import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;

/**
 * Renders the Play Store feature graphic (1024 x 500, opaque RGB PNG).
 *
 * Run from anywhere inside the repo:  java store-assets/tools/FeatureGraphic.java
 * Writes store-assets/feature-graphic.png.
 */
public class FeatureGraphic {
    static final int W = 1024, H = 500;

    static final Color BG = hex(0x17122B), SURFACE = hex(0x211B3A), SURFACE_DIM = hex(0x1C1633),
            BORDER = hex(0x2E2750), BORDER_STRONG = hex(0x3A3161), TEXT = hex(0xF3F0FA),
            MUTED = hex(0xA79FC4), DIM = hex(0x6E6690), SAFFRON = hex(0xF5A524),
            SAFFRON_BRIGHT = hex(0xFFD166), AGAIN = hex(0xE0705F), HARD = hex(0xE8A33D),
            GOOD = hex(0x58C08C), EASY = hex(0x5BA9E8), PAPER = hex(0xF6F1E4), INK = hex(0x2C2550);

    static Font regular, medium, semibold, bold;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Run from inside the abhyas-android repo");
        Path fonts = root.resolve("app/src/main/res/font");
        regular = font(fonts.resolve("space_grotesk_regular.ttf"));
        medium = font(fonts.resolve("space_grotesk_medium.ttf"));
        semibold = font(fonts.resolve("space_grotesk_semibold.ttf"));
        bold = font(fonts.resolve("space_grotesk_bold.ttf"));
        BufferedImage icon = ImageIO.read(root.resolve("store-assets/play-icon-512.png").toFile());

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);

        background(g);
        brand(g, icon);
        notebook(g);
        flashcard(g);
        sparkles(g);

        g.dispose();
        File out = root.resolve("store-assets/feature-graphic.png").toFile();
        ImageIO.write(img, "png", out);
        System.out.println("Wrote " + out + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }

    // ---------------------------------------------------------------- background

    static void background(Graphics2D g) {
        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        // Warm saffron glow behind the illustration.
        glow(g, 720, 250, 460, new Color(245, 165, 36, 58));
        // A cooler violet lift at top-left so the brand side is not flat.
        glow(g, 120, 40, 420, new Color(132, 110, 203, 34));
        // Gentle vignette at the bottom.
        g.setPaint(new GradientPaint(0, H - 140, new Color(23, 18, 43, 0), 0, H, new Color(14, 10, 28, 150)));
        g.fillRect(0, H - 140, W, 140);
        // Faint dot grid, like graph paper, fading toward the left.
        for (int y = 22; y < H; y += 26) {
            for (int x = 22; x < W; x += 26) {
                float a = Math.max(0f, Math.min(1f, (x - 380) / 500f)) * 0.10f;
                if (a <= 0.005f) continue;
                g.setColor(new Color(1f, 1f, 1f, a));
                g.fill(new Ellipse2D.Double(x - 1, y - 1, 2, 2));
            }
        }
    }

    static void glow(Graphics2D g, float cx, float cy, float r, Color c) {
        Color clear = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
        Color mid = new Color(c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha() / 3);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), r,
                new float[]{0f, 0.45f, 1f}, new Color[]{c, mid, clear}));
        g.fill(new Rectangle2D.Float(cx - r, cy - r, 2 * r, 2 * r));
    }

    // ---------------------------------------------------------------- brand block

    static void brand(Graphics2D g, BufferedImage icon) {
        int x = 76, iconY = 92, s = 88;
        // Icon with soft shadow and rounded corners (adaptive-icon style squircle-ish).
        shadow(g, new RoundRectangle2D.Double(x, iconY, s, s, 26, 26), 14, 0.45f);
        Shape clip = new RoundRectangle2D.Double(x, iconY, s, s, 26, 26);
        Shape old = g.getClip();
        g.setClip(clip);
        g.drawImage(icon, x, iconY, s, s, null);
        g.setClip(old);
        g.setColor(new Color(255, 255, 255, 22));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x + 0.5, iconY + 0.5, s - 1, s - 1, 26, 26));

        // Name
        g.setFont(bold.deriveFont(86f));
        g.setColor(TEXT);
        drawTracked(g, "Abhyas", x - 4, 270, -1.5f);

        // Tagline, with the payoff word in saffron
        g.setFont(medium.deriveFont(30f));
        g.setColor(TEXT);
        g.drawString("Your notes ask", x, 322);
        FontMetrics fm = g.getFontMetrics();
        String a = "the ";
        g.drawString(a, x, 360);
        g.setColor(SAFFRON_BRIGHT);
        g.drawString("questions.", x + fm.stringWidth(a), 360);

        // Quiet fact line
        g.setFont(regular.deriveFont(17f));
        g.setColor(MUTED);
        int cx = x;
        String[] facts = {"Offline", "No account", "Spaced repetition"};
        for (int i = 0; i < facts.length; i++) {
            if (i > 0) {
                g.setColor(SAFFRON);
                g.fill(new Ellipse2D.Double(cx + 7, 397 - 6, 5, 5));
                cx += 19;
                g.setColor(MUTED);
            }
            g.drawString(facts[i], cx, 397);
            cx += g.getFontMetrics().stringWidth(facts[i]);
        }
    }

    // ---------------------------------------------------------------- notebook page

    static void notebook(Graphics2D g) {
        AffineTransform saved = g.getTransform();
        double px = 446, py = 96, pw = 236, ph = 300;
        g.rotate(Math.toRadians(-7), px + pw / 2, py + ph / 2);

        Shape page = new RoundRectangle2D.Double(px, py, pw, ph, 14, 14);
        shadow(g, page, 22, 0.55f);
        g.setPaint(new GradientPaint((float) px, (float) py, hex(0xFBF7EC), (float) px, (float) (py + ph), hex(0xEFE8D6)));
        g.fill(page);

        Shape oldClip = g.getClip();
        g.clip(page);
        // Ruled lines
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(91, 169, 232, 70));
        for (double y = py + 58; y < py + ph - 10; y += 26) g.draw(new Line2D.Double(px, y, px + pw, y));
        // Margin rule
        g.setColor(new Color(224, 112, 95, 110));
        g.draw(new Line2D.Double(px + 34, py, px + 34, py + ph));
        // Binding holes
        for (int i = 0; i < 5; i++) {
            double hy = py + 30 + i * 60;
            g.setColor(new Color(23, 18, 43, 235));
            g.fill(new Ellipse2D.Double(px + 11, hy, 11, 11));
        }
        g.setClip(oldClip);

        // Heading scribble, then handwritten-ish lines.
        double lx = px + 46;
        scribble(g, lx, py + 40, 112, 3.0f, INK, 1);
        // Highlighter swash behind line 3 (the one that becomes the card).
        double hy = py + 58 + 26 * 2 - 9;
        g.setColor(new Color(255, 209, 102, 165));
        Path2D hl = new Path2D.Double();
        hl.moveTo(lx - 6, hy - 8);
        hl.curveTo(lx + 60, hy - 12, lx + 120, hy - 9, lx + 168, hy - 11);
        hl.lineTo(lx + 170, hy + 9);
        hl.curveTo(lx + 110, hy + 11, lx + 50, hy + 8, lx - 4, hy + 10);
        hl.closePath();
        g.fill(hl);

        double[] widths = {150, 128, 160, 96, 140, 118, 70, 132};
        for (int i = 0; i < widths.length; i++) {
            double y = py + 58 + 26 * i - 7;
            if (y > py + ph - 16) break;
            scribble(g, lx, y, widths[i], 1.9f, new Color(44, 37, 80, i == 2 ? 255 : 165), 7 + i * 13);
        }
        g.setTransform(saved);
    }

    /** A wobbly handwriting-like stroke made of word-sized loops. */
    static void scribble(Graphics2D g, double x, double y, double width, float stroke, Color c, int seed) {
        java.util.Random r = new java.util.Random(seed);
        g.setColor(c);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double cx = x;
        while (cx < x + width - 10) {
            double w = Math.min(14 + r.nextDouble() * 34, x + width - cx);
            Path2D p = new Path2D.Double();
            p.moveTo(cx, y);
            int bumps = Math.max(2, (int) (w / 6.5));
            double step = w / bumps;
            for (int i = 0; i < bumps; i++) {
                double up = 4.5 + r.nextDouble() * 3.0;
                if (r.nextDouble() < 0.18) up += 5; // an ascender
                double x0 = cx + i * step;
                p.curveTo(x0 + step * 0.15, y - up, x0 + step * 0.65, y - up, x0 + step * 0.55, y - 1);
                p.curveTo(x0 + step * 0.5, y + 1.5, x0 + step * 0.8, y + 1.5, x0 + step, y);
            }
            g.draw(p);
            cx += w + 7 + r.nextDouble() * 4;
        }
    }

    // ---------------------------------------------------------------- sparkles

    /** A little glint where the highlighted line lifts off the page into the card. */
    static void sparkles(Graphics2D g) {
        glow(g, 620, 120, 34, new Color(255, 209, 102, 90));
        sparkle(g, 620, 120, 13, SAFFRON_BRIGHT);
        sparkle(g, 598, 148, 6, SAFFRON);
        sparkle(g, 648, 98, 5, new Color(255, 209, 102, 200));
    }

    static void sparkle(Graphics2D g, double cx, double cy, double r, Color c) {
        Path2D s = new Path2D.Double();
        s.moveTo(cx, cy - r);
        s.quadTo(cx, cy, cx + r, cy);
        s.quadTo(cx, cy, cx, cy + r);
        s.quadTo(cx, cy, cx - r, cy);
        s.quadTo(cx, cy, cx, cy - r);
        g.setColor(c);
        g.fill(s);
    }

    // ---------------------------------------------------------------- flashcard

    static void flashcard(Graphics2D g) {
        double x = 624, y = 128, w = 336, h = 268;
        RoundRectangle2D card = new RoundRectangle2D.Double(x, y, w, h, 26, 26);
        shadow(g, card, 26, 0.7f);
        g.setPaint(new GradientPaint((float) x, (float) y, hex(0x272046), (float) x, (float) (y + h), SURFACE));
        g.fill(card);
        g.setColor(BORDER_STRONG);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(card);
        // Saffron top accent
        Shape oldClip = g.getClip();
        g.clip(card);
        g.setPaint(new GradientPaint((float) x, 0, SAFFRON, (float) (x + w), 0, SAFFRON_BRIGHT));
        g.fill(new Rectangle2D.Double(x, y, w, 4));
        g.setClip(oldClip);

        double ix = x + 24;
        // Label
        g.setFont(semibold.deriveFont(12.5f));
        g.setColor(SAFFRON);
        drawTracked(g, "QUESTION", ix, y + 38, 1.6f);
        // Question
        g.setFont(semibold.deriveFont(22f));
        g.setColor(TEXT);
        text(g, "What do mitochondria", ix, y + 72);
        text(g, "make for the cell?", ix, y + 100);
        // Divider
        g.setColor(BORDER_STRONG);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
        g.draw(new Line2D.Double(ix, y + 120, x + w - 24, y + 120));
        // Answer
        g.setFont(medium.deriveFont(19f));
        g.setColor(MUTED);
        text(g, "ATP — the cell’s energy.", ix, y + 152);

        // Grade buttons
        String[] labels = {"Again", "Hard", "Good", "Easy"};
        String[] ivals = {"1m", "10m", "3d", "2w"};
        Color[] cols = {AGAIN, HARD, GOOD, EASY};
        double gap = 8, bw = (w - 48 - gap * 3) / 4, bh = 62, by = y + h - bh - 22;
        for (int i = 0; i < 4; i++) {
            double bx = ix + i * (bw + gap);
            RoundRectangle2D b = new RoundRectangle2D.Double(bx, by, bw, bh, 16, 16);
            Color c = cols[i];
            boolean on = i == 2;
            g.setColor(on ? c : new Color(c.getRed(), c.getGreen(), c.getBlue(), 38));
            g.fill(b);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), on ? 255 : 120));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(b);
            g.setFont(semibold.deriveFont(16f));
            g.setColor(on ? hex(0x0F2A1D) : c);
            centered(g, labels[i], bx + bw / 2, by + 28);
            g.setFont(medium.deriveFont(13.5f));
            g.setColor(on ? new Color(15, 42, 29, 200) : new Color(243, 240, 250, 175));
            centered(g, ivals[i], bx + bw / 2, by + 48);
        }
    }

    // ---------------------------------------------------------------- helpers

    static void shadow(Graphics2D g, Shape s, int spread, float strength) {
        Rectangle2D b = s.getBounds2D();
        for (int i = spread; i > 0; i -= 2) {
            float a = strength * (1f - i / (float) spread) * 0.12f;
            g.setColor(new Color(6, 3, 16, Math.round(Math.min(1f, a) * 255)));
            g.fill(new RoundRectangle2D.Double(b.getX() - i * 0.6, b.getY() - i * 0.3 + i * 0.5,
                    b.getWidth() + i * 1.2, b.getHeight() + i * 1.2, 26 + i, 26 + i));
        }
    }

    static void text(Graphics2D g, String s, double x, double y) {
        g.drawString(s, (float) x, (float) y);
    }

    static void centered(Graphics2D g, String s, double cx, double baseline) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.getStringBounds(s, g).getWidth() / 2), (float) baseline);
    }

    static void drawTracked(Graphics2D g, String s, double x, double y, float tracking) {
        java.util.Map<java.awt.font.TextAttribute, Object> attrs = new java.util.HashMap<>();
        attrs.put(java.awt.font.TextAttribute.TRACKING, tracking / g.getFont().getSize2D());
        Font f = g.getFont();
        g.setFont(f.deriveFont(attrs));
        g.drawString(s, (float) x, (float) y);
        g.setFont(f);
    }

    static Font font(Path p) throws Exception {
        return Font.createFont(Font.TRUETYPE_FONT, p.toFile());
    }

    static Color hex(int rgb) {
        return new Color(rgb);
    }
}
