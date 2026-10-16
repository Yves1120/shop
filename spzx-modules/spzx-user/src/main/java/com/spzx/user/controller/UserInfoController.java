package com.spzx.user.controller;

import com.google.code.kaptcha.Producer;
import com.spzx.common.core.context.SecurityContextHolder;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.utils.bean.BeanUtils;
import com.spzx.common.core.utils.poi.ExcelUtil;
import com.spzx.common.core.web.controller.BaseController;
import com.spzx.common.core.web.domain.AjaxResult;
import com.spzx.common.core.web.page.TableDataInfo;
import com.spzx.common.log.annotation.Log;
import com.spzx.common.log.enums.BusinessType;
import com.spzx.common.security.annotation.InnerAuth;
import com.spzx.common.security.annotation.RequiresLogin;
import com.spzx.common.security.annotation.RequiresPermissions;
import com.spzx.common.security.utils.SecurityUtils;
import com.github.pagehelper.PageInfo;
import com.spzx.user.domain.UpdateUserLogin;
import com.spzx.user.domain.UserAddress;
import com.spzx.user.domain.UserBrowseHistory;
import com.spzx.user.domain.UserCollect;
import com.spzx.user.domain.UserInfo;
import com.spzx.user.domain.UserInfoVo;
import com.spzx.user.service.IUserInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.FastByteArrayOutputStream;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 会员Controller
 *
 * @author atguigu
 * @date 2024-09-27
 */
@Tag(name = "会员接口管理")
@RestController
@RequestMapping("/userInfo")
public class UserInfoController extends BaseController {
    @Autowired
    private IUserInfoService userInfoService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Resource(name = "captchaProducer")
    private Producer captchaProducer;

    /**
     * 查询会员列表
     */
    @Operation(summary = "查询会员列表")
    @RequiresPermissions("user:userInfo:list")
    @GetMapping("/list")
    public TableDataInfo list(UserInfo userInfo) {
        startPage();
        List<UserInfo> list = userInfoService.selectUserInfoList(userInfo);
        return getDataTable(list);
    }

    /**
     * 导出会员列表
     */
    @Operation(summary = "导出会员列表")
    @RequiresPermissions("user:userInfo:export")
    @Log(title = "会员", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UserInfo userInfo) {
        List<UserInfo> list = userInfoService.selectUserInfoList(userInfo);
        ExcelUtil<UserInfo> util = new ExcelUtil<UserInfo>(UserInfo.class);
        util.exportExcel(response, list, "会员数据");
    }

    /**
     * 获取会员详细信息
     */
    @Operation(summary = "获取会员详细信息")
    @RequiresPermissions("user:userInfo:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(userInfoService.getById(id));
    }

    @Operation(summary = "获取用户地址")
    @RequiresPermissions("user:info:query")
    @GetMapping("getUserAddress/{userId}")
    public AjaxResult getUserAddress(@PathVariable Long userId) {
        List<UserAddress> userAddressList = userInfoService.selectUserAddressList(userId);
        return success(userAddressList);
    }


    @InnerAuth
    @PostMapping("/register")
    public R<Boolean> register(@RequestBody UserInfo userInfo) {
        userInfoService.register(userInfo);
        return R.ok();
    }


    @Operation(summary = "根据用户名获取用户信息")
    @InnerAuth
    @GetMapping("/info/{username}")
    public R<UserInfo> getUserInfo(@PathVariable("username") String username) {
        UserInfo userInfo = userInfoService.selectUserByUserName(username);
        return R.ok(userInfo);
    }

    @Operation(summary = "更新用户登录信息")
    @InnerAuth
    @PutMapping("/updateUserLogin")
    public R<Boolean> updateUserLogin(@RequestBody UpdateUserLogin updateUserLogin) {
        return R.ok(userInfoService.updateUserLogin(updateUserLogin));
    }


    @Operation(summary = "获取当前登录用户信息")
    @RequiresLogin
    @GetMapping("/getLoginUserInfo")
    public AjaxResult getLoginUserInfo(HttpServletRequest request) {
        Long userId = SecurityContextHolder.getUserId();
        UserInfo userInfo = userInfoService.getById(userId);
        if (userInfo == null) {
            return AjaxResult.error("用户信息不存在");
        }
        UserInfoVo userInfoVo = new UserInfoVo();
        BeanUtils.copyProperties(userInfo, userInfoVo);
        return success(userInfoVo);
    }

    @Operation(summary = "generate validate code")
    @GetMapping("/generateValidateCode")
    public R<Map<String, String>> generateValidateCode() throws IOException {
        String code = captchaProducer.createText();
        BufferedImage image = captchaProducer.createImage(code);
        String uuid = UUID.randomUUID().toString().replace("-", "");

        String redisKey = "user:validate:code:" + uuid;
        redisTemplate.opsForValue().set(redisKey, code, 5, TimeUnit.MINUTES);

        FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);

        Map<String, String> result = new HashMap<>();
        result.put("codeKey", uuid);
        result.put("codeImage", "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(outputStream.toByteArray()));
        return R.ok(result);
    }

    @Operation(summary = "判断是否收藏")
    @RequiresLogin
    @GetMapping("/isCollect/{skuId}")
    public R<Boolean> isCollect(@PathVariable Long skuId) {
        Long userId = SecurityUtils.getUserId();
        return R.ok(userInfoService.isCollect(skuId, userId));
    }

    @Operation(summary = "收藏商品")
    @RequiresLogin
    @GetMapping("/collect/{skuId}")
    public R collect(@PathVariable Long skuId) {
        Long userId = SecurityUtils.getUserId();
        userInfoService.collect(skuId, userId);
        return R.ok();
    }

    @Operation(summary = "取消收藏")
    @RequiresLogin
    @GetMapping("/cancelCollect/{skuId}")
    public R cancelCollect(@PathVariable Long skuId) {
        Long userId = SecurityUtils.getUserId();
        userInfoService.cancelCollect(skuId, userId);
        return R.ok();
    }

    @Operation(summary = "用户收藏列表")
    @RequiresLogin
    @GetMapping("/userCollectList/{pageNo}/{pageSize}")
    public R<PageInfo<UserCollect>> userCollectList(@PathVariable Integer pageNo, @PathVariable Integer pageSize) {
        Long userId = SecurityUtils.getUserId();
        return R.ok(userInfoService.userCollectList(pageNo, pageSize, userId));
    }

    @Operation(summary = "用户浏览历史列表")
    @RequiresLogin
    @GetMapping("/userBrowseHistoryList/{pageNo}/{pageSize}")
    public R<PageInfo<UserBrowseHistory>> userBrowseHistoryList(@PathVariable Integer pageNo, @PathVariable Integer pageSize) {
        Long userId = SecurityUtils.getUserId();
        return R.ok(userInfoService.userBrowseHistoryList(pageNo, pageSize, userId));
    }
}
