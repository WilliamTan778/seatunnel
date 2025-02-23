/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.file.source.reader;

import org.apache.seatunnel.api.serialization.DeserializationSchema;
import org.apache.seatunnel.api.source.Collector;
import org.apache.seatunnel.api.table.type.SeaTunnelRow;
import org.apache.seatunnel.api.table.type.SeaTunnelRowType;
import org.apache.seatunnel.common.exception.CommonErrorCodeDeprecated;
import org.apache.seatunnel.connectors.seatunnel.file.config.BaseSourceConfigOptions;
import org.apache.seatunnel.connectors.seatunnel.file.config.CompressFormat;
import org.apache.seatunnel.connectors.seatunnel.file.config.HadoopConf;
import org.apache.seatunnel.connectors.seatunnel.file.exception.FileConnectorException;
import org.apache.seatunnel.format.json.JsonDeserializationSchema;

import io.airlift.compress.lzo.LzopCodec;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
public class JsonReadStrategy extends AbstractReadStrategy {
    private DeserializationSchema<SeaTunnelRow> deserializationSchema;
    private CompressFormat compressFormat = BaseSourceConfigOptions.COMPRESS_CODEC.defaultValue();

    @Override
    public void init(HadoopConf conf) {
        super.init(conf);
        if (pluginConfig.hasPath(BaseSourceConfigOptions.COMPRESS_CODEC.key())) {
            String compressCodec =
                    pluginConfig.getString(BaseSourceConfigOptions.COMPRESS_CODEC.key());
            compressFormat = CompressFormat.valueOf(compressCodec.toUpperCase());
        }
    }

    @Override
    public void setSeaTunnelRowTypeInfo(SeaTunnelRowType seaTunnelRowType) {
        super.setSeaTunnelRowTypeInfo(seaTunnelRowType);
        if (isMergePartition) {
            deserializationSchema =
                    new JsonDeserializationSchema(false, false, this.seaTunnelRowTypeWithPartition);
        } else {
            deserializationSchema =
                    new JsonDeserializationSchema(false, false, this.seaTunnelRowType);
        }
    }

    @Override
    public void read(String path, String tableId, Collector<SeaTunnelRow> output)
            throws FileConnectorException, IOException {
        Map<String, String> partitionsMap = parsePartitionsByPath(path);
        InputStream inputStream;
        switch (compressFormat) {
            case LZO:
                LzopCodec lzo = new LzopCodec();
                inputStream = lzo.createInputStream(hadoopFileSystemProxy.getInputStream(path));
                break;
            case NONE:
                inputStream = hadoopFileSystemProxy.getInputStream(path);
                break;
            default:
                log.warn(
                        "Text file does not support this compress type: {}",
                        compressFormat.getCompressCodec());
                inputStream = hadoopFileSystemProxy.getInputStream(path);
                break;
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            reader.lines()
                    .forEach(
                            line -> {
                                try {
                                    SeaTunnelRow seaTunnelRow =
                                            deserializationSchema.deserialize(line.getBytes());
                                    if (isMergePartition) {
                                        int index = seaTunnelRowType.getTotalFields();
                                        for (String value : partitionsMap.values()) {
                                            seaTunnelRow.setField(index++, value);
                                        }
                                    }
                                    seaTunnelRow.setTableId(tableId);
                                    output.collect(seaTunnelRow);
                                } catch (IOException e) {
                                    String errorMsg =
                                            String.format(
                                                    "Read data from this file [%s] failed", path);
                                    throw new FileConnectorException(
                                            CommonErrorCodeDeprecated.FILE_OPERATION_FAILED,
                                            errorMsg);
                                }
                            });
        }
    }

    @Override
    public SeaTunnelRowType getSeaTunnelRowTypeInfo(String path) throws FileConnectorException {
        throw new FileConnectorException(
                CommonErrorCodeDeprecated.UNSUPPORTED_OPERATION,
                "User must defined schema for json file type");
    }
}
