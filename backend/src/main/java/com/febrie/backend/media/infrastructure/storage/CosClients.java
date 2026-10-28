package com.febrie.backend.media.infrastructure.storage;

import com.febrie.backend.media.infrastructure.config.MediaProperties;
import com.qcloud.cos.*;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.endpoint.EndpointBuilder;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;

/** 每次操作独占配置；总请求定时器会中止HTTP，不修改共享单例超时。 */
public final class CosClients {
    private CosClients() { }
    public static COSClient open(MediaProperties p, int budgetMs) {
        ClientConfig config = new ClientConfig(new Region(p.region()));
        config.setHttpProtocol("https".equals(p.endpoint().getScheme()) ? HttpProtocol.https : HttpProtocol.http);
        config.setConnectionRequestTimeout(Math.min(250, budgetMs));
        config.setConnectionTimeout(Math.min(500, budgetMs)); config.setSocketTimeout(budgetMs);
        config.setRequestTimeOutEnable(true); config.setRequestTimeout(budgetMs);
        config.setMaxErrorRetry(0); config.setMaxErrorRetryForCopyRequest(0);
        config.setChangeEndpointRetry(false); config.setRetryAfterPreflight(false);
        config.setRedirectsEnabled(false); config.setShutdownTimeout(0);
        config.setPrintShutdownStackTrace(false);
        config.setEndpointBuilder(new EndpointBuilder() {
            @Override public String buildGeneralApiEndpoint(String bucket) { return p.endpoint().getRawAuthority(); }
            @Override public String buildGetServiceApiEndpoint() { return p.endpoint().getRawAuthority(); }
        });
        return new COSClient(new BasicCOSCredentials(p.accessKey(), p.secretKey()), config);
    }
}
