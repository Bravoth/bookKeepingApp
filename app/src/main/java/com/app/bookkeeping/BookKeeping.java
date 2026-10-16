package com.app.bookkeeping;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * BookKeeping — reads a Venmo CSV export, categorizes each transaction using
 * Amazon Nova Micro via Bedrock, and loads everything into DynamoDB.
 * The table name is derived from the year and month in the first row's Datetime column,
 * e.g. "Bookkeeping_2026_08".
 */
public class BookKeeping {

    static final Region REGION = Region.US_EAST_1;

    // CSV column indexes
    static final int COL_ID         = 1;
    static final int COL_DATETIME   = 2;
    static final int COL_TYPE       = 3;
    static final int COL_STATUS     = 4;
    static final int COL_NOTE       = 5;
    static final int COL_FROM       = 6;
    static final int COL_TO         = 7;
    static final int COL_AMOUNT     = 8;
    static final int COL_TIP        = 9;
    static final int COL_TAX        = 10;
    static final int COL_FEE        = 11;
    static final int COL_TAX_RATE   = 12;
    static final int COL_TAX_EXEMPT = 13;
    static final int COL_FUNDING    = 14;
    static final int COL_DEST       = 15;
    static final int COL_BEG_BAL    = 16;
    static final int COL_END_BAL    = 17;
    static final int COL_STMT_FEES  = 18;
    static final int COL_TERMINAL   = 19;
    static final int COL_YTD_FEES   = 20;
    static final int COL_DISCLAIMER = 21;

    public static void main(String[] args) {

        if (args.length < 1) {
            System.err.println("Usage: BookKeeping <path-to-csv>");
            System.err.println("Example: BookKeeping data/Aug_2026.csv");
            return;
        }

        String csvPath = args[0];

        DynamoDbClient dynamo = DynamoDbClient.builder()
                .region(REGION)
                .build();

        BedrockRuntimeClient bedrock = BedrockRuntimeClient.builder()
                .region(REGION)
                .build();

        try (CSVReader reader = new CSVReader(new FileReader(new File(csvPath)))) {

            // Skip: account info row, "Account Activity" row, column headers row, opening balance row
            reader.skip(4);

            List<String[]> rows = reader.readAll();

            // Derive table name from the datetime of the first valid row
            // Datetime format: 2026-08-01T00:37:19 -> "Bookkeeping_2026_08"
            String tableName = deriveTableName(rows);
            System.out.println("Using table: " + tableName);

            createTableIfNotExists(dynamo, tableName);

            for (String[] row : rows) {
                if (row.length <= COL_ID || row[COL_ID].isBlank()) continue;

                String id     = row[COL_ID].trim();
                String to     = safeGet(row, COL_TO);
                String from   = safeGet(row, COL_FROM);
                String note   = safeGet(row, COL_NOTE);
                String amount = safeGet(row, COL_AMOUNT);
                String type   = safeGet(row, COL_TYPE);

                String category = categorize(bedrock, to, from, note, amount, type);

                Map<String, AttributeValue> item = new HashMap<>();
                item.put("ID",            attr(id));
                item.put("Datetime",      attr(safeGet(row, COL_DATETIME)));
                item.put("Type",          attr(type));
                item.put("Status",        attr(safeGet(row, COL_STATUS)));
                item.put("Note",          attr(note));
                item.put("From",          attr(from));
                item.put("To",            attr(to));
                item.put("Amount",        attr(amount));
                item.put("Tip",           attr(safeGet(row, COL_TIP)));
                item.put("Tax",           attr(safeGet(row, COL_TAX)));
                item.put("Fee",           attr(safeGet(row, COL_FEE)));
                item.put("TaxRate",       attr(safeGet(row, COL_TAX_RATE)));
                item.put("TaxExempt",     attr(safeGet(row, COL_TAX_EXEMPT)));
                item.put("FundingSource", attr(safeGet(row, COL_FUNDING)));
                item.put("Destination",   attr(safeGet(row, COL_DEST)));
                item.put("BeginBalance",  attr(safeGet(row, COL_BEG_BAL)));
                item.put("EndBalance",    attr(safeGet(row, COL_END_BAL)));
                item.put("StmtFees",      attr(safeGet(row, COL_STMT_FEES)));
                item.put("Terminal",      attr(safeGet(row, COL_TERMINAL)));
                item.put("YTDFees",       attr(safeGet(row, COL_YTD_FEES)));
                item.put("Category",      attr(category));

                dynamo.putItem(PutItemRequest.builder()
                        .tableName(tableName)
                        .item(item)
                        .build());

                System.out.println("Inserted ID=" + id + " | Category=" + category);
            }

        } catch (IOException | CsvException e) {
            System.err.println("Error reading CSV: " + e.getMessage());
        }

        dynamo.close();
        bedrock.close();
        System.out.println("Done. Run AuditBooks to see spending by category.");
    }

