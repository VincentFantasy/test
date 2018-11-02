package me.chanjar.weixin.mp.api.impl;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.common.util.json.WxGsonBuilder;
import me.chanjar.weixin.mp.api.WxMpMemberCardService;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.card.AdvancedInfo;
import me.chanjar.weixin.mp.bean.card.BaseInfo;
import me.chanjar.weixin.mp.bean.card.DateInfo;
import me.chanjar.weixin.mp.bean.card.MemberCard;
import me.chanjar.weixin.mp.bean.card.MemberCardActivateUserFormRequest;
import me.chanjar.weixin.mp.bean.card.MemberCardActivateUserFormResult;
import me.chanjar.weixin.mp.bean.card.MemberCardCreateRequest;
import me.chanjar.weixin.mp.bean.card.WxMpCardCreateResult;
import me.chanjar.weixin.mp.bean.card.enums.BusinessServiceType;
import me.chanjar.weixin.mp.bean.card.enums.CardColor;
import me.chanjar.weixin.mp.bean.card.enums.DateInfoType;
import me.chanjar.weixin.mp.bean.membercard.ActivatePluginParam;
import me.chanjar.weixin.mp.bean.membercard.ActivatePluginParamResult;
import me.chanjar.weixin.mp.bean.membercard.WxMpMemberCardActivatedMessage;
import me.chanjar.weixin.mp.bean.membercard.WxMpMemberCardCreateMessage;
import me.chanjar.weixin.mp.bean.membercard.WxMpMemberCardUpdateMessage;
import me.chanjar.weixin.mp.bean.membercard.WxMpMemberCardUpdateResult;
import me.chanjar.weixin.mp.bean.membercard.WxMpMemberCardUserInfoResult;
import me.chanjar.weixin.mp.util.json.WxMpGsonBuilder;

/**
 * 会员卡相关接口的实现类
 *
 * @author YuJian(mgcnrx11 @ gmail.com)
 * @version 2017/7/8
 */
public class WxMpMemberCardServiceImpl implements WxMpMemberCardService {

  private final Logger log = LoggerFactory.getLogger(WxMpMemberCardServiceImpl.class);

  private WxMpService wxMpService;

  private static final Gson GSON = WxMpGsonBuilder.create();

  WxMpMemberCardServiceImpl(WxMpService wxMpService) {
    this.wxMpService = wxMpService;
  }

  /**
   * 得到WxMpService
   */
  @Override
  public WxMpService getWxMpService() {
    return this.wxMpService;
  }

  /**
   * 会员卡创建接口
   *
   * @param createJson 创建json
   * @return 调用返回的JSON字符串。
   * @throws WxErrorException 接口调用失败抛出的异常
   */
  @Override
  public WxMpCardCreateResult createMemberCard(String createJson) throws WxErrorException {
    WxMpMemberCardCreateMessage createMessage = WxGsonBuilder.create().fromJson(createJson, WxMpMemberCardCreateMessage.class);
    return createMemberCard(createMessage);
  }

  /**
   * 会员卡创建接口
   *
   * @param createMessageMessage 创建所需参数
   * @return WxMpCardCreateResult。
   * @throws WxErrorException 接口调用失败抛出的异常
   */
  @Override
  public WxMpCardCreateResult createMemberCard(WxMpMemberCardCreateMessage createMessageMessage) throws WxErrorException {
    //校验请求对象合法性
    WxMpCardCreateResult validResult = validCheck(createMessageMessage);
    if (!validResult.isSuccess())
      return validResult;
    String response = this.wxMpService.post(MEMBER_CARD_CREAET, GSON.toJson(createMessageMessage));
    return WxMpCardCreateResult.fromJson(response);
  }

