package com.paiban.scheduling.solver.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads {@link CustomConstraint} implementations from the classpath and {@code plugins/*.jar}. */
public final class PluginConstraintLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginConstraintLoader.class);

    private PluginConstraintLoader() {}

    public static List<CustomConstraint> load() {
        List<CustomConstraint> result = new ArrayList<>();
        collect(ServiceLoader.load(CustomConstraint.class), result);

        Path directory = Paths.get("plugins");
        if (!Files.isDirectory(directory)) {
            return result;
        }
        List<URL> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.toString().endsWith(".jar"))
                    .forEach(path -> {
                        try {
                            jars.add(path.toUri().toURL());
                        } catch (IOException ex) {
                            log.warn("忽略无法读取的插件: {}", path, ex);
                        }
                    });
        } catch (IOException ex) {
            log.warn("无法读取 plugins 目录", ex);
        }
        if (jars.isEmpty()) {
            return result;
        }
        URLClassLoader loader = new URLClassLoader(jars.toArray(new URL[0]), PluginConstraintLoader.class.getClassLoader());
        collect(ServiceLoader.load(CustomConstraint.class, loader), result);
        return result;
    }

    private static void collect(ServiceLoader<CustomConstraint> services, List<CustomConstraint> target) {
        for (CustomConstraint constraint : services) {
            try {
                target.add(constraint);
                log.info("Loaded custom constraint plugin: {}", constraint.name());
            } catch (RuntimeException ex) {
                log.warn("Failed to load a custom constraint plugin", ex);
            }
        }
    }
}
