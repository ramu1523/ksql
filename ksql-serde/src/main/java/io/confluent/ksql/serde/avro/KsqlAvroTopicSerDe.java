/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.serde.avro;

import static io.confluent.ksql.logging.processing.ProcessingLoggerUtil.join;

import com.google.errorprone.annotations.Immutable;
import io.confluent.connect.avro.AvroConverter;
import io.confluent.connect.avro.AvroDataConfig;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.logging.processing.ProcessingLogContext;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.KsqlTopicSerDe;
import io.confluent.ksql.serde.connect.KsqlConnectDeserializer;
import io.confluent.ksql.serde.connect.KsqlConnectSerializer;
import io.confluent.ksql.serde.tls.ThreadLocalDeserializer;
import io.confluent.ksql.serde.tls.ThreadLocalSerializer;
import io.confluent.ksql.serde.util.SerdeUtils;
import io.confluent.ksql.util.KsqlConfig;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.connect.data.Schema;

@Immutable
public class KsqlAvroTopicSerDe extends KsqlTopicSerDe {

  private final String fullSchemaName;

  public KsqlAvroTopicSerDe(final String fullSchemaName) {
    super(Format.AVRO);
    this.fullSchemaName = Objects.requireNonNull(fullSchemaName, "fullSchemaName").trim();
    if (this.fullSchemaName.isEmpty()) {
      throw new IllegalArgumentException("the schema name cannot be empty");
    }
  }

  private static AvroConverter getAvroConverter(
      final SchemaRegistryClient schemaRegistryClient, final KsqlConfig ksqlConfig) {
    final AvroConverter avroConverter = new AvroConverter(schemaRegistryClient);
    final Map<String, Object> avroConfig =
        ksqlConfig.originalsWithPrefix(KsqlConfig.KSQL_SCHEMA_REGISTRY_PREFIX);
    avroConfig.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
        ksqlConfig.getString(KsqlConfig.SCHEMA_REGISTRY_URL_PROPERTY));
    avroConfig.put(AvroDataConfig.CONNECT_META_DATA_CONFIG, false);
    avroConverter.configure(avroConfig, false);
    return avroConverter;
  }

  @Override
  public Serde<GenericRow> getGenericRowSerde(
      final Schema schema,
      final KsqlConfig ksqlConfig,
      final Supplier<SchemaRegistryClient> schemaRegistryClientFactory,
      final String loggerNamePrefix,
      final ProcessingLogContext processingLogContext
  ) {
    final Serializer<GenericRow> genericRowSerializer = new ThreadLocalSerializer(
        () -> new KsqlConnectSerializer(
            new AvroDataTranslator(
                schema,
                fullSchemaName,
                ksqlConfig.getBoolean(KsqlConfig.KSQL_USE_NAMED_AVRO_MAPS)
            ),
            getAvroConverter(schemaRegistryClientFactory.get(), ksqlConfig))
    );

    final Deserializer<GenericRow> genericRowDeserializer = new ThreadLocalDeserializer(
        () -> new KsqlConnectDeserializer(
            getAvroConverter(schemaRegistryClientFactory.get(), ksqlConfig),
            new AvroDataTranslator(
                schema,
                fullSchemaName,
                ksqlConfig.getBoolean(KsqlConfig.KSQL_USE_NAMED_AVRO_MAPS)
            ),
            processingLogContext.getLoggerFactory().getLogger(
                join(loggerNamePrefix, SerdeUtils.DESERIALIZER_LOGGER_NAME))
        )
    );

    return Serdes.serdeFrom(genericRowSerializer, genericRowDeserializer);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    final KsqlAvroTopicSerDe that = (KsqlAvroTopicSerDe) o;
    return Objects.equals(fullSchemaName, that.fullSchemaName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), fullSchemaName);
  }
}