  private WxMpCardCreateResult validCheck(WxMpMemberCardCreateMessage createMessageMessage) throws WxErrorException {
    if (createMessageMessage == null) {
      return WxMpCardCreateResult.failure("对象不能为空");
    }
    MemberCardCreateRequest cardCreateRequest = createMessageMessage.getCardCreateRequest();
    if (createMessageMessage == null) {
      return WxMpCardCreateResult.failure("会员卡对象不能为空");
    }
    String cardType = cardCreateRequest.getCardType();
    if (!StringUtils.equals(cardType, "MEMBER_CARD")) {
      return WxMpCardCreateResult.failure("卡券类型必须等于MEMBER_CARD");
    }
    MemberCard memberCard = cardCreateRequest.getMemberCard();

    if (StringUtils.isEmpty(memberCard.getPrerogative())) {
      return WxMpCardCreateResult.failure("会员卡特权说明不能为空:prerogative");
    }
    //卡片激活规则
    if (!memberCard.isAutoActivate() && !memberCard.isWxActivate() && StringUtils.isEmpty(memberCard.getActivateUrl())) {
      return WxMpCardCreateResult.failure("会员卡激活方式为接口激活，activate_url不能为空");
    }

    //积分支持
//    if(memberCard.isSupplyBonus() && StringUtils.isEmpty(memberCard.getBonusUrl())){
//      return WxMpCardCreateResult.failure("会员卡支持积分，bonus_url不能为空");
//    }
//    if(memberCard.isSupplyBonus() && memberCard.getBonusRule() == null){
//      return WxMpCardCreateResult.failure("会员卡支持积分，bonus_rule不能为空");
//    }
    BaseInfo baseInfo = memberCard.getBaseInfo();
    if (baseInfo == null) {
      return WxMpCardCreateResult.failure("会员卡基本信息对象base_info不能为空");
    }

    if (StringUtils.isBlank(baseInfo.getLogoUrl())) {
      return WxMpCardCreateResult.failure("会员卡基本信息的商户logo:logo_url不能为空");
    }

    if (StringUtils.isBlank(baseInfo.getCodeType())) {
      return WxMpCardCreateResult.failure("会员卡基本信息的条码类型:code_type不能为空");
    }

    if (StringUtils.isBlank(baseInfo.getBrandName())) {
      return WxMpCardCreateResult.failure("会员卡基本信息的商户名字:brand_name不能为空");
    }

    if (StringUtils.length(baseInfo.getBrandName()) > 12) {
      return WxMpCardCreateResult.failure("会员卡基本信息的商户名字:brand_name长度不能大于12个汉字");
    }

    if (StringUtils.isBlank(baseInfo.getTitle())) {
      return WxMpCardCreateResult.failure("会员卡基本信息的卡券名称:title不能为空");
    }

    if (StringUtils.length(baseInfo.getTitle()) > 9) {
      return WxMpCardCreateResult.failure("会员卡基本信息的卡券名称:title长度不能大于9个汉字");
    }

    if (StringUtils.isBlank(baseInfo.getColor())) {
      return WxMpCardCreateResult.failure("会员卡基本信息的卡颜色:color不能为空");
    }

    CardColor cardColor = null;
    try {
      cardColor = CardColor.valueOf(baseInfo.getColor());
    } catch (IllegalArgumentException ex) {

    }
    if (cardColor == null) {
      return WxMpCardCreateResult.failure("会员卡基本信息的卡颜色:" + baseInfo.getColor() + "不支持");
    }

    if (StringUtils.isBlank(baseInfo.getNotice())) {
      return WxMpCardCreateResult.failure("会员卡基本信息的使用提醒:notice不能为空");
    }

    if (StringUtils.isBlank(baseInfo.getDescription())) {
      return WxMpCardCreateResult.failure("会员卡基本信息的使用说明:description不能为空");
    }

    if (baseInfo.getSku() == null) {
      return WxMpCardCreateResult.failure("会员卡基本信息的商品信息:sku不能为空");
    }

    DateInfo dateInfo = baseInfo.getDateInfo();
    if (dateInfo == null) {
      return WxMpCardCreateResult.failure("会员卡基本信息的使用日期:date_info不能为空");
    }

    DateInfoType dateInfoType = null;
    try {
      dateInfoType = DateInfoType.valueOf(dateInfo.getType());
    } catch (IllegalArgumentException ex) {

    }

    if (dateInfoType == null) {
      return WxMpCardCreateResult.failure("会员卡基本信息的使用日期类型:" + dateInfo.getType() + "不合法");
    }

    //固定时长
    if (dateInfoType == DateInfoType.DATE_TYPE_FIX_TERM && (dateInfo.getFixedTerm() == null || dateInfo.getFixedBeginTerm() == null)) {
      return WxMpCardCreateResult.failure("会员卡基本信息的使用日期为:" + dateInfoType.getDescription() + "，fixedTerm和fixedBeginTerm不能为空");
    }

    //固定期限
    if (dateInfoType == DateInfoType.DATE_TYPE_FIX_TIME_RANGE && (dateInfo.getBeginTimestamp() == null || dateInfo.getEndTimestamp() == null)) {
      return WxMpCardCreateResult.failure("会员卡基本信息的使用日期为:" + dateInfoType.getDescription() + "，beginTimestamp 和 endTimestamp 不能为空");
    }
    if (dateInfoType == DateInfoType.DATE_TYPE_FIX_TIME_RANGE && (dateInfo.getBeginTimestamp() < System.currentTimeMillis() || dateInfo.getEndTimestamp() < System.currentTimeMillis() || dateInfo.getBeginTimestamp() > dateInfo.getEndTimestamp())) {
      return WxMpCardCreateResult.failure("会员卡基本信息的使用日期为:" + dateInfoType.getDescription() + "，beginTimestamp和endTimestamp的值不合法，请检查");
    }

    if (!baseInfo.isUseAllLocations() && StringUtils.isBlank(baseInfo.getLocationIdList())) {
      return WxMpCardCreateResult.failure("会员卡基本信息的门店使用范围选择指定门店,门店列表:locationIdList不能为空");
    }

    //校验高级信息
    AdvancedInfo advancedInfo = memberCard.getAdvancedInfo();
    if (advancedInfo != null) {
      if (advancedInfo.getBusinessServiceList() != null) {
        for (String bs : advancedInfo.getBusinessServiceList()) {
          BusinessServiceType businessServiceType = null;
          try {
            businessServiceType = BusinessServiceType.valueOf(bs);
          } catch (IllegalArgumentException ex) {
            return WxMpCardCreateResult.failure("会员卡高级信息的商户服务:" + bs + " 不合法");
          }
        }
      }
    }

    return WxMpCardCreateResult.success();
  }

