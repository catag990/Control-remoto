package org.gradle.wrapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Independent, tiny, source-included bootstrapper for Gradle 8.11.1.
 * This is NOT the official Gradle wrapper JAR. It downloads the official HTTPS
 * distribution on first use, verifies the vendor-published SHA-256, then runs Gradle.
 * No previous versions of this remote-control project are required.
 */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        Path base = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(base.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
            p.load(in);
        }
        String url = p.getProperty("distributionUrl", "");
        if (!url.equals("https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"))
            throw new SecurityException("Gradle distributionUrl no autorizado: " + url);
        Path install = Paths.get(System.getProperty("user.home"), ".gradle", "rcp-v05", "gradle-8.11.1");
        boolean win = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path command = install.resolve("bin").resolve(win ? "gradle.bat" : "gradle");
        if (!Files.isRegularFile(command)) {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
            byte[] expected = client.send(HttpRequest.newBuilder(URI.create(url + ".sha256")).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
            String hash = new String(expected, java.nio.charset.StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (!hash.matches("[0-9a-f]{64}")) throw new SecurityException("SHA256 oficial no disponible");
            System.out.println("Descargando Gradle 8.11.1 oficial (primera ejecución)...");
            Path zip = Files.createTempFile("rcp-gradle-", ".zip");
            try {
                HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofFile(zip));
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip));
                if (!HexFormat.of().formatHex(digest).equals(hash))
                    throw new SecurityException("El SHA-256 de Gradle no coincide; abortando");
                Files.createDirectories(install.getParent());
                try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
                    ZipEntry entry;
                    while ((entry = zin.getNextEntry()) != null) {
                        Path dest = install.getParent().resolve(entry.getName()).normalize();
                        if (!dest.startsWith(install.getParent())) throw new SecurityException("ZipSlip");
                        if (entry.isDirectory()) Files.createDirectories(dest);
                        else { Files.createDirectories(dest.getParent()); Files.copy(zin, dest, StandardCopyOption.REPLACE_EXISTING); }
                        zin.closeEntry();
                    }
                }
                if (!Files.isRegularFile(command)) throw new IOException("Gradle no se extrajo correctamente");
                if (!win) command.toFile().setExecutable(true);
            } finally { Files.deleteIfExists(zip); }
        }
        var argv = new ArrayList<String>();
        argv.add(command.toString()); Collections.addAll(argv, args);
        ProcessBuilder builder = new ProcessBuilder(argv).directory(base.toFile()).inheritIO();
        int exit = builder.start().waitFor();
        System.exit(exit);
    }
}
