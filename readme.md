# A Delegating Schema Index Provider

It is intended to be a placeholder for other, schema and legacy index providers (e.g. lucene fulltext or spatial).

It uses a part of the Neo4j database configuration to determine which index to use for which :Label(property) combination.

````
index-wrapper.Label.property=name:lucene,type:fulltext,to_lower_case:true
index-wrapper.Foo.bar=name:mapdb-index
index-wrapper.Location.coords=name:spatial
````

It then resolves the config at startup and lazily for unknown indexes and then delegates to either the Schema-Index-Provider
or to a Wrapper around the legacy index.

Queries that become possible:

    // lucene fulltext
    match n:Label where n.property="a*"

    // mapdb schema
    match n:Foo where n.bar=42

    // spatial
    match n:Location where n.coords="withinWKTGeometry:POLYGON ((15 56, 15 57, 16 57, 16 56, 15 56))"

One idea is to have small Serializer around the original index to for instance allow a array with [lon,lat] be stored
in the Spatial index provider and on lookup you can still use queries.

Currently doesn't work (except for the setup) because adding data to schema index providers happens too late in the transaction cycle,
so that no writes are allowed anymore:

```
Caused by: java.lang.IllegalStateException: Tx status is: STATUS_COMMITING
	at org.neo4j.kernel.impl.transaction.TransactionImpl.enlistResource(TransactionImpl.java:255)
	at org.neo4j.kernel.impl.transaction.xaframework.XaConnectionHelpImpl.enlistResource(XaConnectionHelpImpl.java:103)
	at org.neo4j.kernel.impl.index.IndexConnectionBroker.acquireResourceConnection(IndexConnectionBroker.java:57)
	at org.neo4j.index.impl.lucene.LuceneIndex.getConnection(LuceneIndex.java:85)
	at org.neo4j.index.impl.lucene.LuceneIndex.add(LuceneIndex.java:140)
	at org.neo4j.index.wrapper.WrapperSchemaIndexProvider$LegacyWrapperIndex.add(WrapperSchemaIndexProvider.java:179)
	at org.neo4j.index.wrapper.WrapperSchemaIndexProvider$LegacyWrapperIndex.update(WrapperSchemaIndexProvider.java:193)
	at org.neo4j.index.wrapper.WrapperSchemaIndexProvider$LegacyWrapperIndex.updateAndCommit(WrapperSchemaIndexProvider.java:210)
	at org.neo4j.kernel.impl.api.index.OnlineIndexProxy.update(OnlineIndexProxy.java:55)
	at org.neo4j.kernel.impl.api.index.FlippableIndexProxy.update(FlippableIndexProxy.java:91)
	at org.neo4j.kernel.impl.api.index.AbstractDelegatingIndexProxy.update(AbstractDelegatingIndexProxy.java:44)
	at org.neo4j.kernel.impl.api.index.ContractCheckingIndexProxy.update(ContractCheckingIndexProxy.java:95)
	at org.neo4j.kernel.impl.api.index.AbstractDelegatingIndexProxy.update(AbstractDelegatingIndexProxy.java:44)
	at org.neo4j.kernel.impl.api.index.RuleUpdateFilterIndexProxy.update(RuleUpdateFilterIndexProxy.java:49)
	at org.neo4j.kernel.impl.api.index.AbstractDelegatingIndexProxy.update(AbstractDelegatingIndexProxy.java:44)
	at org.neo4j.kernel.impl.api.index.IndexingService.updateIndexes(IndexingService.java:279)
	at org.neo4j.kernel.impl.nioneo.xa.WriteTransaction.applyCommit(WriteTransaction.java:620)
	at org.neo4j.kernel.impl.nioneo.xa.WriteTransaction.doCommit(WriteTransaction.java:574)
	at org.neo4j.kernel.impl.transaction.xaframework.XaTransaction.commit(XaTransaction.java:321)
	at org.neo4j.kernel.impl.transaction.xaframework.XaResourceManager.commit(XaResourceManager.java:488)
	at org.neo4j.kernel.impl.transaction.xaframework.XaResourceHelpImpl.commit(XaResourceHelpImpl.java:64)
	at org.neo4j.kernel.impl.transaction.TransactionImpl.doCommit(TransactionImpl.java:567)
````