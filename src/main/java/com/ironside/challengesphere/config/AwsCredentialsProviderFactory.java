package com.ironside.challengesphere.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;

public final class AwsCredentialsProviderFactory {

    private AwsCredentialsProviderFactory() {
    }

    public static AwsCredentialsProvider create(String profileName, String accessKeysCsvPath) {
        Path csvPath = accessKeysCsvPath(accessKeysCsvPath);
        if (csvPath != null) {
            return accessKeysCsvCredentialsProvider(csvPath);
        }
        if (profileName == null || profileName.isBlank()) {
            return DefaultCredentialsProvider.create();
        }
        return ProfileCredentialsProvider.builder()
                .profileName(profileName)
                .build();
    }

    private static Path accessKeysCsvPath(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }

        Path defaultLocalCsv = Path.of("challengesphere_accessKeys.csv");
        return Files.isRegularFile(defaultLocalCsv) ? defaultLocalCsv : null;
    }

    private static AwsCredentialsProvider accessKeysCsvCredentialsProvider(Path accessKeysCsvPath) {
        return () -> {
            List<String> lines;
            try {
                lines = Files.readAllLines(accessKeysCsvPath);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read AWS access keys CSV: " + accessKeysCsvPath, e);
            }

            return lines.stream()
                    .skip(1)
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .map(AwsCredentialsProviderFactory::credentialsFromCsvLine)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "AWS access keys CSV must contain an access key row: " + accessKeysCsvPath));
        };
    }

    private static AwsBasicCredentials credentialsFromCsvLine(String line) {
        String[] columns = line.split(",", -1);
        if (columns.length < 2 || columns[0].isBlank() || columns[1].isBlank()) {
            throw new IllegalArgumentException("AWS access keys CSV row must contain access key ID and secret access key");
        }
        return AwsBasicCredentials.create(columns[0].trim(), columns[1].trim());
    }
}
