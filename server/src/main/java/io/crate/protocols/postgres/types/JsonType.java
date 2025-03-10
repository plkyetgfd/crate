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

package io.crate.protocols.postgres.types;

import io.netty.buffer.ByteBuf;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.DeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

class JsonType extends PGType<Object> {

    public static final JsonType INSTANCE = new JsonType();
    static final int OID = 114;

    private static final int TYPE_LEN = -1;
    private static final int TYPE_MOD = -1;

    private JsonType() {
        super(OID, TYPE_LEN, TYPE_MOD, "json");
    }

    @Override
    public int typArray() {
        return PGArray.JSON_ARRAY.oid();
    }

    @Override
    public String typeCategory() {
        return TypeCategory.USER_DEFINED_TYPES.code();
    }

    @Override
    public String type() {
        return Type.BASE.code();
    }

    @Override
    public int writeAsBinary(ByteBuf buffer, @Nonnull Object value) {
        byte[] bytes = encodeAsUTF8Text(value);
        buffer.writeInt(bytes.length);
        buffer.writeBytes(bytes);
        return INT32_BYTE_SIZE + bytes.length;
    }

    @Override
    protected byte[] encodeAsUTF8Text(@Nonnull Object value) {
        if (value instanceof String str) {
            return str.getBytes(StandardCharsets.UTF_8);
        }
        try {
            XContentBuilder builder = JsonXContent.contentBuilder();
            if (value instanceof List<?> values) {
                builder.startArray();
                for (Object o : values) {
                    builder.value(o);
                }
                builder.endArray();
            } else {
                builder.map((Map) value);
            }
            builder.close();
            return BytesReference.toBytes(BytesReference.bytes(builder));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object readBinaryValue(ByteBuf buffer, int valueLength) {
        byte[] bytes = new byte[valueLength];
        buffer.readBytes(bytes);
        return decodeUTF8Text(bytes);
    }

    @Override
    Object decodeUTF8Text(byte[] bytes) {
        try {
            XContentParser parser = JsonXContent.JSON_XCONTENT.createParser(
                NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION, bytes);
            if (bytes.length > 1 && bytes[0] == '[') {
                parser.nextToken();
                return parser.list();
            }
            return parser.map();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
