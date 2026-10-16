package com.spzx.order.controller;

import com.github.pagehelper.PageHelper;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.exception.ServiceException;
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
import com.spzx.order.api.domain.OrderInfo;
import com.spzx.order.domain.vo.OrderForm;
import com.spzx.order.domain.vo.TradeVo;
import com.spzx.order.service.IOrderInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "订单接口管理")
@RestController
@RequestMapping("/orderInfo")
public class OrderInfoController extends BaseController {
    @Autowired
    private IOrderInfoService orderInfoService;

    /**
     * 查询订单列表（管理端）
     */
    @Operation(summary = "查询订单列表")
    @RequiresPermissions("user:orderInfo:list")
    @GetMapping("/list")
    public TableDataInfo list(OrderInfo orderInfo) {
        startPage();
        List<OrderInfo> list = orderInfoService.selectOrderInfoList(orderInfo);
        return getDataTable(list);
    }

    /**
     * 导出订单列表
     */
    @Operation(summary = "导出订单列表")
    @RequiresPermissions("user:orderInfo:export")
    @Log(title = "订单", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, OrderInfo orderInfo) {
        List<OrderInfo> list = orderInfoService.selectOrderInfoList(orderInfo);
        ExcelUtil<OrderInfo> util = new ExcelUtil<OrderInfo>(OrderInfo.class);
        util.exportExcel(response, list, "订单数据");
    }

    /**
     * 获取订单详细信息（管理端）
     */
    @Operation(summary = "获取订单详细信息")
    @RequiresPermissions("user:orderInfo:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(orderInfoService.selectOrderInfoById(id));
    }

    /**
     * H5端：获取结算页数据
     */
    @Operation(summary = "获取结算页数据")
    @RequiresLogin
    @GetMapping("/trade")
    public R<TradeVo> trade() {
        return R.ok(orderInfoService.trade());
    }

    /**
     * H5端：提交订单
     */
    @Operation(summary = "提交订单")
    @RequiresLogin
    @PostMapping("/submitOrder")
    public R<Long> submitOrder(@RequestBody OrderForm orderForm) {
        Long orderId = orderInfoService.submitOrder(orderForm);
        return R.ok(orderId);
    }

    /**
     * H5端：根据订单号获取订单
     */
    @Operation(summary = "根据订单号获取订单")
    @RequiresLogin
    @GetMapping("/getOrderInfo/{orderId}")
    public R<OrderInfo> getOrderInfo(@PathVariable String orderId) {
        OrderInfo orderInfo = orderInfoService.getByOrderId(orderId);
        if (orderInfo == null || !SecurityUtils.getUserId().equals(orderInfo.getUserId())) {
            throw new ServiceException("无权查看该订单");
        }
        return R.ok(orderInfo);
    }

    /**
     * H5端：获取用户订单列表
     */
    @Operation(summary = "获取用户订单列表")
    @RequiresLogin
    @GetMapping("/userOrderInfoList/{pageNo}/{pageSize}")
    public TableDataInfo userOrderInfoList(@PathVariable Integer pageNo,
                                           @PathVariable Integer pageSize,
                                           @RequestParam(defaultValue = "-1") Integer orderStatus) {
        PageHelper.startPage(pageNo, pageSize);
        Long userId = SecurityUtils.getUserId();
        List<OrderInfo> list = orderInfoService.selectUserOrderInfoList(userId, orderStatus);
        return getDataTable(list);
    }

    /**
     * H5端：取消订单
     */
    @Operation(summary = "取消订单")
    @RequiresLogin
    @GetMapping("/cancelOrder/{orderId}")
    public R<Void> cancelOrder(@PathVariable Long orderId) {
        orderInfoService.cancelOrder(orderId);
        return R.ok();
    }

    /**
     * H5端：立即购买
     */
    @Operation(summary = "立即购买")
    @RequiresLogin
    @GetMapping("/buy/{skuId}")
    public R<TradeVo> buy(@PathVariable Long skuId) {
        return R.ok(orderInfoService.buy(skuId));
    }

    /**
     * 内部调用：根据订单号获取订单
     */
    @InnerAuth
    @GetMapping("/getByOrderNo/{orderNo}")
    public R<OrderInfo> getByOrderNo(@PathVariable String orderNo) {
        return R.ok(orderInfoService.getByOrderId(orderNo));
    }

    /**
     * 内部调用：更新订单状态
     */
    @InnerAuth
    @PutMapping("/updateOrderStatus/{orderNo}/{orderStatus}")
    public R<Void> updateOrderStatus(@PathVariable String orderNo, @PathVariable Integer orderStatus) {
        orderInfoService.updateOrderStatus(orderNo, orderStatus);
        return R.ok();
    }
}
