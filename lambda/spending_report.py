"""Lambda: animated spending-by-category bubble chart for one month, uploaded to S3.

Event: {"year": "2026", "month": "09", "bucket": "YOUR_REPORT_BUCKET"}
"""
import calendar
from collections import defaultdict

import boto3
import plotly.graph_objects as go
from plotly.colors import qualitative

ddb = boto3.client("dynamodb")
s3 = boto3.client("s3")


def lambda_handler(event, context):
    year, month = int(event["year"]), int(event["month"])
    table = f"Bookkeeping_{year}_{month:02d}"

    # Spend per (day, category). Debits only: Amount looks like "- $12.34" (same rule as AuditBooks).
    daily = defaultdict(float)
    for page in ddb.get_paginator("scan").paginate(TableName=table):
        for item in page["Items"]:
            amount = item.get("Amount", {}).get("S", "")
            datetime = item.get("Datetime", {}).get("S", "")
            if "- $" not in amount or len(datetime) < 10:
                continue
            category = item.get("Category", {}).get("S", "Other")
            daily[(int(datetime[8:10]), category)] += float(amount.replace("- $", "").replace(",", ""))

    # Running total per category at the end of each day, biggest category first
    month_total = defaultdict(float)
    for (_, category), amount in daily.items():
        month_total[category] += amount
    categories = sorted(month_total, key=month_total.get, reverse=True)
    days = list(range(1, calendar.monthrange(year, month)[1] + 1))
    running, totals = defaultdict(float), {}
    for day in days:
        for category in categories:
            running[category] += daily.get((day, category), 0.0)
        totals[day] = [running[category] for category in categories]

    peak = max(totals[days[-1]], default=0) or 1
    colors = [qualitative.Plotly[i % len(qualitative.Plotly)] for i in range(len(categories))]

    def bubbles(day):
        return go.Scatter(
            x=categories, y=totals[day], mode="markers+text",
            text=[f"${v:,.0f}" for v in totals[day]], textposition="top center",
            marker=dict(size=totals[day], sizemode="area", sizeref=2 * peak / 90**2, color=colors),
        )

    fig = go.Figure(
        data=[bubbles(days[0])],
        frames=[go.Frame(data=[bubbles(day)], name=str(day)) for day in days],
        layout=go.Layout(
            title=f"Spending by category, {calendar.month_name[month]} {year}",
            yaxis=dict(title="Spent so far ($)", range=[0, peak * 1.25]),
            showlegend=False,
            updatemenus=[dict(type="buttons", x=0, y=-0.15, buttons=[dict(
                label="Play", method="animate",
                args=[None, {"frame": {"duration": 400}, "transition": {"duration": 300}, "fromcurrent": True}],
            )])],
            sliders=[dict(currentvalue={"prefix": "Day "}, pad={"t": 50}, steps=[
                dict(label=str(day), method="animate", args=[[str(day)], {"mode": "immediate"}]) for day in days
            ])],
        ),
    )

    key = f"reports/{table}.html"
    s3.put_object(Bucket=event["bucket"], Key=key, Body=fig.to_html(include_plotlyjs="cdn"),
                  ContentType="text/html; charset=utf-8")
    return {"report": f"s3://{event['bucket']}/{key}"}
