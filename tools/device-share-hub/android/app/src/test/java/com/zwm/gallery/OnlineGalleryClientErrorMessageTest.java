package com.zwm.gallery;

import org.junit.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class OnlineGalleryClientErrorMessageTest {
    @Test
    public void connectionFailureUsesGenericMessageWithoutAddress() {
        String message = OnlineGalleryClient.userFacingErrorMessage(new ConnectException(
                "failed to connect to /192.168.0.102 (port 45835) from /192.168.0.108 after 4000ms"));

        assertEquals("无法连接电脑", message);
        assertFalse(message.contains("192.168."));
    }

    @Test
    public void timeoutAndServerErrorsUseShortLabels() {
        assertEquals("连接超时", OnlineGalleryClient.userFacingErrorMessage(
                new SocketTimeoutException("timeout")));
        assertEquals("电脑服务暂不可用", OnlineGalleryClient.userFacingErrorMessage(
                new Exception("HTTP 503 Service Unavailable")));
        assertEquals("网络异常", OnlineGalleryClient.userFacingErrorMessage(null));
    }
}
