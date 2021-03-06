package org.carrot2.elasticsearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.carrot2.clustering.ClusteringAlgorithmProvider;
import org.carrot2.language.LanguageComponents;
import org.carrot2.language.LanguageComponentsProvider;
import org.carrot2.util.ResourceLookup;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Holds the language components initialized and ready throughout
 * the {@link Node}'s lifecycle.
 */
public class ClusteringContext extends AbstractLifecycleComponent {
   public static final String PROP_RESOURCES = "resources";

   private final Environment environment;
   private final LinkedHashMap<String, ClusteringAlgorithmProvider> algorithmProviders;
   private final LinkedHashMap<String, List<LanguageComponentsProvider>> languageComponentProviders;

   private Logger logger;
   private ResourceLookup resourceLookup;
   private LinkedHashMap<String, LanguageComponents> languages;

   public ClusteringContext(Environment environment,
                            LinkedHashMap<String, ClusteringAlgorithmProvider> algorithmProviders,
                            LinkedHashMap<String, List<LanguageComponentsProvider>> languageComponentProviders) {
      this.environment = environment;
      this.logger = LogManager.getLogger("plugin.carrot2");
      this.algorithmProviders = algorithmProviders;
      this.languageComponentProviders = languageComponentProviders;
   }

   @SuppressForbidden(reason = "C2 integration (File API)")
   @Override
   protected void doStart() throws ElasticsearchException {
      try {
         Path esConfig = environment.configFile();
         Path pluginConfigPath = esConfig.resolve(ClusteringPlugin.PLUGIN_NAME);

         if (!Files.isDirectory(pluginConfigPath)) {
            throw new ElasticsearchException("Missing configuration folder?: {}", pluginConfigPath);
         }

         Settings.Builder builder = Settings.builder();
         for (String configName : new String[]{
             "config.yml",
             "config.yaml",
             "config.json",
             "config.properties"
         }) {
            Path resolved = pluginConfigPath.resolve(configName);
            if (Files.exists(resolved)) {
               builder.loadFromPath(resolved);
            }
         }
         Settings c2Settings = builder.build();

         List<Path> resourceLocations = c2Settings.getAsList(PROP_RESOURCES)
             .stream()
             .map(p -> esConfig.resolve(p).toAbsolutePath())
             .filter(p -> {
                boolean exists = Files.exists(p);
                if (!exists) {
                   logger.info("Clustering algorithm resource location does not exist, ignored: {}", p);
                }
                return exists;
             })
             .collect(Collectors.toList());

         if (resourceLocations.isEmpty()) {
            resourceLookup = new PathResourceLookup(resourceLocations);
            for (Path p : resourceLocations) {
               logger.info("Clustering algorithm resources loaded relative to: {}", p);
            }
         } else {
            resourceLookup = null;
            logger.info("Resources read from defaults (JARs).");
         }

         AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            languages = new LinkedHashMap<>();
            for (Map.Entry<String, List<LanguageComponentsProvider>> e : languageComponentProviders.entrySet()) {
               String language = e.getKey();
               languages.put(language, new LanguageComponents(language,
                   componentSuppliers(language, resourceLookup, e.getValue())));
            }

            // Remove languages for which there are no algorithms that support them.
            languages.entrySet().removeIf(e -> {
               LanguageComponents lc = e.getValue();
               return algorithmProviders.values()
                   .stream()
                   .noneMatch(algorithm -> algorithm.get().supports(lc));
            });

            // Remove algorithms for which there are no languages that are supported.
            algorithmProviders.entrySet().removeIf(e -> {
               boolean remove = languages.values().stream()
                   .noneMatch(lc -> e.getValue().get().supports(lc));
               if (remove) {
                  logger.info("Algorithm {} does not support any of the loaded languages and will be ignored.",
                      e.getKey());
               }
               return remove;
            });

            algorithmProviders.forEach((name, prov) -> {
               String supportedLanguages = languages.values().stream()
                   .filter(lc -> prov.get().supports(lc))
                   .map(LanguageComponents::language)
                   .collect(Collectors.joining(", "));

               logger.info("Clustering algorithm {} loaded with support for the following languages: {}",
                   name, supportedLanguages);
            });

            return null;
         });
      } catch (Exception e) {
         throw new ElasticsearchException(
             "Could not initialize clustering.", e);
      }

      if (algorithmProviders == null || algorithmProviders.isEmpty()) {
         throw new ElasticsearchException("No registered/ available clustering algorithms? Check the logs, it's odd.");
      }
   }

   private Map<Class<?>, Supplier<?>> componentSuppliers(String language,
                                                         ResourceLookup resourceLookup,
                                                         List<LanguageComponentsProvider> providers) {
      Map<Class<?>, Supplier<?>> suppliers = new HashMap<>();
      for (LanguageComponentsProvider provider : providers) {
         try {
            Map<Class<?>, Supplier<?>> components =
                resourceLookup == null
                    ? provider.load(language)
                    : provider.load(language, resourceLookup);

            components.forEach((clazz, supplier) -> {
               Supplier<?> existing = suppliers.put(clazz, supplier);
               if (existing != null) {
                  throw new RuntimeException(
                      String.format(
                          Locale.ROOT,
                          "Language '%s' has multiple providers of component '%s': %s",
                          language,
                          clazz.getSimpleName(),
                          Stream.of(existing, supplier)
                              .map(s -> s.getClass().getName())
                              .collect(Collectors.joining(", "))));

               }
            });
         } catch (IOException e) {
            logger.warn(String.format(Locale.ROOT,
                "Could not load resources for language '%s' of provider '%s', provider ignored for this language.",
                language,
                provider.name()));
         }
      }
      return suppliers;
   }

   /**
    * Return a list of available algorithm component identifiers.
    */
   public LinkedHashMap<String, ClusteringAlgorithmProvider> getAlgorithms() {
      return algorithmProviders;
   }

   @Override
   protected void doStop() throws ElasticsearchException {
      // Noop.
   }

   @Override
   protected void doClose() throws ElasticsearchException {
      // Noop.
   }

   public LanguageComponents getLanguageComponents(String lang) {
      return languages.get(lang);
   }

   public boolean isLanguageSupported(String langCode) {
      return languages.containsKey(langCode);
   }
}