  /**
   * 会员卡激活接口
   *
   * @param activatedMessage 激活所需参数
   * @return WxMpCardCreateResult。
   * @throws WxErrorException 接口调用失败抛出的异常
   */
  @Override
  public String activateMemberCard(WxMpMemberCardActivatedMessage activatedMessage) throws WxErrorException {
    return this.wxMpService.post(MEMBER_CARD_ACTIVATE, GSON.toJson(activatedMessage));
  }

  /**
   * 拉取会员信息接口
   *
   * @param cardId 会员卡的CardId，微信分配
   * @param code   领取会员的会员卡Code
   * @return 会员信息的结果对象
   * @throws WxErrorException 接口调用失败抛出的异常
   */
  @Override
  public WxMpMemberCardUserInfoResult getUserInfo(String cardId, String code) throws WxErrorException {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("card_id", cardId);
    jsonObject.addProperty("code", code);

    String responseContent = this.getWxMpService().post(MEMBER_CARD_USER_INFO_GET, jsonObject.toString());
    log.debug("{}", responseContent);
    JsonElement tmpJsonElement = new JsonParser().parse(responseContent);
    return WxMpGsonBuilder.create().fromJson(tmpJsonElement,
      new TypeToken<WxMpMemberCardUserInfoResult>() {
      }.getType());
  }

