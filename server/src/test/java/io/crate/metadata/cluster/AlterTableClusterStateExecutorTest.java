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

package io.crate.metadata.cluster;

import io.crate.execution.ddl.tables.AlterTableRequest;
import io.crate.metadata.PartitionName;
import io.crate.metadata.RelationName;
import io.crate.metadata.Schemas;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexTemplateMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;

import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_CREATION_DATE;
import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.common.settings.AbstractScopedSettings.ARCHIVED_SETTINGS_PREFIX;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AlterTableClusterStateExecutorTest {

    @Test
    public void testPrivateSettingsAreRemovedOnUpdateTemplate() throws IOException {
        IndexScopedSettings indexScopedSettings = new IndexScopedSettings(Settings.EMPTY, Collections.emptySet());

        RelationName relationName = new RelationName(Schemas.DOC_SCHEMA_NAME, "t1");
        String templateName = PartitionName.templateName(relationName.schema(), relationName.name());

        Settings settings = Settings.builder()
            .put(SETTING_CREATION_DATE, false)      // private, must be filtered out
            .put(SETTING_NUMBER_OF_SHARDS, 4)
            .build();
        IndexTemplateMetadata indexTemplateMetadata = IndexTemplateMetadata.builder(templateName)
            .patterns(Collections.singletonList("*"))
            .settings(settings)
            .build();

        ClusterState initialState = ClusterState.builder(ClusterState.EMPTY_STATE)
            .metadata(Metadata.builder().put(indexTemplateMetadata))
            .build();

        ClusterState result =
            AlterTableClusterStateExecutor.updateTemplate(initialState,
                                                          relationName,
                                                          settings,
                                                          Collections.emptyMap(),
                                                          (x, y) -> { },
                                                          indexScopedSettings);

        IndexTemplateMetadata template = result.getMetadata().getTemplates().get(templateName);
        assertThat(template.settings().keySet(), contains(SETTING_NUMBER_OF_SHARDS));
    }

    @Test
    public void testMarkArchivedSettings() {
        Settings.Builder builder = Settings.builder()
            .put(SETTING_NUMBER_OF_SHARDS, 4);
        Settings preparedSettings = AlterTableClusterStateExecutor.markArchivedSettings(builder.build());
        assertThat(preparedSettings.keySet(), containsInAnyOrder(SETTING_NUMBER_OF_SHARDS, ARCHIVED_SETTINGS_PREFIX + "*"));
    }

    @Test
    public void testAddExistingMetaDoNotAddEmptyValues() throws IOException {
        AlterTableRequest request = mock(AlterTableRequest.class);
        when(request.mappingDeltaAsMap()).thenReturn(Collections.singletonMap("_meta", new HashMap<String,Object>()));

        var currentMeta = new HashMap<String,Object>();

        assertThat(AlterTableClusterStateExecutor.addExistingMeta(request, currentMeta), containsString("{\"_meta\":{}}"));
        verify(request, times(1)).mappingDeltaAsMap();
        // DO NOT WANT empty containers and nulls: "{"_meta":{"indices":{},"partitioned_by":[],"primary_keys":null,"check_constraints":null,"constraints":{}}}"
    }

}
