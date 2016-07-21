package me.chanjar.weixin.mp.api;

import com.google.gson.*;
import com.google.gson.internal.Streams;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.thoughtworks.xstream.XStream;
import me.chanjar.weixin.common.bean.WxAccessToken;
import me.chanjar.weixin.common.bean.WxCardApiSignature;
import me.chanjar.weixin.common.bean.WxJsapiSignature;
import me.chanjar.weixin.common.bean.result.WxError;
import me.chanjar.weixin.common.exception.WxErrorException;
import me.chanjar.weixin.common.session.StandardSessionManager;
import me.chanjar.weixin.common.session.WxSessionManager;
import me.chanjar.weixin.common.util.RandomUtils;
import me.chanjar.weixin.common.util.crypto.SHA1;
import me.chanjar.weixin.common.util.crypto.WxCryptUtil;
import me.chanjar.weixin.common.util.http.*;
import me.chanjar.weixin.common.util.xml.XStreamInitializer;
import me.chanjar.weixin.mp.api.impl.*;
import me.chanjar.weixin.mp.bean.*;
import me.chanjar.weixin.mp.bean.result.*;
import me.chanjar.weixin.mp.util.json.WxMpGsonBuilder;
import org.apache.http.Consts;
import org.apache.http.HttpHost;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import java.io.IOException;
import java.io.StringReader;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Map.Entry;

public class WxMpServiceImpl implements WxMpService {

  protected final Logger log = LoggerFactory.getLogger(WxMpServiceImpl.class);

  /**
   * 全局的是否正在刷新access token的锁
   */
  protected final Object globalAccessTokenRefreshLock = new Object();

  /**
   * 全局的是否正在刷新jsapi_ticket的锁
   */
  protected final Object globalJsapiTicketRefreshLock = new Object();

  /**
   * 全局的是否正在刷新卡券api_ticket的锁
   */
  protected final Object globalCardApiTicketRefreshLock = new Object();

  protected WxMpConfigStorage wxMpConfigStorage;
  
  protected WxMpKefuService kefuService = new WxMpKefuServiceImpl(this);

  protected WxMpMaterialService materialService = new WxMpMaterialServiceImpl(this);

  protected WxMpMenuService menuService = new WxMpMenuServiceImpl(this);

  protected WxMpUserService userService = new WxMpUserServiceImpl(this);

  protected WxMpGroupService groupService = new WxMpGroupServiceImpl(this);

  protected WxMpQrcodeService qrCodeService = new WxMpQrcodeServiceImpl(this);

  protected CloseableHttpClient httpClient;

  protected HttpHost httpProxy;

  private int retrySleepMillis = 1000;

  private int maxRetryTimes = 5;

  protected WxSessionManager sessionManager = new StandardSessionManager();

