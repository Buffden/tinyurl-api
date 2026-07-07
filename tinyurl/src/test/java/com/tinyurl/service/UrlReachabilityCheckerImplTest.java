package com.tinyurl.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.tinyurl.exception.UrlUnreachableException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UrlReachabilityCheckerImplTest {

    @Mock
    private HttpClient httpClient;

    private UrlReachabilityCheckerImpl checker;

    @BeforeEach
    void setUp() {
        checker = new UrlReachabilityCheckerImpl(httpClient);
    }

    @Test
    void shouldRejectUnresolvableDomain() {
        assertThrows(UrlUnreachableException.class,
            () -> checker.check("https://this-domain-definitely-does-not-exist-xyz123abc.com"));
    }

    @Test
    void shouldRejectRawIpv4Address() {
        assertThrows(UrlUnreachableException.class,
            () -> checker.check("https://8.8.8.8"));
    }

    @Test
    void shouldRejectLoopbackAddress() {
        assertThrows(UrlUnreachableException.class,
            () -> checker.check("https://127.0.0.1"));
    }

    @Test
    void shouldRejectPrivateRangeAddress() {
        assertThrows(UrlUnreachableException.class,
            () -> checker.check("https://192.168.1.1"));
    }

    @Test
    void shouldAcceptWhenHeadReturns200() throws Exception {
        doReturn(response(200)).when(httpClient).send(any(), any());
        assertDoesNotThrow(() -> checker.check("https://example.com"));
    }

    @Test
    void shouldRejectWhenHeadReturns404() throws Exception {
        doReturn(response(404)).when(httpClient).send(any(), any());
        assertThrows(UrlUnreachableException.class, () -> checker.check("https://example.com"));
    }

    @Test
    void shouldRejectWhenHeadReturns500() throws Exception {
        doReturn(response(500)).when(httpClient).send(any(), any());
        assertThrows(UrlUnreachableException.class, () -> checker.check("https://example.com"));
    }

    @Test
    void shouldFallbackToGetWhen405AndAcceptOn200() throws Exception {
        HttpResponse<Void> res405 = response(405);
        HttpResponse<Void> res200 = response(200);
        doReturn(res405).doReturn(res200).when(httpClient).send(any(), any());
        assertDoesNotThrow(() -> checker.check("https://example.com"));
    }

    @Test
    void shouldRejectWhenBothHeadAndGetFail() throws Exception {
        HttpResponse<Void> res405 = response(405);
        HttpResponse<Void> res500 = response(500);
        doReturn(res405).doReturn(res500).when(httpClient).send(any(), any());
        assertThrows(UrlUnreachableException.class, () -> checker.check("https://example.com"));
    }


    @Test
    void shouldRejectOnTimeout() throws Exception {
        doThrow(new HttpTimeoutException("timeout")).when(httpClient).send(any(), any());
        assertThrows(UrlUnreachableException.class, () -> checker.check("https://example.com"));
    }

    @Test
    void shouldRejectOnConnectionRefused() throws Exception {
        doThrow(new ConnectException("refused")).when(httpClient).send(any(), any());
        assertThrows(UrlUnreachableException.class, () -> checker.check("https://example.com"));
    }

    @Test
    void shouldRejectOnSslError() throws Exception {
        doThrow(new SSLException("certificate unknown")).when(httpClient).send(any(), any());
        assertThrows(UrlUnreachableException.class, () -> checker.check("https://example.com"));
    }


    @Test
    void shouldFollowRedirectAndAcceptOn200() throws Exception {
        HttpResponse<Void> redirect = responseWithLocation(301, "https://example.com/final");
        HttpResponse<Void> ok = response(200);
        doReturn(redirect).doReturn(ok).when(httpClient).send(any(), any());
        assertDoesNotThrow(() -> checker.check("https://example.com/original"));
    }

    @Test
    void shouldRejectWhenRedirectExceedsMaxHops() throws Exception {
        doReturn(responseWithLocation(301, "https://example.com/loop"))
            .when(httpClient).send(any(), any());
        assertThrows(UrlUnreachableException.class, () -> checker.check("https://example.com/start"));
    }

    @Test
    void shouldRejectRedirectToRawPublicIp() throws Exception {
        doReturn(responseWithLocation(301, "https://8.8.8.8/path"))
            .when(httpClient).send(any(), any());
        assertThrows(UrlUnreachableException.class, () -> checker.check("https://example.com"));
    }

    @Test
    void shouldRejectRedirectToPrivateIp() throws Exception {
        doReturn(responseWithLocation(301, "https://192.168.1.1/internal"))
            .when(httpClient).send(any(), any());
        assertThrows(UrlUnreachableException.class, () -> checker.check("https://example.com"));
    }


    @SuppressWarnings("unchecked")
    private HttpResponse<Void> response(int statusCode) {
        HttpResponse<Void> r = mock(HttpResponse.class);
        doReturn(statusCode).when(r).statusCode();
        return r;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<Void> responseWithLocation(int statusCode, String location) {
        HttpResponse<Void> r = mock(HttpResponse.class);
        doReturn(statusCode).when(r).statusCode();
        doReturn(HttpHeaders.of(Map.of("location", List.of(location)), (a, b) -> true))
            .when(r).headers();
        return r;
    }
}
