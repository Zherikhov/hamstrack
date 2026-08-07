package com.hamstrack.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Attachment upload policy, enforced in {@code AttachmentService} — deliberately
 * at the application layer (not only via {@code spring.servlet.multipart.*}) so a
 * future global-admin runtime setting can drive it without a redeploy.
 *
 * <p>{@code maxFileSize} must stay ≤ the servlet multipart ceiling
 * ({@code spring.servlet.multipart.max-file-size}), which is the hard DoS guard.
 * {@code allowedExtensions} is a case-insensitive allow-list matched against the
 * uploaded filename's extension.
 */
@ConfigurationProperties(prefix = "app.attachments")
public record AttachmentProperties(
        @DefaultValue("20MB") DataSize maxFileSize,
        @DefaultValue({"png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "pdf", "txt", "csv",
                "log", "md", "json", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip"})
        List<String> allowedExtensions
) {}
