package com.tinyurl.service;

import com.tinyurl.exception.UrlUnreachableException;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UrlReachabilityCheckerImpl implements UrlReachabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(UrlReachabilityCheckerImpl.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public void check(String url) {
        try {
            guardSsrf(url);
            int status = sendHead(url);
            if (status == 405) {
                status = sendGet(url);
            }
            if (status == 404 || status == 410) {
                throw new UrlUnreachableException("URL_UNREACHABLE");
            }
        } catch (UrlUnreachableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Reachability check failed for host {} — failing open: {}", URI.create(url).getHost(), e.getMessage());
        }
    }

    private void guardSsrf(String url) throws Exception {
        String host = URI.create(url).getHost();
        if (host == null || host.isBlank()) {
            throw new UrlUnreachableException("URL_UNREACHABLE");
        }
        InetAddress address = InetAddress.getByName(host);
        if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || isIpv6UniqueLocal(address)) {
            throw new UrlUnreachableException("URL_UNREACHABLE");
        }
    }

    private boolean isIpv6UniqueLocal(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        // FC00::/7 — unique local addresses (covers FC00:: and FD00:: ranges)
        return (address.getAddress()[0] & 0xFE) == 0xFC;
    }

    private int sendHead(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int sendGet(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
