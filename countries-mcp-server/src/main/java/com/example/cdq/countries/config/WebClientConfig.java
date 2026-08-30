package com.example.cdq.countries.config;

import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;

@Configuration
class WebClientConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
        // On Windows/macOS, use the OS-native trust store so that any system-trusted CA
        // (including SSL-intercepting antiviruses like Avast, Kaspersky, ESET) is accepted
        // without disabling certificate validation entirely.
        String osName = System.getProperty("os.name", "").toLowerCase();
        String storeType = osName.contains("win") ? "Windows-ROOT"
                         : osName.contains("mac") ? "KeychainStore"
                         : null;

        if (storeType != null) {
            try {
                var keyStore = KeyStore.getInstance(storeType);
                keyStore.load(null, null);
                var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(keyStore);
                var sslCtx = SslContextBuilder.forClient().trustManager(tmf).build();
                var httpClient = HttpClient.create().secure(spec -> spec.sslContext(sslCtx));
                return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
            } catch (Exception e) {
                // Fall through to default if OS store is unavailable
            }
        }

        return WebClient.builder();
    }
}
