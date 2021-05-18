/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.datastreams;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest.AliasActions;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesResponse;
import org.elasticsearch.action.admin.indices.close.CloseIndexRequest;
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest;
import org.elasticsearch.action.admin.indices.rollover.RolloverResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.common.collect.List;
import org.elasticsearch.cluster.metadata.DataStreamAlias;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.index.Index;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.snapshots.AbstractSnapshotIntegTestCase;
import org.elasticsearch.snapshots.RestoreInfo;
import org.elasticsearch.snapshots.SnapshotInProgressException;
import org.elasticsearch.snapshots.SnapshotInfo;
import org.elasticsearch.snapshots.SnapshotRestoreException;
import org.elasticsearch.snapshots.SnapshotState;
import org.elasticsearch.snapshots.mockstore.MockRepository;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.xpack.core.action.CreateDataStreamAction;
import org.elasticsearch.xpack.core.action.DeleteDataStreamAction;
import org.elasticsearch.xpack.core.action.GetDataStreamAction;
import org.elasticsearch.xpack.datastreams.DataStreamsPlugin;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@ESIntegTestCase.ClusterScope(transportClientRatio = 0)
public class DataStreamsSnapshotsIT extends AbstractSnapshotIntegTestCase {

    private static final String DS_BACKING_INDEX_NAME = DataStream.getDefaultBackingIndexName("ds", 1);
    private static final String DS2_BACKING_INDEX_NAME = DataStream.getDefaultBackingIndexName("ds2", 1);
    private static final Map<String, Integer> DOCUMENT_SOURCE = Collections.singletonMap("@timestamp", 123);
    public static final String REPO = "repo";
    public static final String SNAPSHOT = "snap";
    private Client client;

