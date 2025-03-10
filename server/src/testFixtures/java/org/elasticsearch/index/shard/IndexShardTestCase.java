/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */
package org.elasticsearch.index.shard;

import io.crate.common.CheckedFunction;
import io.crate.common.io.IOUtils;
import org.apache.lucene.store.Directory;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.replication.TransportReplicationAction;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingHelper;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.MapperTestUtils;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.cache.IndexCache;
import org.elasticsearch.index.cache.query.DisabledQueryCache;
import org.elasticsearch.index.engine.DocIdSeqNoAndSource;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineFactory;
import org.elasticsearch.index.engine.EngineTestCase;
import org.elasticsearch.index.engine.InternalEngineFactory;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.seqno.ReplicationTracker;
import org.elasticsearch.index.seqno.RetentionLeaseSyncer;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.snapshots.IndexShardSnapshotStatus;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.indices.breaker.HierarchyCircuitBreakerService;
import org.elasticsearch.indices.recovery.AsyncRecoveryTarget;
import org.elasticsearch.indices.recovery.PeerRecoveryTargetService;
import org.elasticsearch.indices.recovery.RecoveryFailedException;
import org.elasticsearch.indices.recovery.RecoveryResponse;
import org.elasticsearch.indices.recovery.RecoverySourceHandler;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.indices.recovery.RecoveryTarget;
import org.elasticsearch.indices.recovery.StartRecoveryRequest;
import org.elasticsearch.repositories.ESBlobStoreTestCase;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.test.DummyShardLock;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.elasticsearch.cluster.routing.TestShardRouting.newShardRouting;
import static org.elasticsearch.index.translog.Translog.UNSET_AUTO_GENERATED_TIMESTAMP;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * A base class for unit tests that need to create and shutdown {@link IndexShard} instances easily,
 * containing utilities for shard creation and recoveries. See {{@link #newShard(boolean)}} and
 * {@link #newStartedShard()} for a good starting points
 */
public abstract class IndexShardTestCase extends ESTestCase {

    public static final IndexEventListener EMPTY_EVENT_LISTENER = new IndexEventListener() {};

    private static final AtomicBoolean failOnShardFailures = new AtomicBoolean(true);

    private static final Consumer<IndexShard.ShardFailure> DEFAULT_SHARD_FAILURE_HANDLER = failure -> {
        if (failOnShardFailures.get()) {
            throw new AssertionError(failure.reason, failure.cause);
        }
    };

    protected static final PeerRecoveryTargetService.RecoveryListener recoveryListener = new PeerRecoveryTargetService.RecoveryListener() {
        @Override
        public void onRecoveryDone(RecoveryState state) {

        }

        @Override
        public void onRecoveryFailure(RecoveryState state, RecoveryFailedException e, boolean sendShardFailure) {
            throw new AssertionError(e);
        }
    };

    protected ThreadPool threadPool;
    protected long primaryTerm;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = setUpThreadPool();
        primaryTerm = randomIntBetween(1, 100); // use random but fixed term for creating shards
        failOnShardFailures();
    }

    protected ThreadPool setUpThreadPool() {
        return new TestThreadPool(getClass().getName(), threadPoolSettings());
    }

    @Override
    public void tearDown() throws Exception {
        try {
            tearDownThreadPool();
        } finally {
            super.tearDown();
        }
    }

    protected void tearDownThreadPool() {
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
    }

    /**
     * by default, tests will fail if any shard created by this class fails. Tests that cause failures by design
     * can call this method to ignore those failures
     *
     */
    protected void allowShardFailures() {
        failOnShardFailures.set(false);
    }

    protected void failOnShardFailures() {
        failOnShardFailures.set(true);
    }

    public Settings threadPoolSettings() {
        return Settings.EMPTY;
    }

    protected Store createStore(IndexSettings indexSettings, ShardPath shardPath) throws IOException {
        return createStore(shardPath.getShardId(), indexSettings, newFSDirectory(shardPath.resolveIndex()));
    }

    protected Store createStore(ShardId shardId, IndexSettings indexSettings, Directory directory) throws IOException {
        return new Store(shardId, indexSettings, directory, new DummyShardLock(shardId));
    }

    protected Engine.IndexResult indexDoc(IndexShard shard, String id) throws IOException {
        return indexDoc(shard, id, "{}");
    }

    protected Engine.IndexResult indexDoc(IndexShard shard, String id, String source) throws IOException {
        return indexDoc(shard, id, source, XContentType.JSON, null);
    }

    protected Engine.IndexResult indexDoc(IndexShard shard,
                                          String id,
                                          String source,
                                          XContentType xContentType,
                                          String routing) throws IOException {
        SourceToParse sourceToParse = new SourceToParse(
            shard.shardId().getIndexName(), id, new BytesArray(source), xContentType, routing);
        Engine.IndexResult result;
        if (shard.routingEntry().primary()) {
            result = shard.applyIndexOperationOnPrimary(
                Versions.MATCH_ANY,
                VersionType.INTERNAL,
                sourceToParse,
                SequenceNumbers.UNASSIGNED_SEQ_NO,
                0,
                UNSET_AUTO_GENERATED_TIMESTAMP,
                false);
            if (result.getResultType() == Engine.Result.Type.MAPPING_UPDATE_REQUIRED) {
                updateMappings(shard, IndexMetadata.builder(shard.indexSettings().getIndexMetadata())
                    .putMapping("default", result.getRequiredMappingUpdate().toString()).build());
                result = shard.applyIndexOperationOnPrimary(
                    Versions.MATCH_ANY,
                    VersionType.INTERNAL,
                    sourceToParse,
                    SequenceNumbers.UNASSIGNED_SEQ_NO,
                    0,
                    UNSET_AUTO_GENERATED_TIMESTAMP,
                    false);
            }
            shard.sync(); // advance local checkpoint
            shard.updateLocalCheckpointForShard(shard.routingEntry().allocationId().getId(),
                                                shard.getLocalCheckpoint());
        } else {
            final long seqNo = shard.seqNoStats().getMaxSeqNo() + 1;
            shard.advanceMaxSeqNoOfUpdatesOrDeletes(seqNo); // manually replicate max_seq_no_of_updates
            result = shard.applyIndexOperationOnReplica(seqNo, shard.getOperationPrimaryTerm(), 0, UNSET_AUTO_GENERATED_TIMESTAMP, false, sourceToParse);
            shard.sync(); // advance local checkpoint
            if (result.getResultType() == Engine.Result.Type.MAPPING_UPDATE_REQUIRED) {
                throw new TransportReplicationAction.RetryOnReplicaException(
                    shard.shardId,
                    "Mappings are not available on the replica yet, triggered update: " +
                    result.getRequiredMappingUpdate());
            }
        }
        return result;
    }

    protected void updateMappings(IndexShard shard, IndexMetadata indexMetadata) {
        shard.indexSettings().updateIndexMetadata(indexMetadata);
        shard.mapperService().merge(indexMetadata, MapperService.MergeReason.MAPPING_UPDATE);
    }

    protected void assertDocCount(IndexShard shard, int docDount) throws IOException {
        assertThat(getShardDocUIDs(shard), hasSize(docDount));
    }

    protected Engine.DeleteResult deleteDoc(IndexShard shard, String id) throws IOException {
        Engine.DeleteResult result;
        if (shard.routingEntry().primary()) {
            result = shard.applyDeleteOperationOnPrimary(
                Versions.MATCH_ANY, id, VersionType.INTERNAL, SequenceNumbers.UNASSIGNED_SEQ_NO, 0);
            shard.sync(); // advance local checkpoint
            shard.updateLocalCheckpointForShard(
                shard.routingEntry().allocationId().getId(),
                shard.getLocalCheckpoint()
            );
        } else {
            long seqNo = shard.seqNoStats().getMaxSeqNo() + 1;
            shard.advanceMaxSeqNoOfUpdatesOrDeletes(seqNo); // manually replicate max_seq_no_of_updates
            result = shard.applyDeleteOperationOnReplica(seqNo, primaryTerm, 0L, id);
            shard.sync(); // advance local checkpoint
        }
        return result;
    }

    protected void flushShard(IndexShard shard) {
        flushShard(shard, false);
    }

    public static void flushShard(IndexShard shard, boolean force) {
        shard.flush(new FlushRequest(shard.shardId().getIndexName()).force(force));
    }

    public static boolean recoverFromStore(IndexShard newShard) {
        final PlainActionFuture<Boolean> future = PlainActionFuture.newFuture();
        newShard.recoverFromStore(future);
        return future.actionGet();
    }

    /**
     * creates a new initializing shard. The shard will have its own unique data path.
     *
     * @param primary indicates whether to a primary shard (ready to recover from an empty store) or a replica
     *                (ready to recover from another shard)
     */
    protected IndexShard newShard(boolean primary) throws IOException {
        return newShard(primary, Settings.EMPTY);
    }

    /**
     * Creates a new initializing shard. The shard will have its own unique data path.
     *
     * @param primary indicates whether to a primary shard (ready to recover from an empty store) or a replica (ready to recover from
     *                another shard)
     */
    protected IndexShard newShard(final boolean primary, final Settings settings) throws IOException {
        return newShard(primary, settings, new InternalEngineFactory());
    }

    /**
     * Creates a new initializing shard. The shard will have its own unique data path.
     *
     * @param primary       indicates whether to a primary shard (ready to recover from an empty store) or a replica (ready to recover from
     *                      another shard)
     * @param settings      the settings to use for this shard
     * @param engineFactory the engine factory to use for this shard
     */
    protected IndexShard newShard(boolean primary, Settings settings, EngineFactory engineFactory) throws IOException {
        final RecoverySource recoverySource =
                primary ? RecoverySource.EmptyStoreRecoverySource.INSTANCE : RecoverySource.PeerRecoverySource.INSTANCE;
        final ShardRouting shardRouting =
                TestShardRouting.newShardRouting(
                        new ShardId("index", "_na_", 0), randomAlphaOfLength(10), primary, ShardRoutingState.INITIALIZING, recoverySource);
        return newShard(shardRouting, settings, engineFactory);
    }

    protected IndexShard newShard(ShardRouting shardRouting, final IndexingOperationListener... listeners) throws IOException {
        return newShard(shardRouting, Settings.EMPTY, new InternalEngineFactory(), listeners);
    }

    /**
     * Creates a new initializing shard. The shard will have its own unique data path.
     *
     * @param shardRouting  the {@link ShardRouting} to use for this shard
     * @param settings      the settings to use for this shard
     * @param engineFactory the engine factory to use for this shard
     * @param listeners     an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(
            final ShardRouting shardRouting,
            final Settings settings,
            final EngineFactory engineFactory,
            final IndexingOperationListener... listeners) throws IOException {
        assert shardRouting.initializing() : shardRouting;
        Settings indexSettings = Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), randomBoolean())
                .put(IndexSettings.INDEX_SOFT_DELETES_RETENTION_OPERATIONS_SETTING.getKey(),
                randomBoolean() ? IndexSettings.INDEX_SOFT_DELETES_RETENTION_OPERATIONS_SETTING.get(Settings.EMPTY) : between(0, 1000))
                .put(settings)
                .build();
        IndexMetadata.Builder metadata = IndexMetadata.builder(shardRouting.getIndexName())
            .settings(indexSettings)
            .primaryTerm(0, primaryTerm)
            .putMapping("default", "{ \"properties\": {} }");
        return newShard(shardRouting, metadata.build(), engineFactory, () -> {}, listeners);
    }

    /**
     * creates a new initializing shard. The shard will have its own unique data path.
     *
     * @param shardId   the shard id to use
     * @param primary   indicates whether to a primary shard (ready to recover from an empty store) or a replica
     *                  (ready to recover from another shard)
     * @param listeners an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(ShardId shardId, boolean primary, IndexingOperationListener... listeners) throws IOException {
        ShardRouting shardRouting = TestShardRouting.newShardRouting(shardId, randomAlphaOfLength(5), primary,
            ShardRoutingState.INITIALIZING,
            primary ? RecoverySource.EmptyStoreRecoverySource.INSTANCE : RecoverySource.PeerRecoverySource.INSTANCE);
        return newShard(shardRouting, Settings.EMPTY, new InternalEngineFactory(), listeners);
    }

    /**
     * creates a new initializing shard. The shard will will be put in its proper path under the
     * supplied node id.
     *
     * @param shardId the shard id to use
     * @param primary indicates whether to a primary shard (ready to recover from an empty store) or a replica
     *                (ready to recover from another shard)
     */
    protected IndexShard newShard(ShardId shardId,
                                  boolean primary,
                                  String nodeId,
                                  IndexMetadata indexMetadata) throws IOException {
        return newShard(shardId, primary, nodeId, indexMetadata, () -> {});
    }

    /**
     * creates a new initializing shard. The shard will will be put in its proper path under the
     * supplied node id.
     *
     * @param shardId the shard id to use
     * @param primary indicates whether to a primary shard (ready to recover from an empty store) or a replica
     *                (ready to recover from another shard)
     */
    protected IndexShard newShard(ShardId shardId,
                                  boolean primary,
                                  String nodeId,
                                  IndexMetadata indexMetadata,
                                  Runnable globalCheckpointSyncer) throws IOException {
        ShardRouting shardRouting = TestShardRouting.newShardRouting(shardId, nodeId, primary, ShardRoutingState.INITIALIZING,
            primary ? RecoverySource.EmptyStoreRecoverySource.INSTANCE : RecoverySource.PeerRecoverySource.INSTANCE);
        return newShard(shardRouting, indexMetadata, new InternalEngineFactory(), globalCheckpointSyncer);
    }

    /**
     * creates a new initializing shard. The shard will will be put in its proper path under the
     * current node id the shard is assigned to.
     *
     * @param routing       shard routing to use
     * @param indexMetadata indexMetadata for the shard, including any mapping
     * @param listeners     an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(
            ShardRouting routing, IndexMetadata indexMetadata, EngineFactory engineFactory, IndexingOperationListener... listeners)
        throws IOException {
        return newShard(routing, indexMetadata, engineFactory, () -> {}, listeners);
    }

    /**
     * creates a new initializing shard. The shard will will be put in its proper path under the
     * current node id the shard is assigned to.
     * @param routing                shard routing to use
     * @param indexMetadata          indexMetadata for the shard, including any mapping
     * @param globalCheckpointSyncer callback for syncing global checkpoints
     * @param listeners              an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(ShardRouting routing,
                                  IndexMetadata indexMetadata,
                                  @Nullable EngineFactory engineFactory,
                                  Runnable globalCheckpointSyncer,
                                  IndexingOperationListener... listeners) throws IOException {
        // add node id as name to settings for proper logging
        final ShardId shardId = routing.shardId();
        final NodeEnvironment.NodePath nodePath = new NodeEnvironment.NodePath(createTempDir());
        ShardPath shardPath = new ShardPath(false, nodePath.resolve(shardId), nodePath.resolve(shardId), shardId);
        return newShard(
            routing,
            shardPath,
            indexMetadata,
            null,
            engineFactory,
            globalCheckpointSyncer,
            EMPTY_EVENT_LISTENER,
            listeners
        );
    }

    /**
     * creates a new initializing shard.
     * @param routing                       shard routing to use
     * @param shardPath                     path to use for shard data
     * @param indexMetadata                 indexMetadata for the shard, including any mapping
     * @param storeProvider                 an optional custom store provider to use. If null a default file based store will be created
     * @param globalCheckpointSyncer        callback for syncing global checkpoints
     * @param indexEventListener            index event listener
     * @param listeners                     an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(ShardRouting routing,
                                  ShardPath shardPath,
                                  IndexMetadata indexMetadata,
                                  @Nullable CheckedFunction<IndexSettings, Store, IOException> storeProvider,
                                  @Nullable EngineFactory engineFactory,
                                  Runnable globalCheckpointSyncer,
                                  IndexEventListener indexEventListener,
                                  IndexingOperationListener... listeners) throws IOException {
        return newShard(
            routing,
            shardPath,
            indexMetadata,
            storeProvider,
            engineFactory,
            globalCheckpointSyncer,
            RetentionLeaseSyncer.EMPTY,
            indexEventListener,
            listeners
        );
    }

    /**
     * creates a new initializing shard.
     * @param routing                       shard routing to use
     * @param shardPath                     path to use for shard data
     * @param indexMetadata                 indexMetadata for the shard, including any mapping
     * @param storeProvider                 an optional custom store provider to use. If null a default file based store will be created
     * @param globalCheckpointSyncer        callback for syncing global checkpoints
     * @param indexEventListener            index event listener
     * @param listeners                     an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(ShardRouting routing,
                                  ShardPath shardPath,
                                  IndexMetadata indexMetadata,
                                  @Nullable CheckedFunction<IndexSettings, Store, IOException> storeProvider,
                                  @Nullable EngineFactory engineFactory,
                                  Runnable globalCheckpointSyncer,
                                  RetentionLeaseSyncer retentionLeaseSyncer,
                                  IndexEventListener indexEventListener,
                                  IndexingOperationListener... listeners) throws IOException {
        final Settings nodeSettings = Settings.builder().put("node.name", routing.currentNodeId()).build();
        final IndexSettings indexSettings = new IndexSettings(indexMetadata, nodeSettings);
        final IndexShard indexShard;
        if (storeProvider == null) {
            storeProvider = is -> createStore(is, shardPath);
        }
        final Store store = storeProvider.apply(indexSettings);
        boolean success = false;
        try {
            IndexCache indexCache = new IndexCache(indexSettings, new DisabledQueryCache(indexSettings));
            MapperService mapperService = MapperTestUtils.newMapperService(
                xContentRegistry(),
                createTempDir(),
                indexSettings.getSettings(),
                "index"
            );
            mapperService.merge(indexMetadata, MapperService.MergeReason.MAPPING_RECOVERY);
            ClusterSettings clusterSettings = new ClusterSettings(nodeSettings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
            CircuitBreakerService breakerService = new HierarchyCircuitBreakerService(nodeSettings, clusterSettings);
            indexShard = new IndexShard(
                routing,
                indexSettings,
                shardPath,
                store,
                indexCache,
                mapperService,
                engineFactory,
                indexEventListener,
                threadPool,
                BigArrays.NON_RECYCLING_INSTANCE,
                Arrays.asList(listeners),
                globalCheckpointSyncer,
                retentionLeaseSyncer,
                breakerService
            );
            indexShard.addShardFailureCallback(DEFAULT_SHARD_FAILURE_HANDLER);
            success = true;
        } finally {
            if (success == false) {
                IOUtils.close(store);
            }
        }
        return indexShard;
    }

    /**
     * Takes an existing shard, closes it and starts a new initialing shard at the same location
     *
     * @param listeners new listerns to use for the newly created shard
     */
    protected IndexShard reinitShard(IndexShard current, IndexingOperationListener... listeners) throws IOException {
        final ShardRouting shardRouting = current.routingEntry();
        return reinitShard(current, ShardRoutingHelper.initWithSameId(shardRouting,
            shardRouting.primary() ? RecoverySource.ExistingStoreRecoverySource.INSTANCE : RecoverySource.PeerRecoverySource.INSTANCE
        ), listeners);
    }

    /**
     * Takes an existing shard, closes it and starts a new initialing shard at the same location
     *
     * @param routing   the shard routing to use for the newly created shard.
     * @param listeners new listerns to use for the newly created shard
     */
    protected IndexShard reinitShard(IndexShard current, ShardRouting routing, IndexingOperationListener... listeners) throws IOException {
        closeShards(current);
        return newShard(
                routing,
                current.shardPath(),
                current.indexSettings().getIndexMetadata(),
                null,
                current.engineFactory,
                current.getGlobalCheckpointSyncer(),
            EMPTY_EVENT_LISTENER, listeners);
    }


    /**
     * Takes an existing shard, closes it and starts a new initialing shard at the same location
     *
     * @param routing       the shard routing to use for the newly created shard.
     * @param listeners     new listerns to use for the newly created shard
     * @param engineFactory the engine factory for the new shard
     */
    protected IndexShard reinitShard(IndexShard current,
                                     ShardRouting routing,
                                     EngineFactory engineFactory,
                                     IndexingOperationListener... listeners) throws IOException {
        closeShards(current);
        return newShard(
            routing,
            current.shardPath(),
            current.indexSettings().getIndexMetadata(),
            null,
            engineFactory,
            current.getGlobalCheckpointSyncer(),
            EMPTY_EVENT_LISTENER,
            listeners
        );
    }

    /**
     * Takes an existing shard, closes it and starts a new initialing shard at the same location
     *
     * @param routing       the shard routing to use for the newly created shard.
     * @param listeners     new listerns to use for the newly created shard
     * @param indexMetadata the index metadata to use for the newly created shard
     * @param engineFactory the engine factory for the new shard
     */
    protected IndexShard reinitShard(IndexShard current,
                                     ShardRouting routing,
                                     IndexMetadata indexMetadata,
                                     EngineFactory engineFactory,
                                     IndexingOperationListener... listeners) throws IOException {
        closeShards(current);
        return newShard(
            routing,
            current.shardPath(),
            indexMetadata,
            null,
            engineFactory,
            current.getGlobalCheckpointSyncer(),
            EMPTY_EVENT_LISTENER,
            listeners
        );
    }

    /**
     * Creates a new empty shard and starts it. The shard will randomly be a replica or a primary.
     */
    protected IndexShard newStartedShard() throws IOException {
        return newStartedShard(randomBoolean());
    }

    /**
     * creates a new empty shard and starts it.
     *
     * @param primary controls whether the shard will be a primary or a replica.
     */
    protected IndexShard newStartedShard(final boolean primary) throws IOException {
        return newStartedShard(primary, Settings.EMPTY);
    }

    protected IndexShard newStartedShard(final boolean primary, Settings settings) throws IOException {
        return newStartedShard(primary, settings, new InternalEngineFactory());
    }

    /**
     * Creates a new empty shard with the specified settings and engine factory and starts it.
     *
     * @param primary       controls whether the shard will be a primary or a replica.
     * @param settings      the settings to use for this shard
     * @param engineFactory the engine factory to use for this shard
     */
    protected IndexShard newStartedShard(
            final boolean primary, final Settings settings, final EngineFactory engineFactory) throws IOException {
        return newStartedShard(p -> newShard(p, settings, engineFactory), primary);
    }

    /**
     * creates a new empty shard and starts it.
     *
     * @param shardFunction shard factory function
     * @param primary controls whether the shard will be a primary or a replica.
     */
    protected IndexShard newStartedShard(CheckedFunction<Boolean, IndexShard, IOException> shardFunction,
                                         boolean primary) throws IOException {
        IndexShard shard = shardFunction.apply(primary);
        if (primary) {
            recoverShardFromStore(shard);
            assertThat(shard.getMaxSeqNoOfUpdatesOrDeletes(), equalTo(shard.seqNoStats().getMaxSeqNo()));
        } else {
            recoveryEmptyReplica(shard, true);
        }
        return shard;
    }

    protected void closeShards(IndexShard... shards) throws IOException {
        closeShards(Arrays.asList(shards));
    }

    protected void closeShard(IndexShard shard, boolean assertConsistencyBetweenTranslogAndLucene) throws IOException {
        try {
            if (assertConsistencyBetweenTranslogAndLucene) {
                assertConsistentHistoryBetweenTranslogAndLucene(shard);
            }
            final Engine engine = shard.getEngineOrNull();
            if (engine != null) {
                EngineTestCase.assertAtMostOneLuceneDocumentPerSequenceNumber(engine);
            }
        } finally {
            IOUtils.close(() -> shard.close("test", false), shard.store());
        }
    }

    protected void closeShards(Iterable<IndexShard> shards) throws IOException {
        for (IndexShard shard : shards) {
            if (shard != null) {
                closeShard(shard, true);
            }
        }
    }

    protected void recoverShardFromStore(IndexShard primary) throws IOException {
        primary.markAsRecovering("store", new RecoveryState(primary.routingEntry(),
            getFakeDiscoNode(primary.routingEntry().currentNodeId()),
            null));
        recoverFromStore(primary);
        updateRoutingEntry(primary, ShardRoutingHelper.moveToStarted(primary.routingEntry()));
    }

    protected static AtomicLong currentClusterStateVersion = new AtomicLong();

    public static void updateRoutingEntry(IndexShard shard, ShardRouting shardRouting) throws IOException {
        Set<String> inSyncIds =
            shardRouting.active() ? Collections.singleton(shardRouting.allocationId().getId()) : Collections.emptySet();
        IndexShardRoutingTable newRoutingTable = new IndexShardRoutingTable.Builder(shardRouting.shardId())
            .addShard(shardRouting)
            .build();
        shard.updateShardState(
            shardRouting,
            shard.getPendingPrimaryTerm(),
            null,
            currentClusterStateVersion.incrementAndGet(),
            inSyncIds,
            newRoutingTable);
    }

    protected void recoveryEmptyReplica(IndexShard replica, boolean startReplica) throws IOException {
        IndexShard primary = null;
        try {
            primary = newStartedShard(true);
            recoverReplica(replica, primary, startReplica);
        } finally {
            closeShards(primary);
        }
    }

    protected DiscoveryNode getFakeDiscoNode(String id) {
        return new DiscoveryNode(id, id, buildNewFakeTransportAddress(), Collections.emptyMap(), DiscoveryNodeRole.BUILT_IN_ROLES,
            Version.CURRENT);
    }

    /** recovers a replica from the given primary **/
    protected void recoverReplica(IndexShard replica, IndexShard primary, boolean startReplica) throws IOException {
        recoverReplica(
            replica,
            primary,
            (r, sourceNode) -> new RecoveryTarget(r, sourceNode, recoveryListener),
            true,
            true
        );
    }

    /** recovers a replica from the given primary **/
    protected void recoverReplica(final IndexShard replica,
                                  final IndexShard primary,
                                  final BiFunction<IndexShard, DiscoveryNode, RecoveryTarget> targetSupplier,
                                  final boolean markAsRecovering, final boolean markAsStarted) throws IOException {
        IndexShardRoutingTable.Builder newRoutingTable = new IndexShardRoutingTable.Builder(replica.shardId());
        newRoutingTable.addShard(primary.routingEntry());
        if (replica.routingEntry().isRelocationTarget() == false) {
            newRoutingTable.addShard(replica.routingEntry());
        }
        final Set<String> inSyncIds = Collections.singleton(primary.routingEntry().allocationId().getId());
        final IndexShardRoutingTable routingTable = newRoutingTable.build();
        recoverUnstartedReplica(replica, primary, targetSupplier, markAsRecovering, inSyncIds, routingTable);
        if (markAsStarted) {
            startReplicaAfterRecovery(replica, primary, inSyncIds, routingTable);
        }
    }

    /**
     * Recovers a replica from the give primary, allow the user to supply a custom recovery target. A typical usage of a custom recovery
     * target is to assert things in the various stages of recovery.
     *
     * Note: this method keeps the shard in {@link IndexShardState#POST_RECOVERY} and doesn't start it.
     *
     * @param replica                the recovery target shard
     * @param primary                the recovery source shard
     * @param targetSupplier         supplies an instance of {@link RecoveryTarget}
     * @param markAsRecovering       set to {@code false} if the replica is marked as recovering
     */
    protected final void recoverUnstartedReplica(final IndexShard replica,
                                                 final IndexShard primary,
                                                 final BiFunction<IndexShard, DiscoveryNode, RecoveryTarget> targetSupplier,
                                                 final boolean markAsRecovering,
                                                 final Set<String> inSyncIds,
                                                 final IndexShardRoutingTable routingTable) throws IOException {
        final DiscoveryNode pNode = getFakeDiscoNode(primary.routingEntry().currentNodeId());
        final DiscoveryNode rNode = getFakeDiscoNode(replica.routingEntry().currentNodeId());
        if (markAsRecovering) {
            replica.markAsRecovering("remote", new RecoveryState(replica.routingEntry(), pNode, rNode));
        } else {
            assertEquals(replica.state(), IndexShardState.RECOVERING);
        }
        replica.prepareForIndexRecovery();
        final RecoveryTarget recoveryTarget = targetSupplier.apply(replica, pNode);
        final long startingSeqNo = recoveryTarget.indexShard().recoverLocallyUpToGlobalCheckpoint();
        final StartRecoveryRequest request = PeerRecoveryTargetService.getStartRecoveryRequest(
            logger, rNode, recoveryTarget, startingSeqNo);
        final RecoverySourceHandler recovery = new RecoverySourceHandler(
            primary,
            new AsyncRecoveryTarget(recoveryTarget, threadPool.generic()),
            threadPool,
            request,
            Math.toIntExact(ByteSizeUnit.MB.toBytes(1)),
            between(1, 8));
        primary.updateShardState(
            primary.routingEntry(),
            primary.getPendingPrimaryTerm(),
            null,
            currentClusterStateVersion.incrementAndGet(),
            inSyncIds,
            routingTable
        );
        try {
            PlainActionFuture<RecoveryResponse> future = new PlainActionFuture<>();
            recovery.recoverToTarget(future);
            future.actionGet();
            recoveryTarget.markAsDone();
        } catch (Exception e) {
            recoveryTarget.fail(new RecoveryFailedException(request, e), false);
            throw e;
        }
    }

    protected void startReplicaAfterRecovery(IndexShard replica, IndexShard primary, Set<String> inSyncIds,
                                             IndexShardRoutingTable routingTable) throws IOException {
        ShardRouting initializingReplicaRouting = replica.routingEntry();
        IndexShardRoutingTable newRoutingTable =
            initializingReplicaRouting.isRelocationTarget() ?
                new IndexShardRoutingTable.Builder(routingTable)
                    .removeShard(primary.routingEntry())
                    .addShard(replica.routingEntry())
                    .build() :
                new IndexShardRoutingTable.Builder(routingTable)
                .removeShard(initializingReplicaRouting)
                .addShard(replica.routingEntry())
                .build();
        Set<String> inSyncIdsWithReplica = new HashSet<>(inSyncIds);
        inSyncIdsWithReplica.add(replica.routingEntry().allocationId().getId());
        // update both primary and replica shard state
        primary.updateShardState(
            primary.routingEntry(),
            primary.getPendingPrimaryTerm(),
            null,
            currentClusterStateVersion.incrementAndGet(),
            inSyncIdsWithReplica,
            newRoutingTable
        );
        replica.updateShardState(
            replica.routingEntry().moveToStarted(),
            replica.getPendingPrimaryTerm(),
            null,
            currentClusterStateVersion.get(),
            inSyncIdsWithReplica,
            newRoutingTable
        );
    }


    /**
     * promotes a replica to primary, incrementing it's term and starting it if needed
     */
    protected void promoteReplica(IndexShard replica, Set<String> inSyncIds, IndexShardRoutingTable routingTable) throws IOException {
        assertThat(inSyncIds, contains(replica.routingEntry().allocationId().getId()));
        final ShardRouting routingEntry = newShardRouting(
            replica.routingEntry().shardId(),
            replica.routingEntry().currentNodeId(),
            null,
            true,
            ShardRoutingState.STARTED,
            replica.routingEntry().allocationId());

        final IndexShardRoutingTable newRoutingTable = new IndexShardRoutingTable.Builder(routingTable)
            .removeShard(replica.routingEntry())
            .addShard(routingEntry)
            .build();
        replica.updateShardState(routingEntry, replica.getPendingPrimaryTerm() + 1,
            (is, listener) ->
                listener.onResponse(new PrimaryReplicaSyncer.ResyncTask()),
            currentClusterStateVersion.incrementAndGet(),
            inSyncIds, newRoutingTable);
    }

    private Store.MetadataSnapshot getMetadataSnapshotOrEmpty(IndexShard replica) throws IOException {
        Store.MetadataSnapshot result;
        try {
            result = replica.snapshotStoreMetadata();
        } catch (IndexNotFoundException e) {
            // OK!
            result = Store.MetadataSnapshot.EMPTY;
        } catch (IOException e) {
            logger.warn("failed read store, treating as empty", e);
            result = Store.MetadataSnapshot.EMPTY;
        }
        return result;
    }

    public static Set<String> getShardDocUIDs(final IndexShard shard) throws IOException {
        return getDocIdAndSeqNos(shard).stream().map(DocIdSeqNoAndSource::getId).collect(Collectors.toSet());
    }

    public static List<DocIdSeqNoAndSource> getDocIdAndSeqNos(final IndexShard shard) throws IOException {
        return EngineTestCase.getDocIds(shard.getEngine(), true);
    }

    public static void assertConsistentHistoryBetweenTranslogAndLucene(IndexShard shard) throws IOException {
        if (shard.state() != IndexShardState.POST_RECOVERY && shard.state() != IndexShardState.STARTED) {
            return;
        }
        final Engine engine = shard.getEngineOrNull();
        if (engine != null) {
            EngineTestCase.assertConsistentHistoryBetweenTranslogAndLuceneIndex(engine, shard.mapperService());
        }
    }

    protected void assertDocs(IndexShard shard, String... ids) throws IOException {
        final Set<String> shardDocUIDs = getShardDocUIDs(shard);
        assertThat(shardDocUIDs, contains(ids));
        assertThat(shardDocUIDs, hasSize(ids.length));
    }


    /** Recover a shard from a snapshot using a given repository **/
    protected void recoverShardFromSnapshot(final IndexShard shard,
                                            final Snapshot snapshot,
                                            final Repository repository) {
        final Version version = Version.CURRENT;
        final ShardId shardId = shard.shardId();
        final String index = shardId.getIndexName();
        final IndexId indexId = new IndexId(shardId.getIndex().getName(), shardId.getIndex().getUUID());
        final DiscoveryNode node = getFakeDiscoNode(shard.routingEntry().currentNodeId());
        final RecoverySource.SnapshotRecoverySource recoverySource =
            new RecoverySource.SnapshotRecoverySource(UUIDs.randomBase64UUID(), snapshot, version, index);
        final ShardRouting shardRouting = newShardRouting(shardId, node.getId(), true, ShardRoutingState.INITIALIZING, recoverySource);
        shard.markAsRecovering("from snapshot", new RecoveryState(shardRouting, node, null));
        final PlainActionFuture<Void> future = PlainActionFuture.newFuture();
        repository.restoreShard(shard.store(),
                                snapshot.getSnapshotId(),
                                indexId,
                                shard.shardId(),
                                shard.recoveryState(),
                                future);
        future.actionGet();
    }

    /**
     * Snapshot a shard using a given repository.
     *
     * @return new shard generation
     */
    protected String snapshotShard(final IndexShard shard,
                                   final Snapshot snapshot,
                                   final Repository repository) throws IOException {
        final Index index = shard.shardId().getIndex();
        final IndexId indexId = new IndexId(index.getName(), index.getUUID());
        final IndexShardSnapshotStatus snapshotStatus = IndexShardSnapshotStatus.newInitializing(
            ESBlobStoreTestCase.getRepositoryData(repository).shardGenerations().getShardGen(
                indexId, shard.shardId().getId()));
        final PlainActionFuture<String> future = PlainActionFuture.newFuture();
        final String shardGen;
        try (Engine.IndexCommitRef indexCommitRef = shard.acquireLastIndexCommit(true)) {
            repository.snapshotShard(shard.store(), shard.mapperService(), snapshot.getSnapshotId(), indexId,
                                     indexCommitRef.getIndexCommit(), snapshotStatus, true, future);
            shardGen = future.actionGet();
        }

        final IndexShardSnapshotStatus.Copy lastSnapshotStatus = snapshotStatus.asCopy();
        assertEquals(IndexShardSnapshotStatus.Stage.DONE, lastSnapshotStatus.getStage());
        assertEquals(shard.snapshotStoreMetadata().size(), lastSnapshotStatus.getTotalFileCount());
        assertNull(lastSnapshotStatus.getFailure());
        return shardGen;
    }

    /**
     * Helper method to access (package-protected) engine from tests
     */
    public static Engine getEngine(IndexShard indexShard) {
        return indexShard.getEngine();
    }

    public static Translog getTranslog(IndexShard shard) {
        return EngineTestCase.getTranslog(getEngine(shard));
    }

    public static ReplicationTracker getReplicationTracker(IndexShard indexShard) {
        return indexShard.getReplicationTracker();
    }
}