    /**
     * Finds the first row with a valid Datetime value and extracts year and month.
     * Datetime format: 2026-08-01T00:37:19 -> "Bookkeeping_2026_08"
     */
    static String deriveTableName(List<String[]> rows) {
        for (String[] row : rows) {
            if (row.length <= COL_DATETIME) continue;
            String datetime = row[COL_DATETIME].trim();
            // Datetime starts with YYYY-MM-
            if (datetime.matches("\\d{4}-\\d{2}-.*")) {
                String year  = datetime.substring(0, 4);
                String month = datetime.substring(5, 7);
                return "Bookkeeping_" + year + "_" + month;
            }
        }
        // Fallback if no valid datetime found
        return "Bookkeeping_Unknown";
    }

    /**
     * Creates the DynamoDB table with ID as the partition key if it doesn't already exist.
     */
    static void createTableIfNotExists(DynamoDbClient dynamo, String tableName) {
        try {
            dynamo.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
            System.out.println("Table already exists, skipping creation.");
        } catch (ResourceNotFoundException e) {
            System.out.println("Creating table: " + tableName);
            dynamo.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("ID")
                            .attributeType(ScalarAttributeType.S)
                            .build())
                    .keySchema(KeySchemaElement.builder()
                            .attributeName("ID")
                            .keyType(KeyType.HASH)
                            .build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());

            dynamo.waiter().waitUntilTableExists(
                    DescribeTableRequest.builder().tableName(tableName).build());
            System.out.println("Table created and active.");
        }
    }

    /**
     * Uses Amazon Nova Micro on Bedrock to categorize a transaction.
     */
    static String categorize(BedrockRuntimeClient bedrock, String to, String from, String note, String amount, String type) {
        String prompt = String.format(
            "Categorize this financial transaction into a single short category name " +
            "(e.g. Food, Rent, Transport, Entertainment — use your best judgment).\\n\\n" +
            "Transaction details:\\n" +
            "- To: %s\\n- From: %s\\n- Note: %s\\n- Amount: %s\\n- Type: %s\\n\\n" +
            "Reply with only the category name, nothing else.",
            to, from, note, amount, type
        );

        String body = String.format(
            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"%s\"}]}]}",
            prompt.replace("\"", "\\\"")
        );

        try {
            InvokeModelResponse response = bedrock.invokeModel(InvokeModelRequest.builder()
                    .modelId("amazon.nova-micro-v1:0")
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(body))
                    .build());

            String responseJson = response.body().asUtf8String();
            // Nova Micro response: {"output":{"message":{"content":[{"text":"Food"}]}},...}
            int textIndex = responseJson.indexOf("\"text\":\"") + 8;
            int textEnd   = responseJson.indexOf("\"", textIndex);
            return responseJson.substring(textIndex, textEnd).trim();

        } catch (Exception e) {
            System.err.println("Bedrock error for to=" + to + ": " + e.getMessage());
            return "Other";
        }
    }

    /**
     * Convenience method for AuditBooks — reads the CSV and derives the table name.
     */
    static String deriveTableNameFromCsv(String csvPath) {
        try (CSVReader reader = new CSVReader(new FileReader(new File(csvPath)))) {
            reader.skip(4);
            List<String[]> rows = reader.readAll();
            return deriveTableName(rows);
        } catch (IOException | CsvException e) {
            System.err.println("Could not read CSV to derive table name: " + e.getMessage());
            return "Bookkeeping_Unknown";
        }
    }

    static AttributeValue attr(String value) {
        return AttributeValue.builder().s(value.isBlank() ? "-" : value).build();
    }

    static String safeGet(String[] row, int index) {
        return (index < row.length) ? row[index].trim() : "";
    }
}
