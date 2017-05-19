package me.chanjar.weixin.mp.util.http;


  import me.chanjar.weixin.common.util.http.RequestExecutor;
  import me.chanjar.weixin.common.util.http.RequestHttp;

  import me.chanjar.weixin.mp.bean.material.WxMpMaterialVideoInfoResult;
  import me.chanjar.weixin.mp.util.http.apache.ApacheMaterialVideoInfoRequestExecutor;
  import me.chanjar.weixin.mp.util.http.jodd.JoddMaterialVideoInfoRequestExecutor;
  import me.chanjar.weixin.mp.util.http.okhttp.OkhttpMaterialVideoInfoRequestExecutor;


public abstract class MaterialVideoInfoRequestExecutor<H, P> implements RequestExecutor<WxMpMaterialVideoInfoResult, String> {
  protected RequestHttp<H, P> requestHttp;

  public MaterialVideoInfoRequestExecutor(RequestHttp requestHttp) {
          this.requestHttp = requestHttp;
        }

        public static RequestExecutor<WxMpMaterialVideoInfoResult, String> create(RequestHttp requestHttp) {
          switch (requestHttp.getRequestType()) {
            case apacheHttp:
              return new ApacheMaterialVideoInfoRequestExecutor(requestHttp);
            case joddHttp:
              return new JoddMaterialVideoInfoRequestExecutor(requestHttp);
            case okHttp:
              return new OkhttpMaterialVideoInfoRequestExecutor(requestHttp);
            default:
              return null;
          }
        }

      }
