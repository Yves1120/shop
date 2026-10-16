package com.spzx.order.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.spzx.order.api.domain.OrderInfo;
import com.spzx.order.domain.vo.OrderForm;
import com.spzx.order.domain.vo.TradeVo;

import java.util.List;

public interface IOrderInfoService extends IService<OrderInfo> {
    List<OrderInfo> selectOrderInfoList(OrderInfo orderInfo);
    OrderInfo selectOrderInfoById(Long id);
    // H5端：获取结算页数据
    TradeVo trade();
    // H5端：提交订单
    Long submitOrder(OrderForm orderForm);
    // H5端：根据订单号获取订单
    OrderInfo getByOrderId(String orderId);
    // H5端：获取用户订单列表
    List<OrderInfo> selectUserOrderInfoList(Long userId, Integer orderStatus);
    // H5端：取消订单
    void cancelOrder(Long orderId);
    // 系统内部：取消超时未支付订单
    void cancelUnpaidOrder(Long orderId);
    // H5端：立即购买
    TradeVo buy(Long skuId);
    // 内部调用：更新订单状态
    void updateOrderStatus(String orderNo, Integer orderStatus);
}
