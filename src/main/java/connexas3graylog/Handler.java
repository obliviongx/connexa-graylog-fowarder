package connexas3graylog;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

// Handler value: connexas3graylog.Handler
public class Handler implements RequestHandler<S3Event, String> {
    private static final Logger logger = LoggerFactory.getLogger(Handler.class);
    private final String GRAYLOG_URL = "GRAYLOG_URL";
    private final String GRAYLOG_AUTH_TOKEN_SECRET_ARN = "GRAYLOG_AUTH_TOKEN_SECRET_ARN";
    private final String CUSTOMER_CODE = "CUSTOMER_CODE";
    private final String LOG_SOURCE = "CloudConnexa";
    private final String LOG_TAGS = "GRAYLOG_TAGS";
    private final int MAX_SIZE_PAYLOAD = 5000000;
    private final int MAX_SIZE_SINGLE_LOG = 1000000;
    private final int LOG_MAX_ENTRIES = 1000;
    private final String OBJECT_KEY_PREFIX = "CloudConnexa";
    private final String REGEX = ".*\\.([^.]*)\\.([^.]*)";
    private final String JSONL_TYPE = "jsonl";
    private final String GZ_TYPE = "gz";
    private final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    public String handleRequest(S3Event s3event, Context context) {
        try {
            List<String> allLogs = new ArrayList<>();

            for (S3EventNotificationRecord record : s3event.getRecords()) {
                String bucketName = record.getS3().getBucket().getName();

                S3Client s3Client = S3Client.builder().build();

                // Object key may have spaces or unicode non-ASCII characters.
                String srcKey = record.getS3().getObject().getUrlDecodedKey();

                if (!srcKey.startsWith(OBJECT_KEY_PREFIX)) {
                    logger.info("Unable to infer prefix for key " + srcKey);
                    return "";
                }

                // Infer the type.
                Matcher matcher = Pattern.compile(REGEX).matcher(srcKey);
                if (!matcher.matches()) {
                    logger.info("Unable to infer type for key " + srcKey);
                    return "";
                }
                String jsonType = matcher.group(1);
                if (!(JSONL_TYPE.equals(jsonType))) {
                    logger.info("Skipping not jsonl type " + srcKey);
                    return "";
                }
                String gzType = matcher.group(2);
                if (!(GZ_TYPE.equals(gzType))) {
                    logger.info("Skipping not gz type " + srcKey);
                    return "";
                }

                InputStream s3Object = getObject(s3Client, bucketName, srcKey);
                for (List<String> logs : getLogs(s3Object)) {
                    allLogs.addAll(logs);
                }
            }

            toGraylog(allLogs);

            return "Ok";
        } catch (IOException e) {
            logger.error("Error processing logs", e);
            throw new RuntimeException(e);
        }
    }

    private InputStream getObject(S3Client s3Client, String bucket, String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        return s3Client.getObject(getObjectRequest);
    }

    private List<List<String>> getLogs(InputStream inputStream) throws IOException {
        List<String> logs = new ArrayList<>();
        List<List<String>> result = new ArrayList<>();
        result.add(logs);

        try (GZIPInputStream gzis = new GZIPInputStream(inputStream);
             InputStreamReader reader = new InputStreamReader(gzis);
             BufferedReader in = new BufferedReader(reader)) {
            String readed;
            int size = 0;
            while ((readed = in.readLine()) != null) {
                byte[] readBytes = readed.getBytes();
                size += readBytes.length;
                if (size > MAX_SIZE_PAYLOAD) {
                    logs = new ArrayList<>();
                    result.add(logs);
                    size = 0;
                }

                logs.addAll(Splitter.fixedLength(MAX_SIZE_SINGLE_LOG).splitToList(readed));
            }
        }
        return result;
    }

    private String getSecret(String secretId) {
        // Create a Secrets Manager client
        SecretsManagerClient client = SecretsManagerClient.builder()
                .build();

        GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                .secretId(secretId)
                .build();

        GetSecretValueResponse getSecretValueResponse = null;

        try {
            getSecretValueResponse = client.getSecretValue(getSecretValueRequest);
        } catch (Exception e) {
            logger.error(
                    "Exception when calling SecretsManagerClient#getSecretValue" +
                            " Message: " + e.getMessage(), e
            );
            throw new RuntimeException("Failed to retrieve secret", e);
        }

        return getSecretValueResponse.secretString();
    }

    private void toGraylog(List<String> allLogs) {
        String graylogUrl = System.getenv(GRAYLOG_URL);
        if (graylogUrl == null || graylogUrl.isEmpty()) {
            throw new RuntimeException("GRAYLOG_URL environment variable is not set");
        }

        String authToken = getSecret(System.getenv(GRAYLOG_AUTH_TOKEN_SECRET_ARN));
        String tags = System.getenv(LOG_TAGS);

        for (List<String> logBatch : Lists.partition(allLogs, LOG_MAX_ENTRIES)) {
            try {
                // Format logs according to DataDog format expected by Graylog
                ArrayNode datadogLogs = objectMapper.createArrayNode();
                
                for (String logEntry : logBatch) {
                    // Create a DataDog log entry
                    ObjectNode datadogLog = objectMapper.createObjectNode();
                    
                    // Add required DataDog fields
                    datadogLog.put("ddsource", LOG_SOURCE);
                    if (tags != null && !tags.isEmpty()) {
                        datadogLog.put("ddtags", tags);
                    }
                    
                    // Pass the raw log entry directly as the message field
                    datadogLog.put("message", logEntry);
                    
                    datadogLogs.add(datadogLog);
                }
                
                String jsonPayload = objectMapper.writeValueAsString(datadogLogs);
                logger.info("Sending logs to Graylog: " + jsonPayload);
                
                RequestBody body = RequestBody.create(jsonPayload, JSON);
                // Get customer code from environment variable, default to "default" if not set
                String customerCode = System.getenv(CUSTOMER_CODE);
                if (customerCode == null || customerCode.isEmpty()) {
                    customerCode = "default";
                    logger.warn("CUSTOMER_CODE environment variable not set, using default value: " + customerCode);
                }

                Request request = new Request.Builder()
                        .url(graylogUrl)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("AUTH", authToken)
                        .addHeader("customer_code", customerCode)
                        .post(body)
                        .build();

                // Log the headers explicitly
                logger.info("HEADER_CHECK: customer_code=" + customerCode + " is being sent to Graylog");

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        logger.error("Failed to send logs to Graylog. Status code: " + response.code());
                        logger.error("Response body: " + (response.body() != null ? response.body().string() : "null"));
                        throw new IOException("Unexpected response code: " + response);
                    }
                    logger.info("Successfully sent " + logBatch.size() + " logs to Graylog");
                }
            } catch (Exception e) {
                logger.error("Error sending logs to Graylog", e);
                throw new RuntimeException("Failed to send logs to Graylog", e);
            }
        }
    }
}
