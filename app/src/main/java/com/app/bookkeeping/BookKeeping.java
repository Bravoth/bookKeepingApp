package com.app.bookkeeping;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * BookKeeping — reads a Venmo CSV export, categorizes transactions using
 * a single Amazon Nova Micro call for all unique merchants, then loads
 * everything into DynamoDB.
 *
 * The table name is derived from the Datetime column: 2026-08-01T... -> Bookkeeping_2026_08
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

        DynamoDbClient dynamo = DynamoDbClient.builder().region(REGION).build();
        BedrockRuntimeClient bedrock = BedrockRuntimeClient.builder().region(REGION).build();

        try (CSVReader reader = new CSVReader(new FileReader(new File(csvPath)))) {

            // Skip: account info, "Account Activity", column headers, opening balance
            reader.skip(4);
            List<String[]> rows = reader.readAll();

            String tableName = deriveTableName(rows);
            System.out.println("Using table: " + tableName);
            createTableIfNotExists(dynamo, tableName);

            // Step 1: collect unique "To" values across all valid rows
            Set<String> uniqueMerchants = new LinkedHashSet<>();
            for (String[] row : rows) {
                if (row.length <= COL_ID || row[COL_ID].isBlank()) continue;
                String to = safeGet(row, COL_TO);
                if (!to.isBlank() && !to.equals("-")) uniqueMerchants.add(to);
            }
            System.out.println("Found " + uniqueMerchants.size() + " unique merchants — sending one Bedrock request...");

            // Step 2: one Bedrock call to categorize all unique merchants
            Map<String, String> categoryMap = categorizeMerchants(bedrock, uniqueMerchants);
            System.out.println("Categorization complete. Loading into DynamoDB...");

            // Step 3: write each row to DynamoDB using the category map
            for (String[] row : rows) {
                if (row.length <= COL_ID || row[COL_ID].isBlank()) continue;

                String id   = row[COL_ID].trim();
                String to   = safeGet(row, COL_TO);
                String type = safeGet(row, COL_TYPE);

                // Look up category from map, fall back to "Other" if not found
                String category = categoryMap.getOrDefault(to, "Other");

                Map<String, AttributeValue> item = new HashMap<>();
                item.put("ID",            attr(id));
                item.put("Datetime",      attr(safeGet(row, COL_DATETIME)));
                item.put("Type",          attr(type));
                item.put("Status",        attr(safeGet(row, COL_STATUS)));
                item.put("Note",          attr(safeGet(row, COL_NOTE)));
                item.put("From",          attr(safeGet(row, COL_FROM)));
                item.put("To",            attr(to));
                item.put("Amount",        attr(safeGet(row, COL_AMOUNT)));
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

                System.out.println("Inserted ID=" + id + " | To=" + to + " | Category=" + category);
            }

        } catch (IOException | CsvException e) {
            System.err.println("Error reading CSV: " + e.getMessage());
        }

        dynamo.close();
        bedrock.close();
        System.out.println("Done. Run AuditBooks to see spending by category.");
    }

    /**
     * Sends all unique merchant names to Nova Micro in a single prompt.
     * Returns a map of merchant -> category.
     * Prompt format asks for one "Merchant: Category" line per entry.
     */
    static Map<String, String> categorizeMerchants(BedrockRuntimeClient bedrock, Set<String> merchants) {
        Map<String, String> result = new LinkedHashMap<>();
        if (merchants.isEmpty()) return result;

        // Build a numbered list of merchants for the prompt
        StringBuilder merchantList = new StringBuilder();
        for (String merchant : merchants) {
            merchantList.append("- ").append(merchant).append("\\n");
        }

        String prompt =
            "You are categorizing financial transactions. For each merchant below, " +
            "reply with a single line in the format: Merchant: Category\\n" +
            "Use short category names like Food, Groceries, Rent, Transport, Entertainment, " +
            "Fitness, Utilities, Subscription, Shopping, or whatever fits best.\\n\\n" +
            "Merchants:\\n" + merchantList +
            "\\nReply with only the merchant-category lines, nothing else.";

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
            // Extract the text block from Nova's response
            int textIndex = responseJson.indexOf("\"text\":\"") + 8;
            int textEnd   = responseJson.lastIndexOf("\"");
            String text   = responseJson.substring(textIndex, textEnd)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"");

            // Parse each "Merchant: Category" line
            for (String line : text.split("\n")) {
                int colonIdx = line.indexOf(":");
                if (colonIdx < 0) continue;
                String merchant  = line.substring(0, colonIdx).trim().replaceFirst("^- ", "");
                String category  = line.substring(colonIdx + 1).trim();
                if (!merchant.isBlank() && !category.isBlank()) {
                    result.put(merchant, category);
                }
            }

        } catch (Exception e) {
            System.err.println("Bedrock error: " + e.getMessage());
            // Fall back: assign "Other" to all merchants
            for (String merchant : merchants) result.put(merchant, "Other");
        }

        return result;
    }

    /**
     * Derives table name from the first valid Datetime value in the rows.
     * 2026-08-01T00:37:19 -> "Bookkeeping_2026_08"
     */
    static String deriveTableName(List<String[]> rows) {
        for (String[] row : rows) {
            if (row.length <= COL_DATETIME) continue;
            String datetime = row[COL_DATETIME].trim();
            if (datetime.matches("\\d{4}-\\d{2}-.*")) {
                return "Bookkeeping_" + datetime.substring(0, 4) + "_" + datetime.substring(5, 7);
            }
        }
        return "Bookkeeping_Unknown";
    }

    /**
     * Convenience method for AuditBooks to derive table name from a CSV file.
     */
    static String deriveTableNameFromCsv(String csvPath) {
        try (CSVReader reader = new CSVReader(new FileReader(new File(csvPath)))) {
            reader.skip(4);
            return deriveTableName(reader.readAll());
        } catch (IOException | CsvException e) {
            System.err.println("Could not read CSV to derive table name: " + e.getMessage());
            return "Bookkeeping_Unknown";
        }
    }

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

    static AttributeValue attr(String value) {
        return AttributeValue.builder().s(value.isBlank() ? "-" : value).build();
    }

    static String safeGet(String[] row, int index) {
        return (index < row.length) ? row[index].trim() : "";
    }
}