  @Override
  public boolean checkSignature(String timestamp, String nonce, String signature) {
    try {
      return SHA1.gen(this.wxMpConfigStorage.getToken(), timestamp, nonce).equals(signature);
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public String getAccessToken() throws WxErrorException {
    return getAccessToken(false);
  }

  @Override
  public String getAccessToken(boolean forceRefresh) throws WxErrorException {
    if (forceRefresh) {
      this.wxMpConfigStorage.expireAccessToken();
    }
    if (this.wxMpConfigStorage.isAccessTokenExpired()) {
      synchronized (this.globalAccessTokenRefreshLock) {
        if (this.wxMpConfigStorage.isAccessTokenExpired()) {
          String url = new StringBuffer()
              .append("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential")
              .append("&appid=").append(this.wxMpConfigStorage.getAppId())
              .append("&secret=").append(this.wxMpConfigStorage.getSecret()).toString();
          try {
            HttpGet httpGet = new HttpGet(url);
            if (this.httpProxy != null) {
              RequestConfig config = RequestConfig.custom().setProxy(this.httpProxy).build();
              httpGet.setConfig(config);
            }
            try (CloseableHttpResponse response = getHttpclient().execute(httpGet)) {
              String resultContent = new BasicResponseHandler().handleResponse(response);
              WxError error = WxError.fromJson(resultContent);
              if (error.getErrorCode() != 0) {
                throw new WxErrorException(error);
              }
              WxAccessToken accessToken = WxAccessToken.fromJson(resultContent);
              this.wxMpConfigStorage.updateAccessToken(accessToken.getAccessToken(), accessToken.getExpiresIn());
            }finally {
              httpGet.releaseConnection();
            }
          } catch (ClientProtocolException e) {
            throw new RuntimeException(e);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }
    return this.wxMpConfigStorage.getAccessToken();
  }

  @Override
  public String getJsapiTicket() throws WxErrorException {
    return getJsapiTicket(false);
  }

  @Override
  public String getJsapiTicket(boolean forceRefresh) throws WxErrorException {
    if (forceRefresh) {
      this.wxMpConfigStorage.expireJsapiTicket();
    }
    if (this.wxMpConfigStorage.isJsapiTicketExpired()) {
      synchronized (this.globalJsapiTicketRefreshLock) {
        if (this.wxMpConfigStorage.isJsapiTicketExpired()) {
          String url = "https://api.weixin.qq.com/cgi-bin/ticket/getticket?type=jsapi";
          String responseContent = execute(new SimpleGetRequestExecutor(), url, null);
          JsonElement tmpJsonElement = Streams.parse(new JsonReader(new StringReader(responseContent)));
          JsonObject tmpJsonObject = tmpJsonElement.getAsJsonObject();
          String jsapiTicket = tmpJsonObject.get("ticket").getAsString();
          int expiresInSeconds = tmpJsonObject.get("expires_in").getAsInt();
          this.wxMpConfigStorage.updateJsapiTicket(jsapiTicket, expiresInSeconds);
        }
      }
    }
    return this.wxMpConfigStorage.getJsapiTicket();
  }

  @Override
  public WxJsapiSignature createJsapiSignature(String url) throws WxErrorException {
    long timestamp = System.currentTimeMillis() / 1000;
    String noncestr = RandomUtils.getRandomStr();
    String jsapiTicket = getJsapiTicket(false);
    try {
      String signature = SHA1.genWithAmple(
          "jsapi_ticket=" + jsapiTicket,
          "noncestr=" + noncestr,
          "timestamp=" + timestamp,
          "url=" + url
      );
      WxJsapiSignature jsapiSignature = new WxJsapiSignature();
      jsapiSignature.setAppid(this.wxMpConfigStorage.getAppId());
      jsapiSignature.setTimestamp(timestamp);
      jsapiSignature.setNoncestr(noncestr);
      jsapiSignature.setUrl(url);
      jsapiSignature.setSignature(signature);
      return jsapiSignature;
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void customMessageSend(WxMpCustomMessage message) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/message/custom/send";
    execute(new SimplePostRequestExecutor(), url, message.toJson());
  }

  @Override
  public WxMpMassUploadResult massNewsUpload(WxMpMassNews news) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/media/uploadnews";
    String responseContent = execute(new SimplePostRequestExecutor(), url, news.toJson());
    return WxMpMassUploadResult.fromJson(responseContent);
  }

  @Override
  public WxMpMassUploadResult massVideoUpload(WxMpMassVideo video) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/media/uploadvideo";
    String responseContent = execute(new SimplePostRequestExecutor(), url, video.toJson());
    return WxMpMassUploadResult.fromJson(responseContent);
  }

  @Override
  public WxMpMassSendResult massGroupMessageSend(WxMpMassGroupMessage message) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/message/mass/sendall";
    String responseContent = execute(new SimplePostRequestExecutor(), url, message.toJson());
    return WxMpMassSendResult.fromJson(responseContent);
  }

  @Override
  public WxMpMassSendResult massOpenIdsMessageSend(WxMpMassOpenIdsMessage message) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/message/mass/send";
    String responseContent = execute(new SimplePostRequestExecutor(), url, message.toJson());
    return WxMpMassSendResult.fromJson(responseContent);
  }

  @Override
  public String shortUrl(String long_url) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/shorturl";
    JsonObject o = new JsonObject();
    o.addProperty("action", "long2short");
    o.addProperty("long_url", long_url);
    String responseContent = execute(new SimplePostRequestExecutor(), url, o.toString());
    JsonElement tmpJsonElement = Streams.parse(new JsonReader(new StringReader(responseContent)));
    return tmpJsonElement.getAsJsonObject().get("short_url").getAsString();
  }

  @Override
  public String templateSend(WxMpTemplateMessage templateMessage) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/message/template/send";
    String responseContent = execute(new SimplePostRequestExecutor(), url, templateMessage.toJson());
    JsonElement tmpJsonElement = Streams.parse(new JsonReader(new StringReader(responseContent)));
    final JsonObject jsonObject = tmpJsonElement.getAsJsonObject();
    if (jsonObject.get("errcode").getAsInt() == 0)
      return jsonObject.get("msgid").getAsString();
    throw new WxErrorException(WxError.fromJson(responseContent));
  }

  @Override
  public WxMpSemanticQueryResult semanticQuery(WxMpSemanticQuery semanticQuery) throws WxErrorException {
    String url = "https://api.weixin.qq.com/semantic/semproxy/search";
    String responseContent = execute(new SimplePostRequestExecutor(), url, semanticQuery.toJson());
    return WxMpSemanticQueryResult.fromJson(responseContent);
  }

  @Override
  public String oauth2buildAuthorizationUrl(String scope, String state) {
    return this.oauth2buildAuthorizationUrl(this.wxMpConfigStorage.getOauth2redirectUri(), scope, state);
  }

  @Override
  public String oauth2buildAuthorizationUrl(String redirectURI, String scope, String state) {
    StringBuffer url = new StringBuffer();
    url.append("https://open.weixin.qq.com/connect/oauth2/authorize?");
    url.append("appid=").append(this.wxMpConfigStorage.getAppId());
    url.append("&redirect_uri=").append(URIUtil.encodeURIComponent(redirectURI));
    url.append("&response_type=code");
    url.append("&scope=").append(scope);
    if (state != null) {
      url.append("&state=").append(state);
    }
    url.append("#wechat_redirect");
    return url.toString();
  }

