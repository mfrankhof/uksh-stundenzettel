import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SvgIconLoader {
    private static final Pattern PATH_D = Pattern.compile("\\bd=\"([^\"]+)\"");
    private static final Pattern CIRCLE = Pattern.compile("<circle\\b[^>]*>");

    private SvgIconLoader() {}

    public static SVGPath load(String resourcePath) {
        try (InputStream in = SvgIconLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Icon nicht gefunden: " + resourcePath);
            }
            String svg = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder combined = new StringBuilder();
            Matcher pathMatcher = PATH_D.matcher(svg);
            while (pathMatcher.find()) {
                if (!combined.isEmpty()) combined.append(' ');
                combined.append(pathMatcher.group(1));
            }
            Matcher circleMatcher = CIRCLE.matcher(svg);
            while (circleMatcher.find()) {
                String tag = circleMatcher.group();
                double cx = Double.parseDouble(attr(tag, "cx"));
                double cy = Double.parseDouble(attr(tag, "cy"));
                double r = Double.parseDouble(attr(tag, "r"));
                if (!combined.isEmpty()) combined.append(' ');
                combined.append("M ").append(cx - r).append(' ').append(cy)
                        .append(" A ").append(r).append(' ').append(r).append(" 0 1 0 ").append(cx + r).append(' ').append(cy)
                        .append(" A ").append(r).append(' ').append(r).append(" 0 1 0 ").append(cx - r).append(' ').append(cy)
                        .append(" Z");
            }
            SVGPath path = new SVGPath();
            path.setContent(combined.toString());
            path.setFill(null);
            path.setStroke(Color.web("#888"));
            path.setStrokeWidth(1.5);
            path.setStrokeLineCap(StrokeLineCap.ROUND);
            path.setStrokeLineJoin(StrokeLineJoin.ROUND);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]+)\"").matcher(tag);
        if (!m.find()) throw new IllegalArgumentException("Attribut '" + name + "' fehlt in: " + tag);
        return m.group(1);
    }
}
