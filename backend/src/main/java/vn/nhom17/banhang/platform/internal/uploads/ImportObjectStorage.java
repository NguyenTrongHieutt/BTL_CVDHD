package vn.nhom17.banhang.platform.internal.uploads;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
class ImportObjectStorage implements AutoCloseable {
    private final String bucket;
    private final S3Client client;
    private final S3Presigner presigner;
    private final boolean expectedSizeHeader;

    ImportObjectStorage(@Value("${app.import-uploads.enabled:false}") boolean enabled,
            @Value("${app.import-uploads.bucket:banhang-imports}") String bucket,
            @Value("${app.import-uploads.endpoint:}") String endpoint,
            @Value("${app.import-uploads.public-endpoint:}") String publicEndpoint,
            @Value("${app.import-uploads.region:us-east-1}") String region,
            @Value("${app.import-uploads.access-key:}") String accessKey,
            @Value("${app.import-uploads.secret-key:}") String secretKey,
            @Value("${app.import-uploads.complete-size-header:true}") boolean expectedSizeHeader) {
        this.bucket = bucket;
        this.expectedSizeHeader = expectedSizeHeader;
        if (!enabled) {
            client = null;
            presigner = null;
            return;
        }
        if (accessKey.isBlank() != secretKey.isBlank()) throw new IllegalArgumentException("Incomplete S3 credentials");
        var credentials = accessKey.isBlank() ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        boolean pathStyle = !endpoint.isBlank() || !publicEndpoint.isBlank();
        var s3 = S3Client.builder().region(Region.of(region)).credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(6)).build());
        if (!endpoint.isBlank()) s3.endpointOverride(URI.create(endpoint));
        client = s3.build();
        var signer = S3Presigner.builder().region(Region.of(region)).credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        String signedOrigin = publicEndpoint.isBlank() ? endpoint : publicEndpoint;
        if (!signedOrigin.isBlank()) signer.endpointOverride(URI.create(signedOrigin));
        presigner = signer.build();
    }

    boolean enabled() { return client != null; }

    String initiate(String key, String sessionId) {
        return client.createMultipartUpload(request -> request.bucket(bucket).key(key)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256).checksumType(ChecksumType.COMPOSITE)
                .metadata(Map.of("import-upload-id", sessionId))).uploadId();
    }

    record SignedPart(String url, Map<String, String> headers) {}

    SignedPart sign(String key, String remoteId, int number, long size, String sha, Duration validFor) {
        var signed = presigner.presignUploadPart(request -> request.signatureDuration(validFor)
                .uploadPartRequest(part -> part.bucket(bucket).key(key).uploadId(remoteId).partNumber(number)
                        .contentLength(size).checksumSHA256(sha)));
        var headers = new java.util.TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(signed.signedHeaders());
        if (!List.of(Long.toString(size)).equals(headers.remove("content-length"))) {
            throw new IllegalStateException("S3 presigner omitted exact Content-Length");
        }
        headers.remove("host");
        // Một số signer đưa checksum đã ký vào query. Header tùy chọn chỉ gửi khi signer yêu cầu.
        boolean signedChecksum = List.of(sha).equals(headers.get("x-amz-checksum-sha256"))
                || signed.httpRequest().rawQueryParameters().entrySet().stream().anyMatch(entry ->
                        entry.getKey().equalsIgnoreCase("x-amz-checksum-sha256") && entry.getValue().equals(List.of(sha)));
        if (!signedChecksum) throw new IllegalStateException("S3 presigner omitted SHA-256");
        var browserHeaders = new java.util.LinkedHashMap<String, String>();
        headers.forEach((name, values) -> browserHeaders.put(name, String.join(",", values)));
        return new SignedPart(signed.url().toExternalForm(), Map.copyOf(browserHeaders));
    }

    List<Part> parts(String key, String remoteId) {
        return client.listPartsPaginator(request -> request.bucket(bucket).key(key).uploadId(remoteId))
                .parts().stream().toList();
    }

    void complete(String key, String remoteId, long bytes, List<CompletedPart> parts) {
        client.completeMultipartUpload(request -> {
            request.bucket(bucket).key(key).uploadId(remoteId).ifNoneMatch("*")
                    .checksumType(ChecksumType.COMPOSITE).multipartUpload(upload -> upload.parts(parts));
            if (expectedSizeHeader) request.mpuObjectSize(bytes);
        });
    }

    HeadObjectResponse head(String key) {
        try {
            return client.headObject(request -> request.bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED));
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) return null;
            throw failure;
        }
    }

    InputStream open(String key, String etag, String version) {
        return client.getObject(request -> request.bucket(bucket).key(key).ifMatch(etag).versionId(version));
    }

    void discard(String key, String remoteId, String version) {
        if (remoteId != null) {
            try {
                client.abortMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(remoteId));
                // Một part còn bay có thể tồn tại sau lần abort đầu tiên.
                if (!parts(key, remoteId).isEmpty()) {
                    throw new IllegalStateException("S3 upload still holds parts");
                }
            } catch (S3Exception failure) {
                if (failure.statusCode() != 404) throw failure;
            }
        }
        var object = head(key);
        if (object != null) {
            String exactVersion = version == null ? object.versionId() : version;
            client.deleteObject(request -> request.bucket(bucket).key(key).versionId(exactVersion));
        }
    }

    @Override public void close() {
        if (presigner != null) presigner.close();
        if (client != null) client.close();
    }
}
