package com.financecoach.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ServiceTest {

    @Mock private S3Client s3Client;
    @Mock private S3Presigner presigner;

    @Test
    void uploadFile_putsTheObjectInTheConfiguredBucket() {
        S3Service s3Service = new S3Service(s3Client, presigner, "exports-bucket");

        String key = s3Service.uploadFile("exports/u1/file.csv", "a,b".getBytes(), "text/csv");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(key).isEqualTo("exports/u1/file.csv");
        assertThat(request.getValue().bucket()).isEqualTo("exports-bucket");
        assertThat(request.getValue().key()).isEqualTo("exports/u1/file.csv");
        assertThat(request.getValue().contentType()).isEqualTo("text/csv");
    }

    @Test
    void uploadFile_withoutABucketConfigured_failsClearly() {
        S3Service s3Service = new S3Service(s3Client, presigner, "");

        assertThatThrownBy(() -> s3Service.uploadFile("k", new byte[0], "text/csv"))
                .hasMessageContaining("S3_EXPORT_BUCKET");
        verifyNoInteractions(s3Client);
    }

    @Test
    void generatePresignedUrl_returnsAFifteenMinuteDownloadLink() throws Exception {
        S3Service s3Service = new S3Service(s3Client, presigner, "exports-bucket");
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://exports-bucket.s3.amazonaws.com/k?sig=1").toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = s3Service.generatePresignedUrl("exports/u1/file.csv");

        ArgumentCaptor<GetObjectPresignRequest> request = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(request.capture());
        assertThat(url).isEqualTo("https://exports-bucket.s3.amazonaws.com/k?sig=1");
        assertThat(request.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(request.getValue().getObjectRequest().key()).isEqualTo("exports/u1/file.csv");
    }
}