  /**
   * 当会员持卡消费后，支持开发者调用该接口更新会员信息。会员卡交易后的每次信息变更需通过该接口通知微信，便于后续消息通知及其他扩展功能。
   * <p>
   * 1.开发者可以同时传入add_bonus和bonus解决由于同步失败带来的幂等性问题。同时传入add_bonus和bonus时
   * add_bonus作为积分变动消息中的变量值，而bonus作为卡面上的总积分额度显示。余额变动同理。
   * 2.开发者可以传入is_notify_bonus控制特殊的积分对账变动不发送消息，余额变动同理。
   *
   * @param updateUserMessage 更新会员信息所需字段消息
   * @return 调用返回的JSON字符串。
   * @throws WxErrorException 接口调用失败抛出的异常
   */
  @Override
  public WxMpMemberCardUpdateResult updateUserMemberCard(WxMpMemberCardUpdateMessage updateUserMessage)
    throws WxErrorException {

    String responseContent = this.getWxMpService().post(MEMBER_CARD_UPDATE_USER, GSON.toJson(updateUserMessage));

    JsonElement tmpJsonElement = new JsonParser().parse(responseContent);
    return WxMpGsonBuilder.create().fromJson(tmpJsonElement,
      new TypeToken<WxMpMemberCardUpdateResult>() {
      }.getType());
  }

  /**
   * 设置会员卡激活的字段（会员卡设置：wx_activate=true 时需要）
   *
   * @param userFormRequest
   * @return
   * @throws WxErrorException
   */
  @Override
  public MemberCardActivateUserFormResult setActivateUserForm(MemberCardActivateUserFormRequest userFormRequest) throws WxErrorException {
    String responseContent = this.getWxMpService().post(MEMBER_CARD_ACTIVATEUSERFORM, GSON.toJson(userFormRequest));
    return MemberCardActivateUserFormResult.fromJson(responseContent);
  }

  /**
   * 获取会员卡开卡插件参数(跳转型开卡组件需要参数)
   *
   * @param outStr
   * @return
   * @throws WxErrorException
   */
  public ActivatePluginParam getActivatePluginParam(String cardId, String outStr) throws WxErrorException {
    JsonObject params = new JsonObject();
    params.addProperty("card_id", cardId);
    params.addProperty("outer_str", outStr);
    String response = this.wxMpService.post(MEMBER_CARD_ACTIVATE_URL, GSON.toJson(params));
    ActivatePluginParamResult result = GSON.fromJson(response, ActivatePluginParamResult.class);
    if (0 == result.getErrcode()) {
      String url = result.getUrl();
      try {
        String decodedUrl = URLDecoder.decode(url, "UTF-8");
        Map<String, String> resultMap = parseRequestUrl(decodedUrl);
        ActivatePluginParam activatePluginParam = new ActivatePluginParam();
        activatePluginParam.setEncryptCardId(resultMap.get("encrypt_card_id"));
        activatePluginParam.setOuterStr(resultMap.get("outer_str"));
        activatePluginParam.setBiz(resultMap.get("biz")+"==");
        return activatePluginParam;
      } catch (UnsupportedEncodingException e) {
        e.printStackTrace();
      }
    }
    return null;
  }

  /**
   * 去掉url中的路径，留下请求参数部分
   *
   * @param strURL url地址
   * @return url请求参数部分
   */
  private static String truncateUrlPage(String strURL) {
    String strAllParam = null;
    String[] arrSplit = null;
    arrSplit = strURL.split("[?]");
    if (strURL.length() > 1) {
      if (arrSplit.length > 1) {
        if (arrSplit[1] != null) {
          strAllParam = arrSplit[1];
        }
      }
    }

    return strAllParam;
  }

  /**
   * 解析出url参数中的键值对
   * 如 "index.jsp?Action=del&id=123"，解析出Action:del,id:123存入map中
   *
   * @param URL url地址
   * @return url请求参数部分
   */
  public static Map<String, String> parseRequestUrl(String URL) {
    Map<String, String> mapRequest = new HashMap<String, String>();

    String[] arrSplit = null;

    String strUrlParam = truncateUrlPage(URL);
    if (strUrlParam == null) {
      return mapRequest;
    }
    arrSplit = strUrlParam.split("[&]");
    for (String strSplit : arrSplit) {
      String[] arrSplitEqual = null;
      arrSplitEqual = strSplit.split("[=]");

      //解析出键值
      if (arrSplitEqual.length > 1) {
        //正确解析
        mapRequest.put(arrSplitEqual[0], arrSplitEqual[1]);

      } else {
        if (arrSplitEqual[0] != "") {
          //只有参数没有值，不加入
          mapRequest.put(arrSplitEqual[0], "");
        }
      }
    }
    return mapRequest;
  }

}
