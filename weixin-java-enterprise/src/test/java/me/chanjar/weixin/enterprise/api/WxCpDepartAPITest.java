package me.chanjar.weixin.enterprise.api;

import java.util.List;

import me.chanjar.weixin.enterprise.bean.WxCpDepart;
import org.testng.Assert;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import me.chanjar.weixin.enterprise.exception.WxErrorException;

import com.google.inject.Inject;

/**
 * 测试部门接口
 *
 * @author Daniel Qian
 */
@Test(groups = "departAPI", dependsOnGroups = "baseAPI")
@Guice(modules = ApiTestModule.class)
public class WxCpDepartAPITest {

  @Inject
  protected WxCpServiceImpl wxService;

  protected WxCpDepart depart;

  public void testDepartCreate() throws WxErrorException {
    WxCpDepart depart = new WxCpDepart();
    depart.setName("子部门" + System.currentTimeMillis());
    depart.setParentId(1);
    depart.setOrder(1);
    Integer departId = wxService.departCreate(depart);
  }

  @Test(dependsOnMethods = "testDepartCreate")
  public void testDepartGet() throws WxErrorException {
    System.out.println("=================获取部门");
    List<WxCpDepart> departList = wxService.departGet();
    Assert.assertNotNull(departList);
    Assert.assertTrue(departList.size() > 0);
    for (WxCpDepart g : departList) {
      depart = g;
      System.out.println(depart.getId() + ":" + depart.getName());
      Assert.assertNotNull(g.getName());
    }
  }

  @Test(dependsOnMethods = { "testDepartGet", "testDepartCreate" })
  public void testDepartUpdate() throws WxErrorException {
    System.out.println("=================更新部门");
    depart.setName("子部门改名" + System.currentTimeMillis());
    wxService.departUpdate(depart);
  }

  @Test(dependsOnMethods = "testDepartUpdate")
  public void testDepartDelete() throws WxErrorException {
    System.out.println("=================删除部门");
    System.out.println(depart.getId() + ":" + depart.getName());
    wxService.departDelete(depart.getId());
  }

}
