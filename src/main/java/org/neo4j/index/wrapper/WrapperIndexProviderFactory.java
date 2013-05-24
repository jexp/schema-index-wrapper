package org.neo4j.index.wrapper;

import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.helpers.Service;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.ThreadToStatementContextBridge;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.extension.KernelExtensionFactory;
import org.neo4j.kernel.impl.api.index.SchemaIndexProviderMap;
import org.neo4j.kernel.lifecycle.Lifecycle;

@Service.Implementation(KernelExtensionFactory.class)
public class WrapperIndexProviderFactory extends KernelExtensionFactory<WrapperIndexProviderFactory.Dependencies> {
    public static final String KEY = "wrapper-index";

    public static final SchemaIndexProvider.Descriptor PROVIDER_DESCRIPTOR =
            new SchemaIndexProvider.Descriptor(KEY, "1.0");

    private final WrapperSchemaIndexProvider singleProvider;

    public interface Dependencies {
        Config getConfig();
        GraphDatabaseAPI getGraphDatabaseAPI();
        ThreadToStatementContextBridge getStatementContextProvider();
        DependencyResolver getDependencyResolver();
    }

    public WrapperIndexProviderFactory() {
        this(null);
    }

    public WrapperIndexProviderFactory(WrapperSchemaIndexProvider singleProvider) {
        super(KEY);
        this.singleProvider = singleProvider;
    }

    @Override
    public Lifecycle newKernelExtension(Dependencies deps) throws Throwable {
        return hasSingleProvider() ? singleProvider : new WrapperSchemaIndexProvider(deps.getConfig(),deps.getGraphDatabaseAPI(),deps.getStatementContextProvider(),
                deps.getDependencyResolver());
    }

    private boolean hasSingleProvider() {
        return singleProvider != null;
    }
}
