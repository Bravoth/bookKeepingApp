# Bookkeeping

Loads a Venmo CSV export, categorizes transactions, and reports spending by category.

## Load a month

```powershell
.\gradlew.bat run --args="data/Sep_2026.csv"
```

## Print totals by category

```powershell
.\gradlew.bat audit --args="2026 09"
```

## Spending report

Generates an animated bubble chart of spending by category for a month.
Copy `lambda/event.example.json` to `lambda/event.json`, fill in the month and bucket, then:

```powershell
aws lambda invoke --function-name spending-report --payload fileb://lambda/event.json lambda/out.json
```
