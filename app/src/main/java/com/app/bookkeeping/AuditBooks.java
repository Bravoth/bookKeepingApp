package com.app.bookkeeping;

import java.util.Map;
import java.util.TreeMap;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * AuditBooks — scans a Bookkeeping DynamoDB table and prints total spending per category.
 * Pass the year and month as arguments: e.g. 2026 08
 * Defaults to the most recent table name used by BookKeeping if no args provided.
 */
public class AuditBooks {

    public static void main(String[] args) {

        // Build table name from args (e.g. "2026 08") or fall back to BookKeeping's last run
        String tableName;
        if (args.length >= 2) {
            tableName = "Bookkeeping_" + args[0] + "_" + args[1];
        } else if (args.length == 1) {
            // Accept "2026_08" style as a single arg too
            tableName = "Bookkeeping_" + args[0];
        } else {
            // Default: derive from CSV passed as third arg, or prompt user
            if (args.length >= 1) {
                tableName = BookKeeping.deriveTableNameFromCsv(args[0]);
            } else {
                System.err.println("Usage: AuditBooks <csv-path>  OR  AuditBooks <year> <month>");
                System.err.println("Example: AuditBooks data/Aug_2026.csv");
                System.err.println("Example: AuditBooks 2026 08");
                return;
            }
        }

        System.out.println("Auditing table: " + tableName);

        DynamoDbClient dynamo = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .build();

        Map<String, Double> totals = new TreeMap<>();
        Map<String, AttributeValue> lastKey = null;

        do {
            ScanRequest.Builder scanBuilder = ScanRequest.builder().tableName(tableName);
            if (lastKey != null) scanBuilder.exclusiveStartKey(lastKey);

            ScanResponse response = dynamo.scan(scanBuilder.build());

            for (Map<String, AttributeValue> item : response.items()) {
                String category  = item.containsKey("Category") ? item.get("Category").s() : "Other";
                String amountStr = item.containsKey("Amount")   ? item.get("Amount").s()   : "";

                // Only count debits — amounts formatted as "- $5.00"
                if (!amountStr.contains("- $")) continue;

                // Strip symbols and parse: "- $1,234.56" -> 1234.56
                String cleaned = amountStr.replace("- $", "").replace(",", "").trim();
                try {
                    double amount = Double.parseDouble(cleaned);
                    totals.merge(category, amount, Double::sum);
                } catch (NumberFormatException e) {
                    // skip rows with unparseable amounts
                }
            }

            lastKey = response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();

        } while (lastKey != null);

        dynamo.close();

        // Print summary
        System.out.println("\n===== Spending by Category (" + tableName + ") =====");
        double grandTotal = 0;
        for (Map.Entry<String, Double> entry : totals.entrySet()) {
            System.out.printf("  %-25s $%,.2f%n", entry.getKey(), entry.getValue());
            grandTotal += entry.getValue();
        }
        System.out.println("------------------------------------------------");
        System.out.printf("  %-25s $%,.2f%n", "TOTAL", grandTotal);
        System.out.println("================================================\n");
    }
}
