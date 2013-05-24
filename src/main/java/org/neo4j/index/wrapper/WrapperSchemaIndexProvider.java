package org.neo4j.index.wrapper;

import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexManager;
import org.neo4j.helpers.collection.IteratorWrapper;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.ThreadToStatementContextBridge;
import org.neo4j.kernel.api.LabelNotFoundKernelException;
import org.neo4j.kernel.api.PropertyKeyIdNotFoundException;
import org.neo4j.kernel.api.StatementContext;
import org.neo4j.kernel.api.index.*;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.api.index.IndexDescriptor;
import org.neo4j.kernel.impl.api.index.SchemaIndexProviderMap;
import org.neo4j.kernel.impl.nioneo.store.IndexRule;
import org.neo4j.kernel.impl.nioneo.store.SchemaRule;
import org.neo4j.kernel.impl.nioneo.xa.DefaultSchemaIndexProviderMap;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.neo4j.index.wrapper.WrapperIndexProviderFactory.PROVIDER_DESCRIPTOR;

/**
 * @author mh
 * @since 03.05.13
 */
public class WrapperSchemaIndexProvider extends SchemaIndexProvider {
    static int PRIORITY;

    static {
        PRIORITY = 100;
    }

    // todo this is visibility isolation semantics for the in-memory index
    private final Map<Long, WrapperIndex> indexes = new HashMap<Long, WrapperIndex>();
    private final Map<Long, SchemaIndexProvider> providers = new HashMap<Long, SchemaIndexProvider>();
    private final Map<String, String> params;
    private final GraphDatabaseAPI api;
    private final ThreadToStatementContextBridge statementContextProvider;
    private final DependencyResolver dependencyResolver;
    private SchemaIndexProvider defaultProvider;

    public WrapperSchemaIndexProvider(Config config, GraphDatabaseAPI api, ThreadToStatementContextBridge statementContextProvider, DependencyResolver dependencyResolver) {
        super(PROVIDER_DESCRIPTOR, PRIORITY);
        this.api = api;
        this.statementContextProvider = statementContextProvider;
        this.dependencyResolver = dependencyResolver;
        this.params = config.getParams();
        defaultProvider = dependencyResolver.resolveDependency(SchemaIndexProvider.class, HIGHEST_PRIORITIZED_OR_NONE);
    }

    @Override
    public void start() throws Throwable {
        super.start();

        final StatementContext ctx = statementContextProvider.getCtxForReading();
        final Iterator<IndexRule> indexRules = ctx.getIndexRules();
        final IndexManager manager = api.index();
        while (indexRules.hasNext()) {
            IndexRule rule = indexRules.next();
            if (rule.getKind() != SchemaRule.Kind.INDEX_RULE) continue;
            addIndexRule(ctx, manager, rule.getId(), rule.getLabel(), rule.getPropertyKey());
        }
    }

    private boolean addIndexRule(long indexId) {
        try {
            final StatementContext ctx = statementContextProvider.getCtxForReading();
            final IndexManager manager = api.index();
            IndexRule rule = findRule(indexId, ctx);
            return rule != null && addIndexRule(ctx, manager, indexId, rule.getLabel(), rule.getPropertyKey());
        } catch (Exception e) {
            throw new RuntimeException("Error adding index rule for id " + indexId, e);
        }
    }

    private IndexRule findRule(long indexId, StatementContext ctx) {
        final Iterator<IndexRule> indexRules = ctx.getIndexRules();
        while (indexRules.hasNext()) {
            IndexRule rule = indexRules.next();
            if (rule.getId()==indexId) return rule;
        }
        return null;
    }

    private boolean addIndexRule(StatementContext ctx, IndexManager manager, final long indexId, final long labelId, final long propertyKey) throws LabelNotFoundKernelException, PropertyKeyIdNotFoundException {
        final String label = ctx.getLabelName(labelId);
        final String property = ctx.getPropertyKeyName(propertyKey);
        final String config = params.get(configKey(label, property));
        if (config == null) return false;
        final Map<String, String> configData = MapUtil.stringMap(config.split("[:,]"));
        final SchemaIndexProvider schemaIndexProvider = dependencyResolver.resolveDependency(SchemaIndexProvider.class, new SchemaIndexProviderSelectionStrategy(configData));
        if (schemaIndexProvider != null) {
            providers.put(indexId, schemaIndexProvider);
            return true;
        }
        final Index<Node> index = manager.forNodes(configData.get("name"), configData);
        indexes.put(indexId, new LegacyWrapperIndex(api, index, property));
        return true;
    }

    private String configKey(String label, String property) {
        return String.format("index-wrapper.%s.%s", label, property);
    }

    @Override
    public void shutdown() throws Throwable {
        super.shutdown();
    }

    @Override
    public IndexAccessor getOnlineAccessor(long indexId) {
        final SchemaIndexProvider provider = providers.get(indexId);
        if (provider != null) return provider.getOnlineAccessor(indexId);

        WrapperIndex index = indexes.get(indexId);
        if (index != null && index.getState() != InternalIndexState.ONLINE)
            throw new IllegalStateException("Index " + indexId + " not online yet");

        if (index != null) return index;
        if (!addIndexRule(indexId)) return defaultProvider.getOnlineAccessor(indexId);
        return getOnlineAccessor(indexId);
    }

