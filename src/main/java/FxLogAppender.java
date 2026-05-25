import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.Serializable;

@Plugin(name = "FxLogAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public final class FxLogAppender extends AbstractAppender {
    private static final ObservableList<String> ENTRIES = FXCollections.observableArrayList();

    private FxLogAppender(String name, Layout<? extends Serializable> layout, Filter filter) {
        super(name, filter, layout, false, Property.EMPTY_ARRAY);
    }

    @PluginFactory
    public static FxLogAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginElement("Filter") Filter filter) {
        Layout<? extends Serializable> effectiveLayout = layout != null ? layout
                : PatternLayout.newBuilder()
                        .setPattern("%d{HH:mm:ss.SSS} %-5level %logger{1} - %msg")
                        .build();
        return new FxLogAppender(name, effectiveLayout, filter);
    }

    public static ObservableList<String> entries() {
        return ENTRIES;
    }

    @Override
    public void append(LogEvent event) {
        String formatted = getLayout().toSerializable(event).toString();
        if (Platform.isFxApplicationThread()) {
            ENTRIES.add(formatted);
            return;
        }
        try {
            Platform.runLater(() -> ENTRIES.add(formatted));
        } catch (IllegalStateException ignored) {
        }
    }
}