    private String id;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(MockRepository.Plugin.class, DataStreamsPlugin.class);
    }

    @Before
    public void setup() throws Exception {
        client = client();
        Path location = randomRepoPath();
        createRepository(REPO, "fs", location);

        DataStreamIT.putComposableIndexTemplate("t1", List.of("ds", "other-ds"));

        CreateDataStreamAction.Request request = new CreateDataStreamAction.Request("ds");
        AcknowledgedResponse response = client.execute(CreateDataStreamAction.INSTANCE, request).get();
        assertTrue(response.isAcknowledged());

        request = new CreateDataStreamAction.Request("other-ds");
        response = client.execute(CreateDataStreamAction.INSTANCE, request).get();
        assertTrue(response.isAcknowledged());

        IndexResponse indexResponse = client.prepareIndex("ds", "_doc")
            .setOpType(DocWriteRequest.OpType.CREATE)
            .setSource(DOCUMENT_SOURCE)
            .get();
        assertEquals(DocWriteResponse.Result.CREATED, indexResponse.getResult());
        id = indexResponse.getId();

        IndicesAliasesRequest aliasesRequest = new IndicesAliasesRequest();
        aliasesRequest.addAliasAction(new AliasActions(AliasActions.Type.ADD).alias("my-alias").index("ds"));
        aliasesRequest.addAliasAction(new AliasActions(AliasActions.Type.ADD).alias("my-alias").index("other-ds"));
        assertAcked(client.admin().indices().aliases(aliasesRequest).actionGet());
    }

    @After
    public void cleanup() {
        AcknowledgedResponse response = client().execute(
            DeleteDataStreamAction.INSTANCE,
            new DeleteDataStreamAction.Request(new String[] { "*" })
        ).actionGet();
        assertAcked(response);
    }

    public void testSnapshotAndRestore() throws Exception {
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setIncludeGlobalState(false)
            .get();

        RestStatus status = createSnapshotResponse.getSnapshotInfo().status();
        assertEquals(RestStatus.OK, status);

        assertEquals(Collections.singletonList(DS_BACKING_INDEX_NAME), getSnapshot(REPO, SNAPSHOT).indices());

        assertTrue(
            client.execute(DeleteDataStreamAction.INSTANCE, new DeleteDataStreamAction.Request(new String[] { "ds" }))
                .get()
                .isAcknowledged()
        );

        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .get();

        assertEquals(1, restoreSnapshotResponse.getRestoreInfo().successfulShards());

        assertEquals(DOCUMENT_SOURCE, client.prepareGet(DS_BACKING_INDEX_NAME, "_doc", id).get().getSourceAsMap());
        SearchHit[] hits = client.prepareSearch("ds").get().getHits().getHits();
        assertEquals(1, hits.length);
        assertEquals(DOCUMENT_SOURCE, hits[0].getSourceAsMap());

        GetDataStreamAction.Response ds = client.execute(
            GetDataStreamAction.INSTANCE,
            new GetDataStreamAction.Request(new String[] { "ds" })
        ).get();
        assertEquals(1, ds.getDataStreams().size());
        assertEquals(1, ds.getDataStreams().get(0).getDataStream().getIndices().size());
        assertEquals(DS_BACKING_INDEX_NAME, ds.getDataStreams().get(0).getDataStream().getIndices().get(0).getName());

        GetAliasesResponse getAliasesResponse = client.admin().indices().getAliases(new GetAliasesRequest("my-alias")).actionGet();
        assertThat(getAliasesResponse.getDataStreamAliases().keySet(), containsInAnyOrder("ds", "other-ds"));
        assertThat(getAliasesResponse.getDataStreamAliases().get("ds").size(), equalTo(1));
        assertThat(getAliasesResponse.getDataStreamAliases().get("ds").get(0).getName(), equalTo("my-alias"));
        assertThat(getAliasesResponse.getDataStreamAliases().get("other-ds").size(), equalTo(1));
        assertThat(getAliasesResponse.getDataStreamAliases().get("other-ds").get(0).getName(), equalTo("my-alias"));
    }

    public void testSnapshotAndRestoreAllDataStreamsInPlace() throws Exception {
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setIncludeGlobalState(false)
            .get();

        RestStatus status = createSnapshotResponse.getSnapshotInfo().status();
        assertEquals(RestStatus.OK, status);

        assertEquals(Collections.singletonList(DS_BACKING_INDEX_NAME), getSnapshot(REPO, SNAPSHOT).indices());

        // Close all indices:
        CloseIndexRequest closeIndexRequest = new CloseIndexRequest("*");
        closeIndexRequest.indicesOptions(IndicesOptions.strictExpandHidden());
        assertAcked(client.admin().indices().close(closeIndexRequest).actionGet());

        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .get();
        assertEquals(1, restoreSnapshotResponse.getRestoreInfo().successfulShards());

        assertEquals(DOCUMENT_SOURCE, client.prepareGet(DS_BACKING_INDEX_NAME, "_doc", id).get().getSourceAsMap());
        SearchHit[] hits = client.prepareSearch("ds").get().getHits().getHits();
        assertEquals(1, hits.length);
        assertEquals(DOCUMENT_SOURCE, hits[0].getSourceAsMap());

        GetDataStreamAction.Request getDataSteamRequest = new GetDataStreamAction.Request(new String[] { "*" });
        GetDataStreamAction.Response ds = client.execute(GetDataStreamAction.INSTANCE, getDataSteamRequest).get();
        assertThat(
            ds.getDataStreams().stream().map(e -> e.getDataStream().getName()).collect(Collectors.toList()),
            contains(equalTo("ds"), equalTo("other-ds"))
        );
        java.util.List<Index> backingIndices = ds.getDataStreams().get(0).getDataStream().getIndices();
        assertThat(backingIndices.stream().map(Index::getName).collect(Collectors.toList()), contains(DS_BACKING_INDEX_NAME));
        backingIndices = ds.getDataStreams().get(1).getDataStream().getIndices();
        String expectedBackingIndexName = DataStream.getDefaultBackingIndexName("other-ds", 1);
        assertThat(backingIndices.stream().map(Index::getName).collect(Collectors.toList()), contains(expectedBackingIndexName));
    }

    public void testSnapshotAndRestoreInPlace() throws Exception {
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setIncludeGlobalState(false)
            .get();

        RestStatus status = createSnapshotResponse.getSnapshotInfo().status();
        assertEquals(RestStatus.OK, status);

        assertEquals(Collections.singletonList(DS_BACKING_INDEX_NAME), getSnapshot(REPO, SNAPSHOT).indices());

        // A rollover after taking snapshot. The new backing index should be a standalone index after restoring
        // and not part of the data stream:
        RolloverRequest rolloverRequest = new RolloverRequest("ds", null);
        RolloverResponse rolloverResponse = client.admin().indices().rolloverIndex(rolloverRequest).actionGet();
        assertThat(rolloverResponse.isRolledOver(), is(true));
        assertThat(rolloverResponse.getNewIndex(), equalTo(DataStream.getDefaultBackingIndexName("ds", 2)));

        // Close all backing indices of ds data stream:
        CloseIndexRequest closeIndexRequest = new CloseIndexRequest(".ds-ds-*");
        closeIndexRequest.indicesOptions(IndicesOptions.strictExpandHidden());
        assertAcked(client.admin().indices().close(closeIndexRequest).actionGet());

        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .get();
        assertEquals(1, restoreSnapshotResponse.getRestoreInfo().successfulShards());

        assertEquals(DOCUMENT_SOURCE, client.prepareGet(DS_BACKING_INDEX_NAME, "_doc", id).get().getSourceAsMap());
        SearchHit[] hits = client.prepareSearch("ds").get().getHits().getHits();
        assertEquals(1, hits.length);
        assertEquals(DOCUMENT_SOURCE, hits[0].getSourceAsMap());

        GetDataStreamAction.Request getDataSteamRequest = new GetDataStreamAction.Request(new String[] { "ds" });
        GetDataStreamAction.Response ds = client.execute(GetDataStreamAction.INSTANCE, getDataSteamRequest).actionGet();
        assertThat(
            ds.getDataStreams().stream().map(e -> e.getDataStream().getName()).collect(Collectors.toList()),
            contains(equalTo("ds"))
        );
        java.util.List<Index> backingIndices = ds.getDataStreams().get(0).getDataStream().getIndices();
        assertThat(ds.getDataStreams().get(0).getDataStream().getIndices(), hasSize(1));
        assertThat(backingIndices.stream().map(Index::getName).collect(Collectors.toList()), contains(equalTo(DS_BACKING_INDEX_NAME)));

        // The backing index created as part of rollover should still exist (but just not part of the data stream)
        assertThat(indexExists(DataStream.getDefaultBackingIndexName("ds", 2)), is(true));
        // An additional rollover should create a new backing index (3th generation) and leave .ds-ds-...-2 index as is:
        rolloverRequest = new RolloverRequest("ds", null);
        rolloverResponse = client.admin().indices().rolloverIndex(rolloverRequest).actionGet();
        assertThat(rolloverResponse.isRolledOver(), is(true));
        assertThat(rolloverResponse.getNewIndex(), equalTo(DataStream.getDefaultBackingIndexName("ds", 3)));
    }

    public void testSnapshotAndRestoreAll() throws Exception {
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setIncludeGlobalState(false)
            .get();

        RestStatus status = createSnapshotResponse.getSnapshotInfo().status();
        assertEquals(RestStatus.OK, status);

        assertEquals(Collections.singletonList(DS_BACKING_INDEX_NAME), getSnapshot(REPO, SNAPSHOT).indices());

        assertAcked(client.execute(DeleteDataStreamAction.INSTANCE, new DeleteDataStreamAction.Request(new String[] { "*" })).get());
        assertAcked(client.admin().indices().prepareDelete("*").setIndicesOptions(IndicesOptions.LENIENT_EXPAND_OPEN_CLOSED_HIDDEN));

        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setRestoreGlobalState(true)
            .get();

        assertEquals(1, restoreSnapshotResponse.getRestoreInfo().successfulShards());

        assertEquals(DOCUMENT_SOURCE, client.prepareGet(DS_BACKING_INDEX_NAME, "_doc", id).get().getSourceAsMap());
        SearchHit[] hits = client.prepareSearch("ds").get().getHits().getHits();
        assertEquals(1, hits.length);
        assertEquals(DOCUMENT_SOURCE, hits[0].getSourceAsMap());

        GetDataStreamAction.Response ds = client.execute(
            GetDataStreamAction.INSTANCE,
            new GetDataStreamAction.Request(new String[] { "ds" })
        ).get();
        assertEquals(1, ds.getDataStreams().size());
        assertEquals(1, ds.getDataStreams().get(0).getDataStream().getIndices().size());
        assertEquals(DS_BACKING_INDEX_NAME, ds.getDataStreams().get(0).getDataStream().getIndices().get(0).getName());

        GetAliasesResponse getAliasesResponse = client.admin().indices().getAliases(new GetAliasesRequest("my-alias")).actionGet();
        assertThat(getAliasesResponse.getDataStreamAliases().keySet(), containsInAnyOrder("ds"));
        assertThat(getAliasesResponse.getDataStreamAliases().get("ds"), equalTo(List.of(new DataStreamAlias("my-alias", List.of("ds")))));

        assertAcked(client().execute(DeleteDataStreamAction.INSTANCE, new DeleteDataStreamAction.Request(new String[] { "ds" })).get());
    }

    public void testRename() throws Exception {
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setIncludeGlobalState(false)
            .get();

        RestStatus status = createSnapshotResponse.getSnapshotInfo().status();
        assertEquals(RestStatus.OK, status);

        expectThrows(
            SnapshotRestoreException.class,
            () -> client.admin().cluster().prepareRestoreSnapshot(REPO, SNAPSHOT).setWaitForCompletion(true).setIndices("ds").get()
        );

        client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setRenamePattern("ds")
            .setRenameReplacement("ds2")
            .get();

        GetDataStreamAction.Response ds = client.execute(
            GetDataStreamAction.INSTANCE,
            new GetDataStreamAction.Request(new String[] { "ds2" })
        ).get();
        assertEquals(1, ds.getDataStreams().size());
        assertEquals(1, ds.getDataStreams().get(0).getDataStream().getIndices().size());
        assertEquals(DS2_BACKING_INDEX_NAME, ds.getDataStreams().get(0).getDataStream().getIndices().get(0).getName());
        assertEquals(DOCUMENT_SOURCE, client.prepareSearch("ds2").get().getHits().getHits()[0].getSourceAsMap());
        assertEquals(DOCUMENT_SOURCE, client.prepareGet(DS2_BACKING_INDEX_NAME, "_doc", id).get().getSourceAsMap());

        GetAliasesResponse getAliasesResponse = client.admin().indices().getAliases(new GetAliasesRequest("my-alias")).actionGet();
        assertThat(getAliasesResponse.getDataStreamAliases().keySet(), containsInAnyOrder("ds", "ds2", "other-ds"));
        assertThat(getAliasesResponse.getDataStreamAliases().get("ds2").size(), equalTo(1));
        assertThat(getAliasesResponse.getDataStreamAliases().get("ds2").get(0).getName(), equalTo("my-alias"));
        assertThat(getAliasesResponse.getDataStreamAliases().get("ds").size(), equalTo(1));
        assertThat(getAliasesResponse.getDataStreamAliases().get("ds").get(0).getName(), equalTo("my-alias"));
        assertThat(getAliasesResponse.getDataStreamAliases().get("other-ds").size(), equalTo(1));
        assertThat(getAliasesResponse.getDataStreamAliases().get("other-ds").get(0).getName(), equalTo("my-alias"));
    }

    public void testBackingIndexIsNotRenamedWhenRestoringDataStream() {
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setIncludeGlobalState(false)
            .get();

        RestStatus status = createSnapshotResponse.getSnapshotInfo().status();
        assertEquals(RestStatus.OK, status);

        expectThrows(
            SnapshotRestoreException.class,
            () -> client.admin().cluster().prepareRestoreSnapshot(REPO, SNAPSHOT).setWaitForCompletion(true).setIndices("ds").get()
        );

        // delete data stream
        client.execute(DeleteDataStreamAction.INSTANCE, new DeleteDataStreamAction.Request(new String[] { "ds" })).actionGet();

        // restore data stream attempting to rename the backing index
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setRenamePattern(DS_BACKING_INDEX_NAME)
            .setRenameReplacement("new_index_name")
            .get();

        assertThat(restoreSnapshotResponse.status(), is(RestStatus.OK));

        GetDataStreamAction.Request getDSRequest = new GetDataStreamAction.Request(new String[] { "ds" });
        GetDataStreamAction.Response response = client.execute(GetDataStreamAction.INSTANCE, getDSRequest).actionGet();
        assertThat(response.getDataStreams().get(0).getDataStream().getIndices().get(0).getName(), is(DS_BACKING_INDEX_NAME));
    }

    public void testDataStreamAndBackingIndicesAreRenamedUsingRegex() {
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setIncludeGlobalState(false)
            .get();

        RestStatus status = createSnapshotResponse.getSnapshotInfo().status();
        assertEquals(RestStatus.OK, status);

        expectThrows(
            SnapshotRestoreException.class,
            () -> client.admin().cluster().prepareRestoreSnapshot(REPO, SNAPSHOT).setWaitForCompletion(true).setIndices("ds").get()
        );

        // restore data stream attempting to rename the backing index
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, SNAPSHOT)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setRenamePattern("(.+)")
            .setRenameReplacement("test-$1")
            .get();

        assertThat(restoreSnapshotResponse.status(), is(RestStatus.OK));

        // assert "ds" was restored as "test-ds" and the backing index has a valid name
        GetDataStreamAction.Request getRenamedDS = new GetDataStreamAction.Request(new String[] { "test-ds" });
        GetDataStreamAction.Response response = client.execute(GetDataStreamAction.INSTANCE, getRenamedDS).actionGet();
        assertThat(
            response.getDataStreams().get(0).getDataStream().getIndices().get(0).getName(),
            is(DataStream.getDefaultBackingIndexName("test-ds", 1L))
        );

        // data stream "ds" should still exist in the system
        GetDataStreamAction.Request getDSRequest = new GetDataStreamAction.Request(new String[] { "ds" });
        response = client.execute(GetDataStreamAction.INSTANCE, getDSRequest).actionGet();
        assertThat(response.getDataStreams().get(0).getDataStream().getIndices().get(0).getName(), is(DS_BACKING_INDEX_NAME));
    }

    public void testWildcards() throws Exception {
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, "snap2")
            .setWaitForCompletion(true)
            .setIndices("d*")
            .setIncludeGlobalState(false)
            .get();

        RestStatus status = createSnapshotResponse.getSnapshotInfo().status();
        assertEquals(RestStatus.OK, status);

        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, "snap2")
            .setWaitForCompletion(true)
            .setIndices("d*")
            .setRenamePattern("ds")
            .setRenameReplacement("ds2")
            .get();

        assertEquals(RestStatus.OK, restoreSnapshotResponse.status());

        GetDataStreamAction.Response ds = client.execute(
            GetDataStreamAction.INSTANCE,
            new GetDataStreamAction.Request(new String[] { "ds2" })
        ).get();
        assertEquals(1, ds.getDataStreams().size());
        assertEquals(1, ds.getDataStreams().get(0).getDataStream().getIndices().size());
        assertEquals(DS2_BACKING_INDEX_NAME, ds.getDataStreams().get(0).getDataStream().getIndices().get(0).getName());
        assertThat(
            "we renamed the restored data stream to one that doesn't match any existing composable template",
            ds.getDataStreams().get(0).getIndexTemplate(),
            is(nullValue())
        );
    }

    public void testDataStreamNotStoredWhenIndexRequested() {
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, "snap2")
            .setWaitForCompletion(true)
            .setIndices(DS_BACKING_INDEX_NAME)
            .setIncludeGlobalState(false)
            .get();

        RestStatus status = createSnapshotResponse.getSnapshotInfo().status();
        assertEquals(RestStatus.OK, status);
        expectThrows(
            Exception.class,
            () -> client.admin().cluster().prepareRestoreSnapshot(REPO, "snap2").setWaitForCompletion(true).setIndices("ds").get()
        );
    }

    public void testDataStreamNotRestoredWhenIndexRequested() throws Exception {
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, "snap2")
            .setWaitForCompletion(true)
            .setIndices("ds")
            .setIncludeGlobalState(false)
            .get();

        RestStatus status = createSnapshotResponse.getSnapshotInfo().status();
        assertEquals(RestStatus.OK, status);

        assertTrue(
            client.execute(DeleteDataStreamAction.INSTANCE, new DeleteDataStreamAction.Request(new String[] { "ds" }))
                .get()
                .isAcknowledged()
        );

        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, "snap2")
            .setWaitForCompletion(true)
            .setIndices(".ds-ds-*")
            .get();

        assertEquals(RestStatus.OK, restoreSnapshotResponse.status());

        GetDataStreamAction.Request getRequest = new GetDataStreamAction.Request(new String[] { "ds" });
        expectThrows(ResourceNotFoundException.class, () -> client.execute(GetDataStreamAction.INSTANCE, getRequest).actionGet());
    }

    public void testDataStreamNotIncludedInLimitedSnapshot() throws ExecutionException, InterruptedException {
        final String snapshotName = "test-snap";
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(REPO, snapshotName)
            .setWaitForCompletion(true)
            .setIndices("does-not-exist-*")
            .setIncludeGlobalState(true)
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().state(), Matchers.is(SnapshotState.SUCCESS));

        assertThat(
            client().execute(DeleteDataStreamAction.INSTANCE, new DeleteDataStreamAction.Request(new String[] { "*" }))
                .get()
                .isAcknowledged(),
            is(true)
        );

        final RestoreSnapshotResponse restoreSnapshotResponse = client().admin().cluster().prepareRestoreSnapshot(REPO, snapshotName).get();
        assertThat(restoreSnapshotResponse.getRestoreInfo().indices(), empty());
    }

    public void testDeleteDataStreamDuringSnapshot() throws Exception {
        Client client = client();

        // this test uses a MockRepository
        assertAcked(client().admin().cluster().prepareDeleteRepository(REPO));

        final String repositoryName = "test-repo";
        createRepository(
            repositoryName,
            "mock",
            Settings.builder()
                .put("location", randomRepoPath())
                .put("compress", randomBoolean())
                .put("chunk_size", randomIntBetween(100, 1000), ByteSizeUnit.BYTES)
                .put("block_on_data", true)
        );

        String dataStream = "datastream";
        DataStreamIT.putComposableIndexTemplate("dst", Collections.singletonList(dataStream));

        logger.info("--> indexing some data");
        for (int i = 0; i < 100; i++) {
            client.prepareIndex(dataStream, "_doc")
                .setOpType(DocWriteRequest.OpType.CREATE)
                .setId(Integer.toString(i))
                .setSource(Collections.singletonMap("@timestamp", "2020-12-12"))
                .execute()
                .actionGet();
        }
        refresh();
        assertDocCount(dataStream, 100L);

        logger.info("--> snapshot");
        ActionFuture<CreateSnapshotResponse> future = client.admin()
            .cluster()
            .prepareCreateSnapshot(repositoryName, SNAPSHOT)
            .setIndices(dataStream)
            .setWaitForCompletion(true)
            .setPartial(false)
            .execute();
        logger.info("--> wait for block to kick in");
        waitForBlockOnAnyDataNode(repositoryName);

        // non-partial snapshots do not allow delete operations on data streams where snapshot has not been completed
        try {
            logger.info("--> delete index while non-partial snapshot is running");
            client.execute(DeleteDataStreamAction.INSTANCE, new DeleteDataStreamAction.Request(new String[] { dataStream })).actionGet();
            fail("Expected deleting index to fail during snapshot");
        } catch (SnapshotInProgressException e) {
            assertThat(e.getMessage(), containsString("Cannot delete data streams that are being snapshotted: [" + dataStream));
        } finally {
            logger.info("--> unblock all data nodes");
            unblockAllDataNodes(repositoryName);
        }
        logger.info("--> waiting for snapshot to finish");
        CreateSnapshotResponse createSnapshotResponse = future.get();

        logger.info("--> snapshot successfully completed");
        SnapshotInfo snapshotInfo = createSnapshotResponse.getSnapshotInfo();
        assertThat(snapshotInfo.state(), equalTo((SnapshotState.SUCCESS)));
        assertThat(snapshotInfo.dataStreams(), contains(dataStream));
        assertThat(snapshotInfo.indices(), contains(DataStream.getDefaultBackingIndexName(dataStream, 1)));
    }

    public void testCloneSnapshotThatIncludesDataStream() throws Exception {
        final String sourceSnapshotName = "snap-source";
        final String indexWithoutDataStream = "test-idx-no-ds";
        createIndexWithContent(indexWithoutDataStream);
        assertSuccessful(
            client.admin()
                .cluster()
                .prepareCreateSnapshot(REPO, sourceSnapshotName)
                .setWaitForCompletion(true)
                .setIndices("ds", indexWithoutDataStream)
                .setIncludeGlobalState(false)
                .execute()
        );
        assertAcked(
            client().admin()
                .cluster()
                .prepareCloneSnapshot(REPO, sourceSnapshotName, "target-snapshot-1")
                .setIndices(indexWithoutDataStream)
                .get()
        );
    }

    public void testPartialRestoreSnapshotThatIncludesDataStream() {
        final String snapshot = "test-snapshot";
        final String indexWithoutDataStream = "test-idx-no-ds";
        createIndexWithContent(indexWithoutDataStream);
        createFullSnapshot(REPO, snapshot);
        assertAcked(client.admin().indices().prepareDelete(indexWithoutDataStream));
        RestoreInfo restoreInfo = client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, snapshot)
            .setIndices(indexWithoutDataStream)
            .setWaitForCompletion(true)
            .setRestoreGlobalState(randomBoolean())
            .get()
            .getRestoreInfo();
        assertThat(restoreInfo.failedShards(), is(0));
        assertThat(restoreInfo.successfulShards(), is(1));
    }

    public void testSnapshotDSDuringRollover() throws Exception {
        // repository consistency check requires at least one snapshot per registered repository
        createFullSnapshot(REPO, "snap-so-repo-checks-pass");
        final String repoName = "mock-repo";
        createRepository(repoName, "mock");
        final boolean partial = randomBoolean();
        blockAllDataNodes(repoName);
        final String snapshotName = "ds-snap";
        final ActionFuture<CreateSnapshotResponse> snapshotFuture = client().admin()
            .cluster()
            .prepareCreateSnapshot(repoName, snapshotName)
            .setWaitForCompletion(true)
            .setPartial(partial)
            .setIncludeGlobalState(randomBoolean())
            .execute();
        waitForBlockOnAnyDataNode(repoName);
        awaitNumberOfSnapshotsInProgress(1);
        final ActionFuture<RolloverResponse> rolloverResponse = client().admin().indices().rolloverIndex(new RolloverRequest("ds", null));

        if (partial) {
            assertTrue(rolloverResponse.get().isRolledOver());
        } else {
            SnapshotInProgressException e = expectThrows(SnapshotInProgressException.class, rolloverResponse::actionGet);
            assertThat(e.getMessage(), containsString("Cannot roll over data stream that is being snapshotted:"));
        }
        unblockAllDataNodes(repoName);
        final SnapshotInfo snapshotInfo = assertSuccessful(snapshotFuture);

        assertThat(snapshotInfo.dataStreams(), hasItems("ds"));
        assertAcked(client().execute(DeleteDataStreamAction.INSTANCE, new DeleteDataStreamAction.Request(new String[] { "ds" })).get());

        RestoreInfo restoreSnapshotResponse = client().admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName)
            .setWaitForCompletion(true)
            .setIndices("ds")
            .get()
            .getRestoreInfo();

        assertEquals(restoreSnapshotResponse.successfulShards(), restoreSnapshotResponse.totalShards());
        assertEquals(restoreSnapshotResponse.failedShards(), 0);
    }

    public void testSnapshotDSDuringRolloverAndDeleteOldIndex() throws Exception {
        // repository consistency check requires at least one snapshot per registered repository
        createFullSnapshot(REPO, "snap-so-repo-checks-pass");
        final String repoName = "mock-repo";
        createRepository(repoName, "mock");
        blockAllDataNodes(repoName);
        final String snapshotName = "ds-snap";
        final ActionFuture<CreateSnapshotResponse> snapshotFuture = client().admin()
            .cluster()
            .prepareCreateSnapshot(repoName, snapshotName)
            .setWaitForCompletion(true)
            .setPartial(true)
            .setIncludeGlobalState(randomBoolean())
            .execute();
        waitForBlockOnAnyDataNode(repoName);
        awaitNumberOfSnapshotsInProgress(1);
        final RolloverResponse rolloverResponse = client().admin().indices().rolloverIndex(new RolloverRequest("ds", null)).get();
        assertTrue(rolloverResponse.isRolledOver());

        logger.info("--> deleting former write index");
        assertAcked(client().admin().indices().prepareDelete(rolloverResponse.getOldIndex()));

        unblockAllDataNodes(repoName);
        final SnapshotInfo snapshotInfo = assertSuccessful(snapshotFuture);

        assertThat(
            "snapshot should not contain 'ds' since none of its indices existed both at the start and at the end of the snapshot",
            snapshotInfo.dataStreams(),
            not(hasItems("ds"))
        );
        assertAcked(
            client().execute(DeleteDataStreamAction.INSTANCE, new DeleteDataStreamAction.Request(new String[] { "other-ds" })).get()
        );

        RestoreInfo restoreSnapshotResponse = client().admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName)
            .setWaitForCompletion(true)
            .setIndices("other-ds")
            .get()
            .getRestoreInfo();

        assertEquals(restoreSnapshotResponse.successfulShards(), restoreSnapshotResponse.totalShards());
        assertEquals(restoreSnapshotResponse.failedShards(), 0);
    }

    public void testExcludeDSFromSnapshotWhenExcludingItsIndices() {
        final String snapshot = "test-snapshot";
        final String indexWithoutDataStream = "test-idx-no-ds";
        createIndexWithContent(indexWithoutDataStream);
        final SnapshotInfo snapshotInfo = createSnapshot(REPO, snapshot, List.of("*", "-.*"));
        assertThat(snapshotInfo.dataStreams(), empty());
        assertAcked(client.admin().indices().prepareDelete(indexWithoutDataStream));
        RestoreInfo restoreInfo = client.admin()
            .cluster()
            .prepareRestoreSnapshot(REPO, snapshot)
            .setWaitForCompletion(true)
            .setRestoreGlobalState(randomBoolean())
            .get()
            .getRestoreInfo();
        assertThat(restoreInfo.failedShards(), is(0));
        assertThat(restoreInfo.successfulShards(), is(1));
    }
}
