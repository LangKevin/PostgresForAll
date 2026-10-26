import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders installer/icon/icon.svg into every icon file the app and installers use:
 * <ul>
 *   <li>{@code installer/icon/png/icon-N.png}: window / taskbar icons, copied into the app's resources by Gradle</li>
 *   <li>{@code installer/windows/PostgresForAll.ico}: launcher, Start menu, Add/Remove Programs</li>
 *   <li>{@code installer/macos/PostgresForAll.icns}: .app bundle, Dock, Finder</li>
 * </ul>
 * Both container formats are written directly (PNG entries), so no Mac or ImageMagick is needed.
 * Run from the repository root after {@code ./gradlew :app:installDist}:
 * <pre>java -cp "app/build/install/PostgresForAll/lib/*" installer/icon/MakeIcons.java</pre>
 * The outputs are committed; builds don't run this.
 */
public class MakeIcons {
    static final List<Integer> WINDOW_SIZES = List.of(16, 20, 24, 32, 40, 48, 64, 128, 256);
    static final List<Integer> ICO_SIZES = List.of(16, 20, 24, 32, 40, 48, 64, 128, 256);

    public static void main(String[] args) throws Exception {
        var root = Path.of(args.length > 0 ? args[0] : "installer");
        var svg = root.resolve("icon/icon.svg");
        var doc = new SVGLoader().load(svg.toUri().toURL());
        if (doc == null) throw new IOException("Couldn't parse " + svg);

        var cache = new LinkedHashMap<Integer, byte[]>();
        java.util.function.IntFunction<byte[]> png = size -> cache.computeIfAbsent(size, s -> {
            var img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            var g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            doc.render(null, g, new ViewBox(0, 0, s, s));
            g.dispose();
            try {
                var out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        var pngDir = root.resolve("icon/png");
        Files.createDirectories(pngDir);
        for (var s : WINDOW_SIZES) Files.write(pngDir.resolve("icon-" + s + ".png"), png.apply(s));

        var ico = root.resolve("windows/PostgresForAll.ico");
        Files.createDirectories(ico.getParent());
        Files.write(ico, ico(ICO_SIZES, png));

        // ICNS entry types holding PNG data, smallest first.
        var icns = new LinkedHashMap<String, Integer>();
        icns.put("icp4", 16);
        icns.put("icp5", 32);
        icns.put("ic11", 32);   // 16@2x
        icns.put("ic12", 64);   // 32@2x
        icns.put("ic07", 128);
        icns.put("ic13", 256);  // 128@2x
        icns.put("ic08", 256);
        icns.put("ic14", 512);  // 256@2x
        icns.put("ic09", 512);
        icns.put("ic10", 1024); // 512@2x
        var icnsFile = root.resolve("macos/PostgresForAll.icns");
        Files.createDirectories(icnsFile.getParent());
        Files.write(icnsFile, icns(icns, png));

        System.out.println("wrote " + pngDir + ", " + ico + ", " + icnsFile);
    }

    /** ICO with PNG-compressed images (Windows Vista and later). Little-endian headers. */
    static byte[] ico(List<Integer> sizes, java.util.function.IntFunction<byte[]> png) throws IOException {
        var out = new ByteArrayOutputStream();
        writeLE16(out, 0);              // reserved
        writeLE16(out, 1);              // type: icon
        writeLE16(out, sizes.size());
        var offset = 6 + 16 * sizes.size();
        for (var s : sizes) {
            var data = png.apply(s);
            out.write(s >= 256 ? 0 : s);    // width (0 = 256)
            out.write(s >= 256 ? 0 : s);    // height
            out.write(0);                   // palette colours
            out.write(0);                   // reserved
            writeLE16(out, 1);              // colour planes
            writeLE16(out, 32);             // bits per pixel
            writeLE32(out, data.length);
            writeLE32(out, offset);
            offset += data.length;
        }
        for (var s : sizes) out.write(png.apply(s));
        return out.toByteArray();
    }

    /** ICNS: "icns" + total length, then (type, length incl. 8-byte header, PNG data) per entry. Big-endian. */
    static byte[] icns(Map<String, Integer> entries, java.util.function.IntFunction<byte[]> png) throws IOException {
        var body = new ByteArrayOutputStream();
        var d = new DataOutputStream(body);
        for (var e : entries.entrySet()) {
            var data = png.apply(e.getValue());
            d.writeBytes(e.getKey());
            d.writeInt(8 + data.length);
            d.write(data);
        }
        var out = new ByteArrayOutputStream();
        var h = new DataOutputStream(out);
        h.writeBytes("icns");
        h.writeInt(8 + body.size());
        h.write(body.toByteArray());
        return out.toByteArray();
    }

    static void writeLE16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    static void writeLE32(ByteArrayOutputStream out, int v) {
        writeLE16(out, v & 0xFFFF);
        writeLE16(out, (v >>> 16) & 0xFFFF);
    }
}