  @Override
  public WxMpOAuth2AccessToken oauth2getAccessToken(String code) throws WxErrorException {
    StringBuffer url = new StringBuffer();
    url.append("https://api.weixin.qq.com/sns/oauth2/access_token?");
    url.append("appid=").append(this.wxMpConfigStorage.getAppId());
    url.append("&secret=").append(this.wxMpConfigStorage.getSecret());
    url.append("&code=").append(code);
    url.append("&grant_type=authorization_code");

    try {
      RequestExecutor<String, String> executor = new SimpleGetRequestExecutor();
      String responseText = executor.execute(getHttpclient(), this.httpProxy, url.toString(), null);
      return WxMpOAuth2AccessToken.fromJson(responseText);
    } catch (ClientProtocolException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public WxMpOAuth2AccessToken oauth2refreshAccessToken(String refreshToken) throws WxErrorException {
    StringBuffer url = new StringBuffer();
    url.append("https://api.weixin.qq.com/sns/oauth2/refresh_token?");
    url.append("appid=").append(this.wxMpConfigStorage.getAppId());
    url.append("&grant_type=refresh_token");
    url.append("&refresh_token=").append(refreshToken);

    try {
      RequestExecutor<String, String> executor = new SimpleGetRequestExecutor();
      String responseText = executor.execute(getHttpclient(), this.httpProxy, url.toString(), null);
      return WxMpOAuth2AccessToken.fromJson(responseText);
    } catch (ClientProtocolException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public WxMpUser oauth2getUserInfo(WxMpOAuth2AccessToken oAuth2AccessToken, String lang) throws WxErrorException {
    StringBuffer url = new StringBuffer();
    url.append("https://api.weixin.qq.com/sns/userinfo?");
    url.append("access_token=").append(oAuth2AccessToken.getAccessToken());
    url.append("&openid=").append(oAuth2AccessToken.getOpenId());
    if (lang == null) {
      url.append("&lang=zh_CN");
    } else {
      url.append("&lang=").append(lang);
    }

    try {
      RequestExecutor<String, String> executor = new SimpleGetRequestExecutor();
      String responseText = executor.execute(getHttpclient(), this.httpProxy, url.toString(), null);
      return WxMpUser.fromJson(responseText);
    } catch (ClientProtocolException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean oauth2validateAccessToken(WxMpOAuth2AccessToken oAuth2AccessToken) {
    StringBuffer url = new StringBuffer();
    url.append("https://api.weixin.qq.com/sns/auth?");
    url.append("access_token=").append(oAuth2AccessToken.getAccessToken());
    url.append("&openid=").append(oAuth2AccessToken.getOpenId());

    try {
      RequestExecutor<String, String> executor = new SimpleGetRequestExecutor();
      executor.execute(getHttpclient(), this.httpProxy, url.toString(), null);
    } catch (ClientProtocolException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    } catch (WxErrorException e) {
      return false;
    }
    return true;
  }

  @Override
  public String[] getCallbackIP() throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/getcallbackip";
    String responseContent = get(url, null);
    JsonElement tmpJsonElement = Streams.parse(new JsonReader(new StringReader(responseContent)));
    JsonArray ipList = tmpJsonElement.getAsJsonObject().get("ip_list").getAsJsonArray();
    String[] ipArray = new String[ipList.size()];
    for (int i = 0; i < ipList.size(); i++) {
      ipArray[i] = ipList.get(i).getAsString();
    }
    return ipArray;
  }

  @Override
  public String get(String url, String queryParam) throws WxErrorException {
    return execute(new SimpleGetRequestExecutor(), url, queryParam);
  }

  @Override
  public String post(String url, String postData) throws WxErrorException {
    return execute(new SimplePostRequestExecutor(), url, postData);
  }

  /**
   * 向微信端发送请求，在这里执行的策略是当发生access_token过期时才去刷新，然后重新执行请求，而不是全局定时请求
   */
  @Override
  public <T, E> T execute(RequestExecutor<T, E> executor, String uri, E data) throws WxErrorException {
    int retryTimes = 0;
    do {
      try {
        return executeInternal(executor, uri, data);
      } catch (WxErrorException e) {
        WxError error = e.getError();
        /**
         * -1 系统繁忙, 1000ms后重试
         */
        if (error.getErrorCode() == -1) {
          int sleepMillis = this.retrySleepMillis * (1 << retryTimes);
          try {
            this.log.debug("微信系统繁忙，{}ms 后重试(第{}次)", sleepMillis, retryTimes + 1);
            Thread.sleep(sleepMillis);
          } catch (InterruptedException e1) {
            throw new RuntimeException(e1);
          }
        } else {
          throw e;
        }
      }
    } while (++retryTimes < this.maxRetryTimes);

    throw new RuntimeException("微信服务端异常，超出重试次数");
  }

  protected synchronized <T, E> T executeInternal(RequestExecutor<T, E> executor, String uri, E data) throws WxErrorException {
    if (uri.indexOf("access_token=") != -1) {
      throw new IllegalArgumentException("uri参数中不允许有access_token: " + uri);
    }
    String accessToken = getAccessToken(false);

    String uriWithAccessToken = uri;
    uriWithAccessToken += uri.indexOf('?') == -1 ? "?access_token=" + accessToken : "&access_token=" + accessToken;

    try {
      return executor.execute(getHttpclient(), this.httpProxy, uriWithAccessToken, data);
    } catch (WxErrorException e) {
      WxError error = e.getError();
      /*
       * 发生以下情况时尝试刷新access_token
       * 40001 获取access_token时AppSecret错误，或者access_token无效
       * 42001 access_token超时
       */
      if (error.getErrorCode() == 42001 || error.getErrorCode() == 40001) {
        // 强制设置wxMpConfigStorage它的access token过期了，这样在下一次请求里就会刷新access token
        this.wxMpConfigStorage.expireAccessToken();
        return execute(executor, uri, data);
      }
      if (error.getErrorCode() != 0) {
        throw new WxErrorException(error);
      }
      return null;
    } catch (ClientProtocolException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected CloseableHttpClient getHttpclient() {
    return this.httpClient;
  }

  @Override
  public void setWxMpConfigStorage(WxMpConfigStorage wxConfigProvider) {
    this.wxMpConfigStorage = wxConfigProvider;

    ApacheHttpClientBuilder apacheHttpClientBuilder = this.wxMpConfigStorage.getApacheHttpClientBuilder();
    if (null == apacheHttpClientBuilder) {
      apacheHttpClientBuilder = DefaultApacheHttpHttpClientBuilder.get();
    }
    apacheHttpClientBuilder.httpProxyHost(this.wxMpConfigStorage.getHttp_proxy_host())
      .httpProxyPort(this.wxMpConfigStorage.getHttp_proxy_port())
      .httpProxyUsername(this.wxMpConfigStorage.getHttp_proxy_username())
      .httpProxyPassword(this.wxMpConfigStorage.getHttp_proxy_password());

    if (wxConfigProvider.getSSLContext() != null){
      SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
          wxConfigProvider.getSSLContext(),
          new String[] { "TLSv1" },
          null,
          SSLConnectionSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER);
      apacheHttpClientBuilder.sslConnectionSocketFactory(sslsf);
    }

    this.httpClient = apacheHttpClientBuilder.build();
  }

  @Override
  public WxMpConfigStorage getWxMpConfigStorage() {
    return this.wxMpConfigStorage;
  }

  @Override
  public void setRetrySleepMillis(int retrySleepMillis) {
    this.retrySleepMillis = retrySleepMillis;
  }


  @Override
  public void setMaxRetryTimes(int maxRetryTimes) {
    this.maxRetryTimes = maxRetryTimes;
  }

  @Override
  public WxMpPrepayIdResult getPrepayId(String openId, String outTradeNo, double amt, String body, String tradeType, String ip, String callbackUrl) {
    Map<String, String> packageParams = new HashMap<>();
    packageParams.put("appid", this.wxMpConfigStorage.getAppId());
    packageParams.put("mch_id", this.wxMpConfigStorage.getPartnerId());
    packageParams.put("body", body);
    packageParams.put("out_trade_no", outTradeNo);
    packageParams.put("total_fee", (int) (amt * 100) + "");
    packageParams.put("spbill_create_ip", ip);
    packageParams.put("notify_url", callbackUrl);
    packageParams.put("trade_type", tradeType);
    packageParams.put("openid", openId);

    return getPrepayId(packageParams);
  }

  @Override
  public WxMpPrepayIdResult getPrepayId(final Map<String, String> parameters) {
    String nonce_str = System.currentTimeMillis() + "";

    final SortedMap<String, String> packageParams = new TreeMap<>(parameters);
    packageParams.put("appid", this.wxMpConfigStorage.getAppId());
    packageParams.put("mch_id", this.wxMpConfigStorage.getPartnerId());
    packageParams.put("nonce_str", nonce_str);
    checkParameters(packageParams);

    String sign = WxCryptUtil.createSign(packageParams, this.wxMpConfigStorage.getPartnerKey());
    packageParams.put("sign", sign);

    StringBuilder request = new StringBuilder("<xml>");
    for (Entry<String, String> para : packageParams.entrySet()) {
      request.append(String.format("<%s>%s</%s>", para.getKey(), para.getValue(), para.getKey()));
    }
    request.append("</xml>");

    HttpPost httpPost = new HttpPost("https://api.mch.weixin.qq.com/pay/unifiedorder");
    if (this.httpProxy != null) {
      RequestConfig config = RequestConfig.custom().setProxy(this.httpProxy).build();
      httpPost.setConfig(config);
    }

    StringEntity entity = new StringEntity(request.toString(), Consts.UTF_8);
    httpPost.setEntity(entity);
    try(CloseableHttpResponse response = getHttpclient().execute(httpPost)) {
      String responseContent = Utf8ResponseHandler.INSTANCE.handleResponse(response);
      XStream xstream = XStreamInitializer.getInstance();
      xstream.alias("xml", WxMpPrepayIdResult.class);
      WxMpPrepayIdResult wxMpPrepayIdResult = (WxMpPrepayIdResult) xstream.fromXML(responseContent);
      return wxMpPrepayIdResult;
    } catch (IOException e) {
      throw new RuntimeException("Failed to get prepay id due to IO exception.", e);
    }finally {
      httpPost.releaseConnection();
    }
  }

  final String[] REQUIRED_ORDER_PARAMETERS = new String[] { "appid", "mch_id", "body", "out_trade_no", "total_fee", "spbill_create_ip", "notify_url",
      "trade_type", };

  private void checkParameters(Map<String, String> parameters) {
    for (String para : this.REQUIRED_ORDER_PARAMETERS) {
      if (!parameters.containsKey(para))
        throw new IllegalArgumentException("Reqiured argument '" + para + "' is missing.");
    }
    if ("JSAPI".equals(parameters.get("trade_type")) && !parameters.containsKey("openid"))
      throw new IllegalArgumentException("Reqiured argument 'openid' is missing when trade_type is 'JSAPI'.");
    if ("NATIVE".equals(parameters.get("trade_type")) && !parameters.containsKey("product_id"))
      throw new IllegalArgumentException("Reqiured argument 'product_id' is missing when trade_type is 'NATIVE'.");
  }

 	@Override
	public Map<String, String> getJsapiPayInfo(String openId,String outTradeNo, double amt, String body,String ip, String callbackUrl) throws WxErrorException{
		Map<String, String> packageParams = new HashMap<>();
		packageParams.put("appid", this.wxMpConfigStorage.getAppId());
		packageParams.put("mch_id", this.wxMpConfigStorage.getPartnerId());
		packageParams.put("body", body);
		packageParams.put("out_trade_no", outTradeNo);
		packageParams.put("total_fee", (int) (amt * 100) + "");
		packageParams.put("spbill_create_ip", ip);
		packageParams.put("notify_url", callbackUrl);
		packageParams.put("trade_type", "JSAPI");
	 	packageParams.put("openid", openId);
	 
		return getPayInfo(packageParams);
	}
	
	@Override
	public Map<String, String> getNativePayInfo(String productId,String outTradeNo, double amt, String body,String ip, String callbackUrl) throws WxErrorException{
		Map<String, String> packageParams = new HashMap<>();
		packageParams.put("appid", this.wxMpConfigStorage.getAppId());
		packageParams.put("mch_id", this.wxMpConfigStorage.getPartnerId());
		packageParams.put("body", body);
		packageParams.put("out_trade_no", outTradeNo);
		packageParams.put("total_fee", (int) (amt * 100) + "");
		packageParams.put("spbill_create_ip", ip);
		packageParams.put("notify_url", callbackUrl);
		packageParams.put("trade_type", "NATIVE");
		packageParams.put("product_id", productId);
		 
		return getPayInfo(packageParams);
	}
  
  @Override
  public Map<String, String> getPayInfo(Map<String, String> parameters) throws WxErrorException {
    WxMpPrepayIdResult wxMpPrepayIdResult = getPrepayId(parameters);
    
    if (!"SUCCESS".equalsIgnoreCase(wxMpPrepayIdResult.getReturn_code())
            ||!"SUCCESS".equalsIgnoreCase(wxMpPrepayIdResult.getResult_code())) {
      WxError error = new WxError();
      error.setErrorCode(-1);
      error.setErrorMsg("return_code:" + wxMpPrepayIdResult.getReturn_code() +
                        ";return_msg:" + wxMpPrepayIdResult.getReturn_msg() +
                        ";result_code:" + wxMpPrepayIdResult.getResult_code() +
                        ";err_code" + wxMpPrepayIdResult.getErr_code() +
                        ";err_code_des" + wxMpPrepayIdResult.getErr_code_des());
      throw new WxErrorException(error);
    }
    
    String prepayId = wxMpPrepayIdResult.getPrepay_id();
    if (prepayId == null || prepayId.equals("")) {
      throw new RuntimeException(String.format("Failed to get prepay id due to error code '%s'(%s).", wxMpPrepayIdResult.getErr_code(), wxMpPrepayIdResult.getErr_code_des()));
    }

    Map<String, String> payInfo = new HashMap<>();
    payInfo.put("appId", this.wxMpConfigStorage.getAppId());
    // 支付签名时间戳，注意微信jssdk中的所有使用timestamp字段均为小写。但最新版的支付后台生成签名使用的timeStamp字段名需大写其中的S字符
    payInfo.put("timeStamp", String.valueOf(System.currentTimeMillis() / 1000));
    payInfo.put("nonceStr", System.currentTimeMillis() + "");
    payInfo.put("package", "prepay_id=" + prepayId);
    payInfo.put("signType", "MD5");
    if("NATIVE".equals(parameters.get("trade_type"))){
      payInfo.put("codeUrl", wxMpPrepayIdResult.getCode_url());
    }

    String finalSign = WxCryptUtil.createSign(payInfo, this.wxMpConfigStorage.getPartnerKey());
    payInfo.put("paySign", finalSign);
    return payInfo;
  }

  @Override
  public WxMpPayResult getJSSDKPayResult(String transactionId, String outTradeNo) {
    String nonce_str = System.currentTimeMillis() + "";

    SortedMap<String, String> packageParams = new TreeMap<>();
    packageParams.put("appid", this.wxMpConfigStorage.getAppId());
    packageParams.put("mch_id", this.wxMpConfigStorage.getPartnerId());
    if (transactionId != null && !"".equals(transactionId.trim()))
      packageParams.put("transaction_id", transactionId);
    else if (outTradeNo != null && !"".equals(outTradeNo.trim()))
      packageParams.put("out_trade_no", outTradeNo);
    else
      throw new IllegalArgumentException("Either 'transactionId' or 'outTradeNo' must be given.");
    packageParams.put("nonce_str", nonce_str);
    packageParams.put("sign", WxCryptUtil.createSign(packageParams, this.wxMpConfigStorage.getPartnerKey()));

    StringBuilder request = new StringBuilder("<xml>");
    for (Entry<String, String> para : packageParams.entrySet()) {
      request.append(String.format("<%s>%s</%s>", para.getKey(), para.getValue(), para.getKey()));
    }
    request.append("</xml>");

    HttpPost httpPost = new HttpPost("https://api.mch.weixin.qq.com/pay/orderquery");
    if (this.httpProxy != null) {
      RequestConfig config = RequestConfig.custom().setProxy(this.httpProxy).build();
      httpPost.setConfig(config);
    }

    StringEntity entity = new StringEntity(request.toString(), Consts.UTF_8);
    httpPost.setEntity(entity);
    try(CloseableHttpResponse response = this.httpClient.execute(httpPost)) {
      String responseContent = Utf8ResponseHandler.INSTANCE.handleResponse(response);
      XStream xstream = XStreamInitializer.getInstance();
      xstream.alias("xml", WxMpPayResult.class);
      WxMpPayResult wxMpPayResult = (WxMpPayResult) xstream.fromXML(responseContent);
      return wxMpPayResult;
    } catch (IOException e) {
      throw new RuntimeException("Failed to query order due to IO exception.", e);
    }
  }

  @Override
  public WxMpPayCallback getJSSDKCallbackData(String xmlData) {
    try {
      XStream xstream = XStreamInitializer.getInstance();
      xstream.alias("xml", WxMpPayCallback.class);
      WxMpPayCallback wxMpCallback = (WxMpPayCallback) xstream.fromXML(xmlData);
      return wxMpCallback;
    } catch (Exception e){
      e.printStackTrace();
    }
    return new WxMpPayCallback();
  }
  
  @Override
  public WxMpPayRefundResult refundPay(Map<String, String> parameters) throws WxErrorException {
    SortedMap<String, String> refundParams = new TreeMap<>(parameters);
    refundParams.put("appid", this.wxMpConfigStorage.getAppId());
    refundParams.put("mch_id", this.wxMpConfigStorage.getPartnerId());
    refundParams.put("nonce_str", System.currentTimeMillis() + "");
    refundParams.put("op_user_id", this.wxMpConfigStorage.getPartnerId());
    String sign = WxCryptUtil.createSign(refundParams, this.wxMpConfigStorage.getPartnerKey());
    refundParams.put("sign", sign);

    StringBuilder request = new StringBuilder("<xml>");
    for (Entry<String, String> para : refundParams.entrySet()) {
      request.append(String.format("<%s>%s</%s>", para.getKey(), para.getValue(), para.getKey()));
    }
    request.append("</xml>");
    
    HttpPost httpPost = new HttpPost("https://api.mch.weixin.qq.com/secapi/pay/refund");
    if (this.httpProxy != null) {
      RequestConfig config = RequestConfig.custom().setProxy(this.httpProxy).build();
      httpPost.setConfig(config);
    }
    
    StringEntity entity = new StringEntity(request.toString(), Consts.UTF_8);
    httpPost.setEntity(entity);
    try(
      CloseableHttpResponse response = getHttpclient().execute(httpPost)) {
      String responseContent = Utf8ResponseHandler.INSTANCE.handleResponse(response);
      XStream xstream = XStreamInitializer.getInstance();
      xstream.processAnnotations(WxMpPayRefundResult.class);
      WxMpPayRefundResult wxMpPayRefundResult = (WxMpPayRefundResult) xstream.fromXML(responseContent);
      
      if (!"SUCCESS".equalsIgnoreCase(wxMpPayRefundResult.getResultCode())
            ||!"SUCCESS".equalsIgnoreCase(wxMpPayRefundResult.getReturnCode())) {
        WxError error = new WxError();
        error.setErrorCode(-1);
        error.setErrorMsg("return_code:" + wxMpPayRefundResult.getReturnCode() +
                          ";return_msg:" + wxMpPayRefundResult.getReturnMsg() +
                          ";result_code:" + wxMpPayRefundResult.getResultCode() +
                          ";err_code" + wxMpPayRefundResult.getErrCode() +
                          ";err_code_des" + wxMpPayRefundResult.getErrCodeDes());
        throw new WxErrorException(error);
      }
      
      return wxMpPayRefundResult;
    } catch (IOException e) {
      this.log.error(MessageFormatter.format("The exception was happened when sending refund '{}'.", request.toString()).getMessage(), e);
      WxError error = new WxError();
      error.setErrorCode(-1);
      error.setErrorMsg("incorrect response.");
      throw new WxErrorException(error);
    }finally {
      httpPost.releaseConnection();
    }
  }
  
  @Override
  public boolean checkJSSDKCallbackDataSignature(Map<String, String> kvm, String signature) {
    return signature.equals(WxCryptUtil.createSign(kvm, this.wxMpConfigStorage.getPartnerKey()));
  }

  @Override
  public WxRedpackResult sendRedpack(Map<String, String> parameters) throws WxErrorException {
    String nonce_str = System.currentTimeMillis() + "";

    SortedMap<String, String> packageParams = new TreeMap<>(parameters);
    packageParams.put("wxappid", this.wxMpConfigStorage.getAppId());
    packageParams.put("mch_id", this.wxMpConfigStorage.getPartnerId());
    packageParams.put("nonce_str", nonce_str);

    String sign = WxCryptUtil.createSign(packageParams, this.wxMpConfigStorage.getPartnerKey());
    packageParams.put("sign", sign);
    
    StringBuilder request = new StringBuilder("<xml>");
    for (Entry<String, String> para : packageParams.entrySet()) {
      request.append(String.format("<%s>%s</%s>", para.getKey(), para.getValue(), para.getKey()));
    }
    request.append("</xml>");
    
    HttpPost httpPost = new HttpPost("https://api.mch.weixin.qq.com/mmpaymkttransfers/sendredpack");
    if (this.httpProxy != null) {
      RequestConfig config = RequestConfig.custom().setProxy(this.httpProxy).build();
      httpPost.setConfig(config);
    }

    StringEntity entity = new StringEntity(request.toString(), Consts.UTF_8);
    httpPost.setEntity(entity);
    try(CloseableHttpResponse response = getHttpclient().execute(httpPost)) {
      String responseContent = Utf8ResponseHandler.INSTANCE.handleResponse(response);
      XStream xstream = XStreamInitializer.getInstance();
      xstream.processAnnotations(WxRedpackResult.class);
      WxRedpackResult wxMpRedpackResult = (WxRedpackResult) xstream.fromXML(responseContent);
      return wxMpRedpackResult;
    } catch (IOException e) {
      this.log.error(MessageFormatter.format("The exception was happened when sending redpack '{}'.", request.toString()).getMessage(), e);
      WxError error = new WxError();
      error.setErrorCode(-1);
      throw new WxErrorException(error);
    }finally {
      httpPost.releaseConnection();
    }
  }

  /**
   * 获得卡券api_ticket，不强制刷新卡券api_ticket
   *
   * @return 卡券api_ticket
   * @throws WxErrorException
   * @see #getCardApiTicket(boolean)
   */
  @Override
  public String getCardApiTicket() throws WxErrorException {
    return getCardApiTicket(false);
  }

  /**
   * <pre>
   * 获得卡券api_ticket
   * 获得时会检查卡券apiToken是否过期，如果过期了，那么就刷新一下，否则就什么都不干
   *
   * 详情请见：http://mp.weixin.qq.com/wiki/7/aaa137b55fb2e0456bf8dd9148dd613f.html#.E9.99.84.E5.BD
   * .954-.E5.8D.A1.E5.88.B8.E6.89.A9.E5.B1.95.E5.AD.97.E6.AE.B5.E5.8F.8A.E7.AD.BE.E5.90.8D.E7.94
   * .9F.E6.88.90.E7.AE.97.E6.B3.95
   * </pre>
   *
   * @param forceRefresh 强制刷新
   * @return 卡券api_ticket
   * @throws WxErrorException
   */
  @Override
  public String getCardApiTicket(boolean forceRefresh) throws WxErrorException {
    if (forceRefresh) {
      this.wxMpConfigStorage.expireCardApiTicket();
    }
    if (this.wxMpConfigStorage.isCardApiTicketExpired()) {
      synchronized (this.globalCardApiTicketRefreshLock) {
        if (this.wxMpConfigStorage.isCardApiTicketExpired()) {
          String url = "https://api.weixin.qq.com/cgi-bin/ticket/getticket?type=wx_card";
          String responseContent = execute(new SimpleGetRequestExecutor(), url, null);
          JsonElement tmpJsonElement = Streams.parse(new JsonReader(new StringReader(responseContent)));
          JsonObject tmpJsonObject = tmpJsonElement.getAsJsonObject();
          String cardApiTicket = tmpJsonObject.get("ticket").getAsString();
          int expiresInSeconds = tmpJsonObject.get("expires_in").getAsInt();
          this.wxMpConfigStorage.updateCardApiTicket(cardApiTicket, expiresInSeconds);
        }
      }
    }
    return this.wxMpConfigStorage.getCardApiTicket();
  }

  /**
   * <pre>
   * 创建调用卡券api时所需要的签名
   *
   * 详情请见：http://mp.weixin.qq.com/wiki/7/aaa137b55fb2e0456bf8dd9148dd613f.html#.E9.99.84.E5.BD
   * .954-.E5.8D.A1.E5.88.B8.E6.89.A9.E5.B1.95.E5.AD.97.E6.AE.B5.E5.8F.8A.E7.AD.BE.E5.90.8D.E7.94
   * .9F.E6.88.90.E7.AE.97.E6.B3.95
   * </pre>
   *
   * @param optionalSignParam 参与签名的参数数组。
   *                  可以为下列字段：app_id, card_id, card_type, code, openid, location_id
   *                  </br>注意：当做wx.chooseCard调用时，必须传入app_id参与签名，否则会造成签名失败导致拉取卡券列表为空
   * @return 卡券Api签名对象
   */
  @Override
  public WxCardApiSignature createCardApiSignature(String... optionalSignParam) throws
      WxErrorException {
    long timestamp = System.currentTimeMillis() / 1000;
    String nonceStr = RandomUtils.getRandomStr();
    String cardApiTicket = getCardApiTicket(false);

    String[] signParam = Arrays.copyOf(optionalSignParam, optionalSignParam.length + 3);
    signParam[optionalSignParam.length] = String.valueOf(timestamp);
    signParam[optionalSignParam.length + 1] = nonceStr;
    signParam[optionalSignParam.length + 2] = cardApiTicket;
    try {
      String signature = SHA1.gen(signParam);
      WxCardApiSignature cardApiSignature = new WxCardApiSignature();
      cardApiSignature.setTimestamp(timestamp);
      cardApiSignature.setNonceStr(nonceStr);
      cardApiSignature.setSignature(signature);
      return cardApiSignature;
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 卡券Code解码
   *
   * @param encryptCode 加密Code，通过JSSDK的chooseCard接口获得
   * @return 解密后的Code
   * @throws WxErrorException
   */
  @Override
  public String decryptCardCode(String encryptCode) throws WxErrorException {
    String url = "https://api.weixin.qq.com/card/code/decrypt";
    JsonObject param = new JsonObject();
    param.addProperty("encrypt_code", encryptCode);
    String responseContent = post(url, param.toString());
    JsonElement tmpJsonElement = Streams.parse(new JsonReader(new StringReader(responseContent)));
    JsonObject tmpJsonObject = tmpJsonElement.getAsJsonObject();
    JsonPrimitive jsonPrimitive = tmpJsonObject.getAsJsonPrimitive("code");
    return jsonPrimitive.getAsString();
  }

  /**
   * 卡券Code查询
   *
   * @param cardId       卡券ID代表一类卡券
   * @param code         单张卡券的唯一标准
   * @param checkConsume 是否校验code核销状态，填入true和false时的code异常状态返回数据不同
   * @return WxMpCardResult对象
   * @throws WxErrorException
   */
  @Override
  public WxMpCardResult queryCardCode(String cardId, String code, boolean checkConsume) throws WxErrorException {
    String url = "https://api.weixin.qq.com/card/code/get";
    JsonObject param = new JsonObject();
    param.addProperty("card_id", cardId);
    param.addProperty("code", code);
    param.addProperty("check_consume", checkConsume);
    String responseContent = post(url, param.toString());
    JsonElement tmpJsonElement = Streams.parse(new JsonReader(new StringReader(responseContent)));
    return WxMpGsonBuilder.INSTANCE.create().fromJson(tmpJsonElement,
        new TypeToken<WxMpCardResult>() {
        }.getType());
  }

  /**
   * 卡券Code核销。核销失败会抛出异常
   *
   * @param code 单张卡券的唯一标准
   * @return 调用返回的JSON字符串。
   * <br>可用 com.google.gson.JsonParser#parse 等方法直接取JSON串中的errcode等信息。
   * @throws WxErrorException
   */
  @Override
  public String consumeCardCode(String code) throws WxErrorException {
    return consumeCardCode(code, null);
  }

  /**
   * 卡券Code核销。核销失败会抛出异常
   *
   * @param code   单张卡券的唯一标准
   * @param cardId 当自定义Code卡券时需要传入card_id
   * @return 调用返回的JSON字符串。
   * <br>可用 com.google.gson.JsonParser#parse 等方法直接取JSON串中的errcode等信息。
   * @throws WxErrorException
   */
  @Override
  public String consumeCardCode(String code, String cardId) throws WxErrorException {
    String url = "https://api.weixin.qq.com/card/code/consume";
    JsonObject param = new JsonObject();
    param.addProperty("code", code);
    
    if (cardId != null && !"".equals(cardId)) {
      param.addProperty("card_id", cardId);
    }
    
    String responseContent = post(url, param.toString());
    return responseContent;
  }

  /**
   * 卡券Mark接口。
   * 开发者在帮助消费者核销卡券之前，必须帮助先将此code（卡券串码）与一个openid绑定（即mark住），
   * 才能进一步调用核销接口，否则报错。
   *
   * @param code   卡券的code码
   * @param cardId 卡券的ID
   * @param openId 用券用户的openid
   * @param isMark 是否要mark（占用）这个code，填写true或者false，表示占用或解除占用
   * @throws WxErrorException
   */
  @Override
  public void markCardCode(String code, String cardId, String openId, boolean isMark) throws
      WxErrorException {
    String url = "https://api.weixin.qq.com/card/code/mark";
    JsonObject param = new JsonObject();
    param.addProperty("code", code);
    param.addProperty("card_id", cardId);
    param.addProperty("openid", openId);
    param.addProperty("is_mark", isMark);
    String responseContent = post(url, param.toString());
    JsonElement tmpJsonElement = Streams.parse(new JsonReader(new StringReader(responseContent)));
    WxMpCardResult cardResult = WxMpGsonBuilder.INSTANCE.create().fromJson(tmpJsonElement,
        new TypeToken<WxMpCardResult>() { }.getType());
    if (!cardResult.getErrorCode().equals("0")) {
      this.log.warn("朋友的券mark失败：{}", cardResult.getErrorMsg());
    }
  }

  @Override
  public String getCardDetail(String cardId) throws WxErrorException {
    String url = "https://api.weixin.qq.com/card/get";
    JsonObject param = new JsonObject();
    param.addProperty("card_id", cardId);
    String responseContent = post(url, param.toString());
    
    // 判断返回值
    JsonObject json = (new JsonParser()).parse(responseContent).getAsJsonObject();
    String errcode = json.get("errcode").getAsString();
    if (!"0".equals(errcode)) {
      String errmsg = json.get("errmsg").getAsString();
      WxError error = new WxError();
      error.setErrorCode(Integer.valueOf(errcode));
      error.setErrorMsg(errmsg);
      throw new WxErrorException(error);
    }
    
    return responseContent;
  }

  @Override
  public WxMpMassSendResult massMessagePreview(WxMpMassPreviewMessage wxMpMassPreviewMessage) throws Exception {
    String url = "https://api.weixin.qq.com/cgi-bin/message/mass/preview";
    String responseContent = execute(new SimplePostRequestExecutor(), url, wxMpMassPreviewMessage.toJson());
    return WxMpMassSendResult.fromJson(responseContent);
  }

  @Override
  public String setIndustry(WxMpIndustry wxMpIndustry) throws WxErrorException {
    if (null == wxMpIndustry.getPrimaryIndustry() || null == wxMpIndustry.getPrimaryIndustry().getId()
        || null == wxMpIndustry.getSecondIndustry() || null == wxMpIndustry.getSecondIndustry().getId()) {
      throw new IllegalArgumentException("industry id is empty");
    }
    String url = "https://api.weixin.qq.com/cgi-bin/template/api_set_industry";
    return execute(new SimplePostRequestExecutor(), url, wxMpIndustry.toJson());
  }

  @Override
  public WxMpIndustry getIndustry() throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/template/get_industry";
    String responseContent = execute(new SimpleGetRequestExecutor(), url, null);
    return WxMpIndustry.fromJson(responseContent);
  }

  @Override
  public WxMpKefuService getKefuService() {
    return this.kefuService;
  }

  @Override
  public WxMpMaterialService getMaterialService() {
    return this.materialService;
  }

  @Override
  public WxMpMenuService getMenuService() {
    return this.menuService;
  }

  @Override
  public WxMpUserService getUserService() {
    return this.userService;
  }

  @Override
  public WxMpGroupService getGroupService() {
    return this.groupService;
  }

  @Override
  public WxMpQrcodeService getQrcodeService() {
    return this.qrCodeService;
  }

}
