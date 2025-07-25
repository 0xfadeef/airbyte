/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.mssql.v2

import io.airbyte.cdk.load.check.DestinationChecker
import io.airbyte.cdk.load.command.Append
import io.airbyte.cdk.load.command.DestinationStream
import io.airbyte.cdk.load.command.NamespaceMapper
import io.airbyte.cdk.load.data.FieldType
import io.airbyte.cdk.load.data.IntegerType
import io.airbyte.cdk.load.data.ObjectType
import io.airbyte.cdk.load.message.DestinationRecordJsonSource
import io.airbyte.cdk.load.message.DestinationRecordRaw
import io.airbyte.cdk.load.util.Jsons
import io.airbyte.cdk.load.util.UUIDGenerator
import io.airbyte.integrations.destination.mssql.v2.config.AzureBlobStorageClientCreator
import io.airbyte.integrations.destination.mssql.v2.config.BulkLoadConfiguration
import io.airbyte.integrations.destination.mssql.v2.config.MSSQLConfiguration
import io.airbyte.integrations.destination.mssql.v2.config.MSSQLDataSourceFactory
import io.airbyte.protocol.models.v0.AirbyteMessage
import io.airbyte.protocol.models.v0.AirbyteRecordMessage
import jakarta.inject.Singleton
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking

@Singleton
class MSSQLChecker(
    private val dataSourceFactory: MSSQLDataSourceFactory,
    private val uuidGenerator: UUIDGenerator,
) : DestinationChecker<MSSQLConfiguration> {

    companion object {
        private const val TEST_CSV_FILENAME = "check_test_data.csv"
        private const val TEST_ID_VALUE = 1
        private const val COLUMN_NAME = "id"
    }

    private val testStream =
        DestinationStream(
            unmappedNamespace = null,
            unmappedName = "check_test_${UUID.randomUUID()}",
            importType = Append,
            schema =
                ObjectType(linkedMapOf(COLUMN_NAME to FieldType(IntegerType, nullable = true))),
            generationId = 0L,
            minimumGenerationId = 0L,
            syncId = 0L,
            namespaceMapper = NamespaceMapper()
        )

    override fun check(config: MSSQLConfiguration) {
        val dataSource: DataSource = dataSourceFactory.getDataSource(config)
        val sqlBuilder = MSSQLQueryBuilder(config.schema, testStream)

        dataSource.connection.use { connection ->
            try {
                // Create test table
                sqlBuilder.createTableIfNotExists(connection)

                // Perform bulk load test if configured
                if (
                    config.mssqlLoadTypeConfiguration.loadTypeConfiguration is BulkLoadConfiguration
                ) {
                    doBulkLoadTest(connection, config, dataSource, sqlBuilder)
                }
            } finally {
                // Drop the test table
                sqlBuilder.dropTable(connection)
            }
        }
    }

    private fun doBulkLoadTest(
        connection: Connection,
        config: MSSQLConfiguration,
        dataSource: DataSource,
        sqlBuilder: MSSQLQueryBuilder
    ) {
        val bulkLoadConfig =
            config.mssqlLoadTypeConfiguration.loadTypeConfiguration as BulkLoadConfiguration

        // Create necessary helpers
        val azureBlobClient =
            AzureBlobStorageClientCreator.createAzureBlobClient(
                bulkLoadConfiguration = bulkLoadConfig
            )
        val mssqlFormatFileCreator =
            MSSQLFormatFileCreator(
                dataSource,
                stream = testStream,
                azureBlobClient = azureBlobClient
            )
        val mssqlBulkLoadHandler =
            MSSQLBulkLoadHandler(
                dataSource,
                config.schema,
                testStream.mappedDescriptor.name,
                bulkLoadConfig.bulkLoadDataSource,
                sqlBuilder
            )

        // Prepare test CSV data
        val csvData = createTestCsvData(testStream)
        val csvFilePath = "${testStream.mappedDescriptor.name}/$TEST_CSV_FILENAME"

        // Upload files & perform bulk load
        runBlocking {
            // 1) Upload CSV
            val csvBlob = azureBlobClient.put(csvFilePath, csvData)

            // 2) Create and upload format file
            val formatFileBlob = mssqlFormatFileCreator.createAndUploadFormatFile(config.schema)

            try {
                // 3) Perform the actual bulk load
                mssqlBulkLoadHandler.bulkLoadForAppendOverwrite(csvBlob.key, formatFileBlob.key)

                // 4) Verify the data loaded successfully
                verifyDataLoaded(connection, config.schema, testStream.mappedDescriptor.name)
            } finally {
                // 5) Clean up remote files
                azureBlobClient.delete(formatFileBlob)
                azureBlobClient.delete(csvBlob)
            }
        }
    }

    /** Creates a CSV file with headers matching the required table structure and one test record */
    private fun createTestCsvData(stream: DestinationStream): ByteArray {
        return ByteArrayOutputStream().use { outputStream ->
            MSSQLCSVFormattingWriter(stream, outputStream, true).use { csvWriter ->
                // TODO this is kind of dumb
                val destinationRecord =
                    AirbyteMessage()
                        .withType(AirbyteMessage.Type.RECORD)
                        .withRecord(
                            AirbyteRecordMessage()
                                .withEmittedAt(0)
                                .withData(Jsons.valueToTree(mapOf(COLUMN_NAME to TEST_ID_VALUE)))
                        )
                        .let { message ->
                            DestinationRecordRaw(
                                stream,
                                DestinationRecordJsonSource(message),
                                Jsons.writeValueAsString(message).length.toLong(),
                                airbyteRawId = uuidGenerator.v7(),
                            )
                        }
                csvWriter.accept(destinationRecord)
                csvWriter.flush()
            }
            // Return the generated CSV data
            outputStream.toByteArray()
        }
    }

    /**
     * Verifies that the test data was successfully loaded into the table Uses quoted identifiers
     * for schema and table names to handle special characters properly
     */
    private fun verifyDataLoaded(connection: Connection, schema: String, tableName: String) {
        val query =
            "SELECT COUNT(*) FROM [${schema}].[${tableName}] WHERE [$COLUMN_NAME] = $TEST_ID_VALUE"
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { resultSet ->
                if (resultSet.next()) {
                    val count = resultSet.getInt(1)
                    if (count != 1) {
                        throw RuntimeException(
                            "Bulk load verification failed: Expected 1 record but found $count"
                        )
                    }
                } else {
                    throw RuntimeException("Bulk load verification failed: No results returned")
                }
            }
        }
    }
}
