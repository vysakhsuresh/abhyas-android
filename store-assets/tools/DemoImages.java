import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the two pictures the store screenshots need:
 *
 *   app/src/debug/assets/demo-diagram.jpg   a labelled heart diagram for the picture-card demo
 *   store-assets/tools/out/notes-page.jpg   a page of notes for the capture screenshot
 *
 * Run from the repository root:  java store-assets/tools/DemoImages.java
 * Label positions in the diagram match the regions DemoSeedActivity masks.
 */
public class DemoImages {

    public static void main(String[] args) throws Exception {
        File root = new File(".").getCanonicalFile();
        Font grotesk = Font.createFont(Font.TRUETYPE_FONT,
            new File(root, "app/src/main/res/font/space_grotesk_medium.ttf"));
        diagram(new File(root, "app/src/debug/assets/demo-diagram.jpg"), grotesk);
        notes(new File(root, "store-assets/tools/out/notes-page.jpg"));
    }

    private static Graphics2D smooth(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private static void diagram(File out, Font base) throws Exception {
        int w = 1200, h = 1200;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = smooth(image);
        g.setColor(new Color(0xFBF8F1));
        g.fillRect(0, 0, w, h);

        // Heart body.
        g.setColor(new Color(0xD9534F));
        Path2D heart = new Path2D.Double();
        heart.moveTo(600, 1030);
        heart.curveTo(330, 860, 300, 560, 400, 400);
        heart.curveTo(470, 300, 560, 320, 600, 380);
        heart.curveTo(640, 320, 730, 300, 800, 400);
        heart.curveTo(900, 560, 870, 860, 600, 1030);
        heart.closePath();
        g.fill(heart);

        // Great vessels.
        g.setColor(new Color(0xB03A36));
        g.fill(new RoundRectangle2D.Double(470, 150, 90, 260, 40, 40));
        g.setColor(new Color(0x4F7BC0));
        g.fill(new RoundRectangle2D.Double(640, 170, 90, 240, 40, 40));

        // Chambers.
        g.setColor(new Color(0xF2B8B5));
        g.fill(new Ellipse2D.Double(420, 470, 160, 150));
        g.fill(new Ellipse2D.Double(620, 470, 160, 150));
        g.setColor(new Color(0xEE9C98));
        g.fill(new Ellipse2D.Double(440, 670, 150, 230));
        g.fill(new Ellipse2D.Double(610, 670, 150, 230));
        g.setColor(new Color(0x8E2C29));
        g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(600, 440, 600, 960));

        // Labels and leader lines. Positions match DemoSeedActivity's regions.
        String[][] labels = {
            {"Aorta", "L", "0.175", "515", "220"},
            {"Pulmonary artery", "R", "0.175", "685", "230"},
            {"Right atrium", "L", "0.475", "470", "545"},
            {"Left atrium", "R", "0.475", "730", "545"},
            {"Right ventricle", "L", "0.775", "500", "790"},
            {"Left ventricle", "R", "0.775", "700", "790"},
        };
        Font font = base.deriveFont(Font.PLAIN, 34f);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        g.setStroke(new BasicStroke(3f));
        for (String[] label : labels) {
            boolean left = label[1].equals("L");
            double cy = Double.parseDouble(label[2]) * h;
            int textW = fm.stringWidth(label[0]);
            double x = left ? 0.165 * w - textW / 2.0 : 0.835 * w - textW / 2.0;
            double anchorX = left ? 0.30 * w + 6 : 0.70 * w - 6;
            g.setColor(new Color(0x5A5A66));
            g.draw(new Line2D.Double(anchorX, cy, Double.parseDouble(label[3]), Double.parseDouble(label[4])));
            g.fill(new Ellipse2D.Double(Double.parseDouble(label[3]) - 7, Double.parseDouble(label[4]) - 7, 14, 14));
            g.setColor(new Color(0x22222B));
            g.drawString(label[0], (float) x, (float) (cy + fm.getAscent() / 2.0 - 4));
        }

        g.setColor(new Color(0x22222B));
        g.setFont(base.deriveFont(Font.BOLD, 44f));
        g.drawString("The human heart", 40, 70);
        g.dispose();
        out.getParentFile().mkdirs();
        ImageIO.write(image, "jpg", out);
    }

    private static void notes(File out) throws Exception {
        int w = 1240, h = 1754;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = smooth(image);
        g.setColor(new Color(0xFCFBF6));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0xDCE6F2));
        for (int y = 200; y < h; y += 64) g.fillRect(0, y, w, 2);
        g.setColor(new Color(0xF0B4B4));
        g.fillRect(110, 0, 3, h);

        g.setColor(new Color(0x1E2A44));
        Font serif = new Font("Serif", Font.PLAIN, 40);
        g.setFont(new Font("Serif", Font.BOLD, 56));
        g.drawString("Chapter 4 - The Cell", 140, 150);

        g.setFont(serif);
        int y = 250;
        String[] lines = {
            "Cell membrane: a thin layer that controls what",
            "enters and leaves the cell.",
            "",
            "Nucleus: the control centre of the cell, which",
            "contains the DNA.",
            "",
            "What is osmosis? Osmosis is the movement of water",
            "across a partially permeable membrane.",
        };
        for (String line : lines) {
            g.drawString(line, 140, y);
            y += 64;
        }

        y += 40;
        g.setFont(new Font("Serif", Font.BOLD, 42));
        g.drawString("Organelle", 140, y);
        g.drawString("Function", 620, y);
        g.setFont(serif);
        String[][] rows = {
            {"Mitochondria", "Releases energy as ATP"},
            {"Ribosome", "Makes proteins"},
            {"Chloroplast", "Carries out photosynthesis"},
            {"Vacuole", "Stores water and salts"},
            {"Cell wall", "Supports the plant cell"},
        };
        for (String[] row : rows) {
            y += 72;
            g.drawString(row[0], 140, y);
            g.drawString(row[1], 620, y);
        }
        g.dispose();
        out.getParentFile().mkdirs();
        ImageIO.write(image, "jpg", out);
    }
}
