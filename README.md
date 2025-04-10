# CloudConnexa to Graylog Forwarder

This repository contains a Lambda function that forwards CloudConnexa logs from an S3 bucket to Graylog.

## Overview

The CloudConnexa to Graylog Forwarder is an AWS Lambda function that:

1. Gets triggered when new log files are uploaded to an S3 bucket
2. Processes the log files (decompresses, reads lines)
3. Formats the logs in a DataDog-compatible format that Graylog can understand
4. Sends the logs to Graylog with the appropriate headers, including a customer_code header

## Deployment

### Prerequisites

- AWS CLI installed and configured
- An S3 bucket where CloudConnexa logs are stored
- A Graylog server endpoint that accepts logs in DataDog format
- A Graylog authentication token

### Building the Lambda Function

```bash
./gradlew build
```

This will create a zip file in `build/distributions/connexa-s3-graylog-1.0.0.zip` that can be deployed to AWS Lambda.

### Deploying with CloudFormation

You can deploy the Lambda function using the provided CloudFormation template:

```bash
aws cloudformation deploy \
  --template-file cloudconnexa-s3-graylog-forwarder-cloudformation.yaml \
  --stack-name cloudconnexa-graylog-forwarder \
  --parameter-overrides \
    BucketName=your-bucket-name \
    GraylogUrl=https://your-graylog-server/api/datadog/logs \
    GraylogAuthToken=your-auth-token \
    GraylogTags=env:prod,source:cloudconnexa \
    CustomerCode=your-customer-code
```

## Configuration

The Lambda function can be configured with the following environment variables:

- `GRAYLOG_URL`: The URL of the Graylog server endpoint
- `GRAYLOG_AUTH_TOKEN_SECRET_ARN`: The ARN of the secret in AWS Secrets Manager that contains the Graylog authentication token
- `GRAYLOG_TAGS`: Optional tags to add to the logs
- `CUSTOMER_CODE`: The customer code to include in the header when sending logs to Graylog (defaults to "default" if not set)

## Testing

You can test the Lambda function by uploading a test log file to the S3 bucket:

```bash
echo '{"timestamp":"2025-04-10T10:00:00Z","message":"TEST_LOG","level":"INFO"}' > test.jsonl
gzip test.jsonl
aws s3 cp test.jsonl.gz s3://your-bucket/CloudConnexa/
```

Then check the CloudWatch logs to verify that the logs were sent successfully:

```bash
aws logs filter-log-events --log-group-name /aws/lambda/cloudconnexas3-to-graylog-forwarder-lambda --filter-pattern "Successfully sent"
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.
