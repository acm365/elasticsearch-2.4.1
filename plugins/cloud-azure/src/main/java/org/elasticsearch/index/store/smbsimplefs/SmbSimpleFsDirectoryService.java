/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.store.smbsimplefs;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.store.SimpleFSDirectory;
import org.elasticsearch.cloud.azure.SmbDirectoryWrapper;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.index.settings.IndexSettingsService;
import org.elasticsearch.index.shard.ShardPath;
import org.elasticsearch.index.store.FsDirectoryService;
import org.elasticsearch.index.store.IndexStore;

import java.io.IOException;
import java.nio.file.Path;

public class SmbSimpleFsDirectoryService extends FsDirectoryService {

    @Inject
    public SmbSimpleFsDirectoryService(IndexSettingsService indexSettingsService, IndexStore indexStore, ShardPath path) {
        super(indexSettingsService.getSettings(), indexStore, path);
    }

    @Override
    protected Directory newFSDirectory(Path location, LockFactory lockFactory) throws IOException {
        logger.debug("wrapping SimpleFSDirectory for SMB");
        return new SmbDirectoryWrapper(new SimpleFSDirectory(location, lockFactory));
    }
}
