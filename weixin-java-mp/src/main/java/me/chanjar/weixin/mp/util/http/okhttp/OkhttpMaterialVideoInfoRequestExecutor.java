package me.chanjar.weixin.mp.util.http.okhttp;

import me.chanjar.weixin.common.bean.result.WxError;
import me.chanjar.weixin.common.exception.WxErrorException;
import me.chanjar.weixin.common.util.http.RequestHttp;
import me.chanjar.weixin.common.util.http.okhttp.OkhttpProxyInfo;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialVideoInfoResult;
import me.chanjar.weixin.mp.util.http.MaterialVideoInfoRequestExecutor;
import okhttp3.*;

import java.io.IOException;

/**
 * Created by ecoolper on 2017/5/5.
 */
public class OkhttpMaterialVideoInfoRequestExecutor extends MaterialVideoInfoRequestExecutor<ConnectionPool, OkhttpProxyInfo> {
  public OkhttpMaterialVideoInfoRequestExecutor(RequestHttp requestHttp) {
    super(requestHttp);
  }

  @Override
  public WxMpMaterialVideoInfoResult execute(String uri, String materialId) throws WxErrorException, IOException {
    OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder().connectionPool(requestHttp.getRequestHttpClient());
    //设置代理
    if (requestHttp.getRequestHttpProxy() != null) {
      clientBuilder.proxy(requestHttp.getRequestHttpProxy().getProxy());
    }
    //设置授权
    clientBuilder.authenticator(new Authenticator() {
      @Override
      public Request authenticate(Route route, Response response) throws IOException {
        String credential = Credentials.basic(requestHttp.getRequestHttpProxy().getProxyUsername(), requestHttp.getRequestHttpProxy().getProxyPassword());
        return response.request().newBuilder()
          .header("Authorization", credential)
          .build();
      }
    });
    //得到httpClient
    OkHttpClient client = clientBuilder.build();

    RequestBody requestBody = new FormBody.Builder().add("media_id", materialId).build();
    Request request = new Request.Builder().url(uri).post(requestBody).build();
    Response response = client.newCall(request).execute();
    String responseContent = response.body().string();
    WxError error = WxError.fromJson(responseContent);
    if (error.getErrorCode() != 0) {
      throw new WxErrorException(error);
    } else {
      return WxMpMaterialVideoInfoResult.fromJson(responseContent);
    }
  }
}
