package ch.it4user.fintube.media;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Marks the Jellyfin artwork of downloaded videos with a green frame so local copies are recognisable at a glance.
 * The untouched YouTube thumbnail is kept in a hidden file (ignored by Jellyfin) so the frame can be removed again.
 */
public final class DownloadedArtwork {
    static final Color FRAME_COLOR = new Color(0x2E, 0xCC, 0x71);
    static final String THUMB = "video-thumb.jpg";
    static final String POSTER = "video-poster.jpg";
    static final String ORIGINAL = ".fintube-original-thumb.jpg";

    private DownloadedArtwork() { }

    /** Applies or removes the frame depending on whether the folder holds a downloaded media file. */
    public static void apply(Path folder, boolean downloaded) {
        try {
            if (downloaded) mark(folder); else unmark(folder);
        } catch (Exception ignored) {
            // Artwork decoration must never break library generation or downloads.
        }
    }

    private static void mark(Path folder) throws IOException {
        Path thumb = folder.resolve(THUMB);
        Path poster = folder.resolve(POSTER);
        Path original = folder.resolve(ORIGINAL);
        if (Files.exists(original)) {
            // Already framed; only restore artwork that went missing.
            if (!Files.exists(thumb) || !Files.exists(poster)) {
                BufferedImage framed = framed(original);
                if (framed == null) return;
                if (!Files.exists(thumb)) write(framed, thumb);
                if (!Files.exists(poster)) write(framed, poster);
            }
            return;
        }
        if (!Files.isRegularFile(thumb)) return;
        BufferedImage framed = framed(thumb);
        if (framed == null) return;
        Files.copy(thumb, original, StandardCopyOption.REPLACE_EXISTING);
        write(framed, thumb);
        write(framed, poster);
    }

    private static void unmark(Path folder) throws IOException {
        Path original = folder.resolve(ORIGINAL);
        if (!Files.isRegularFile(original)) return;
        Files.copy(original, folder.resolve(THUMB), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(original, folder.resolve(POSTER), StandardCopyOption.REPLACE_EXISTING);
        Files.delete(original);
    }

    static BufferedImage framed(Path source) throws IOException {
        BufferedImage image = ImageIO.read(source.toFile());
        if (image == null) return null;
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(image, 0, 0, null);
            int thickness = Math.max(6, Math.round(Math.min(width, height) * 0.035f));
            g.setColor(FRAME_COLOR);
            g.setStroke(new BasicStroke(thickness));
            g.drawRect(thickness / 2, thickness / 2, width - thickness, height - thickness);
        } finally {
            g.dispose();
        }
        return result;
    }

    private static void write(BufferedImage image, Path target) throws IOException {
        Path tmp = target.resolveSibling(".fintube-tmp-" + target.getFileName());
        if (!ImageIO.write(image, "jpg", tmp.toFile())) throw new IOException("no JPEG writer");
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