    @Override
    public InternalIndexState getInitialState(long indexId) {
        final SchemaIndexProvider provider = providers.get(indexId);
        if (provider != null) return provider.getInitialState(indexId);

        WrapperIndex index = indexes.get(indexId);

        if (index != null) return index.getState();
        if (!addIndexRule(indexId)) return defaultProvider.getInitialState(indexId);
        return getInitialState(indexId);
    }

    @Override
    public IndexPopulator getPopulator(long indexId) {
        final SchemaIndexProvider provider = providers.get(indexId);
        if (provider != null) return provider.getPopulator(indexId);

        final WrapperIndex index = indexes.get(indexId);
        if (index != null) return index;
        if (!addIndexRule(indexId)) return defaultProvider.getPopulator(indexId);
        return getPopulator(indexId);
    }

    private static abstract class WrapperIndex extends IndexAccessor.Adapter implements IndexPopulator {
        abstract InternalIndexState getState();
    }

    public static class LegacyWrapperIndex extends WrapperIndex {
        private final GraphDatabaseAPI api;
        private Index<Node> index;
        private final String key;
        private InternalIndexState state = InternalIndexState.POPULATING;

        public LegacyWrapperIndex(GraphDatabaseAPI api, Index<Node> index, String key) {
            this.api = api;
            this.index = index;
            this.key = key;
        }

        @Override
        InternalIndexState getState() {
            return state;
        }

        @Override
        public void add(long nodeId, Object propertyValue) {
            final Node pc = api.getNodeById(nodeId); // todo virtual PC
            index.add(pc, key, propertyValue);
        }

        private void removed(long nodeId, Object propertyValue) {
            final Node pc = api.getNodeById(nodeId); // todo virtual PC
            index.remove(pc, key, propertyValue);
        }


        @Override
        public void update(Iterable<NodePropertyUpdate> updates) {
            for (NodePropertyUpdate update : updates) {
                switch (update.getUpdateMode()) {
                    case ADDED:
                        add(update.getNodeId(), update.getValueAfter());
                        break;
                    case CHANGED:
                        removed(update.getNodeId(), update.getValueBefore());
                        add(update.getNodeId(), update.getValueAfter());
                        break;
                    case REMOVED:
                        removed(update.getNodeId(), update.getValueBefore());
                        break;
                    default:
                        throw new UnsupportedOperationException();
                }
            }
        }

        @Override
        public void updateAndCommit(Iterable <NodePropertyUpdate> updates) {
            update(updates);
        }

        @Override
        public void recover(Iterable<NodePropertyUpdate> updates) throws IOException {
            update(updates);
        }

        @Override
        public void force() {
        }

        @Override
        public void create() {
            // todo not in tx exception
            // final String name = index.getName();
            // index.delete();
            // index = api.index().forNodes(name);
        }

        @Override
        public void drop() {
// TODO IllegalStateException: Tx status is: STATUS_COMMITING
//            index.delete();
        }

        @Override
        public void close(boolean populationCompletedSuccessfully) {
            if (populationCompletedSuccessfully)
                state = InternalIndexState.ONLINE;
        }

        @Override
        public void close() {
        }

        /**
         * @return a new {@link IndexReader} responsible for looking up results in the index.
         *         The returned reader must honor repeatable reads.
         */
        @Override
        public IndexReader newReader() {
            return new WrapperIndexReader(index, key);
        }
    }

    private static class WrapperIndexReader implements IndexReader {
        private Index<Node> snapshot;
        private final String key;

        WrapperIndexReader(Index<Node> snapshot, String key) {
            this.snapshot = snapshot;
            this.key = key;
            // todo tx with readlock ?
        }

        @Override
        public Iterator<Long> lookup(Object value) {
            return new IteratorWrapper<Long, Node>(snapshot.query(key, value)) {
                @Override
                protected Long underlyingObjectToObject(Node node) {
                    return node.getId();
                }
            };
        }

        @Override
        public void close() {
            snapshot = null;
        }
    }

    private static class SchemaIndexProviderSelectionStrategy implements DependencyResolver.SelectionStrategy<SchemaIndexProvider> {

        private final String name;
        private final String version;

        private SchemaIndexProviderSelectionStrategy(Map<String, String> configData) {
            name = configData.get("name");
            version = configData.get("version");
        }

        @Override
        public SchemaIndexProvider select(Class<SchemaIndexProvider> type, Iterable<SchemaIndexProvider> candidates) throws IllegalArgumentException {
            for (SchemaIndexProvider candidate : candidates) {
                final Descriptor descriptor = candidate.getProviderDescriptor();
                if (descriptor.getKey().equals(name) && (version == null || descriptor.getVersion().equals(version)))
                    return candidate;
            }
            return null;
        }
    }
}
